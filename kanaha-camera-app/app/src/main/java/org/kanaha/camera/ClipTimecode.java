package org.kanaha.camera;

import android.content.Context;
import android.media.MediaRecorder;
import android.preference.PreferenceManager;
import android.util.Log;

import net.sourceforge.opencamera.PreferenceKeys;
import net.sourceforge.opencamera.cameracontroller.CameraController2;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

/**
 * The separate timecode track of a normal recording: a WAV of the timecode input timed
 * against the camera's own clock, and a log of every frame's sensor timestamp, so the
 * clip's frames can be placed on timecode without trusting the MP4.
 *
 * <p>Why: a recording's MP4 lines its audio up with its picture by when each audio
 * buffer reached the app, which misses the delay inside the iRig and USB. On the Moto
 * G Play that put the picture about 42 ms (1.3 frames) early against its own timecode.
 * A 12-bit take avoids it by stamping its WAV and its frames on the same clock, and
 * this does the same for normal video:
 * <ul>
 * <li>{@code <basename>.timecode.wav}: the input, with the boot-time instant of its first
 *     sample (Android's AudioTimestamp) in the start sidecar;
 * <li>{@code <basename>.frames.csv}: frame number, sensor timestamp, exposure,
 *     rolling-shutter skew and the frame duration the camera reports, of every frame the camera produced while it ran, a little
 *     longer than the recording on both ends.
 * </ul>
 * The MP4 keeps its own audio. tools/kanaha-timecode-offset matches the MP4's frames to
 * the log by their timing and prints how far the MP4's timecode is off, the number
 * parseLTC_kanaha.sh takes as a camera's timecode offset.
 *
 * <p>Optional: the "Separate timecode track" video setting, or startRecording with
 * "timecode_audio": true for one recording. Both files sit beside the start sidecar and
 * travel with the clip (listFiles, deleteFiles, sftpTransfer).
 *
 * <p>Driven by OpenCamera's recording callbacks (MyApplicationInterface), so it covers a
 * recording however it was started: startingVideo before the recorder starts (the frame
 * log), startedVideo once it has (the WAV), stoppedVideo once the file is complete, and
 * abort if the recording never started.
 */
public final class ClipTimecode {
    private static final String TAG = "KanahaClipTimecode";
    static final String WAV_SUFFIX = ".timecode.wav";
    static final String FRAMES_SUFFIX = ".frames.csv";

    /** Set by startRecording's "timecode_audio": the next recording records one, whatever the setting. */
    static volatile boolean requestedForNext;

    private static ClipTimecode active;

    private final Context context;
    private final String videoName;
    private final File wav, frames;
    private final TimecodeAudio audio;
    private final BufferedWriter frameOut;
    private final boolean bootClock;
    private int frameCount;

    private ClipTimecode(Context context, String videoName, int audioSource, boolean bootClock) throws IOException {
        this.context = context.getApplicationContext();
        this.videoName = videoName;
        this.bootClock = bootClock;
        String base = baseName(videoName);
        File dir = CameraControlReceiver.sidecarDirectory(this.context);
        wav = new File(dir, base + WAV_SUFFIX);
        frames = new File(dir, base + FRAMES_SUFFIX);
        frameOut = new BufferedWriter(new FileWriter(frames));
        frameOut.write("frame_number,timestamp_ns,exposure_ns,rolling_shutter_skew_ns,frame_duration_ns\n");
        audio = new TimecodeAudio(wav, audioSource);
    }

    /**
     * A recording is about to start into videoName: start the frame log, if this
     * recording wants a separate track, so it covers the first frame. audioSourcePref is
     * OpenCamera's audio source setting, so the WAV will record the input the way the
     * MP4's audio does (on the Pixel the unprocessed input clips).
     */
    public static synchronized void startingVideo(Context context, String videoName, String audioSourcePref,
                                                  boolean bootClock) {
        boolean wanted = requestedForNext || PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PreferenceKeys.ClipTimecodePreferenceKey, false);
        requestedForNext = false;
        if (active != null) {
            // A restart into a new file (maximum file size) without a stop for the old one.
            active.finish();
            active = null;
        }
        if (!wanted || videoName == null) return;
        try {
            ClipTimecode c = new ClipTimecode(context, videoName, audioSource(audioSourcePref), bootClock);
            CameraController2.kanahaFrameListener = c::onFrame;
            active = c;
        } catch (Exception e) {
            Log.w(TAG, "Separate timecode track not recorded for " + videoName + ": " + e.getMessage());
            deleteFiles(context, videoName);
        }
    }

    /**
     * The recorder has started: open the timecode input now, not before. An audio
     * capture already open when MediaRecorder starts can make the start fail (OpenCamera
     * stops its own audio listener for that reason). The WAV begins a moment after the
     * first frame, which costs nothing: frames are matched wherever both run.
     */
    public static synchronized void startedVideo() {
        if (active == null) return;
        try {
            active.audio.start();
            Log.i(TAG, "Separate timecode track started for " + active.videoName);
        } catch (Exception e) {
            // A phone that will not give one app the input twice: the recording goes on
            // without the separate track.
            Log.w(TAG, "Separate timecode track not recorded for " + active.videoName + ": " + e.getMessage());
            abort();
        }
    }

    /** The recording has stopped and its file is complete. */
    public static synchronized void stoppedVideo() {
        if (active == null) return;
        active.finish();
        active = null;
    }

    /** The recording failed to start: nothing to keep. */
    public static synchronized void abort() {
        if (active == null) return;
        ClipTimecode c = active;
        active = null;
        c.close();
        deleteFiles(c.context, c.videoName);
    }

    private void onFrame(long frameNumber, long timestampNs, long exposureNs, long skewNs, long frameDurationNs) {
        synchronized (frameOut) {
            try {
                frameOut.write(String.format(Locale.US, "%d,%d,%d,%d,%d\n", frameNumber, timestampNs, exposureNs, skewNs,
                        frameDurationNs));
                frameCount++;
            } catch (IOException ignored) {
                // a full disk ends the recording too; the frame count in the sidecar shows it
            }
        }
    }

    /** Stop both and record what they hold in the clip's start sidecar. */
    private void finish() {
        close();
        try {
            File sidecar = CameraControlReceiver.startSidecarFor(context, videoName);
            JSONObject j = sidecar.isFile() ? new JSONObject(readAll(sidecar)) : new JSONObject();
            if (!j.has("file")) j.put("file", videoName);
            j.put("timecode_audio", wav.getName());
            j.put("timecode_audio_first_sample_boot_ns", audio.firstSampleBootNs());
            j.put("timecode_audio_stamp_iqr_ns", audio.stampIqrNs());
            j.put("timecode_audio_seconds", Math.round(audio.seconds() * 10) / 10.0);
            j.put("timecode_audio_read_errors", audio.readErrors());
            j.put("frame_log", frames.getName());
            j.put("frame_log_frames", frameCount);
            // A REALTIME sensor stamps frames on the boot clock, the clock the WAV's first
            // sample is given in; anything else cannot be lined up with it.
            j.put("frame_clock", bootClock ? "boottime" : "unknown");
            File tmp = new File(sidecar.getParentFile(), sidecar.getName() + ".tmp");
            try (FileWriter fw = new FileWriter(tmp)) {
                fw.write(j.toString(2));
            }
            if (!tmp.renameTo(sidecar)) Log.w(TAG, "Could not rename the start sidecar into place: " + sidecar);
            Log.i(TAG, "Separate timecode track for " + videoName + ": " + frameCount + " frames, "
                    + audio.seconds() + " s of audio");
        } catch (Exception e) {
            Log.w(TAG, "Separate timecode track: sidecar not updated for " + videoName, e);
        }
    }

    private void close() {
        CameraController2.kanahaFrameListener = null;
        try {
            audio.stop();
        } catch (Exception e) {
            Log.w(TAG, "timecode audio stop: " + e.getMessage());
        }
        synchronized (frameOut) {
            try {
                frameOut.close();
            } catch (IOException ignored) { }
        }
    }

    private static void deleteFiles(Context context, String videoName) {
        File dir = CameraControlReceiver.sidecarDirectory(context);
        String base = baseName(videoName);
        new File(dir, base + WAV_SUFFIX).delete();
        new File(dir, base + FRAMES_SUFFIX).delete();
    }

    /** OpenCamera's audio source setting as a MediaRecorder.AudioSource, as Preview maps it. */
    private static int audioSource(String pref) {
        if (pref == null) return MediaRecorder.AudioSource.CAMCORDER;
        switch (pref) {
            case "audio_src_mic": return MediaRecorder.AudioSource.MIC;
            case "audio_src_default": return MediaRecorder.AudioSource.DEFAULT;
            case "audio_src_voice_communication": return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
            case "audio_src_voice_recognition": return MediaRecorder.AudioSource.VOICE_RECOGNITION;
            case "audio_src_unprocessed": return MediaRecorder.AudioSource.UNPROCESSED;
            default: return MediaRecorder.AudioSource.CAMCORDER;
        }
    }

    static String baseName(String videoName) {
        int dot = videoName.lastIndexOf('.');
        return dot > 0 ? videoName.substring(0, dot) : videoName;
    }

    private static String readAll(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (FileReader r = new FileReader(f)) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
