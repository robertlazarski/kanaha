package org.kanaha.camera;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The screen of a 12-bit RAW take (startRecording with "raw12": true). It owns the
 * camera for the take - OpenCamera closes its camera while this screen is in front -
 * and shows the live preview with one status line: frames, drops, free space, heat.
 *
 * <p>CameraControlReceiver creates the {@link RawTake} and starts this screen; the
 * take ends on stopRecording, when its duration runs out, when free space runs low,
 * or if this screen is closed. Then the screen closes and OpenCamera returns.
 *
 * <p>Like the RAW probe it shows over the lock screen and handles configuration
 * changes itself (manifest): a phone that locks mid-take would otherwise recreate the
 * activity and take the camera away from the take.
 */
public class RawTakeActivity extends Activity {
    private static final String TAG = "KanahaRawTake";

    private RawTake take;
    private TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        take = RawTake.active;
        if (savedInstanceState != null || take == null || take.state != RawTake.State.ARMING) {
            // Recreated, or launched without a take waiting: never start a second one.
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(Color.BLACK);
        TextureView preview = new TextureView(this);
        layout.addView(preview, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0x80000000);
        status.setPadding(24, 12, 24, 12);
        layout.addView(status, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        setContentView(layout);

        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { begin(st); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        if (preview.isAvailable()) begin(preview.getSurfaceTexture());
    }

    private void begin(SurfaceTexture st) {
        if (started) return;
        started = true;
        st.setDefaultBufferSize(1440, 1080);   // 4:3, as the sensor
        take.start(new Surface(st));
        ui.post(tick);
    }

    /** Once a second: the status line, and the limits that end a take by themselves. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            RawTake t = take;
            if (t == null) return;
            if (t.state == RawTake.State.DONE || t.state == RawTake.State.FAILED) {
                finish();
                return;
            }
            if (t.isRecording()) {
                if (t.seconds() >= t.maxSeconds) stopInBackground("duration reached");
                else if (t.freeBytes() < RawTake.STOP_BELOW_FREE_BYTES) stopInBackground("storage low");
            }
            int thermal = ((PowerManager) getSystemService(Context.POWER_SERVICE)).getCurrentThermalStatus();
            try {
                org.json.JSONObject s = t.status();
                status.setText(String.format(java.util.Locale.US, "12-bit RAW  %s  %s   %d frames  %d dropped  %.0f s   %d GB free   heat %d",
                        s.getString("take"), s.getString("state"), s.getInt("frames"), s.getInt("dropped"),
                        s.getDouble("seconds"), s.getLong("free_gb"), thermal));
            } catch (Exception e) {
                Log.w(TAG, "status line", e);
            }
            ui.postDelayed(this, 1000);
        }
    };

    private void stopInBackground(String reason) {
        RawTake t = take;
        if (t != null) new Thread(() -> t.stop(reason), "KanahaRawTakeStop").start();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(tick);
        // Closing this screen mid-take (home, back) ends the take cleanly rather than
        // leaving the camera and the helper running with nothing to show for it.
        RawTake t = take;
        if (t != null && t.isRunning() && t.state != RawTake.State.STOPPING) stopInBackground("take screen closed");
        super.onDestroy();
    }
}
