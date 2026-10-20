package org.kanaha.camera;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Debug-only 12-bit RAW probe (docs/OPENGATE.md, Part 2). Answers on the device the
 * questions that size the 12-bit work before any of it is designed:
 * <ol>
 *   <li>capture: does the camera deliver full-size RAW at 30 fps without drops?</li>
 *   <li>write: can the frames reach storage as fast as they arrive (the "write raw
 *       during the take, build DNGs afterwards" design)?</li>
 *   <li>dng: how long does DngCreator take per frame, CPU alone and to a file?</li>
 *   <li>bits: are the samples genuinely 12-bit, or 10-bit data scaled into a 12-bit
 *       range? whiteLevel 4095 does not settle that; the low bits of real frames do.</li>
 * </ol>
 * Opening this activity takes the camera from Kanaha Camera, which releases it when
 * paused. Launch, then read the log:
 * <pre>
 *   adb shell am start -n org.kanaha.camera/org.kanaha.camera.RawProbeActivity
 *   adb logcat -s KANAHA_RAW12
 * </pre>
 * Extras: {@code --ei seconds 5} (per timed phase), {@code --es format raw12} to time
 * packed RAW12 capture instead of RAW_SENSOR (dng and bits need RAW_SENSOR and are
 * skipped). {@code --ei dng_seconds 60} adds a sustained phase that writes every frame
 * as its own DNG for that long, {@code --ei dng_threads 3} workers; the DNGs are deleted
 * afterwards. Results also go to {@code <external files>/raw_probe/result.json}; the
 * frames written in the write phase are deleted afterwards.
 */
public class RawProbeActivity extends Activity {
    private static final String TAG = "KANAHA_RAW12";
    private static final int TARGET_FPS = 30;
    private static final int DNG_FRAMES = 5;
    private static final int WRITE_BUFFERS = 8;

    private TextView output;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private volatile CameraDevice camera;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        output = new TextView(this);
        output.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        setContentView(scroll);

        int seconds = getIntent().getIntExtra("seconds", 5);
        boolean raw12 = "raw12".equals(getIntent().getStringExtra("format"));
        int dngSeconds = getIntent().getIntExtra("dng_seconds", 0);
        int dngThreads = Math.max(1, getIntent().getIntExtra("dng_threads", 3));

        cameraThread = new HandlerThread("RawProbeCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        new Thread(() -> runProbe(seconds, raw12, dngSeconds, dngThreads), "RawProbe").start();
    }

    @Override
    protected void onDestroy() {
        CameraDevice c = camera;
        if (c != null) c.close();
        cameraThread.quitSafely();
        super.onDestroy();
    }

    private void report(String line) {
        Log.i(TAG, line);
        runOnUiThread(() -> output.append(line + "\n"));
    }

    private void runProbe(int seconds, boolean raw12, int dngSeconds, int dngThreads) {
        JSONObject result = new JSONObject();
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String cameraId = findRawCamera(manager);
            if (cameraId == null) {
                report("RESULT no back camera advertises RAW capability");
                return;
            }
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            result.put("camera_id", cameraId);
            result.put("characteristics", describeCamera(chars));

            int format = raw12 ? ImageFormat.RAW12 : ImageFormat.RAW_SENSOR;
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = largest(map.getOutputSizes(format));
            if (size == null) {
                report("RESULT format " + formatName(format) + " not offered");
                return;
            }
            report("probe: camera " + cameraId + " " + formatName(format) + " " + size
                    + ", " + seconds + " s per timed phase");

            camera = openCamera(manager, cameraId);
            result.put("capture", timedPhase(chars, format, size, seconds, false));
            result.put("write", timedPhase(chars, format, size, seconds, true));
            if (!raw12) {
                result.put("dng_and_bits", dngAndBits(chars, size));
                if (dngSeconds > 0) {
                    result.put("dng_sustained", dngSustained(chars, size, dngSeconds, dngThreads));
                }
            }
            report("RESULT " + result.toString());
        } catch (Exception e) {
            Log.e(TAG, "probe failed", e);
            report("RESULT failed: " + e);
        } finally {
            CameraDevice c = camera;
            if (c != null) c.close();
            camera = null;
            writeResult(result);
        }
    }

    private String findRawCamera(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK || caps == null) continue;
            for (int cap : caps) {
                if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) return id;
            }
        }
        return null;
    }

    /** What the camera claims, including any key that looks like dual conversion gain. */
    private JSONObject describeCamera(CameraCharacteristics chars) throws Exception {
        JSONObject d = new JSONObject();
        d.put("white_level", chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL));
        d.put("pixel_array", String.valueOf(chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)));
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        JSONObject formats = new JSONObject();
        for (int f : new int[]{ImageFormat.RAW_SENSOR, ImageFormat.RAW10, ImageFormat.RAW12}) {
            Size s = largest(map.getOutputSizes(f));
            if (s == null) continue;
            JSONObject e = new JSONObject();
            e.put("size", s.toString());
            e.put("min_frame_ns", map.getOutputMinFrameDuration(f, s));
            e.put("stall_ns", map.getOutputStallDuration(f, s));
            formats.put(formatName(f), e);
        }
        d.put("raw_formats", formats);
        JSONArray dcgKeys = new JSONArray();
        for (CameraCharacteristics.Key<?> k : chars.getKeys()) {
            if (looksLikeDcg(k.getName())) dcgKeys.put(k.getName() + "=" + valueString(chars.get(k)));
        }
        for (CaptureResult.Key<?> k : chars.getAvailableCaptureResultKeys()) {
            if (looksLikeDcg(k.getName())) dcgKeys.put("result:" + k.getName());
        }
        d.put("dcg_like_keys", dcgKeys);
        report("camera: " + d);
        return d;
    }

    private static boolean looksLikeDcg(String name) {
        String n = name.toLowerCase();
        return n.contains("dcg") || n.contains("conversion") || n.contains("dual_gain")
                || n.contains("dualgain") || (n.contains("hdr") && !n.startsWith("android.control"));
    }

    private static String valueString(Object v) {
        if (v instanceof int[]) return java.util.Arrays.toString((int[]) v);
        if (v instanceof byte[]) return java.util.Arrays.toString((byte[]) v);
        if (v instanceof long[]) return java.util.Arrays.toString((long[]) v);
        if (v instanceof float[]) return java.util.Arrays.toString((float[]) v);
        return String.valueOf(v);
    }

    /**
     * Repeating 30 fps capture for {@code seconds}. Without write, each frame is closed at
     * once: the camera's own sustained rate. With write, frames are copied to direct
     * buffers and appended to one file by a writer thread; a frame with no free buffer is
     * dropped and counted, which is exactly what a real recorder would have to do.
     */
    private JSONObject timedPhase(CameraCharacteristics chars, int format, Size size, int seconds,
                                  boolean write) throws Exception {
        String phase = write ? "write" : "capture";
        ImageReader reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), format, 6);
        List<Long> timestamps = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger bufferLost = new AtomicInteger();
        AtomicInteger captureFailed = new AtomicInteger();
        AtomicInteger writerDrops = new AtomicInteger();
        AtomicLong bytesWritten = new AtomicLong();

        BlockingQueue<ByteBuffer> free = new ArrayBlockingQueue<>(WRITE_BUFFERS);
        BlockingQueue<ByteBuffer> full = new ArrayBlockingQueue<>(WRITE_BUFFERS);
        File file = new File(probeDir(), "frames.raw");
        Thread writer = null;
        CountDownLatch writerDone = new CountDownLatch(1);
        if (write) {
            int frameBytes = frameBytes(size, format);
            for (int i = 0; i < WRITE_BUFFERS; i++) {
                free.add(ByteBuffer.allocateDirect(frameBytes));
            }
            writer = new Thread(() -> {
                try (RandomAccessFile raf = new RandomAccessFile(file, "rw");
                     FileChannel channel = raf.getChannel()) {
                    while (true) {
                        ByteBuffer b = full.poll(2, TimeUnit.SECONDS);
                        if (b == null) break;
                        while (b.hasRemaining()) bytesWritten.addAndGet(channel.write(b));
                        b.clear();
                        free.add(b);
                    }
                    channel.force(false);
                } catch (Exception e) {
                    Log.e(TAG, "writer failed", e);
                } finally {
                    writerDone.countDown();
                }
            }, "RawProbeWriter");
            writer.start();
        } else {
            writerDone.countDown();
        }

        reader.setOnImageAvailableListener(r -> {
            Image image = r.acquireNextImage();
            if (image == null) return;
            try {
                timestamps.add(image.getTimestamp());
                if (write) {
                    ByteBuffer b = free.poll();
                    if (b == null) {
                        writerDrops.incrementAndGet();
                    } else {
                        ByteBuffer src = image.getPlanes()[0].getBuffer();
                        src.limit(Math.min(src.limit(), b.capacity()));
                        b.put(src);
                        b.flip();
                        full.add(b);
                    }
                }
            } finally {
                image.close();
            }
        }, cameraHandler);

        CameraCaptureSession session = createSession(Collections.singletonList(reader.getSurface()));
        CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        builder.addTarget(reader.getSurface());
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(TARGET_FPS, TARGET_FPS));
        long start = System.nanoTime();
        session.setRepeatingRequest(builder.build(), new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureFailed(CameraCaptureSession s, CaptureRequest req, CaptureFailure failure) {
                captureFailed.incrementAndGet();
            }

            @Override
            public void onCaptureBufferLost(CameraCaptureSession s, CaptureRequest req, Surface target, long frame) {
                bufferLost.incrementAndGet();
            }
        }, cameraHandler);
        Thread.sleep(seconds * 1000L);
        session.stopRepeating();
        session.close();
        double wallSeconds = (System.nanoTime() - start) / 1e9;
        writerDone.await(30, TimeUnit.SECONDS);
        reader.close();

        JSONObject r = frameStats(new ArrayList<>(timestamps));
        r.put("buffer_lost", bufferLost.get());
        r.put("capture_failed", captureFailed.get());
        if (write) {
            r.put("writer_drops", writerDrops.get());
            r.put("bytes_written", bytesWritten.get());
            r.put("write_mb_per_s", Math.round(bytesWritten.get() / 1e6 / wallSeconds));
            r.put("free_space_gb_after", Math.round(probeDir().getUsableSpace() / 1e9));
            if (!file.delete()) Log.w(TAG, "could not delete " + file);
        }
        report(phase + ": " + r);
        return r;
    }

    /** Frames delivered, the rate they arrived at, and how many the sensor timestamps say are missing. */
    private static JSONObject frameStats(List<Long> ts) throws Exception {
        JSONObject r = new JSONObject();
        r.put("frames", ts.size());
        if (ts.size() < 2) return r;
        Collections.sort(ts);
        long frameNs = 1_000_000_000L / TARGET_FPS;
        long missing = 0;
        long maxGap = 0;
        for (int i = 1; i < ts.size(); i++) {
            long gap = ts.get(i) - ts.get(i - 1);
            maxGap = Math.max(maxGap, gap);
            long frames = Math.round((double) gap / frameNs);
            if (frames > 1) missing += frames - 1;
        }
        double span = (ts.get(ts.size() - 1) - ts.get(0)) / 1e9;
        r.put("fps", Math.round((ts.size() - 1) / span * 100) / 100.0);
        r.put("missing_frames", missing);
        r.put("max_gap_ms", Math.round(maxGap / 1e5) / 10.0);
        return r;
    }

    /**
     * DngCreator timing on {@link #DNG_FRAMES} frames, and the bit-depth check on the
     * first. A repeating request runs, so the timing is what a 30 fps recorder would see.
     */
    private JSONObject dngAndBits(CameraCharacteristics chars, Size size) throws Exception {
        ImageReader reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.RAW_SENSOR, 4);
        Map<Long, TotalCaptureResult> results = new ConcurrentHashMap<>();
        BlockingQueue<Image> images = new ArrayBlockingQueue<>(2);
        reader.setOnImageAvailableListener(r -> {
            Image image = r.acquireNextImage();
            if (image != null && !images.offer(image)) image.close();
        }, cameraHandler);

        CameraCaptureSession session = createSession(Collections.singletonList(reader.getSurface()));
        CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        builder.addTarget(reader.getSurface());
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(TARGET_FPS, TARGET_FPS));
        session.setRepeatingRequest(builder.build(), new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult result) {
                Long t = result.get(CaptureResult.SENSOR_TIMESTAMP);
                if (t != null) results.put(t, result);
            }
        }, cameraHandler);

        JSONObject r = new JSONObject();
        JSONArray cpuMs = new JSONArray();
        JSONArray fileMs = new JSONArray();
        File dng = new File(probeDir(), "probe.dng");
        int done = 0;
        long deadline = System.currentTimeMillis() + 30_000;
        while (done < DNG_FRAMES && System.currentTimeMillis() < deadline) {
            Image image = images.poll(2, TimeUnit.SECONDS);
            if (image == null) continue;
            try {
                TotalCaptureResult result = null;
                for (int i = 0; i < 20 && result == null; i++) {
                    result = results.get(image.getTimestamp());
                    if (result == null) Thread.sleep(10);
                }
                if (result == null) continue;
                if (done == 0) {
                    r.put("bits", bitDepth(image, chars));
                    r.put("first_result", resultSummary(result));
                }
                try (DngCreator creator = new DngCreator(chars, result)) {
                    long t0 = System.nanoTime();
                    creator.writeImage(new DiscardingOutputStream(), image);
                    long t1 = System.nanoTime();
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dng), 1 << 20)) {
                        creator.writeImage(out, image);
                    }
                    long t2 = System.nanoTime();
                    cpuMs.put(Math.round((t1 - t0) / 1e5) / 10.0);
                    fileMs.put(Math.round((t2 - t1) / 1e5) / 10.0);
                }
                done++;
            } finally {
                image.close();
            }
        }
        session.stopRepeating();
        session.close();
        reader.close();
        r.put("dng_bytes", dng.length());
        if (!dng.delete()) Log.w(TAG, "could not delete " + dng);
        r.put("dng_cpu_ms", cpuMs);
        r.put("dng_file_ms", fileMs);
        r.put("budget_ms_at_30fps", Math.round(1000.0 / TARGET_FPS * 10) / 10.0);
        report("dng: " + r);
        return r;
    }

    /**
     * The 12-bit recorder as it would run: a 30 fps repeating request for {@code seconds},
     * every frame written as its own DNG by {@code threads} workers. A frame that arrives
     * while all workers and the queue are busy is dropped and counted, as a recorder
     * would have to. Answers whether DngCreator plus storage keeps up over a whole take,
     * not just the five frames {@link #dngAndBits} times.
     */
    private JSONObject dngSustained(CameraCharacteristics chars, Size size, int seconds, int threads)
            throws Exception {
        int maxImages = threads + 4;
        ImageReader reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.RAW_SENSOR, maxImages);
        Map<Long, TotalCaptureResult> results = new ConcurrentHashMap<>();
        List<Long> timestamps = Collections.synchronizedList(new ArrayList<>());
        List<Long> frameMs = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger drops = new AtomicInteger();
        AtomicInteger noResult = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger written = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        File dir = new File(probeDir(), "dng_sustained");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("could not create " + dir);

        // Queue bounded so held images never exceed maxImages: threads running + queued + 2 spare.
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                threads, threads, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(maxImages - threads - 2));
        reader.setOnImageAvailableListener(r -> {
            Image image = r.acquireNextImage();
            if (image == null) return;
            timestamps.add(image.getTimestamp());
            try {
                pool.execute(() -> {
                    try {
                        TotalCaptureResult result = null;
                        for (int i = 0; i < 20 && result == null; i++) {
                            result = results.remove(image.getTimestamp());
                            if (result == null) Thread.sleep(5);
                        }
                        if (result == null) {
                            noResult.incrementAndGet();
                            return;
                        }
                        File f = new File(dir, image.getTimestamp() + ".dng");
                        long t0 = System.nanoTime();
                        try (DngCreator creator = new DngCreator(chars, result);
                             OutputStream out = new BufferedOutputStream(new FileOutputStream(f), 1 << 20)) {
                            creator.writeImage(out, image);
                        }
                        frameMs.add((System.nanoTime() - t0) / 1_000_000);
                        bytes.addAndGet(f.length());
                        written.incrementAndGet();
                    } catch (Exception e) {
                        failures.incrementAndGet();
                        Log.e(TAG, "dng write failed", e);
                    } finally {
                        image.close();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                drops.incrementAndGet();
                image.close();
            }
        }, cameraHandler);

        CameraCaptureSession session = createSession(Collections.singletonList(reader.getSurface()));
        CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        builder.addTarget(reader.getSurface());
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(TARGET_FPS, TARGET_FPS));
        long start = System.nanoTime();
        session.setRepeatingRequest(builder.build(), new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult result) {
                Long t = result.get(CaptureResult.SENSOR_TIMESTAMP);
                if (t != null) results.put(t, result);
            }
        }, cameraHandler);
        Thread.sleep(seconds * 1000L);
        session.stopRepeating();
        session.close();
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);
        double wallSeconds = (System.nanoTime() - start) / 1e9;
        reader.close();

        JSONObject r = frameStats(new ArrayList<>(timestamps));
        r.put("threads", threads);
        r.put("dngs_written", written.get());
        r.put("queue_drops", drops.get());
        r.put("no_capture_result", noResult.get());
        r.put("write_failures", failures.get());
        r.put("mb_per_s", Math.round(bytes.get() / 1e6 / wallSeconds));
        r.put("free_space_gb_at_end", Math.round(dir.getUsableSpace() / 1e9));
        List<Long> ms = new ArrayList<>(frameMs);
        Collections.sort(ms);
        if (!ms.isEmpty()) {
            r.put("dng_ms_median", ms.get(ms.size() / 2));
            r.put("dng_ms_p95", ms.get(ms.size() * 95 / 100));
            r.put("dng_ms_max", ms.get(ms.size() - 1));
        }
        File[] left = dir.listFiles();
        if (left != null) for (File f : left) if (!f.delete()) Log.w(TAG, "could not delete " + f);
        report("dng_sustained: " + r);
        return r;
    }

    /**
     * Whether the samples use all 12 bits. Data shifted up from 10 bits has its two low
     * bits always zero, so values divisible by 4 make up ~100% instead of ~25%.
     */
    private static JSONObject bitDepth(Image image, CameraCharacteristics chars) throws Exception {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int rowStride = plane.getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();
        long samples = 0, divisibleBy4 = 0, over1023 = 0;
        int max = 0;
        for (int y = 0; y < height; y += 4) {               // every 4th row is plenty
            int row = y * rowStride;
            for (int x = 0; x < width; x++) {
                int v = buf.getShort(row + x * 2) & 0xffff;
                samples++;
                if ((v & 3) == 0) divisibleBy4++;
                if (v > 1023) over1023++;
                if (v > max) max = v;
            }
        }
        JSONObject r = new JSONObject();
        r.put("samples", samples);
        r.put("max_value", max);
        r.put("white_level", chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL));
        r.put("fraction_divisible_by_4", Math.round(1000.0 * divisibleBy4 / samples) / 1000.0);
        r.put("fraction_over_1023", Math.round(1000.0 * over1023 / samples) / 1000.0);
        // The low-bit test holds at any brightness: scaled 10-bit data is always a multiple
        // of 4. Brightness only says how much of the 12-bit range the scene reached.
        String verdict = divisibleBy4 > 0.9 * samples ? "10-bit data scaled to 12 bits" : "12-bit precision (low bits populated)";
        if (max <= 1023) verdict += "; scene stayed under 1024, rerun on a bright scene to see the range used";
        r.put("verdict", verdict);
        return r;
    }

    private static JSONObject resultSummary(TotalCaptureResult result) throws Exception {
        JSONObject r = new JSONObject();
        r.put("iso", result.get(CaptureResult.SENSOR_SENSITIVITY));
        r.put("exposure_ns", result.get(CaptureResult.SENSOR_EXPOSURE_TIME));
        r.put("frame_ns", result.get(CaptureResult.SENSOR_FRAME_DURATION));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            r.put("dynamic_white_level", result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL));
        }
        return r;
    }

    @SuppressLint("MissingPermission") // Kanaha Camera holds CAMERA; this runs in the same app
    private CameraDevice openCamera(CameraManager manager, String id) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        CameraDevice[] opened = new CameraDevice[1];
        Exception[] failure = new Exception[1];
        manager.openCamera(id, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice c) {
                opened[0] = c;
                latch.countDown();
            }

            @Override
            public void onDisconnected(CameraDevice c) {
                c.close();
                failure[0] = new IllegalStateException("camera disconnected");
                latch.countDown();
            }

            @Override
            public void onError(CameraDevice c, int error) {
                c.close();
                failure[0] = new IllegalStateException("camera error " + error);
                latch.countDown();
            }
        }, cameraHandler);
        if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("camera did not open");
        if (failure[0] != null) throw failure[0];
        return opened[0];
    }

    @SuppressWarnings("deprecation") // the List<Surface> form keeps minSdk 23
    private CameraCaptureSession createSession(List<Surface> surfaces) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        CameraCaptureSession[] session = new CameraCaptureSession[1];
        camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession s) {
                session[0] = s;
                latch.countDown();
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession s) {
                latch.countDown();
            }
        }, cameraHandler);
        if (!latch.await(10, TimeUnit.SECONDS) || session[0] == null) {
            throw new IllegalStateException("capture session did not configure");
        }
        return session[0];
    }

    private File probeDir() {
        File dir = new File(getExternalFilesDir(null), "raw_probe");
        if (!dir.exists() && !dir.mkdirs()) Log.w(TAG, "could not create " + dir);
        return dir;
    }

    private void writeResult(JSONObject result) {
        try (FileOutputStream out = new FileOutputStream(new File(probeDir(), "result.json"))) {
            out.write(result.toString(2).getBytes("UTF-8"));
        } catch (Exception e) {
            Log.e(TAG, "could not write result.json", e);
        }
    }

    private static int frameBytes(Size size, int format) {
        int bits = format == ImageFormat.RAW12 ? 12 : 16;
        // Row padding can make a plane larger than the packed size; leave headroom.
        return (int) ((long) size.getWidth() * size.getHeight() * bits / 8 * 11 / 10);
    }

    private static Size largest(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best;
    }

    private static String formatName(int format) {
        switch (format) {
            case ImageFormat.RAW_SENSOR: return "RAW_SENSOR";
            case ImageFormat.RAW10: return "RAW10";
            case ImageFormat.RAW12: return "RAW12";
            default: return "format_" + format;
        }
    }

    /** Lets DngCreator's CPU time be measured apart from the storage it writes to. */
    private static final class DiscardingOutputStream extends OutputStream {
        @Override public void write(int b) { }
        @Override public void write(byte[] b, int off, int len) { }
    }
}
