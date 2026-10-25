# Open Gate Recording in Kanaha

## BLUF

Add `"open_gate": true` to a `startRecording` request. The camera reopens at its largest recordable 4:3 size, **2560×1920** on the Pixel 10 Pro XL, and records the full sensor width and height with no 16:9 crop. In post, reframe the 4:3 footage to any delivery ratio — 16:9, 2.39:1, 9:16, 1:1.

**Open gate trades resolution for field of view.** It records the full sensor height, so every crop can include the sky and foreground that 16:9 never records; the grid in [the demo below](#demo-deliverables-grid--one-shoot-every-platform) is that. At 2560×1920 it covers **1080p deliverables without upscaling**: a 16:9 crop is 2560×1440, and a 9:16 vertical is exactly 1080×1920, TikTok's native size. A 4K 16:9 recording (3840×2160) has more pixels but only the 16:9 band's height: its 9:16 crop is 1215×2160 and loses the top and bottom of the scene. Shoot 4K for a 4K 16:9 deliverable; shoot open gate for 1080p and vertical deliverables, or when you need the coverage. Why the ceiling is 2560×1920, not 4032×3024, is under [the encoder limit](#the-encoder-limit-measured-2026-10-19).

**What you need to know:**
- The **Pixel 10 Pro XL** records open gate at 2560×1920 and the **Moto G Play 2024** at 1600×1200 under the Camera2 API (both limited by their encoders). A phone with no 4:3 video size falls back silently to their existing quality (no error returned; `open_gate: false` in the sidecar confirms it was not applied).
- The `startRecording` call **blocks 3–5 s** while the camera session reopens at 4:3. Account for this in `start_at` scheduling — use at least a 5 s lead time.
- After an open gate session, the **next standard recording** triggers a second session reopen to reset quality back to 16:9. This is intentional and takes another ~2 s.
- Each recording's start sidecar (`<basename>.kanaha_start.json`) includes `"open_gate": true` so post scripts know which clips need reframing.

**Most bugs come from:**
- Editing `camera_control_service.c` but **not rebuilding `libhttpd.so`** — Gradle does not rebuild it. Run `build-android.sh` at the repository root, which relinks it and installs it into `jniLibs`. See [Build Process](#build-process-modifying-the-c-layer) below.
- Expecting `startRecording` to return immediately with `open_gate=true` — it returns only after the camera reopen completes (~3–5 s).
- Reading UI-thread camera state from a background handler thread without a `CountDownLatch`. See [`docs/THREAD_MODEL.md`](THREAD_MODEL.md) for the full threading model.

> **Quick reference**: For installation, certificate setup, and the full HTTP API, see the [README](../README.md).

---

## What Is Open Gate?

Open gate recording uses the **full active sensor area** — no horizontal or vertical crop. On a native 4:3 sensor, standard 16:9 video silently discards roughly the top and bottom 14% of sensor rows. Open gate preserves all of them, giving you a taller frame (~1.33:1) that you reframe in post-production (DaVinci Resolve, etc.) to whatever final aspect ratio you need: 16:9, 2.39:1 cinematic, 1:1 social, 9:16 vertical, or any custom crop.

The practical benefit: shoot once, reframe for any deliverable. On the Pixel 10 Pro XL the 4:3 frame is 2560×1920, so a 4K deliverable cut from it is upscaled; see the BLUF.

### The encoder limit, measured 2026-10-19

The Pixel 10 Pro XL's camera offers 4:3 video streams up to 4032×3024 (`dumpsys media.camera`,
format 34), which is where an earlier version of this doc took "4032×3024 for video" from. It
was never recorded at that size, and cannot be:

- The phone's hardware encoders accept at most **3840×2160** (`c2.google.avc.encoder`) and
  **3840×2176** (`c2.google.hevc.encoder`), per `/vendor/etc/media_codecs_c2.xml`. No frame
  taller than 2176 lines can be encoded in hardware, by Kanaha or any other app.
- Of the 4:3 sizes the camera offers, the largest that fits is **2560×1920**. An `open_gate`
  take on 2026-10-19 recorded at exactly that, at a steady 30 fps.
- OpenCamera independently drops every video size above 4096×2160
  (`CameraController2.java`, a Nexus 6 workaround), so the cap holds even before the encoder
  would refuse.

The 12-megapixel 4:3 frame is reachable only as RAW (4080×3072), which no video encoder
takes. Kanaha records it as 12-bit RAW takes: see [12-bit_depth.md](12-bit_depth.md).

---

## Bit Depth: What Each Phone Records

Bit depth is how many steps each colour channel is divided into: 256 at 8-bit, 1024 at
10-bit, 4096 at 12-bit. More steps means a grade can stretch a smooth gradient, a dusk sky
or a snowfield, without it breaking into bands. Bit depth is not dynamic range: the range
from shadow to highlight is set by the sensor and the tone curve, and the extra bits
describe that same range in finer steps (see [Step 4](#step-4--the-ab-that-decides-whether-any-of-this-is-worth-it)).

This section covers video: what the phone's encoder records. 12-bit RAW takes bypass the
encoder and are a separate, specialist route, covered in [12-bit_depth.md](12-bit_depth.md).

### Pixel 10 Pro XL (A-camera)

Measured on the device, Android 17, 2026-10-19.

| | 8-bit (default) | 10-bit HLG |
|---|---|---|
| How | `startRecording` | `startRecording` with `"hlg10": true` |
| Codec | H.264 High | HEVC Main 10 |
| Pixel format | `yuv420p` | `yuv420p10le` |
| Colour tags | BT.709 primaries, sRGB transfer (`iec61966-2-1`) | BT.2020, HLG (`arib-std-b67`) |
| 4K30 data rate | 23 Mbps dim scene, 73 Mbps daylight | 55 Mbps dim scene |
| Largest frame | 3840×2160; open gate 2560×1920 | 3840×2160; open gate 2560×1920 |

- **10-bit HLG** is correctly tagged by the camera, so it grades without retagging. It
  always records HEVC whatever `format` says, since H.264 has no 10-bit profile on this
  phone. `getStatus` reports `supports_hlg10`. `startRecording` refuses `hlg10` while
  `setExposure` has a fixed `tone` curve pinned, because the curve would distort the HLG
  transfer function. Open gate and `hlg10` together work: 2560×1920 HEVC Main 10 with the
  same HLG tags, a steady 30 fps, about 34 Mbps (2026-10-19).
- **The 8-bit files are tagged with the sRGB transfer curve** rather than BT.709. Players
  and ffmpeg read the tag as written; it matters when converting colour spaces in post.
- **12-bit RAW**, the full 4080×3072 sensor, is recorded as takes with `"raw12": true`:
  [12-bit_depth.md](12-bit_depth.md).
- **The data rates above are not a property of bit depth.** They are what the encoder
  produced for these scenes and codecs, and they move with the scene and the encoder's
  settings; the dim and daylight 8-bit takes alone differ threefold.

### Moto G Play 2024 (B-camera)

Measured on the device, Android 14, 2026-10-19, with Kanaha switched to the Camera2 API
(Settings > Camera API). Motorola phones default to Camera1, which has no 10-bit, RAW or
manual controls at all.

- **A 1080p camera.** Its hardware encoder (`c2.qti.avc.encoder`) is declared for
  1920×1088 at most. The camera offers far larger streams (16:9 up to 4080×2296, 4:3 up to
  4080×3072), and the encoder refuses them, so `quality: "4K"` records the largest size the
  encoder takes, 1920×1080. Before 2026-10-19 Kanaha picked 4080×1836 and every default
  recording failed. (The old `OMX.qcom` entries in `/vendor/etc/media_codecs.xml`, which list
  4096×2176, are not the encoder in use.)
- **8-bit H.264** only. The camera does not advertise 10-bit video, and `startRecording`
  refuses `hlg10` with that reason; `getStatus` reports `supports_hlg10: false`.
- **Open gate works, at 1600×1200**, the largest 4:3 size the encoder accepts in practice
  (it takes 1600×1200 although the declaration says 1088 lines). Recorded at exactly that,
  with the exposure settings surviving the reopen.
- **RAW is 10-bit** (white level 1023), so `startRecording` refuses `raw12` with that
  reason.
- **Its files are tagged differently from the Pixel's:** full range (`yuvj420p`,
  `color_range=pc`) and BT.601 colour (`bt470bg`, `smpte170m`), where the Pixel writes limited
  range and BT.709. Convert before cutting the two together; see
  [Matching cameras](MULTI_CAMERA_DEPLOYMENT_SYSTEM.md#matching-cameras).


### Summary for the current rig

| Phone | 8-bit | 10-bit HLG | 12-bit RAW takes | Open gate |
|---|---|---|---|---|
| Pixel 10 Pro XL | Default | Yes, `hlg10` | Yes, `raw12` ([12-bit_depth.md](12-bit_depth.md)) | 2560×1920 |
| Moto G Play 2024 (Camera1, default) | Default | No | No | Not tested |
| Moto G Play 2024 (Camera2) | Default | No | No (10-bit RAW) | 1600×1200 |

---

## Using Open Gate in Kanaha

### HTTP API

Add `"open_gate": true` to the `startRecording` JSON body. Requires mTLS client cert (see [SECURITY.md](SECURITY.md#certificate-model-per-device-provisioning)):

```bash
curl --cert client.crt --key client.key --cacert ca.crt \
     --http2 \
     -X POST https://192.168.1.182:8443/services/CameraControlService/startRecording \
     -H 'Content-Type: application/json' \
     -d '{"action":"startRecording","clip_name":"shot01","open_gate":true}'
```

Or with `start_at` for synchronized multi-camera start (open gate on the Pixel only):

```bash
START_AT=$(( $(date +%s%N) / 1000000 + 5000 ))  # 5s lead time for open gate reopen

# Pixel 10 Pro XL — open gate
curl --cert client.crt --key client.key --cacert ca.crt --http2 \
     -X POST https://192.168.1.182:8443/services/CameraControlService/startRecording \
     -H 'Content-Type: application/json' \
     -d "{\"action\":\"startRecording\",\"clip_name\":\"A001\",\"open_gate\":true,\"start_at\":$START_AT}"

# Phones without a 4:3 video size record standard 16:9 (open_gate ignored)
curl --cert client.crt --key client.key --cacert ca.crt --http2 \
     -X POST https://192.168.1.95:8443/services/CameraControlService/startRecording \
     -H 'Content-Type: application/json' \
     -d "{\"action\":\"startRecording\",\"clip_name\":\"A001\",\"start_at\":$START_AT}"
curl --cert client.crt --key client.key --cacert ca.crt --http2 \
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

If no 4:3 resolution is found, a warning is logged and recording starts with the existing quality unchanged.

#### Critical Threading Note

`CameraControlReceiver.onReceive()` runs on the **main UI thread**. Camera2 `onClosed`/`onOpened` callbacks are also dispatched via the main thread's Looper. If `onReceive()` blocks the main thread (e.g. with `Thread.sleep()` while waiting for a camera reopen), those callbacks are queued and never execute — the camera never reopens.

The fix (committed 2026-03-08): `onReceive()` now calls `goAsync()` and dispatches all handler logic to a background thread (`KanahaCameraControl`). The main thread remains free to process Camera2 callbacks. `runOnUiThread()` inside handlers now correctly **posts** to the main thread (async) rather than executing inline.

See `docs/THREAD_MODEL.md` for the full threading model, JMM visibility rules, and guidance for new handlers.

### Sidecar JSON

Each recording's start sidecar, `<basename>.kanaha_start.json`, includes `open_gate`:

```json
{
  "file": "VID_20260308_084000.mp4",
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
  /storage/emulated/0/Android/data/org.kanaha.camera/files/VID_20260308_084000.kanaha_start.json
```

---

## Reframing in Post

Any editor, or ffmpeg alone, reframes the 2560×1920 frame; reframe only the open gate
tracks when they sit beside 16:9 cameras. Line cameras up by timecode where the rig has
it, otherwise by the `recording_start_ms` sidecar.

| Target | Crop from 2560×1920 | ffmpeg |
|---|---|---|
| **16:9** | 2560×1440, top and bottom trimmed | `-vf crop=2560:1440` |
| **2.39:1** | 2560×1072 | `-vf crop=2560:1072` |
| **1:1** | 1920×1920 | `-vf crop=1920:1920` |
| **9:16 vertical** | 1080×1920, TikTok's native size | `-vf crop=1080:1920` |

`crop` centres by default; add `:x:y` to move the window, for example
`crop=1080:1920:600:0` to frame left of centre. The grid in
[the demo below](#demo-deliverables-grid--one-shoot-every-platform) shows all four from
one frame.

---

## Testing the Pixel 10 Pro XL

The Pixel 10 Pro XL is the A-camera. This is the procedure that confirmed its 10-bit
support on the actual hardware, in the order that fails cheapest first; the results are
in [Bit Depth](#bit-depth-what-each-phone-records).

### Step 1 — ask the phone what it exposes

Before any code changes. Nothing here needs Kanaha built.

```sh
adb shell dumpsys media.camera | grep -iE "10.?bit|dynamic.?range|RAW|white level"
```

Three things to look for:

- `REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT` — the phone will accept
  a 10-bit profile. Without this, stop; nothing else matters.
- `HLG10` in the supported dynamic range profiles. Android documents HLG10 as
  mandatory wherever the ten-bit capability is present, but confirm rather than
  assume.
- `RAW12` alongside `RAW_SENSOR` in the output formats, and the white level. **Camera2
  has no 12-bit video path:** its four HDR profiles (HLG10, HDR10, HDR10+, Dolby
  Vision 8.4) are all 10-bit HEVC. 12-bit comes only as RAW frames, which Kanaha
  records as takes of DNG frames: [12-bit_depth.md](12-bit_depth.md).

### Step 2 — confirm the file is what you think it is

After recording a 10-bit clip, before grading anything:

```sh
ffprobe -v error -select_streams v:0 \
  -show_entries stream=pix_fmt,profile,color_transfer,color_primaries,color_space \
  -of default=nw=1 demo_og.mp4
```

Wanted:

```
pix_fmt=yuv420p10le
profile=Main 10
color_transfer=arib-std-b67      <- HLG
color_primaries=bt2020
color_space=bt2020nc
```

`pix_fmt=yuv420p` means it recorded 8-bit and the rest of this section does not
apply. Correct pixel format but `color_transfer=bt709` means the frames are
10-bit HLG while the file claims Rec.709 — every player and NLE will then grade
it wrongly, and it will look washed out. That is a tagging bug, not a capture
failure, and `-color_trc arib-std-b67 -color_primaries bt2020 -colorspace
bt2020nc` on a stream copy fixes it without re-encoding.

### Step 3 — grade in HLG, do not convert to Rec.709

Converting HLG to Rec.709 throws away the headroom that was the point of
shooting 10-bit. Stay in BT.2020/HLG all the way to delivery.

**The cost of that decision:** the IWLTBAP LUTs in `~/Downloads/lut/` are
authored for Rec.709 input. They cannot be applied to HLG footage directly — the
result is flat and wrongly saturated, and it looks like the 10-bit work failed
when it did not. Two honest options.

**3a. Grade with primitives, staying in HLG.** No LUT, nothing to convert, and
the tonality stays where the camera put it:

```sh
HLG="setparams=color_primaries=bt2020:color_trc=arib-std-b67:colorspace=bt2020nc:range=tv"

ffmpeg -i demo_og.mp4 \
  -vf "format=gbrp10le,\
       eq=saturation=1.10:contrast=1.05:gamma=0.96,\
       colorbalance=rm=0.03:bm=-0.02:rh=0.01:bh=-0.02,\
       format=yuv420p10le,${HLG}" \
  -c:v libx265 -crf 18 -preset slow -pix_fmt yuv420p10le \
  -tag:v hvc1 -c:a copy demo_og_graded.mp4
```

Tagging the output is not optional — without it the file carries 10-bit HLG
frames labelled Rec.709 and every downstream tool misreads it. Note the tags are
applied with the `setparams` **filter**, not the `-color_primaries` / `-color_trc`
output options: tested on ffmpeg 8.0.1, those options set `colorspace` but leave
`color_primaries` and `color_transfer` as `unknown`. `setparams` sets all three,
verified with the `ffprobe` command in Step 2.

**3b. Round-trip a Rec.709 LUT and come back to HLG.** Keeps the Aspen look and
an HLG deliverable, at the cost of the LUT imposing SDR tonality on the middle of
the range — you keep the container and the bit depth, not the full HDR intent:

```sh
LUT_DIR="${LUT_DIR:-$HOME/Downloads/lut}"   # see Post-Production, below
LUT="$LUT_DIR/IWLTBAP - Renata (Free LUT)/LUTs by IWLTBAP (CUBE)/BONUS/Aspen/IWLTBAP Aspen - Standard.cube"
HLG="setparams=color_primaries=bt2020:color_trc=arib-std-b67:colorspace=bt2020nc:range=tv"

ffmpeg -i demo_og.mp4 \
  -vf "format=yuv420p10le,${HLG},\
       zscale=t=linear:npl=100,\
       zscale=p=bt709:t=bt709:m=bt709:r=tv,\
       format=gbrp10le,lut3d=file='${LUT}':interp=tetrahedral,\
       zscale=t=linear,\
       zscale=p=bt2020:t=arib-std-b67:m=bt2020nc:r=tv,\
       format=yuv420p10le,${HLG}" \
  -c:v libx265 -crf 18 -preset slow -pix_fmt yuv420p10le \
  -tag:v hvc1 -c:a copy demo_og_aspen_hlg.mp4
```

The leading `${HLG}` is load-bearing. `zscale=t=linear` cannot linearise frames
whose transfer function it does not know, and fails with nothing more useful than
"Generic error in an external library". Tagging first tells it what it is
holding. On correctly tagged footage the leading tag is redundant but harmless —
and it is exactly what rescues a file that recorded 10-bit while claiming
Rec.709, the failure described in Step 2.

If you want the Aspen look properly in HDR rather than round-tripped, the answer
is a LUT authored for HLG input, not a cleverer filter chain.

### Step 4 — the A/B that decides whether any of this is worth it

Half an hour, and it settles the question for your material rather than in the
abstract. Shoot the same scene twice, 8-bit and 10-bit, tripod, same exposure.
Run both through the same grade. Compare **a smooth gradient** — dusk sky,
snowfield, a shaded wall — at 100%.

What to expect, so the result is not a surprise:

- **Ungraded, on a phone screen: no visible difference.** 10-bit is not "more
  detail" and not "more dynamic range". The sensor captures the same light; the
  extra bits describe it in finer steps.
- **After the grade: the difference is banding.** A dusk sky spans perhaps thirty
  8-bit levels; stretching it to "rich cinematic blue" makes those steps visible
  as bands. 10-bit has roughly four times the steps and holds together.
- **On an HDR display,** the wider BT.2020 gamut and the HLG curve are a more
  obvious change than the bit depth itself, because highlights get to be bright
  rather than clipped.

If the gradients look the same after grading, 10-bit is not buying anything for
the way you shoot, and the file sizes are not worth it.

### A note that applies even on 8-bit

The command in *Post-Production* below uses `format=rgb24` — eight bits per
channel — so it truncates before the LUT is applied. Widening that intermediate
to `format=gbrp10le` reduces banding even from an 8-bit source, because the LUT
arithmetic stops rounding at every step. It cannot recover what was never
captured, but it stops adding error. Free improvement, no re-shoot.

---

## Pixel 11 Pro — shipped August 2026: still not an upgrade for Kanaha

Announced 12 August 2026, released 20 August, Android 17, Tensor G6 on TSMC 3nm.
The prediction in the earlier version of this section held; what follows is what
actually shipped, checked in September 2026.

**The conclusion is unchanged — but not for the reason first given.** The old
text said the Pixel 11 was "largely the same hardware" with AI on top. That is
wrong. The telephoto is a genuinely new part.

| | Pixel 10 Pro | Pixel 11 Pro |
|---|---|---|
| Main (wide) | 50 MP, 1/1.31" | 50 MP, f/1.68, **1/1.3"** — unchanged |
| Ultrawide | — | 48 MP, f/1.7, 1/2.51" |
| Telephoto | — | 48 MP, f/2.8, 5×, **1/1.95" — larger sensor and optics** |
| SoC | Tensor G5 | Tensor G6, TSMC 3nm, MediaTek modem |
| Video (spec sheet) | — | 1080p/4K at 24/30/60, **8K at 24/30** |

**Why it still does not matter here:** Kanaha's open gate work is main-sensor,
and the main sensor did not change. 1/1.31" to 1/1.3" is rounding, not a new
part. Everything Kanaha records comes off the lens that stayed the same.

The telephoto upgrade is real and is available to any Camera2 app — it is
hardware, not an AI feature. It would matter for a tele B-camera or anything shot
at 5×. It does not touch open gate.

### What the headline features actually are

| Feature | Shipped | Kanaha/Camera2 accessible? |
|---|---|---|
| Gemini Intelligence | Yes | **No** — agentic tooling on cloud Gemini plus on-device Gemma. Not a camera capability at all |
| Pro Zoom (120×, up from 100×) | Yes | **No** — AI-assisted consumer zoom in the stock app |
| Tensor G6 ISP | Yes | **Yes, passively** — better base footage for every Camera2 app, unquantified |
| Larger telephoto | Yes | **Yes** — ordinary hardware, no gatekeeping |

No new camera APIs for third-party developers are documented anywhere I could
find.

### Two things deliberately left unresolved

Both would need a Pixel 11 in hand to settle, and neither changes the
recommendation.

- **Whether third-party 10-bit access is unchanged.** The sources covering this
  phone do not mention 10-bit HDR, HLG, open gate or Camera2 at all, so "carries
  forward from Pixel 10" is an assumption, not a finding. Android 17 could have
  moved something.
- **Whether the 8K in the spec sheet is reachable from Camera2.** The earlier
  version of this section listed 8K as cloud-only Video Boost. Plain 8K/24/30 now
  appears in the ordinary spec list, which hints at a normal encoder path, but
  that is inference. `adb shell dumpsys media.camera` on the device would answer
  it in one line.

**Recommendation, unchanged:** the Pixel 10 Pro delivers the Camera2-accessible
improvements that matter for Kanaha — 10-bit HLG, larger open gate. The Pixel 11
Pro adds a better telephoto and AI features that no OSS pipeline can reach.
**Not worth trading up for open gate work.**

---

## 10-bit HLG: how it was built

Notes for whoever changes it. The results are in
[Bit Depth](#bit-depth-what-each-phone-records); file and line references are to the
OpenCamera 0dd4cbe tree as changed.

### What changed

| File | Change |
|---|---|
| `CameraController2.createOutputConfigurationList()` | `setDynamicRangeProfile(HLG10)` on the preview and recorder surfaces, plus `logHlg10Constraints()` |
| `CameraController2` session gates (2 sites) | widened to `cameraIdSPhysical != null \|\| want_jpeg_r \|\| want_hlg10`, or the `OutputConfiguration` path is never taken |
| `VideoProfile.copyToMediaRecorder()` | `setVideoEncodingProfileLevel(HEVCProfileMain10, HEVCMainTierLevel51)`; before this nothing in the tree set a profile, so the encoder always ran 8-bit Main |
| `Preview.java` | `camera_controller.setHlg10(...)` before session creation; forces HEVC, since H.264 has no 10-bit profile |
| `MyApplicationInterface.getHlg10Pref()` | the gate: preference on **and** video mode **and** HEVC output |
| `DrawPreview.updateSettings()` | histogram, zebras, focus peaking and pre-shots off while 10-bit is on |

The two widened session gates are the easiest thing to miss: without them
`createOutputConfigurationList()` is never called, the profile is never set, and the
recording is silently 8-bit.

### Why the preview flips to HLG10 during a recording

The Pixel reports HLG10 with a concurrent-use set of HLG10 only
(`availableDynamicRangeProfilesMap = [2, 2, 0]`), confirmed at runtime:

```
KANAHA_HLG10: supports_hlg10=true capabilities_10bit=true sdk=37
KANAHA_HLG10: HLG10 concurrent constraints: [2]
KANAHA_HLG10: STANDARD NOT allowed - every surface must be HLG10
```

So every surface in a 10-bit session must be HLG10, and `TextureView` turned out to
accept it (2026-09-05). Two consequences, both in the code:

- **HLG10 goes only on the preview and recorder surfaces.** On the JPEG `ImageReader`
  it fails session configuration with `Failed to create capture session`, which looks
  exactly like `TextureView` rejecting HLG10 and is not. A configuration failure here
  does not say which surface caused it.
- **Only during a recording, and no video snapshots.** The idle preview session carries
  a JPEG reader that must stay STANDARD, so the preview switches to HLG10 when
  recording starts and back when it stops.

The overlays are refused rather than the reverse because they read frames through an
8-bit `ARGB_8888` bitmap: on a 10-bit surface they would fail or misreport exposure,
and the alternative would be recording 8-bit after the operator asked for 10-bit.

### On a phone that cannot do 10-bit

`setDynamicRangeProfile()` with an unadvertised profile fails session configuration,
which kills the preview, not just the recording. Three independent guards:

1. **The setting is removed** from Settings > Video (`PreferenceSubVideo`).
2. **`Preview` does not request it:** `setHlg10()` gets
   `supports_hlg10 && !is_extension && pref`.
3. **The apply site refuses it:** `createOutputConfigurationList()` applies nothing if
   HLG10 is not in the camera's supported set.

`supports_hlg10` needs the `DYNAMIC_RANGE_TEN_BIT` capability, API 33 and HLG10 in
`REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES`; it is logged at every camera open
(`adb logcat -d | grep KANAHA_HLG10`). Verify the file rather than the log, since an
encoder can accept a 10-bit profile and quietly ignore it:

```sh
ffprobe -v error -select_streams v:0 \
  -show_entries stream=pix_fmt,profile,color_transfer,color_primaries \
  -of default=nw=1 recorded.mp4
# want: pix_fmt=yuv420p10le  profile=Main 10  color_transfer=arib-std-b67
```


## Post-Production: LUT Grading with ffmpeg

All commands below take the LUT pack location from `$LUT_DIR`. Set it once for
the shell, or put it in your profile:

```sh
export LUT_DIR="$HOME/Downloads/lut"      # wherever the IWLTBAP pack was unpacked
```


Open gate footage from Kanaha is standard H.264/HEVC — it drops into any post pipeline. The recommended workflow is to shoot flat and grade in post; do not bake a LUT at capture.

### Recommended LUT for snow/winter exterior

From the IWLTBAP Renata pack (`$LUT_DIR`, below), **Aspen Standard** works best for winter snow scenes: it lifts the sky to a rich cinematic blue, adds contrast to tree lines, and keeps snow whites natural without the teal push of Humble/Renata.

```bash
LUT="$LUT_DIR/IWLTBAP - Renata (Free LUT)/LUTs by IWLTBAP (CUBE)/BONUS/Aspen/IWLTBAP Aspen - Standard.cube"

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

> **Better use case for Red emulation:** A scene with cloud shadows across snow — shadow zones at code value ~150 vs. lit snow at ~215 gives the contrast and gamma adjustments real midtone material to work with, and the warm/cool split between sunlit and shadowed areas is where `colorbalance` produces the characteristic Red look. Flat bright snow has no shadow zones, leaving the emulation with nothing to grade except already-compressed highlights.

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
W, H = src.size  # 2560x1920 open gate

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
              "4:3  OPEN GATE",    "Full sensor field of view"),
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
LUT="$LUT_DIR/IWLTBAP - Renata (Free LUT)/LUTs by IWLTBAP (CUBE)/BONUS/Aspen/IWLTBAP Aspen - Standard.cube"

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

- **~3s reopen latency**: `configureOpenGate()` triggers a photo→video mode cycle which causes a camera session reopen. On the Pixel 10 Pro XL the camera was ready 500 ms after the switch back to video and the `open_gate=true` startRecording call returned in 3.6 s (2026-10-19); the poller returns as soon as the camera is ready (max 10s), so allow 3–5 s. For use with `start_at` scheduling, the sidecar is written before the recording starts so timing is still accurate.
- **Zoom reset**: `configureOpenGate()` resets digital zoom to 1x. If you need open gate at a specific focal length, use optical zoom (different lens) rather than digital zoom.
- **12-bit is not video**: open gate video is 8- or 10-bit, as the encoder records it. 12-bit RAW takes are a separate route: [12-bit_depth.md](12-bit_depth.md).
- **Moto G graceful fallback**: No error is returned; the phones simply record in their default quality. The `open_gate: false` in the sidecar confirms it was not applied.

---

## Build Process: Modifying the C Layer

The HTTP server running on the phone is **`jniLibs/arm64-v8a/libhttpd.so`** — a pre-built ARM64 Apache + Axis2/C binary. Gradle does **not** rebuild this file. Any change to `camera_control_service.c` requires a manual cross-compilation step.

### Workflow

```bash
# 1. Edit the C source
vim app/src/main/cpp/axis2c/camera_control_service.c

# 2. Cross-compile, relink, strip and install into jniLibs
(cd ../.. && bash build-android.sh)    # from kanaha-camera-app/; the script is at the repo root

# 3. Rebuild APK (includes updated libhttpd.so)
./gradlew assembleDebug

# 4. Install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### What `build-android.sh` Does

1. Compiles `axis2_static_service_adapter.c` + `camera_control_service.c` → `libkanaha_services.a`
2. Links with all static Apache modules, Axis2/C libs, OpenSSL, APR, nghttp2, PCRE2 using NDK clang (`aarch64-linux-android21-clang`). Those libraries, and the httpd build tree, come from [kanaha-android-deps](https://github.com/robertlazarski/kanaha-android-deps), which must be built first.
3. Strips the binary and installs it as `kanaha-camera-app/app/src/main/jniLibs/arm64-v8a/libhttpd.so`

The resulting binary is the full Apache HTTP/2 + mTLS server with the Kanaha camera control service compiled in. The pre-built binary in `jniLibs/arm64-v8a/libhttpd.so` is the one actually deployed to the phone.

### Verification

After install, confirm the C layer picked up your changes:
```bash
# Check strings are present in the deployed binary
adb shell "strings /data/app/*/org.kanaha.camera*/lib/arm64/libhttpd.so | grep open_gate"
```

See `docs/ANDROID_CROSS_COMPILATION.md` for the full toolchain setup and dependency build instructions.
