package org.kanaha.camera;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/**
 * One take's compressor: the native program libkanaha_dng.so (app/src/main/cpp/dng/
 * kanaha_dng.c), started and fed here. No JNI: like httpd (ApacheService), it is an
 * executable in nativeLibraryDir that ProcessBuilder runs as a child process.
 *
 * <p>Frames are not streamed to it. The take's frame buffers are shared memory
 * (SharedMemory) made here; their file descriptors go to the helper once, attached to the
 * TAKE message on a Unix domain socket, and it maps the same memory. Per frame only
 * a short message travels: "slot 7 holds frame 1234". Streaming 25 MB a frame
 * through a pipe instead fell behind once the phone warmed.
 *
 * <p>How a take flows:
 * <ol>
 *   <li>{@link #start} runs the helper, which listens on a socket in the app's cache
 *       directory, and connects to it.</li>
 *   <li>{@link #allocate} makes the frame buffers. The caller uses none of them yet.</li>
 *   <li>{@link #sendTake} sends the frame size, the DNG header (PackedDngWriter's,
 *       from the camera's own DngCreator file) and the buffers' descriptors.</li>
 *   <li>The helper maps the buffers, checks the marker in each, and releases every
 *       slot once: only then does the {@code released} callback hand a buffer to the
 *       caller, so no frame can overwrite a marker before it is checked.</li>
 *   <li>{@link #sendFrame} says which buffer holds which frame. When the helper has
 *       written that frame's DNG it releases the slot again.</li>
 *   <li>{@link #finish} sends END and waits for every frame to be written.</li>
 * </ol>
 * The helper also prints one line per frame on stdout ({@code OK index bytes
 * compress_ms write_ms queued_ms}, {@code ERR ...}, {@code DONE frames bytes});
 * a reader thread here counts them.
 */
final class DngHelper {
    private static final String TAG = "KANAHA_DNG";
    private static final int TAKE = 1, FRAME = 2, END = 3;

    final AtomicInteger written = new AtomicInteger();
    final AtomicInteger failures = new AtomicInteger();
    final AtomicLong bytes = new AtomicLong();
    final List<Long> frameMs = Collections.synchronizedList(new ArrayList<>());     // compress + write
    final List<Long> compressMs = Collections.synchronizedList(new ArrayList<>());
    final List<Long> writeMs = Collections.synchronizedList(new ArrayList<>());
    final List<Long> queuedMs = Collections.synchronizedList(new ArrayList<>());    // waiting for an encoder thread
    private volatile String done;

    private final Process process;
    private final LocalSocket socket;
    private final OutputStream out;
    private final Thread output;
    private final List<ParcelFileDescriptor> memfds = new ArrayList<>();   // the buffers' descriptors, sent to the helper
    private final List<SharedMemory> shared = new ArrayList<>();
    private Thread releases;
    private int bufferBytes;

    private DngHelper(Process process, LocalSocket socket, Thread output) throws IOException {
        this.process = process;
        this.socket = socket;
        this.out = socket.getOutputStream();
        this.output = output;
    }

    /** Run the helper for a take written into {@code outDir} by {@code threads} encoders. */
    static DngHelper start(Context context, File outDir, int threads) throws IOException, InterruptedException {
        // Unix socket paths are limited to 108 bytes; the app's cache directory is short.
        File socketFile = new File(context.getCacheDir(), "kdng.sock");
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        ProcessBuilder pb = new ProcessBuilder(nativeLibDir + "/libkanaha_dng.so",
                socketFile.getAbsolutePath(), outDir.getAbsolutePath(), String.valueOf(threads));
        pb.redirectErrorStream(true);
        pb.environment().put("LD_LIBRARY_PATH", nativeLibDir);
        Process process = pb.start();

        // The helper's stdout: READY once it listens, then one line per frame.
        DngHelper[] self = new DngHelper[1];
        CountDownLatch ready = new CountDownLatch(1), constructed = new CountDownLatch(1);
        Thread output = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                for (String line; (line = in.readLine()) != null; ) {
                    if (line.equals("READY")) { ready.countDown(); continue; }
                    constructed.await();
                    if (self[0] != null) self[0].onLine(line); else Log.e(TAG, line);
                }
            } catch (Exception e) {
                Log.e(TAG, "reading the helper's output failed", e);
            } finally {
                ready.countDown();
                constructed.countDown();
            }
        }, "DngHelperOutput");
        output.start();

        if (!ready.await(5, TimeUnit.SECONDS) || !process.isAlive()) {
            process.destroy();
            throw new IOException("the DNG helper did not start");
        }
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(socketFile.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
            self[0] = new DngHelper(process, socket, output);
        } catch (IOException e) {
            process.destroy();
            throw e;
        } finally {
            constructed.countDown();
        }
        return self[0];
    }

    /**
     * Make {@code count} frame buffers of {@code size} bytes as shared memory the helper
     * will map: memory with a file descriptor, never on flash.
     *
     * <p>SharedMemory maps it for Java; its descriptor, which the helper needs, comes out
     * through its public Parcelable form (writeToParcel writes exactly the descriptor).
     * Making a memfd directly does not work: mapping it from Java means opening
     * /proc/self/fd, and the app's SELinux policy refuses that (EACCES).
     */
    List<ByteBuffer> allocate(int count, int size) throws IOException, ErrnoException {
        bufferBytes = size;
        List<ByteBuffer> buffers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            SharedMemory shm = SharedMemory.create("kdng" + i, size);
            shared.add(shm);
            buffers.add(shm.mapReadWrite());
            Parcel parcel = Parcel.obtain();
            ParcelFileDescriptor pfd;
            try {
                shm.writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                pfd = parcel.readFileDescriptor();
            } finally {
                parcel.recycle();
            }
            if (pfd == null) throw new IOException("SharedMemory gave no descriptor");
            memfds.add(pfd);
            // A marker the helper checks once it has mapped the buffer, proving it sees this
            // memory (the descriptor is ashmem on the Pixel, whose size fstat reports as 0,
            // so the check has to be end to end). The first frame overwrites it.
            ByteBuffer b = buffers.get(i);
            b.put(0, (byte) 'K').put(1, (byte) 'D').put(2, (byte) 'N').put(3, (byte) 'G')
             .put(4, (byte) 'B').put(5, (byte) 'U').put(6, (byte) 'F').put(7, (byte) i);
        }
        return buffers;
    }

    /**
     * The take: frame size, the DNG header every frame file starts with, and the
     * buffers from {@link #allocate}. {@code released} is called with a buffer's slot
     * each time the helper has finished with it.
     */
    void sendTake(int width, int height, int strideBytes, byte[] dngHeader, IntConsumer released) throws IOException {
        ByteBuffer m = ByteBuffer.allocate(16 + 20 + dngHeader.length).order(ByteOrder.LITTLE_ENDIAN);
        m.put((byte) 'K').put((byte) 'D').put((byte) 'N').put((byte) 'G');
        m.putInt(TAKE).putInt(memfds.size()).putInt(20 + dngHeader.length);
        m.putInt(width).putInt(height).putInt(strideBytes).putInt(bufferBytes).putInt(dngHeader.length);
        m.put(dngHeader);
        FileDescriptor[] fds = new FileDescriptor[memfds.size()];
        for (int i = 0; i < fds.length; i++) fds[i] = memfds.get(i).getFileDescriptor();
        socket.setFileDescriptorsForSend(fds);   // attached to the next write: the whole message
        out.write(m.array());
        out.flush();
        socket.setFileDescriptorsForSend(null);

        DataInputStream in = new DataInputStream(socket.getInputStream());
        releases = new Thread(() -> {
            byte[] slot = new byte[4];
            try {
                while (true) {
                    in.readFully(slot);
                    released.accept(ByteBuffer.wrap(slot).order(ByteOrder.LITTLE_ENDIAN).getInt());
                }
            } catch (EOFException e) {
                // the helper closed the connection: the take is over
            } catch (IOException e) {
                Log.e(TAG, "reading released buffers failed", e);
            }
        }, "DngHelperReleases");
        releases.start();
    }

    /** Buffer {@code slot} holds frame {@code index}, {@code length} bytes of samples. */
    synchronized void sendFrame(int slot, int index, int length) throws IOException {
        ByteBuffer m = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        m.put((byte) 'K').put((byte) 'D').put((byte) 'N').put((byte) 'G');
        m.putInt(FRAME).putInt(index).putInt(8).putInt(slot).putInt(length);
        out.write(m.array());
    }

    /** No more frames: wait (up to {@code seconds}) for the helper to write the rest. */
    String finish(int seconds) throws IOException, InterruptedException {
        try {
            synchronized (this) {
                ByteBuffer m = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                m.put((byte) 'K').put((byte) 'D').put((byte) 'N').put((byte) 'G').putInt(END).putInt(0).putInt(0);
                out.write(m.array());
            }
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroy();
                throw new IOException("the DNG helper did not finish in " + seconds + " s");
            }
        } finally {
            socket.close();
            for (ParcelFileDescriptor pfd : memfds) pfd.close();
            for (SharedMemory shm : shared) shm.close();   // mappings stay valid until unmapped
        }
        if (releases != null) releases.join(2000);
        output.join(2000);
        return done != null ? done : "exit " + process.exitValue();
    }

    /** The take failed before it started: stop the helper and free the buffers. */
    void abort() {
        process.destroy();
        try { socket.close(); } catch (IOException ignored) { }
        for (ParcelFileDescriptor pfd : memfds) { try { pfd.close(); } catch (IOException ignored) { } }
        for (SharedMemory shm : shared) shm.close();
    }

    private void onLine(String line) {
        if (line.startsWith("OK ")) {
            String[] p = line.split(" ");
            if (p.length == 6) {
                written.incrementAndGet();
                bytes.addAndGet(Long.parseLong(p[2]));
                long compress = Long.parseLong(p[3]), write = Long.parseLong(p[4]);
                compressMs.add(compress);
                writeMs.add(write);
                queuedMs.add(Long.parseLong(p[5]));
                frameMs.add(compress + write);
            }
        } else if (line.startsWith("DONE")) {
            done = line;
        } else if (line.startsWith("ERR")) {
            failures.incrementAndGet();
            Log.e(TAG, line);
        } else {
            Log.i(TAG, line);
        }
    }
}
