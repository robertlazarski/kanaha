package org.kanaha.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A 12-bit RAW take: every 30 fps frame of the full sensor written as a lossless DNG
 * (startRecording with "raw12": true). Run inside {@link RawTakeActivity}, which owns
 * the camera for the take; OpenCamera closes its camera while that screen is in front.
 *
 * <p>The frames go to the native compressor, libkanaha_dng.so, through shared memory
 * ({@link DngHelper}): the camera callback copies each frame into a free buffer and
 * sends its slot number; the helper compresses and writes {@code frame_NNNNNN.dng}
 * and releases the slot. If no buffer is free the frame is dropped and counted.
 * Measured on a Pixel 10 Pro XL: three 270 s takes with no dropped frame
 * (docs/12-bit_depth.md). Threading: docs/THREAD_MODEL.md.
 *
 * <p>A take writes one folder: the frames, {@code take.wav} (the audio input, which
 * with the iRig is SMPTE timecode) and {@code take.kanaha_start.json}, which says when
 * the first frame was exposed and where it falls in the WAV.
 *
 * <p>Exposure is fixed for the take from setExposure's saved settings, and the shutter
 * must be pinned: on auto exposure in a dim room the sensor ran at 29.74 fps.
 * White balance does not change RAW samples, only the "as shot" colour each DNG
 * records; a locked white balance is applied as-is, otherwise auto white balance is
 * locked once the take is armed, so it cannot drift during the take.
 */
final class RawTake {
    private static final String TAG = "KanahaRawTake";
    static final int TARGET_FPS = 30;
    static final int THREADS = 4;           // three suffice; four leave capacity late in a long take
    static final int BUFFERS = 40;          // about 1.3 s of frames, ~1 GB of shared memory
    /** Bytes a second to plan storage by: 12.5 MB a frame, the larger end measured. */
    static final long PLANNED_BYTES_PER_SECOND = 12_500_000L * TARGET_FPS;
    /** A take stops itself when free space falls this low. */
    static final long STOP_BELOW_FREE_BYTES = 6L << 30;
    static final String SIDECAR = "take.kanaha_start.json";

    enum State { ARMING, WAITING, RECORDING, STOPPING, DONE, FAILED }

    /** The take in progress, or the last one; read by CameraControlReceiver. */
    static volatile RawTake active;

    /** The exposure a take runs at, from setExposure's saved settings. */
    static final class Settings {
        int iso;
        long exposureNs;
        float[] wbGains;          // with wbTransform: the locked white balance, or null
        float[] wbTransform;
        float focusDistance = -1; // dioptres; -1 = continuous autofocus
    }

    final String clip;
    final File dir;
    final long startAtMs;         // 0 = as soon as armed
    final long maxSeconds;
    final Settings settings;
    private final Context context;

    volatile State state = State.ARMING;
    volatile String error;
    volatile String endReason;
    private final AtomicInteger frames = new AtomicInteger();
    private final AtomicInteger drops = new AtomicInteger();
    private final AtomicInteger missing = new AtomicInteger();
    private final AtomicInteger sendFailures = new AtomicInteger();
    private volatile long firstFrameNs, lastFrameNs, firstFrameWallMs;
    private volatile long startClockNs;      // frames stamped earlier are not part of the take
    private volatile boolean bootClock;      // sensor timestamps are boot time (REALTIME source)
    private volatile String helperResult;
    private final long wallMinusBootNs = System.currentTimeMillis() * 1_000_000L - SystemClock.elapsedRealtimeNanos();

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private DngHelper helper;
    private TimecodeAudio audio;
    private Size size;
    private final BlockingQueue<ByteBuffer> free = new ArrayBlockingQueue<>(BUFFERS);
    private final List<ByteBuffer> buffers = new ArrayList<>();
    private final Map<ByteBuffer, Integer> slotOf = new IdentityHashMap<>();
    private final Map<Long, TotalCaptureResult> results = new ConcurrentHashMap<>();
    private final AtomicBoolean templating = new AtomicBoolean();
    private volatile boolean armed;
    private CountDownLatch armedLatch = new CountDownLatch(1);

    RawTake(Context context, String clip, File dir, long startAtMs, long maxSeconds, Settings settings) {
        this.context = context.getApplicationContext();
        this.clip = clip;
        this.dir = dir;
        this.startAtMs = startAtMs;
        this.maxSeconds = maxSeconds;
        this.settings = settings;
    }

    /**
     * Why this phone cannot record 12-bit RAW takes, or null if it can: a back camera
     * with RAW capture, 12-bit samples (white level 4095) and full-size RAW at 30 fps.
     */
    static String unsupportedReason(Context context) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String id = rawCameraId(manager);
            if (id == null) return "This phone's back camera does not offer RAW capture";
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer white = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
            if (white == null || white != 4095) return "This camera's RAW is not 12-bit (white level " + white + ")";
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size s = largest(map.getOutputSizes(ImageFormat.RAW_SENSOR));
            if (s == null) return "This camera offers no RAW_SENSOR output";
            if (map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, s) > 1_000_000_000L / TARGET_FPS + 100_000) {
                return "This camera cannot deliver full-size RAW at 30 fps";
            }
            return null;
        } catch (Exception e) {
            return "Could not read the camera's RAW capabilities: " + e.getMessage();
        }
    }

    static String rawCameraId(CameraManager manager) throws Exception {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK || caps == null) continue;
            for (int cap : caps) if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) return id;
        }
        return null;
    }

    /** Start arming: open the camera and the helper, on a thread of the take's own. */
    void start(Surface preview) {
        new Thread(() -> {
            try {
                arm(preview);
            } catch (Throwable e) {
                fail("Could not start the take: " + e.getMessage(), e);
            }
        }, "KanahaRawTakeArm").start();
    }

    /** Wait up to {@code ms} until the take is armed (or has failed). */
    boolean awaitArmed(long ms) throws InterruptedException {
        return armedLatch.await(ms, TimeUnit.MILLISECONDS);
    }

    @SuppressLint("MissingPermission") // Kanaha Camera holds CAMERA
    private void arm(Surface preview) throws Exception {
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
        cameraThread = new HandlerThread("KanahaRawTakeCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        String id = rawCameraId(manager);
        CameraCharacteristics chars = manager.getCameraCharacteristics(id);
        Integer source = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        bootClock = source != null && source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
        size = largest(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.RAW_SENSOR));

        helper = DngHelper.start(context, dir, THREADS);
        camera = openCamera(manager, id);
        reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.RAW_SENSOR, 8);
        reader.setOnImageAvailableListener(r -> onFrame(r, chars), cameraHandler);

        List<Surface> surfaces = new ArrayList<>();
        surfaces.add(reader.getSurface());
        if (preview != null) surfaces.add(preview);
        session = createSession(surfaces);
        audio = new TimecodeAudio(new File(dir, "take.wav"));
        audio.start();   // before the first frame, so the WAV covers the whole take
        session.setRepeatingRequest(request(surfaces, false), captureCallback, cameraHandler);

        if (!armedLatch.await(10, TimeUnit.SECONDS) || !armed) {
            if (state != State.FAILED) fail("The take did not arm within 10 s", null);
            return;
        }
        if (settings.wbGains == null) {
            // Auto white balance has settled while arming; hold it for the take.
            session.setRepeatingRequest(request(surfaces, true), captureCallback, cameraHandler);
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult result) {
            if (armed) return;   // only the arming frame needs its result (for the DNG template)
            Long t = result.get(CaptureResult.SENSOR_TIMESTAMP);
            if (t != null) results.put(t, result);
        }
    };

    private CaptureRequest request(List<Surface> surfaces, boolean lockAwb) throws Exception {
        CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        for (Surface s : surfaces) b.addTarget(s);
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(TARGET_FPS, TARGET_FPS));
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        b.set(CaptureRequest.SENSOR_SENSITIVITY, settings.iso);
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.exposureNs);
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / TARGET_FPS);
        if (settings.focusDistance >= 0) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, settings.focusDistance);
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        }
        if (settings.wbGains != null && settings.wbTransform != null) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
            float[] g = settings.wbGains;
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, new RggbChannelVector(g[0], g[1], g[2], g[3]));
            int[] m = new int[18];
            for (int i = 0; i < 9; i++) { m[2 * i] = Math.round(settings.wbTransform[i] * 10000); m[2 * i + 1] = 10000; }
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, new ColorSpaceTransform(m));
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AWB_LOCK, lockAwb);
        }
        return b.build();
    }

    /** Every camera frame, on the camera thread. Never blocks beyond one copy and one small write. */
    private void onFrame(ImageReader r, CameraCharacteristics chars) {
        Image image = r.acquireNextImage();
        if (image == null) return;
        if (!armed) {
            if (templating.compareAndSet(false, true)) {
                new Thread(() -> buildTemplate(image, chars), "KanahaRawTakeTemplate").start();
            } else {
                image.close();
            }
            return;
        }
        long ts = image.getTimestamp();
        long clockNs = bootClock ? ts : SystemClock.elapsedRealtimeNanos();
        if (state == State.WAITING) {
            if (clockNs < startClockNs) { image.close(); return; }
            state = State.RECORDING;
            firstFrameNs = ts;
            firstFrameWallMs = ((bootClock ? ts : SystemClock.elapsedRealtimeNanos()) + wallMinusBootNs) / 1_000_000L;
            new Thread(() -> writeSidecar(false), "KanahaRawTakeSidecar").start();
        }
        if (state != State.RECORDING) { image.close(); return; }
        if (lastFrameNs != 0) {
            long gap = Math.round((double) (ts - lastFrameNs) * TARGET_FPS / 1e9);
            if (gap > 1) missing.addAndGet((int) (gap - 1));
        }
        lastFrameNs = ts;
        int index = frames.getAndIncrement();
        ByteBuffer copy = free.poll();
        if (copy == null) {
            drops.incrementAndGet();
            image.close();
            return;
        }
        copy.clear();
        copy.put(image.getPlanes()[0].getBuffer());
        copy.flip();
        image.close();   // the camera has its buffer back at once
        try {
            helper.sendFrame(slotOf.get(copy), index, copy.remaining());
        } catch (Exception e) {
            sendFailures.incrementAndGet();
            free.add(copy);
            Log.e(TAG, "sending a frame to the DNG helper failed", e);
        }
    }

    /** At arming: the DNG header every frame carries, the shared buffers, the helper's TAKE. */
    private void buildTemplate(Image image, CameraCharacteristics chars) {
        try {
            TotalCaptureResult result = null;
            for (int i = 0; i < 40 && result == null; i++) {
                result = results.get(image.getTimestamp());
                if (result == null) Thread.sleep(5);
            }
            if (result == null) throw new IllegalStateException("no capture result for the arming frame");
            ByteArrayOutputStream template = new ByteArrayOutputStream(26 << 20);
            try (DngCreator creator = new DngCreator(chars, result)) {
                creator.writeImage(template, image);
            }
            byte[] header = PackedDngWriter.fromTemplate(template.toByteArray()).header();
            Image.Plane plane = image.getPlanes()[0];
            int stride = plane.getRowStride();
            buffers.addAll(helper.allocate(BUFFERS, plane.getBuffer().remaining()));
            for (int i = 0; i < buffers.size(); i++) slotOf.put(buffers.get(i), i);
            // Buffers become usable only as the helper releases them, once it has mapped
            // them and checked each one's marker.
            helper.sendTake(size.getWidth(), size.getHeight(), stride, header, slot -> free.add(buffers.get(slot)));
            results.clear();
            long nowClock = SystemClock.elapsedRealtimeNanos();
            startClockNs = startAtMs > 0 ? Math.max(nowClock, startAtMs * 1_000_000L - wallMinusBootNs) : nowClock;
            state = State.WAITING;
            armed = true;
        } catch (Throwable e) {
            fail("Could not arm the take: " + e.getMessage(), e);
        } finally {
            image.close();
            armedLatch.countDown();
        }
    }

    /** End the take: stop the camera, let the helper finish writing, close everything. */
    void stop(String reason) {
        synchronized (this) {
            if (state == State.DONE || state == State.FAILED || state == State.STOPPING) return;
            state = State.STOPPING;
            endReason = reason;
        }
        try {
            // Let a frame already in the camera callback finish before the helper is told to end.
            CountDownLatch drained = new CountDownLatch(1);
            if (cameraHandler != null && cameraHandler.post(drained::countDown)) drained.await(2, TimeUnit.SECONDS);
            if (session != null) { session.stopRepeating(); session.close(); }
            if (audio != null) audio.stop();
            if (helper != null) helperResult = helper.finish(120);
            writeSidecar(true);
            state = State.DONE;
        } catch (Exception e) {
            fail("Stopping the take failed: " + e.getMessage(), e);
        } finally {
            close();
        }
    }

    /**
     * Android took the camera mid-take (another app, or this screen hidden): end the take
     * with what it has, rather than leave it "recording" with no frames arriving.
     */
    private void lost(String reason) {
        if (armed && isRunning() && state != State.STOPPING) {
            new Thread(() -> stop(reason), "KanahaRawTakeLost").start();
        }
    }

    private void fail(String message, Throwable e) {
        Log.e(TAG, message, e);
        synchronized (this) {
            if (state == State.DONE) return;
            error = message;
            state = State.FAILED;
        }
        armedLatch.countDown();
        try { if (audio != null) audio.stop(); } catch (InterruptedException ignored) { }
        if (helper != null) helper.abort();
        close();
    }

    private void close() {
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        if (camera != null) camera.close();
        if (reader != null) reader.close();
        if (cameraThread != null) cameraThread.quitSafely();
    }

    boolean isRunning() {
        State s = state;
        return s == State.ARMING || s == State.WAITING || s == State.RECORDING || s == State.STOPPING;
    }

    boolean isRecording() { return state == State.RECORDING; }

    double seconds() {
        long first = firstFrameNs, last = lastFrameNs;
        return first == 0 || last <= first ? 0 : (last - first) / 1e9;
    }

    long freeBytes() { return dir.getUsableSpace(); }

    /** What getStatus and stopRecording report about this take. */
    JSONObject status() throws JSONException {
        JSONObject s = new JSONObject();
        s.put("take", dir.getName());
        s.put("clip_name", clip);
        s.put("state", state.name().toLowerCase(java.util.Locale.US));
        s.put("frames", frames.get());
        s.put("dropped", drops.get());
        s.put("missing_at_sensor", missing.get());
        s.put("seconds", Math.round(seconds() * 10) / 10.0);
        s.put("free_gb", Math.round(freeBytes() / 1e9));
        if (firstFrameWallMs != 0) s.put("recording_start_ms", firstFrameWallMs);
        if (helper != null) {
            s.put("written", helper.written.get());
            s.put("bytes", helper.bytes.get());
            if (helper.failures.get() + sendFailures.get() > 0) s.put("failures", helper.failures.get() + sendFailures.get());
        }
        if (helperResult != null) s.put("helper", helperResult);
        if (endReason != null) s.put("ended_by", endReason);
        if (error != null) s.put("error", error);
        return s;
    }

    /** take.kanaha_start.json: when the take began, and at the end what it holds. */
    private void writeSidecar(boolean finished) {
        try {
            JSONObject j = status();
            j.put("raw12", true);
            j.put("resolution", size.getWidth() + "x" + size.getHeight());
            j.put("fps", TARGET_FPS);
            j.put("iso", settings.iso);
            j.put("exposure_time_ns", settings.exposureNs);
            j.put("audio", audio != null ? audio.fileName() : JSONObject.NULL);
            if (audio != null && bootClock && audio.firstSampleBootNs() != 0 && firstFrameNs != 0) {
                // Where the first frame falls in take.wav, so every frame maps to timecode.
                j.put("first_frame_after_first_sample_ms", Math.round((firstFrameNs - audio.firstSampleBootNs()) / 1e5) / 10.0);
            }
            if (finished && audio != null) j.put("audio_seconds", Math.round(audio.seconds() * 10) / 10.0);
            j.put("finished", finished);
            File tmp = new File(dir, SIDECAR + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(j.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(new File(dir, SIDECAR))) Log.w(TAG, "could not rename " + tmp);
        } catch (Exception e) {
            Log.e(TAG, "could not write the take sidecar", e);
        }
    }

    @SuppressLint("MissingPermission")
    private CameraDevice openCamera(CameraManager manager, String id) throws Exception {
        // OpenCamera closes its camera as this screen comes to the front, on a thread of
        // its own; until it has, opening fails as in use, so retry for a few seconds.
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (true) {
            CountDownLatch latch = new CountDownLatch(1);
            CameraDevice[] opened = new CameraDevice[1];
            int[] err = {0};
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) { opened[0] = c; latch.countDown(); }
                @Override public void onDisconnected(CameraDevice c) { c.close(); err[0] = -1; lost("camera disconnected"); latch.countDown(); }
                @Override public void onError(CameraDevice c, int e) { c.close(); err[0] = e; lost("camera error " + e); latch.countDown(); }
            }, cameraHandler);
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("the camera did not open");
            if (opened[0] != null) return opened[0];
            if (SystemClock.uptimeMillis() > deadline) throw new IllegalStateException("the camera is in use (error " + err[0] + ")");
            Thread.sleep(200);
        }
    }

    @SuppressWarnings("deprecation") // the List<Surface> form keeps minSdk 23
    private CameraCaptureSession createSession(List<Surface> surfaces) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        CameraCaptureSession[] s = new CameraCaptureSession[1];
        camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
            @Override public void onConfigured(CameraCaptureSession c) { s[0] = c; latch.countDown(); }
            @Override public void onConfigureFailed(CameraCaptureSession c) { latch.countDown(); }
        }, cameraHandler);
        if (!latch.await(10, TimeUnit.SECONDS) || s[0] == null) throw new IllegalStateException("the capture session did not configure");
        return s[0];
    }

    static Size largest(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        return Arrays.stream(sizes).max((a, b) -> Long.compare((long) a.getWidth() * a.getHeight(),
                (long) b.getWidth() * b.getHeight())).get();
    }
}
