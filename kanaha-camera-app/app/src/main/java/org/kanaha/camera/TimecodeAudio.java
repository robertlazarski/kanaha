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

/**
 * The audio of a 12-bit RAW take: a WAV of the phone's audio input, 48 kHz stereo
 * 16-bit. With the iRig attached that input is the Tentacle's SMPTE timecode, so the
 * WAV is what lines the take's frames up with every other camera and the sound
 * recorder. It also records the boot-time instant of its first sample, the clock the
 * camera stamps frames with, so each frame's place in the WAV is known exactly.
 */
final class TimecodeAudio {
    private static final String TAG = "KanahaTimecodeAudio";
    static final int RATE = 48000;

    private final File file;
    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;
    private volatile long dataBytes;
    private volatile long firstSampleBootNs;
    private volatile int readErrors;

    TimecodeAudio(File file) {
        this.file = file;
    }

    @SuppressLint("MissingPermission") // Kanaha Camera holds RECORD_AUDIO
    void start() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        // UNPROCESSED: no gain control or noise suppression, which would mangle timecode.
        record = new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, RATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, RATE * 4 / 2));
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
                if (firstSampleBootNs == 0 && n > 0) {
                    AudioTimestamp at = new AudioTimestamp();
                    if (record.getTimestamp(at, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                        firstSampleBootNs = at.nanoTime - at.framePosition * 1_000_000_000L / RATE;
                    }
                }
                raf.write(buf, 0, n);
                dataBytes += n;
            }
            raf.seek(0);
            raf.write(wavHeader(dataBytes));
        } catch (Exception e) {
            Log.e(TAG, "timecode audio capture failed", e);
        }
    }

    void stop() throws InterruptedException {
        if (!running) return;
        running = false;
        record.stop();
        thread.join(5000);
        record.release();
    }

    /** Boot-time ns of the WAV's first sample, or 0 if the platform did not say. */
    long firstSampleBootNs() { return firstSampleBootNs; }

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
