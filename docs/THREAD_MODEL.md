# Kanaha Threading Model

## BLUF

Kanaha's IPC pipeline crosses three threading contexts: the C Apache/Axis2 worker thread, the Android main (UI) thread where `onReceive()` lands, and the background `KanahaCameraControl` thread that actually runs handlers. Any handler that sleeps or waits **must** run on the background thread — blocking the main thread deadlocks Camera2 callbacks that also dispatch on it.

---

## The Full IPC Pipeline

```
curl (laptop)
  → TCP/TLS → Apache httpd (C, Axis2 worker thread, PID libhttpd.so)
    → am broadcast → Android system
      → CameraControlReceiver.onReceive() [MAIN THREAD, PID == TID]
        → goAsync() + new Thread("KanahaCameraControl") [BACKGROUND THREAD]
          → handleStartRecording() / handlePlayTone() / etc.
            → mainActivity.runOnUiThread(lambda)  ← posts to main thread queue
              → Camera2 API calls [MAIN THREAD, now free]
                → Camera2 HAL callbacks [MAIN THREAD, dispatched by Looper]
```

Key transition: `goAsync()` at the `onReceive` boundary is what keeps the main thread free.

---

## Why onReceive Runs on the Main Thread

Android dispatches `BroadcastReceiver.onReceive()` on the process's **main thread** (the Looper thread whose PID == TID). You can confirm this in logcat:

```
17607 17607 I KanahaCameraReceiver: Received camera control intent   ← PID==TID = main thread
17607 17819 I KanahaCameraReceiver: configureOpenGate: selected ...  ← TID 17819 = background thread
```

The first log line (from `onReceive` itself before the thread is spawned) always shows PID==TID.

---

## The goAsync() Pattern

Without `goAsync()`:

```java
// BROKEN — onReceive on main thread, Thread.sleep blocks camera callbacks
@Override
public void onReceive(Context context, Intent intent) {
    handleStartRecording(context, intent);  // calls Thread.sleep(2000) → DEADLOCK
}
```

Camera2's `onClosed`/`onOpened` callbacks are posted to the main thread's Looper. While `Thread.sleep()` is blocking the main thread, those callbacks queue up and never run. The camera never reopens regardless of how long you sleep.

With `goAsync()`:

```java
@Override
public void onReceive(Context context, Intent intent) {
    final BroadcastReceiver.PendingResult pendingResult = goAsync();
    new Thread(() -> {
        try {
            // Thread.sleep() here is fine — main thread is free for Camera2 callbacks
            handleStartRecording(context, intent);
            writeResponseToFile(context, operationId, response);
        } finally {
            pendingResult.finish();  // tells system we're done with the broadcast
        }
    }, "KanahaCameraControl").start();
}
```

**Rule:** Any Kanaha handler that calls `Thread.sleep()`, polls, or waits for camera state must execute on the background thread via this pattern.

---

## runOnUiThread: Inline vs Posted

`Activity.runOnUiThread(Runnable)` behaves differently depending on the calling thread:

| Calling thread | Behaviour |
|---|---|
| **Main (UI) thread** | Runs the lambda **immediately and synchronously** — no queue, no delay |
| **Background thread** | **Posts** the lambda to the main thread's message queue — returns immediately, lambda runs later |

Before the `goAsync()` fix, all handlers ran on the main thread. `runOnUiThread(() -> takePicture())` executed inline — `takePicture()` ran synchronously before the next line. After the fix, it posts, and the Camera2 callback chain runs asynchronously on the now-free main thread.

**Implication for timing:** After posting a `runOnUiThread` lambda from a background thread, the lambda executes on the main thread's next available message-loop slot. In practice this is sub-millisecond when the main thread is idle, but it is not instantaneous. Any check on the result of that lambda must wait (poll or latch).

---

## Reading UI-Thread State from Background Threads

Camera2's `camera_controller` and OpenCamera's `is_video` flag are written on the UI thread. Reading them from a background thread has **Java Memory Model visibility** issues: without `volatile` or synchronization, the background thread may see stale values.

**Wrong (background thread read):**
```java
// May see stale null even if camera_controller was just set on UI thread
if (preview.getCameraController() != null) { ... }
```

**Right (UI thread read via CountDownLatch):**
```java
CountDownLatch latch = new CountDownLatch(1);
AtomicBoolean ready = new AtomicBoolean(false);
mainActivity.runOnUiThread(() -> {
    ready.set(preview.getCameraController() != null && preview.isVideo());
    latch.countDown();
});
latch.await(1, TimeUnit.SECONDS);
if (ready.get()) { ... }
```

The lambda runs on the UI thread where all writes to `camera_controller` and `is_video` originated. The `AtomicBoolean` provides the cross-thread visibility guarantee for the result.

---

## Camera2 State Machine: reopenCamera() vs clickedSwitchVideo()

Two ways to trigger a camera session reopen:

| Method | What it does | Risk |
|---|---|---|
| `preview.reopenCamera()` | Directly calls `closeCamera(async=true, callback)` → `openCamera()` | Bypasses OpenCamera's state machine. If called repeatedly across failed attempts, leaves `camera_controller` null and the camera in `CAMERAOPENSTATE_CLOSING` indefinitely. |
| `mainActivity.clickedSwitchVideo(null)` | OpenCamera's official photo↔video toggle. Calls `switchVideo()` → `reopenCamera()` internally, but also sets `is_video` correctly and resets other state flags. | Safe to call from a `runOnUiThread` post. |

**Rule:** Use `clickedSwitchVideo` for mode changes. Reserve direct `reopenCamera()` calls for cases where you are certain about the camera state and are not doing a mode change.

For open gate recording, the correct sequence is:
1. Set quality preference
2. `clickedSwitchVideo` → photo mode (closes video session cleanly)
3. Sleep ~2s (background thread — main thread processes `onClosed` callback during this time)
4. `clickedSwitchVideo` → video mode (opens new session with new quality)
5. Poll for `getCameraController() != null && isVideo()` **on UI thread** via CountDownLatch

---

## The start_at / Handler.postDelayed Pattern

The scheduled-start feature (`start_at` UTC millisecond) uses a different threading pattern — it does **not** wait for the scheduled time on the background thread. Instead, it posts a delayed callback to the main Looper and returns immediately:

```java
// In handleStartRecording(), running on background thread:
new Handler(Looper.getMainLooper()).postDelayed(() -> {
    writeSyncSidecar(context, clip, fireTime, og);
    startRecordingInternal(activity, clip);  // called on main thread at scheduled time
}, delayMs);
// Returns immediately — C layer gets "scheduled" response right away
```

This is correct: `postDelayed` on the main Looper does not block the background thread. The lambda runs on the main thread at the scheduled time. Note that `startRecordingInternal()` called from this lambda is on the main thread — any waits in that code would block the main thread. For the scheduled path this is acceptable because `startRecordingInternal` does a single `takePicture()` (quick) with no long sleeps.

See `docs/GPS.md` for sync accuracy analysis of the `start_at` mechanism.

---

## Threading Rules for New Handlers

When adding a new action handler to `CameraControlReceiver`:

1. **The handler runs on the `KanahaCameraControl` background thread** — `Thread.sleep()` is safe
2. **Camera API calls go through `mainActivity.runOnUiThread()`** — they post to the main thread
3. **Reading camera state from the handler thread requires a CountDownLatch** (see above)
4. **Never call `Thread.sleep()` inside a `runOnUiThread` lambda** — that would block the main thread
5. **`writeSyncSidecar()` and file I/O are safe** from the background thread
6. **`pendingResult.finish()`** must be called in the `finally` block — the system reclaims the broadcast slot

---

## Related Docs

- `docs/OPENGATE.md` — open gate feature; the `goAsync` fix and `CountDownLatch` pattern in context
- `docs/GPS.md` — `start_at` scheduling; `Handler.postDelayed` on main Looper
- `docs/ANDROID_CROSS_COMPILATION.md` — C layer build; the Axis2 worker thread that originates the broadcast
- `docs/MULTI_CAMERA_DEPLOYMENT_SYSTEM.md` — overall architecture; H2 worker thread model
