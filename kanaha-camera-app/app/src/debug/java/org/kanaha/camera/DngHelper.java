package org.kanaha.camera;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One take's compressor: the native program libkanaha_dng.so (app/src/main/cpp/dng/
 * kanaha_dng.c), started and fed here. No JNI: like httpd (ApacheService), it is an
 * executable in nativeLibraryDir that ProcessBuilder runs as a child process.
 *
 * <p>How a take flows:
 * <ol>
 *   <li>{@link #start} makes a named pipe (FIFO) in the app's cache directory and runs
 *       the helper with it, the output folder and a thread count.</li>
 *   <li>{@link #sendTake} sends the frame size and the DNG header (PackedDngWriter's,
 *       built from the camera's own DngCreator file).</li>
 *   <li>{@link #sendFrame} writes each camera frame into the pipe: a 16-byte header,
 *       then the 16-bit samples straight from shared memory. The helper compresses it
 *       (lossless JPEG) and writes {@code frame_NNNNNN.dng}.</li>
 *   <li>{@link #finish} sends END and waits for the helper to write every frame.</li>
 * </ol>
 * The helper answers one line per frame on its stdout ({@code OK index bytes compress_ms write_ms},
 * {@code ERR ...}, finally {@code DONE frames bytes}); a reader thread here counts them.
 * When the helper falls behind, the pipe fills and {@link #sendFrame} blocks, so the
 * caller's frame buffers run out and it drops frames - which it counts.
 *
 * <p>Message format, little-endian: "KDNG", uint32 type (1 TAKE, 2 FRAME, 3 END),
 * uint32 index, uint32 payload length, then the payload. See kanaha_dng.c.
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
    private volatile String done;

    private final Process process;
    private final File fifo;
    private final FileChannel pipe;
    private final Thread reader;
    private final ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);

    private DngHelper(Process process, File fifo, FileChannel pipe, Thread reader) {
        this.process = process;
        this.fifo = fifo;
        this.pipe = pipe;
        this.reader = reader;
    }

    /** Run the helper for a take written into {@code outDir} by {@code threads} encoders. */
    static DngHelper start(Context context, File outDir, int threads) throws IOException, ErrnoException, InterruptedException {
        File fifo = new File(context.getCacheDir(), "kanaha_dng.fifo");
        if (fifo.exists() && !fifo.delete()) throw new IOException("cannot remove old " + fifo);
        Os.mkfifo(fifo.getAbsolutePath(), 0600);   // only this app (its UID) can open it

        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        ProcessBuilder pb = new ProcessBuilder(nativeLibDir + "/libkanaha_dng.so",
                fifo.getAbsolutePath(), outDir.getAbsolutePath(), String.valueOf(threads));
        pb.redirectErrorStream(true);
        pb.environment().put("LD_LIBRARY_PATH", nativeLibDir);
        Process process = pb.start();

        // The helper's stdout: one line per frame. Counted here, logged when not OK.
        DngHelper[] self = new DngHelper[1];
        CountDownLatch constructed = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                constructed.await();
                for (String line; (line = in.readLine()) != null; ) self[0].onLine(line);
            } catch (Exception e) {
                Log.e(TAG, "reading the helper's output failed", e);
            }
        }, "DngHelperOutput");
        reader.start();

        FileChannel pipe;
        try {
            pipe = openForWriting(fifo, process);
        } catch (IOException | ErrnoException e) {
            process.destroy();
            throw e;
        }
        self[0] = new DngHelper(process, fifo, pipe, reader);
        constructed.countDown();
        return self[0];
    }

    /**
     * Open the pipe for writing. A plain open blocks until the helper opens its end,
     * forever if it died first; so open without blocking, retry for up to 5 s while the
     * helper starts, then switch the pipe back to blocking writes (back-pressure).
     */
    private static FileChannel openForWriting(File fifo, Process process) throws IOException, ErrnoException, InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (true) {
            try {
                FileDescriptor fd = Os.open(fifo.getAbsolutePath(), OsConstants.O_WRONLY | OsConstants.O_NONBLOCK, 0);
                Os.fcntlInt(fd, OsConstants.F_SETFL, OsConstants.O_WRONLY);   // blocking again
                return new FileOutputStream(fd).getChannel();
            } catch (ErrnoException e) {
                // ENXIO: the helper has not opened its end yet.
                if (e.errno != OsConstants.ENXIO) throw e;
                if (!process.isAlive()) throw new IOException("the DNG helper exited with " + process.exitValue());
                if (System.currentTimeMillis() > deadline) throw new IOException("the DNG helper did not open the pipe");
                Thread.sleep(20);
            }
        }
    }

    private void onLine(String line) {
        if (line.startsWith("OK ")) {
            String[] p = line.split(" ");
            if (p.length == 5) {
                written.incrementAndGet();
                bytes.addAndGet(Long.parseLong(p[2]));
                long compress = Long.parseLong(p[3]), write = Long.parseLong(p[4]);
                compressMs.add(compress);
                writeMs.add(write);
                frameMs.add(compress + write);
            }
        } else if (line.startsWith("DONE")) {
            done = line;
        } else if (line.startsWith("ERR")) {
            failures.incrementAndGet();
            Log.e(TAG, line);
        } else if (!line.equals("READY")) {
            Log.i(TAG, line);
        }
    }

    /** The take: frame size and the DNG header every frame file starts with. */
    void sendTake(int width, int height, int strideBytes, byte[] dngHeader) throws IOException {
        ByteBuffer take = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        take.putInt(width).putInt(height).putInt(strideBytes).putInt(dngHeader.length).flip();
        send(TAKE, 0, 16 + dngHeader.length);
        writeAll(take);
        writeAll(ByteBuffer.wrap(dngHeader));
    }

    /** One frame: {@code samples} from position to limit. Blocks while the helper is behind. */
    void sendFrame(int index, ByteBuffer samples) throws IOException {
        send(FRAME, index, samples.remaining());
        writeAll(samples);
    }

    /** No more frames: wait (up to {@code seconds}) for the helper to write the rest. */
    String finish(int seconds) throws IOException, InterruptedException {
        try {
            send(END, 0, 0);
        } finally {
            pipe.close();
        }
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
            process.destroy();
            throw new IOException("the DNG helper did not finish in " + seconds + " s");
        }
        reader.join(2000);
        if (!fifo.delete()) Log.w(TAG, "could not remove " + fifo);
        return done != null ? done : "exit " + process.exitValue();
    }

    private void send(int type, int index, int length) throws IOException {
        header.clear();
        header.put((byte) 'K').put((byte) 'D').put((byte) 'N').put((byte) 'G');
        header.putInt(type).putInt(index).putInt(length).flip();
        writeAll(header);
    }

    private void writeAll(ByteBuffer b) throws IOException {
        while (b.hasRemaining()) pipe.write(b);
    }
}
