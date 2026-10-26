package org.kanaha.camera;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

/**
 * The phone's motion during a 12-bit RAW take, as {@code take.gcsv}: Gyroflow's IMU log
 * format, so a take shot handheld or on a moving rig can be stabilised in post with
 * Gyroflow. Every gyroscope sample is written with the latest accelerometer reading.
 *
 * <p>Time 0 is the first frame's exposure: sensor events and camera frames are both
 * stamped with the boot clock, so the log needs no syncing against the video. Samples
 * before the first frame are not kept. At the platform's fastest rate the Pixel gives
 * several hundred samples a second, about 10 MB over a whole song.
 */
final class MotionLog implements SensorEventListener {
    private static final String TAG = "KanahaMotionLog";
    static final String FILE = "take.gcsv";

    private final File file;
    private final SensorManager sensors;
    private HandlerThread thread;
    private BufferedWriter out;
    private volatile long zeroNs;          // the first frame's boot-clock time; 0 until known
    private volatile double readoutMs;     // the rolling shutter's top-to-bottom time
    private final float[] accel = new float[3];
    private boolean haveAccel;
    private volatile long samples;
    private volatile boolean failed;

    MotionLog(Context context, File dir) {
        this.file = new File(dir, FILE);
        this.sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    }

    /** Start listening; false if the phone has no gyroscope. */
    boolean start() {
        Sensor gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        Sensor acc = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (gyro == null) return false;
        thread = new HandlerThread("KanahaMotionLog");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST, h);
        if (acc != null) sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_FASTEST, h);
        return true;
    }

    /** The take's first frame: samples from here on are written, timed from it. */
    void begin(long firstFrameBootNs, double rollingShutterMs) {
        readoutMs = rollingShutterMs;
        zeroNs = firstFrameBootNs;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(e.values, 0, accel, 0, 3);
            haveAccel = true;
            return;
        }
        long zero = zeroNs;
        if (zero == 0 || failed || e.timestamp < zero) return;
        try {
            if (out == null) open();
            out.write(String.format(Locale.US, "%d,%.6f,%.6f,%.6f,%.5f,%.5f,%.5f\n",
                    (e.timestamp - zero) / 1000, e.values[0], e.values[1], e.values[2],
                    haveAccel ? accel[0] : 0f, haveAccel ? accel[1] : 0f, haveAccel ? accel[2] : 0f));
            samples++;
        } catch (IOException ex) {
            failed = true;   // the take goes on without its motion log
            Log.e(TAG, "writing " + file + " failed", ex);
        }
    }

    private void open() throws IOException {
        out = new BufferedWriter(new FileWriter(file), 1 << 16);
        out.write("GYROFLOW IMU LOG\nversion,1.3\nid,kanaha_camera\n");
        // Android's axes are the phone's in portrait; the RAW frame is the sensor's
        // landscape readout, whose right is the phone's -Y and up its +X. Confirmed on a
        // Pixel 10 Pro XL take (SENSOR_ORIENTATION 90) in Gyroflow 1.6.3; a phone with its
        // sensor mounted another way would need a different string.
        out.write("orientation,yXZ\n");
        if (readoutMs > 0) out.write(String.format(Locale.US, "frame_readout_time,%.3f\n", readoutMs));
        out.write("tscale,0.000001\n");               // t in microseconds
        out.write("gscale,1.0\n");                     // rad/s, as Android gives it
        out.write(String.format(Locale.US, "ascale,%.8f\n", 1 / SensorManager.GRAVITY_EARTH));  // m/s² to g
        out.write("t,gx,gy,gz,ax,ay,az\n");
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    /** Stop listening and close the file; returns the samples written. */
    long stop() {
        sensors.unregisterListener(this);
        if (thread == null) return samples;
        Handler h = new Handler(thread.getLooper());
        h.post(() -> {
            try { if (out != null) out.close(); } catch (IOException e) { Log.e(TAG, "closing " + file, e); }
        });
        thread.quitSafely();
        try { thread.join(2000); } catch (InterruptedException ignored) { }
        return samples;
    }

    String fileName() { return file.getName(); }
}
