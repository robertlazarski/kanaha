# Open Gate Recording in Kanaha

## BLUF

Add `"open_gate": true` to a `startRecording` request. The camera reopens at its native 4:3 sensor resolution (2560×1920 on Pixel 9 Pro) and records with no horizontal or vertical crop. In post, reframe the 4:3 footage to any delivery ratio — 16:9, 2.39:1, 9:16, 1:1 — without upscaling.

**What you need to know:**
- Only the **Pixel 9 Pro** supports open gate. Moto G phones fall back silently to their existing quality (no error returned; `open_gate: false` in the sidecar confirms it was not applied).
- The `startRecording` call **blocks 3–5 s** while the camera session reopens at 4:3. Account for this in `start_at` scheduling — use at least a 5 s lead time.
- After an open gate session, the **next standard recording** triggers a second session reopen to reset quality back to 16:9. This is intentional and takes another ~2 s.
- The sidecar JSON (`kanaha_recording_start.json`) includes `"open_gate": true` so post scripts know which clips need reframing.

**Most bugs come from:**
- Editing `camera_control_service.c` but **not rebuilding `libhttpd.so`** — Gradle does not rebuild it. Run `~/android-cross-builds/link-httpd-axis2.sh` then copy the output. See [Build Process](#build-process-modifying-the-c-layer) below.
- Expecting `startRecording` to return immediately with `open_gate=true` — it returns only after the camera reopen completes (~3–5 s).
- Reading UI-thread camera state from a background handler thread without a `CountDownLatch`. See [`docs/THREAD_MODEL.md`](THREAD_MODEL.md) for the full threading model.

> **Quick reference**: For installation, certificate setup, and the full HTTP API, see the [README](../README.md).

---

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
- **Pixel 9 Pro**: Hardware supports 10-bit HDR but Google **locks the HLG profile at the HAL level** — only the stock Pixel Camera app can invoke it. Third-party Camera2 apps (including OpenCamera/Kanaha, Blackmagic Camera, MotionCam Pro) receive no 10-bit profile regardless of implementation. Kanaha records 8-bit H.264.
- **Pixel 10 Pro**: First Pixel to unlock 10-bit (and 12-bit DCG) for third-party Camera2 apps. Kanaha could record 10-bit HLG on a Pixel 10 Pro with an HEVC Main 10 implementation — but OpenCamera upstream does not yet support it (ticket [#1218](https://sourceforge.net/p/opencamera/tickets/1218/), open as of early 2026, no developer response).
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
| Pixel 9 Pro | **Yes** — 4:3 resolution available via Camera2 | **8-bit H.264** | 10-bit HLG via stock Pixel Camera app only (HAL-locked); 12-bit RAW via MotionCam Pro/Blackmagic (private API) |
| Pixel 10 Pro | **Yes** — 4:3 resolution available via Camera2 | **8-bit H.264** (10-bit feasible once OpenCamera adds HEVC Main 10) | 10-bit HLG + 12-bit DCG RAW — unlocked for all Camera2 apps |
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

See `docs/THREAD_MODEL.md` for the full threading model, JMM visibility rules, and guidance for new handlers.

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

## Post-Production: LUT Grading with ffmpeg

Open gate footage from Kanaha is standard H.264/HEVC — it drops into any post pipeline. The recommended workflow is to shoot flat and grade in post; do not bake a LUT at capture.

### Recommended LUT for snow/winter exterior

From the IWLTBAP Renata pack (`/home/robert/Downloads/lut/`), **Aspen Standard** works best for winter snow scenes: it lifts the sky to a rich cinematic blue, adds contrast to tree lines, and keeps snow whites natural without the teal push of Humble/Renata.

```bash
LUT="/home/robert/Downloads/lut/IWLTBAP - Renata (Free LUT)/LUTs by IWLTBAP (CUBE)/BONUS/Aspen/IWLTBAP Aspen - Standard.cube"

ffmpeg -i demo_og.mp4 \
  -vf "format=rgb24,lut3d='${LUT}',format=yuv420p" \
  -pix_fmt yuv420p -color_range tv \
  -c:v libx264 -crf 18 -preset slow \
  -c:a copy \
  demo_og_aspen.mp4
```

**LUT comparison for snow (Standard variants):**

| LUT | Snow look |
|-----|-----------|
| **Aspen** | ✓ Best — rich blue sky, natural whites, cinematic contrast |
| K25 (Kodachrome) | Deep teal sky, dramatic, slightly heavy-handed |
| Humble | Cool/icy, strong teal cast on sky |
| Renata | Most dramatic teal push, Instagram-style |
| Sedona | Too warm/orange for snow |

**Red camera emulation (no LUT file required):**
```bash
ffmpeg -i demo_og.mp4 \
  -vf "format=rgb24,
       eq=saturation=1.15:contrast=1.08:gamma=0.92:gamma_r=1.05:gamma_b=0.95,
       unsharp=3:3:0.5:3:3:0,
       colorbalance=rm=0.04:gm=-0.01:bm=-0.03:rh=0.02:gh=0.00:bh=-0.02,
       format=yuv420p" \
  -pix_fmt yuv420p -color_range tv \
  -c:a copy demo_og_red.mp4
```

> **Do not stack LUT + Red emulation** on 8-bit phone footage — double-grading clips highlights in snow whites and crushes shadows.

---

## Demo: Deliverables Grid — One Shoot, Every Platform

A single open gate recording reframes to any delivery format in post with no upscaling. The following script extracts one frame, applies the Aspen LUT, and composites a 2×2 grid showing all four deliverables.

```python
# Requires: Pillow  (pip install pillow)
# Input:    graded.jpg  — a frame from demo_og.mp4 with Aspen LUT applied
# Output:   deliverables_grid.jpg

from PIL import Image, ImageDraw, ImageFont
import os

src = Image.open("graded.jpg")
W, H = src.size  # 2560x1920 from Pixel 9 Pro open gate

CELL_W, CELL_H = 1280, 960

def make_cell(img, crop_box, label, sublabel):
    cropped = img.copy() if crop_box is None else \
              img.crop((crop_box[0], crop_box[1],
                        crop_box[0]+crop_box[2], crop_box[1]+crop_box[3]))
    cw, ch = cropped.size
    scale = min(CELL_W/cw, CELL_H/ch)
    resized = cropped.resize((int(cw*scale), int(ch*scale)), Image.LANCZOS)
    cell = Image.new("RGB", (CELL_W, CELL_H), (0,0,0))
    cell.paste(resized, ((CELL_W-resized.width)//2, (CELL_H-resized.height)//2))
    # label bar
    font_big   = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 38)
    font_small = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 24)
    overlay = Image.new("RGBA", (CELL_W, CELL_H), (0,0,0,0))
    ImageDraw.Draw(overlay).rectangle([0, CELL_H-82, CELL_W, CELL_H], fill=(0,0,0,170))
    cell = Image.alpha_composite(cell.convert("RGBA"), overlay).convert("RGB")
    draw = ImageDraw.Draw(cell)
    bb = draw.textbbox((0,0), label, font=font_big)
    draw.text(((CELL_W-(bb[2]-bb[0]))//2, CELL_H-76), label, font=font_big, fill=(255,255,255))
    bb2 = draw.textbbox((0,0), sublabel, font=font_small)
    draw.text(((CELL_W-(bb2[2]-bb2[0]))//2, CELL_H-34), sublabel, font=font_small, fill=(170,210,255))
    return cell

cells = [
    make_cell(src, None,
              "4:3  OPEN GATE",    "Full sensor · all pixels · Pixel 9 Pro"),
    make_cell(src, (0, (H-round(W*9/16))//2, W, round(W*9/16)),
              "16:9  YOUTUBE",     "Center crop · no upscale"),
    make_cell(src, ((W-round(H*9/16))//2, 0, round(H*9/16), H),
              "9:16  TIKTOK",      "Vertical reframe · same shoot"),
    make_cell(src, (0, (H-round(W/2.39))//2, W, round(W/2.39)),
              "2.39:1  CINEMATIC", "Anamorphic crop · one curl command"),
]

grid = Image.new("RGB", (CELL_W*2, CELL_H*2), (0,0,0))
for i, cell in enumerate(cells):
    grid.paste(cell, ((i%2)*CELL_W, (i//2)*CELL_H))
draw = ImageDraw.Draw(grid)
draw.line([(CELL_W,0),(CELL_W,CELL_H*2)], fill=(30,30,30), width=2)
draw.line([(0,CELL_H),(CELL_W*2,CELL_H)],  fill=(30,30,30), width=2)
grid.save("deliverables_grid.jpg", quality=94)
```

**To reproduce the full demo from a raw clip:**
```bash
LUT="/home/robert/Downloads/lut/IWLTBAP - Renata (Free LUT)/LUTs by IWLTBAP (CUBE)/BONUS/Aspen/IWLTBAP Aspen - Standard.cube"

# 1. Extract best frame
ffmpeg -ss 60 -i demo_og.mp4 -vframes 1 -update 1 -q:v 1 source.jpg

# 2. Grade with LUT
ffmpeg -i source.jpg \
  -vf "format=rgb24,lut3d='${LUT}',format=yuv420p" \
  -q:v 1 graded.jpg

# 3. Build grid
python3 deliverables_grid.py
```

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
