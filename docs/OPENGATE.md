# Open Gate Recording in Kanaha

## What Is Open Gate?

Open gate recording uses the **full active sensor area** — no horizontal or vertical crop. On a native 4:3 sensor, standard 16:9 video silently discards roughly the top and bottom 14% of sensor rows. Open gate preserves all of them, giving you a taller frame (~1.33:1) that you reframe in post-production (DaVinci Resolve, etc.) to whatever final aspect ratio you need: 16:9, 2.39:1 cinematic, 1:1 social, 9:16 vertical, or any custom crop.

The practical benefit: shoot once, reframe for any deliverable without losing image quality to upscaling.

---

## Sensor Bit Depth: 12-bit vs 10-bit vs 8-bit

Understanding bit depth matters for how much dynamic range and color information your open gate footage carries.

### 8-bit (standard H.264 / HEVC baseline)
- **Value range per channel**: 0–255 (256 levels)
- **Dynamic range**: ~8–10 stops usable
- **Banding risk**: Visible in smooth gradients (sky, skin) during heavy color grading
- **File size**: Smallest
- **All three Kanaha phones** record 8-bit by default in H.264

### 10-bit (HEVC Main 10 / HDR)
- **Value range per channel**: 0–1023 (4× more steps than 8-bit)
- **Dynamic range**: ~12–13 stops usable depending on tone mapping
- **Banding risk**: Rare even with aggressive grades
- **File size**: ~20–30% larger than 8-bit
- **Pixel 9 Pro**: Supports 10-bit HDR video (HLG/HDR10 profiles) — accessible via standard Camera2 API and therefore Kanaha
- **Moto G 2025 / Moto G 5G 2024**: 8-bit only

### 12-bit (RAW DCG — Dual Conversion Gain)
- **Value range per channel**: 0–4095 (16× more steps than 8-bit)
- **Dynamic range**: 14+ stops; DCG captures two analog gains simultaneously and merges before digitization, giving cleaner shadows and highlights than any tone-mapped 10-bit
- **File size**: Very large (DNG sequences, not H.264/HEVC)
- **Pixel 9 Pro**: Hardware capable but **not accessible via standard Camera2 API** — requires MotionCam Pro or Blackmagic Camera using privileged/private Google APIs. Kanaha uses Camera2 only and therefore **cannot reach 12-bit RAW**.
- **Pixel 10 Pro**: First Pixel to unlock DCG as a Camera2-accessible feature (as of late 2025). Third-party apps including Blackmagic Camera and MotionCam Pro can access 12-bit RAW at 4030×3072 open gate.
- **Moto G 2025 / Moto G 5G 2024**: 8-bit only; no DCG hardware

### Summary table for the current Kanaha rig

| Phone | Open Gate Support | Max Bit Depth via Kanaha | Max Bit Depth (any app) |
|---|---|---|---|
| Pixel 9 Pro | **Yes** — 4:3 resolution available via Camera2 | 10-bit HDR (HLG) | 10-bit (Camera2); 12-bit RAW via MotionCam Pro/Blackmagic (private API) |
| Moto G 2025 | No — HAL exposes 16:9 only | 8-bit | 8-bit |
| Moto G 5G 2024 | No — HAL exposes 16:9 only | 8-bit | 8-bit |

---

## Using Open Gate in Kanaha

### HTTP API

Add `"open_gate": true` to the `startRecording` JSON body. Requires mTLS client cert (see `app/src/main/assets/ssl/`):

```bash
curl --cert client.crt --key client.key --cacert ca.crt \
     --http2 -k \
     -X POST https://192.168.1.182:8443/services/CameraControlService/startRecording \
     -H 'Content-Type: application/json' \
     -d '{"action":"startRecording","clip_name":"shot01","open_gate":true}'
```

Or with `start_at` for synchronized multi-camera start (open gate on the Pixel only):

```bash
START_AT=$(date -d "+5 seconds" +%s%3N)  # 5s lead time for open gate reopen

# Pixel 9 Pro — open gate
curl --cert client.crt --key client.key --cacert ca.crt --http2 -k \
     -X POST https://192.168.1.182:8443/services/CameraControlService/startRecording \
     -H 'Content-Type: application/json' \
     -d "{\"action\":\"startRecording\",\"clip_name\":\"A001\",\"open_gate\":true,\"start_at\":$START_AT}"

# Moto G cameras — standard 16:9 (open_gate ignored if not supported)
curl --cert client.crt --key client.key --cacert ca.crt --http2 -k \
     -X POST https://192.168.1.95:8443/services/CameraControlService/startRecording \
     -H 'Content-Type: application/json' \
     -d "{\"action\":\"startRecording\",\"clip_name\":\"A001\",\"start_at\":$START_AT}"
curl --cert client.crt --key client.key --cacert ca.crt --http2 -k \
     -X POST https://192.168.1.170:8443/services/CameraControlService/startRecording \
     -H 'Content-Type: application/json' \
     -d "{\"action\":\"startRecording\",\"clip_name\":\"A001\",\"start_at\":$START_AT}"
```

### What Happens Internally

1. `configureOpenGate()` iterates all Camera2-reported video quality strings
2. Resolves each to pixel dimensions via `Preview.getCamcorderProfile()`
3. Selects the largest resolution with aspect ratio 4:3 ± 2%
4. Sets the quality preference and resets zoom to 1x
5. Calls `clickedSwitchVideo` (photo → video cycle) via `runOnUiThread` to trigger a clean camera session reopen with the new quality — this uses OpenCamera's official state-machine transition rather than `reopenCamera()` directly
6. Polls every 500ms (up to 10s) for camera readiness; the readiness check runs **on the UI thread** via `runOnUiThread + CountDownLatch` to avoid Java Memory Model visibility issues with `camera_controller`
7. `startRecordingInternal()` calls `takePicture(false)` and polls for `isVideoRecording()` (up to 5s)

If no 4:3 resolution is found (Moto G phones), a warning is logged and recording starts with the existing quality unchanged.

#### Critical Threading Note

`CameraControlReceiver.onReceive()` runs on the **main UI thread**. Camera2 `onClosed`/`onOpened` callbacks are also dispatched via the main thread's Looper. If `onReceive()` blocks the main thread (e.g. with `Thread.sleep()` while waiting for a camera reopen), those callbacks are queued and never execute — the camera never reopens.

The fix (committed 2026-03-08): `onReceive()` now calls `goAsync()` and dispatches all handler logic to a background thread (`KanahaCameraControl`). The main thread remains free to process Camera2 callbacks. `runOnUiThread()` inside handlers now correctly **posts** to the main thread (async) rather than executing inline.

### Sidecar JSON

The `kanaha_recording_start.json` sidecar now includes `open_gate`:

```json
{
  "recording_start_ms": 1741452000123,
  "clip_name": "A001",
  "open_gate": true,
  "gps_time": 1741452000089,
  "gps_age_ms": 34
}
```

Pull via ADB:
```bash
adb -s <pixel-serial> pull \
  /storage/emulated/0/Android/data/org.kanaha.camera/files/kanaha_recording_start.json
```

---

## DaVinci Resolve Workflow

1. Ingest open gate clips into a **4:3 timeline** (e.g. 3840×2880 or native sensor res)
2. Use the **Transform** or **Crop** tool in the Color or Edit page to reframe to your output ratio
3. For multi-camera projects: open gate Pixel footage sits alongside standard 16:9 Moto G footage — both sync via the `recording_start_ms` sidecar. Apply the reframe only to the Pixel tracks.
4. Common reframe targets from 4:3 open gate:
   - **16:9**: crop top/bottom (standard delivery)
   - **2.39:1 anamorphic**: crop top/bottom more aggressively for a cinematic look
   - **1:1**: crop left/right (social media)
   - **9:16 vertical**: crop and rotate — useful if you want a vertical cut from a horizontal shoot

---

## Pixel 10 Pro — Worth Buying for Open Gate?

**Short answer: yes, a meaningful upgrade over the Pixel 9 Pro for open gate workflows.**

| Feature | Pixel 9 Pro | Pixel 10 Pro |
|---|---|---|
| Sensor | Samsung GNK, 1/1.31", 50MP | Samsung GNK successor, 1/1.31", 50MP |
| Native aspect ratio | 4:3 | 4:3 |
| Open gate resolution (Camera2) | ~3840×2880 (estimated) | 4030×3072 (confirmed) |
| 10-bit HDR | Yes (HLG) | Yes (HLG) |
| 12-bit RAW open gate | No (Camera2); Yes via private API | **Yes via standard Camera2** (DCG unlocked) |
| DCG (Dual Conversion Gain) | Hardware present; software-locked for third-party | **Unlocked** — accessible to any Camera2 app |

The Pixel 10 Pro is the **first Pixel where DCG is accessible via standard Camera2 API**, meaning Kanaha could eventually record 12-bit RAW open gate footage without relying on Blackmagic or MotionCam Pro's private APIs. The 4030×3072 open gate resolution is confirmed and accessible to third-party apps.

For your current DaVinci Resolve workflow, the Pixel 10 Pro would give you:
- Slightly higher resolution open gate (4030×3072 vs estimated 3840×2880)
- True 12-bit dynamic range (14+ stops vs ~12–13 stops at 10-bit)
- Cleaner shadows — noticeably better in mixed lighting or when pulling exposures in Resolve

**Note**: Kanaha's current implementation records compressed video (H.264/HEVC), not RAW DNG. Adding 12-bit RAW support would require significant MediaRecorder/ImageReader pipeline changes and is a separate workstream.

---

## Pixel 11 Pro — September 2026 Release: Rumoured Outlook

Expected release: **August/September 2026** (Tensor G6, TSMC N3P process).

Camera rumours as of early 2026:
- **64MP periscope telephoto** with up to 10x optical zoom
- New **4K 30fps Cinematic Blur** video feature
- **Ultra-low Light video** (5–10 lux capability)
- Under-display IR camera for improved face unlock
- Main sensor: unconfirmed, likely same class as Pixel 10 Pro or incremental improvement

**Open gate outlook for Pixel 11 Pro**:
- Almost certainly inherits the Pixel 10 Pro's DCG capability (now standard in Pixel lineage)
- If the main sensor is upgraded (larger sensor or higher resolution), open gate resolution could increase
- 12-bit RAW open gate via Camera2 expected to carry forward
- **No strong reason to wait for Pixel 11 Pro specifically for open gate** — the Pixel 10 Pro already has the feature and the rumoured Pixel 11 Pro camera improvements (Cinematic Blur, low-light video) are primarily software/computational, not raw sensor improvements that would change open gate capability meaningfully

**Recommendation**: If open gate and 12-bit RAW are the primary drivers, **buy the Pixel 10 Pro now**. If you are willing to wait ~6 months (September 2026), the Pixel 11 Pro will likely add the 10x telephoto and computational video features, but the open gate/DCG story will be similar.

---

## Limitations and Known Issues

- **~3s reopen latency**: `configureOpenGate()` triggers a photo→video mode cycle which causes a camera session reopen. On the Pixel 9 Pro this typically completes in 500–2000ms; the poller returns as soon as the camera is ready (max 10s). The `open_gate=true` startRecording call therefore takes 3–5s to return. For use with `start_at` scheduling, the sidecar is written before the recording starts so timing is still accurate.
- **Zoom reset**: `configureOpenGate()` resets digital zoom to 1x. If you need open gate at a specific focal length, use optical zoom (different lens) rather than digital zoom.
- **12-bit not supported**: Kanaha records compressed video via MediaRecorder. 12-bit RAW requires an ImageReader pipeline (DNG capture) — out of scope for the current implementation.
- **Moto G graceful fallback**: No error is returned; the phones simply record in their default quality. The `open_gate: false` in the sidecar confirms it was not applied.

---

## Build Process: Modifying the C Layer

The HTTP server running on the phone is **`jniLibs/arm64-v8a/libhttpd.so`** — a pre-built ARM64 Apache + Axis2/C binary. Gradle does **not** rebuild this file. Any change to `camera_control_service.c` requires a manual cross-compilation step.

### Workflow

```bash
# 1. Edit the C source
vim app/src/main/cpp/axis2c/camera_control_service.c

# 2. Cross-compile and relink
~/android-cross-builds/link-httpd-axis2.sh

# 3. Strip and copy to jniLibs
llvm-strip --strip-all ~/android-cross-builds/httpd-2.4.66/httpd
cp ~/android-cross-builds/httpd-2.4.66/httpd \
   app/src/main/jniLibs/arm64-v8a/libhttpd.so

# 4. Rebuild APK (includes updated libhttpd.so in assets)
./gradlew assembleDebug

# 5. Install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### What `link-httpd-axis2.sh` Does

1. Compiles `axis2_static_service_adapter.c` + `camera_control_service.c` → `libkanaha_services.a`
2. Links with all static Apache modules, Axis2/C libs, OpenSSL, APR, nghttp2, PCRE2 using NDK clang (`aarch64-linux-android21-clang`)
3. Output: `~/android-cross-builds/httpd-2.4.66/httpd` (~9.4MB unstripped, ~5.5MB stripped)

The resulting binary is the full Apache HTTP/2 + mTLS server with the Kanaha camera control service compiled in. The pre-built binary in `jniLibs/arm64-v8a/libhttpd.so` is the one actually deployed to the phone.

### Verification

After install, confirm the C layer picked up your changes:
```bash
# Check strings are present in the deployed binary
adb shell "strings /data/app/*/org.kanaha.camera*/lib/arm64/libhttpd.so | grep open_gate"
```

See `docs/ANDROID_CROSS_COMPILATION.md` for the full toolchain setup and dependency build instructions.
