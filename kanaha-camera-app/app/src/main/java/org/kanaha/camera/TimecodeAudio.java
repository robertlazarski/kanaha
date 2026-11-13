package org.kanaha.camera;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * A WAV of the phone's audio input, 48 kHz stereo 16-bit: the audio of a 12-bit RAW
 * take, and the separate timecode track of a normal recording ({@link ClipTimecode}).
 * With the iRig attached that input is the Tentacle's SMPTE timecode, so the WAV is
 * what lines the frames up with every other camera and the sound recorder. It also
 * records the boot-time instant of its first sample, the clock the camera stamps
 * frames with, so each frame's place in the WAV is known exactly.
 */
final class TimecodeAudio {
    private static final String TAG = "KanahaTimecodeAudio";
    static final int RATE = 48000;

    private final File file;
    private final int source;
    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;
    private volatile long dataBytes;
    private volatile long firstSampleBootNs;
    private volatile long stampIqrNs;
    private volatile int readErrors;
    private final AudioTimestamp at = new AudioTimestamp();
    private final long[] stamps = new long[100];   // one per 100 ms read, ten seconds' worth
    private final long[] stampPos = new long[100];
    private int stampCount;
    private volatile boolean stampDone;
    private long startPos;                         // median frame position of the first window
    // The last ten seconds of timestamps, as a ring, for the rate (settleRate).
    private final long[] endStamps = new long[100];
    private final long[] endPos = new long[100];
    private int endCount;
    private volatile double rateHz;                // the input's real rate on the boot clock, or 0

    /** UNPROCESSED: no gain control or noise suppression, which would mangle timecode. */
    TimecodeAudio(File file) {
        this(file, MediaRecorder.AudioSource.UNPROCESSED);
    }

    /** source: a MediaRecorder.AudioSource, such as the one a recording's own audio uses. */
    TimecodeAudio(File file, int source) {
        this.file = file;
        this.source = source;
    }

    @SuppressLint("MissingPermission") // Kanaha Camera holds RECORD_AUDIO
    void start() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        record = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, RATE * 4 / 2));
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            // Another capture can hold the input; say so rather than write an empty WAV.
            record.release();
            record = null;
            throw new IllegalStateException("audio input could not be opened (source " + source + ")");
        }
        running = true;
        record.startRecording();
        thread = new Thread(this::capture, "KanahaTimecodeAudio");
        thread.start();
    }

    private void capture() {
        byte[] buf = new byte[RATE * 4 / 10];   // 100 ms
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(0);
            raf.write(new byte[44]);            // the header goes in at the end, with the size
            while (running) {
                int n = record.read(buf, 0, buf.length);
                if (n < 0) { readErrors++; continue; }
                raf.write(buf, 0, n);
                dataBytes += n;
                // The first timestamps after startRecording can be tens of ms off, and one
                // bad reading shifts every frame of the take alike. Skip the first second and
                // keep the median of what each read implies over the next ten.
                if (dataBytes < RATE * 4L) continue;
                if (record.getTimestamp(at, AudioTimestamp.TIMEBASE_BOOTTIME) != AudioRecord.SUCCESS) continue;
                long implied = at.nanoTime - at.framePosition * 1_000_000_000L / RATE;
                if (!stampDone) {
                    stampPos[stampCount] = at.framePosition;
                    stamps[stampCount++] = implied;
                    if (stampCount == stamps.length || dataBytes >= RATE * 4L * 11) settleStamp();
                } else {
                    endPos[endCount % endPos.length] = at.framePosition;
                    endStamps[endCount++ % endStamps.length] = implied;
                }
            }
            settleStamp();                      // a take shorter than 11 s
            settleRate();
            raf.seek(0);
            raf.write(wavHeader(dataBytes));
        } catch (Exception e) {
            Log.e(TAG, "timecode audio capture failed", e);
        }
    }

    private void settleStamp() {
        if (stampDone || stampCount == 0) return;
        long[] s = Arrays.copyOf(stamps, stampCount);
        Arrays.sort(s);
        firstSampleBootNs = s[stampCount / 2];
        stampIqrNs = s[stampCount * 3 / 4] - s[stampCount / 4];
        startPos = median(stampPos, stampCount);
        stampDone = true;
    }

    /**
     * The input's real sample rate on the boot clock, from the first ten seconds of
     * timestamps and the last ten. Each timestamp implies a first-sample time assuming
     * exactly 48 kHz; a converter running a few ppm off moves that implied time steadily
     * through the take (1 ms over a 4-minute song at 4 ppm), and the two windows' medians
     * measure it. Needs a minute between them to be good to about 1 ppm.
     */
    private void settleRate() {
        int n = Math.min(endCount, endStamps.length);
        if (!stampDone || n < 10) return;
        long endP = median(endPos, n);
        if (endP - startPos < RATE * 60L) return;
        double excess = (double) (median(endStamps, n) - firstSampleBootNs) / (endP - startPos);   // ns a sample
        rateHz = 1e9 / (1e9 / RATE + excess);
        // The first window sat a few seconds in, so its first-sample time carries that much of the error too.
        firstSampleBootNs -= Math.round(startPos * excess);
    }

    private static long median(long[] a, int n) {
        long[] s = Arrays.copyOf(a, n);
        Arrays.sort(s);
        return s[n / 2];
    }

    void stop() throws InterruptedException {
        if (!running || record == null) return;
        running = false;
        record.stop();
        thread.join(5000);
        record.release();
    }

    /** Boot-time ns of the WAV's first sample, or 0 if the platform did not say. */
    long firstSampleBootNs() { return firstSampleBootNs; }

    /** The interquartile range of the timestamps behind firstSampleBootNs. */
    long stampIqrNs() { return stampIqrNs; }

    /** The input's measured sample rate on the boot clock, or 0 if the take was under about 80 s. */
    double rateHz() { return rateHz; }

    double seconds() { return dataBytes / (RATE * 4.0); }

    int readErrors() { return readErrors; }

    String fileName() { return file.getName(); }

    private static byte[] wavHeader(long data) {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt((int) (36 + data)).put("WAVE".getBytes());
        h.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 2)
                .putInt(RATE).putInt(RATE * 4).putShort((short) 4).putShort((short) 16);
        h.put("data".getBytes()).putInt((int) data);
        return h.array();
    }
}
