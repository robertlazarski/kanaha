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

The 12-megapixel 4:3 frame is reachable only as RAW (4080×3072, Part 2 below), which is
encoded by nothing.

---

## Bit Depth: What Each Phone Records

Bit depth is how many steps each colour channel is divided into: 256 at 8-bit, 1024 at
10-bit, 4096 at 12-bit. More steps means a grade can stretch a smooth gradient, a dusk sky
or a snowfield, without it breaking into bands. Bit depth is not dynamic range: the range
from shadow to highlight is set by the sensor and the tone curve, and the extra bits
describe that same range in finer steps (see [Step 4](#step-4--the-ab-that-decides-whether-any-of-this-is-worth-it)).

### Pixel 10 Pro XL (A-camera)

Measured on the device, Android 17, 2026-10-19.

| | 8-bit (default) | 10-bit HLG | 12-bit RAW |
|---|---|---|---|
| How | `startRecording` | `startRecording` with `"hlg10": true` | Not recordable by Kanaha yet; the debug probe captures it |
| Codec | H.264 High | HEVC Main 10 | None: one DNG per frame |
| Pixel format | `yuv420p` | `yuv420p10le` | 16-bit container, 12 significant bits |
| Colour tags | BT.709 primaries, sRGB transfer (`iec61966-2-1`) | BT.2020, HLG (`arib-std-b67`) | Camera colour matrices in each DNG |
| 4K30 data rate | 23 Mbps dim scene, 73 Mbps daylight | 55 Mbps dim scene | 753 MB/s (25.1 MB per frame) |
| Largest frame | 3840×2160; open gate 2560×1920 | 3840×2160; open gate 2560×1920 | 4080×3072, the full 4:3 sensor |

- **10-bit HLG** is correctly tagged by the camera, so it grades without retagging. It
  always records HEVC whatever `format` says, since H.264 has no 10-bit profile on this
  phone. `getStatus` reports `supports_hlg10`. `startRecording` refuses `hlg10` while
  `setExposure` has a fixed `tone` curve pinned, because the curve would distort the HLG
  transfer function. Open gate and `hlg10` together work: 2560×1920 HEVC Main 10 with the
  same HLG tags, a steady 30 fps, about 34 Mbps (2026-10-19).
- **The 8-bit files are tagged with the sRGB transfer curve** rather than BT.709. Players
  and ffmpeg read the tag as written; it matters when converting colour spaces in post.
- **12-bit RAW is real.** The camera offers `RAW_SENSOR`, `RAW12` and `RAW10` at 4080×3072
  and 30 fps. In a daylight scene the samples reached 4095, 10.5% sat above the 10-bit
  range, and their two lowest bits were populated, which 10-bit data scaled up would never
  have. Recording it as video is Part 2 below. Whether the sensor used dual conversion
  gain to produce those samples cannot be seen: the camera exposes no key for it.
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
- **RAW is 10-bit** (white level 1023), stills only; Kanaha records no RAW video.
- **Its files are tagged differently from the Pixel's:** full range (`yuvj420p`,
  `color_range=pc`) and BT.601 colour (`bt470bg`, `smpte170m`), where the Pixel writes limited
  range and BT.709. Convert before cutting the two together; see
  [Matching cameras](MULTI_CAMERA_DEPLOYMENT_SYSTEM.md#matching-cameras).


### Summary for the current rig

| Phone | 8-bit | 10-bit HLG | 12-bit RAW | Open gate |
|---|---|---|---|---|
| Pixel 10 Pro XL | Default | Yes, `hlg10` | Camera delivers it; Kanaha records it only through the debug probe | 2560×1920 |
| Moto G Play 2024 (Camera1, default) | Default | No | No | Not tested |
| Moto G Play 2024 (Camera2) | Default | No | 10-bit stills only | 1600×1200 |

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
- `RAW12` alongside `RAW_SENSOR` in the output formats. This is the 12-bit
  question and it is worth being clear about the answer: **Camera2 has no 12-bit
  video path at all.** Its four HDR profiles — HLG10, HDR10, HDR10+, Dolby
  Vision 8.4 — are every one of them 10-bit HEVC. `RAW12` is a stills format.
  What other apps call "12-bit video" is a DNG sequence: numbered raw stills at
  frame rate, no audio, no container. Nothing is locked; it is a different
  capture mode, and one that does not fit Kanaha's HTTP-triggered, file-per-take
  pipeline without a separate ImageReader workstream.

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

---

## 12-bit RAW: what the Pixel 10 Pro XL actually offers

Measured on the device with `adb shell dumpsys media.camera`, 2026-09-04, not
taken from a spec sheet. Every number below is from that dump.

### The capability is real and nothing is gatekeeping it

| Finding | Value |
|---|---|
| `sensor.info.whiteLevel` (main rear camera) | **4095** — 2¹²−1, a genuine 12-bit readout |
| `RAW12` (format 38) output size | **4080×3072** |
| `RAW_SENSOR` (format 32) output size | 4080×3072 |
| Min frame duration at that size | **33,333,333 ns = 30 fps** |
| **Stall duration** | **0 ns** |
| Pixel array / binned | 8160×6144 → 4080×3072 |

The other physical cameras report `whiteLevel` 1023 — 10-bit. The 12-bit
readout is the main sensor only.

**The stall duration is the number that matters.** Zero means the pipeline does
not block when a RAW12 frame is pulled: the sensor will sustain 30 fps of 12-bit
frames without holding up anything else in the session. The camera is not the
constraint. Neither is the API, and neither is OpenCamera — it already carries
the whole DNG path (`DngCreator` in `CameraController2`, `RawImage.writeImage`
in `ImageSaver`), it simply uses it one frame at a time for stills.

So this is genuinely closer than it looks. It is also blocked, and not where
you would guess.

### Where it is actually blocked

**Throughput, not permission.**

| Stream | Per frame | At 30 fps | Per minute |
|---|---|---|---|
| RAW12 packed | 18.8 MB | **564 MB/s** | 33.8 GB |
| RAW_SENSOR (16-bit container, what `DngCreator` consumes) | 25.1 MB | **752 MB/s** | 45.1 GB |

A one-minute take is 34–45 GB. That is the whole problem in one line, and it has
three separate edges:

1. **Sustained write rate — measured, and fine.** 1.5 GB/s over a 10 GB write
   with `fsync`, and 93 separate files a second. Roughly twice the throughput and
   three times the file rate needed. Struck off the risk list; see the
   implementation plan below for the figures. **Capacity** is the constraint that
   replaces it: 180 GB free at 45 GB/min is four minutes of recording in total.
2. **`DngCreator` was expected to be the wall, and measured inside budget.** It
   assembles a DNG on the CPU per image; the probe timed it at 7–10 ms of CPU and
   21–25 ms including the file write, against 33 ms per frame at 30 fps. **Confirmed
   sustained, 2026-10-20:** a 60 s take writing every frame as its own DNG on four
   worker threads kept up — 1,782 of 1,783 frames written (one dropped at start), no
   frames missing at the sensor, 745 MB/s, 41 ms median and 96 ms worst per DNG with
   four in flight. A 30 s take on three threads gave the same (890 of 891). Run it with
   `--ei dng_seconds 60 --ei dng_threads 4` on `RawProbeActivity`.
3. **Kanaha's model assumes one file per take.** The HTTP API returns a path. A
   DNG sequence is a directory of a few thousand files with no audio and no
   container, which every part of the pipeline — the service response, the
   sidecar JSON, the ffmpeg steps — currently assumes does not happen.

### Honest read

The interesting half is done and was done by other people: the sensor exposes
it, the frame rate is there, the pipeline does not stall, and the DNG writer is
already in the tree. What remains is a sustained-throughput problem and a
file-model problem, and those are ordinary engineering rather than a locked door.

That makes it worth doing at some point. It does not make it next — 10-bit HLG
delivers most of the grading benefit for a fraction of the work and none of the
storage cost, and it fits the existing one-file-per-take model without touching
it.

That measurement has since been taken and storage passed comfortably, and the
sustained `DngCreator` run above passed too, which leaves the file model and
capacity — tractable, real work. The implementation plan below sizes them.


---

## Implementation plan: 10-bit and 12-bit

Written to let the effort be judged before it is started. Everything below is
from reading the OpenCamera 0dd4cbe tree and the device dump, with file and line
references so the claims can be checked rather than trusted.

### Storage: measured, and not the problem

| Test | Result |
|---|---|
| 2 GB sequential, `conv=fsync` | 1.2 GB/s |
| 10 GB sustained, `conv=fsync` | **1.5 GB/s**, no degradation |
| 60 × 25 MB separate files + `sync` | 2340 MB/s, **93 files/sec** |

RAW_SENSOR at 30 fps needs 752 MB/s and 30 files/sec. The phone delivers roughly
**twice the throughput and three times the file rate** required. Storage speed can
be struck off the risk list.

**Capacity cannot.** 180 GB free of 228 GB usable, at 45 GB/min, is **four
minutes of 12-bit recording in total** — not per take, in total, before the phone
is full and needs offloading. External USB-C storage raises the ceiling but not
the rate: USB 3.2 on a Pixel will not sustain 1.5 GB/s, so it is a capacity
answer, not a throughput one. Offloading 45 GB over USB is itself several
minutes.

---

### Part 1 — 10-bit HLG video

**Estimate: 1–2 days**, with one genuine unknown that could turn it into a week.
See "the risk" below before committing.

#### What changes

1. **`CameraController2.createOutputConfigurationList()`** (~line 5211). The call
   is already written and commented out. Uncomment it, gate it on the Kanaha
   preference, and apply it to **every** surface in the session, not just the
   preview.
2. **`VideoProfile.copyToMediaRecorder()`** (line 104 sets the encoder). Add
   `media_recorder.setVideoEncodingProfileLevel(HEVCProfileMain10, level)`.
   Nothing in the tree calls this today — there are zero references to
   `CodecProfileLevel` anywhere — so the encoder has always run at its default
   8-bit Main profile.
3. **`Preview.java:3843`** already picks `MediaRecorder.VideoEncoder.HEVC` under
   some conditions; 10-bit must force that branch, since Main 10 is an HEVC
   profile and H.264 cannot carry it.
4. **A Kanaha preference and an HTTP parameter**, so the setting is reachable from
   the existing `startRecording` API alongside `open_gate`.

#### The guards, and why each exists

- **`capabilities_10bit`** is already computed at `CameraController2:1936-2012`
  from `REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT`. Refuse if absent.
- **API 33+**, since `DynamicRangeProfiles` does not exist before it.
- **Refuse the histogram, zebra stripes, focus peaking and pre-shots while
  10-bit is enabled.** `DrawPreview.updateSettings()` sets
  `want_preview_bitmap = want_histogram || want_zebra_stripes || want_focus_peaking || want_pre_shots`,
  and when that is true `Preview` pulls frames with `textureView.getBitmap()`
  into an `ARGB_8888` bitmap — 8-bit sRGB. On a 10-bit surface those overlays
  would either fail or, worse, quietly misreport exposure. **Implemented:**
  `updateSettings()` now clears all four `want_*` flags whenever the 10-bit
  preference is on and the camera supports HLG10. It keys off the preference
  rather than the live recording session so the overlays cannot switch on in
  the gap between enabling the setting and pressing record. The refusal runs
  in this direction — overlays yield to 10-bit, not the reverse — because the
  alternative would silently record 8-bit after the operator asked for 10-bit,
  which is the failure this whole section warns about. All four default off
  and Kanaha drives the camera headlessly, so nothing in normal use trips it.

#### The risk that decides the estimate

**The preview cannot stay 8-bit.** The device reports
`availableDynamicRangeProfilesMap = [2, 2, 0]`: profile HLG10, and a concurrent-use
constraint set containing HLG10 *only*. Reading that literally, STANDARD and HLG10
cannot coexist in one capture session — so enabling 10-bit for the recorder forces
the preview to HLG10 as well. Worth confirming at runtime with
`DynamicRangeProfiles.getProfileCaptureRequestConstraints(HLG10)` before writing
any code; it is one log line and it settles the whole shape of the work.

That matters because **Camera2 mode hardcodes `TextureView`**
(`Preview.java:470-476`; `MySurfaceView` is only used with the legacy Camera1
API). `TextureView` renders through a `SurfaceTexture`, and that path has
historically been the weak one for 10-bit HDR formats, where `SurfaceView` is
the supported route.

This is almost certainly the wall the upstream maintainer hit.
`MyApplicationInterface.java:1784` disables Ultra HDR stills during video with the
comment *"video recording fails if CameraController2 sets
`config.setDynamicRangeProfile(DynamicRangeProfiles.HLG10)` for the preview"*, and
the call at `CameraController2:5222` is commented out with a note that it gave
"much lower saturation" on a Galaxy S24+. Two devices, two different failures,
and he backed out.

**So there are two outcomes.** If `TextureView` accepts HLG10 on this phone, the
work is the 1–2 days above. If it does not, the preview has to move to
`SurfaceView` for 10-bit sessions — and the comment at `Preview.java:479` explains
the knock-on: *"a TextureView can't be used both as a camera preview, and used for
drawing on, so we use a separate CanvasView"*. Changing the surface type changes
how every overlay is drawn. That is the week, not the two days.

**Find out first.** Set the profile on both surfaces, start a recording, read
logcat. Half a day of work that de-risks the entire estimate, and it can be done
before committing to anything.

#### The probe, as built

Implemented on branch `opencamera-upgrade` (`b201bbf`), gated behind a new
**Settings > Video > 10-bit HLG video** checkbox (`preference_kanaha_hlg10`,
default off). It is deliberately the smallest thing that answers the question:

| File | Change |
|---|---|
| `CameraController2.createOutputConfigurationList()` | `setDynamicRangeProfile(HLG10)` on **every** surface, plus `logHlg10Constraints()` |
| `CameraController2` session gates (2 sites) | widened to `cameraIdSPhysical != null \|\| want_jpeg_r \|\| want_hlg10`, or the `OutputConfiguration` path is never taken |
| `VideoProfile.copyToMediaRecorder()` | `setVideoEncodingProfileLevel(HEVCProfileMain10, HEVCMainTierLevel51)` |
| `Preview.java` | `camera_controller.setHlg10(...)` before session creation; tags `video_profile.hlg10` |
| `MyApplicationInterface.getHlg10Pref()` | the gate: pref on **and** video mode **and** HEVC output format |

The two widened session gates are the easiest thing to miss. Without them
`createOutputConfigurationList()` is never called at all, the profile is never
set, and the probe silently reports success while recording 8-bit.

#### Probe result, 2026-09-05: it works

**`TextureView` accepts HLG10. This is the 1-2 day path, not the week.**

Recorded on the Pixel 10 Pro XL (`sdk=37`), verified with `ffprobe`:

```
codec_name=hevc          profile=Main 10
pix_fmt=yuv420p10le      1920x1080 @ 29.75fps, 27 Mbps
color_transfer=arib-std-b67    color_primaries=bt2020    color_space=bt2020nc
```

Genuinely 10-bit, genuinely HLG, and **correctly tagged by the camera**. That
last part matters for post: the `setparams` filter documented below exists
because the 8-bit files came out untagged. Camera-native 10-bit files do not
need it, and `zscale=t=linear` will work on them directly.

The capability line and the constraint set, confirmed at runtime:

```
KANAHA_HLG10: supports_hlg10=true capabilities_10bit=true sdk=37
KANAHA_HLG10: supported profiles: [1, 2]
KANAHA_HLG10: HLG10 concurrent constraints: [2]
KANAHA_HLG10: STANDARD NOT allowed - every surface must be HLG10
```

So the `[2, 2, 0]` dumpsys reading was right: HLG10 permits no other profile
alongside it. That has two consequences that cost a false negative before they
were understood, and both are now in the code:

- **HLG10 only goes on the preview and recorder surfaces.** The first probe run
  applied it to *every* surface, including the JPEG `ImageReader`.
  `setDynamicRangeProfile()` is not meaningful on a JPEG/BLOB surface, and that
  alone fails configuration — producing `Failed to create capture session` that
  looks exactly like `TextureView` rejecting HLG10. It is not. **If you take one
  thing from this section, take that:** a configuration failure here does not
  identify which surface caused it.
- **Only during a recording session, and no video snapshots.** The idle preview
  session carries a JPEG reader that must stay STANDARD, so the preview flips to
  HLG10 when recording starts and back to 8-bit when it stops. Video snapshots
  are dropped for the duration, since that reader cannot join an HLG10 session.

**HTTP parameter:** `startRecording` takes `"hlg10": true`. It applies to that
recording only, so omitting it records 8-bit whatever the Settings toggle says,
and a camera without 10-bit support refuses it with an error rather than
recording 8-bit. `getStatus` reports `supports_hlg10`. No session reopen is
needed: the recording session is built fresh at start, which is where HLG10 is
applied. The histogram/zebra/focus-peaking/pre-shots refusal described above is
done (`DrawPreview.updateSettings()`).

#### Running it again

To finish:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Settings > Video > output format = HEVC, then tick "10-bit HLG video"
adb logcat -c && adb logcat | grep -E "KANAHA_HLG10|CameraController2|MediaRecorder"
# start a recording from the UI or the HTTP API
```

**What each outcome means.** `logHlg10Constraints()` prints the constraint set
before anything is configured, so the first two lines already settle the shape of
the work:

- `STANDARD allowed alongside` — the preview can stay 8-bit. Best case, and it
  would contradict the `[2, 2, 0]` reading from dumpsys.
- `STANDARD NOT allowed - every surface must be HLG10` — expected. Then the
  question is only whether `TextureView` accepts it.
- Session configures and recording starts — **1–2 days**, finish the remaining
  guards and the HTTP parameter.
- `onConfigureFailed`, or a preview that is black, green or washed out —
  `TextureView` is the wall, the preview has to move to `SurfaceView`, and every
  overlay's drawing path changes with it. **That is the week.**

#### On a phone that cannot do 10-bit

Three independent guards, because the failure mode is bad enough to be worth
belt and braces: `setDynamicRangeProfile()` with an unadvertised profile fails
**session configuration**, which kills the preview, not just the recording. The
camera simply would not start.

1. **The setting is removed** from Settings > Video (`PreferenceSubVideo`), so
   there is no switch that silently does nothing.
2. **`Preview` does not request it** — `setHlg10()` is passed
   `supports_hlg10 && !is_extension && pref`.
3. **The apply site refuses it** — `createOutputConfigurationList()` calls
   `logHlg10Constraints()` and applies nothing if HLG10 is not in the camera's
   supported set, whatever the layers above asked for.

`supports_hlg10` requires all three of the `DYNAMIC_RANGE_TEN_BIT` capability,
API 33, and HLG10 in `REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES`. It is logged
unconditionally at camera open — `MyDebug.LOG` is `false` in release builds, and
this is the one line that distinguishes a phone that genuinely cannot do 10-bit
from a guard wrongly refusing on one that can:

```sh
adb logcat -d | grep KANAHA_HLG10
# KANAHA_HLG10: supports_hlg10=true capabilities_10bit=true sdk=36
```

The Moto G cameras in the rig are the ones to check this against — they already
ignore `open_gate`, and they will not offer the toggle at all.

Verify the output rather than trusting the log — the encoder can accept a 10-bit
profile request and quietly ignore it:

```sh
ffprobe -v error -select_streams v:0 \
  -show_entries stream=pix_fmt,profile,color_transfer,color_primaries \
  -of default=nw=1 recorded.mp4
# want: pix_fmt=yuv420p10le  profile=Main 10  color_transfer=arib-std-b67
```

---

### Part 2 — 12-bit RAW as DNG sequences

**Estimate: 2–4 weeks**, and the shape of the work is different — nothing is
locked, but three things need building that do not exist.

#### What already exists, and it is more than expected

- The sensor: `whiteLevel 4095`, `RAW12` and `RAW_SENSOR` at 4080×3072,
  **30 fps, stall duration 0**. The pipeline will sustain it.
- The writer: OpenCamera already carries the whole DNG path — `DngCreator` in
  `CameraController2:940`, `RawImage.writeImage()` called from
  `ImageSaver.java:2447`. It is used one frame at a time for stills.
- Storage: measured above, twice the headroom needed.

#### What does not exist

**1. `DngCreator` at 30 fps, sustained.** It assembles a DNG on the CPU per image,
for someone pressing a shutter. Measured per frame it fits the 30 fps budget (probe
results below), which makes the simple design, one DNG per frame written during the
take, the one to try first. Untested: 30 a second for minutes, alongside capture and
a 750 MB/s write. If that fails, the fallback is to write frames raw during the take
and build DNGs afterwards: a capture format, an index and an offline converter.

**2. OpenCamera never requests RAW12 or RAW10.** It only ever asks for
`RAW_SENSOR` (`CameraController2:2143`). `RAW_SENSOR` is a 16-bit container
holding the 12 significant bits, which is what `DngCreator` consumes — so it
works, but costs 25.1 MB/frame against RAW12's 18.8 MB. Using packed RAW12 saves
25% of the data rate and means writing the DNG assembly by hand, because
`DngCreator` expects `RAW_SENSOR`. A real trade, not an obvious one.

**3. Kanaha assumes one file per take.** The HTTP API returns a path; the sidecar
JSON describes a file; the ffmpeg steps open a file. A DNG sequence is a
directory of a few thousand files with no audio and no container. Every stage
needs a notion of "a take is a directory", including whatever offloads it.

#### The probe that sizes it

`RawProbeActivity` (debug builds only, `app/src/debug/`) answers the open
questions on the device before any design work: whether full-size RAW arrives at
30 fps without drops, whether the frames can be written as fast as they arrive
(the write-raw-now, build-DNGs-later design), how long `DngCreator` takes per
frame, and whether the samples really use 12 bits. A white level of 4095 does not
settle the last one: 10-bit data scaled into a 12-bit range has its two low bits
always zero, so the probe counts them in a real frame.

```sh
adb shell am start -n org.kanaha.camera/org.kanaha.camera.RawProbeActivity
adb logcat -s KANAHA_RAW12
```

Point the camera at a bright scene first, or the bit-depth check reports that the
values never left the 10-bit range. `--ei seconds 10` lengthens each timed phase;
`--es format raw12` times packed RAW12 capture instead (the DNG and bit checks
need `RAW_SENSOR` and are skipped). The full result is also written to
`Android/data/org.kanaha.camera/files/raw_probe/result.json`. The write phase puts
several GB on storage and deletes it afterwards.

How to read it: `dng_cpu_ms` under 33 means the simple DNG-per-frame design can
work; over it, the deferred design is mandatory. `writer_drops` above zero means
storage or the copy cannot keep up. `missing_frames` in the capture phase means
the camera itself cannot sustain 30 fps at full size, which ends the question.

#### Probe results, Pixel 10 Pro XL, 2026-10-19

Android 17 (`CP3A.260905.009`), 5 s per timed phase, indoors and dim.

| Question | Result |
|---|---|
| Full-size `RAW_SENSOR` (4080×3072) at 30 fps | **Yes**: 147 frames, none missing, no buffer lost |
| Frames written as fast as they arrive | **Yes**: 753 MB/s sustained over 5 s, 3.77 GB, zero frames dropped |
| `DngCreator` per frame | **7–10 ms CPU, 21–25 ms to a file**, against a 33 ms budget; 25.1 MB per DNG |
| Genuine 12-bit samples | **Yes**: 25.8% of samples divisible by 4, where data shifted up from 10 bits gives ~100% |
| `RAW10` and `RAW12` offered | Both, at 4080×3072 and 30 fps, stall 0 |
| Dual conversion gain visible | **No**: no DCG-related key; the only vendor keys are Google HDR+ tags |

What this settles: the camera, the storage and the DNG writer each keep up with 30 fps
on their own. All three together over a whole take was settled on 2026-10-20 (a 60 s
take of per-frame DNGs kept up; see "Where it is actually blocked" above). Still open:
heat over takes longer than a minute. The 12-bit check shows the samples carry 12 bits of precision; whether the sensor
used dual conversion gain to produce them cannot be seen from outside.

**Second run, same day, daylight scene** (ISO 21, 1/1024 s): 148 frames at 30 fps with
none missing; 764 MB/s written with no drops; `DngCreator` 5–7 ms CPU and 19–22 ms to a
file. Samples reached the full 4095 (the window clipped), 10.5% of them sat above the
10-bit range, and 25.1% were divisible by 4. So the raw data spans the whole 12-bit range
with populated low bits, in a bright scene as in a dim one.

#### The capacity question, honestly

Four minutes of total recording on the free space available. That is not a
technical blocker; it is a workflow one. It means offload between takes, every
take, and it means a shoot is planned around storage in a way an H.265 shoot is
not. External USB-C storage would move the ceiling, but on this rig the USB-C port
carries SMPTE timecode through the iRig, so it is not available during a take.

#### Why DNG, and why it is this big

**BRAW and ProRes RAW aren't available to us.** They are the efficient 12-bit raw
formats (visually lossless compression, one file per take), but their encoders live
only inside Blackmagic's and Apple-licensed cameras and recorders; nothing on Android
or Linux can write them. CinemaDNG, a folder of DNG frames plus audio, is the only
open raw video format, which is why the probe writes DNG.

What the Pixel writes is DNG at its least efficient. `DngCreator` stores the samples
uncompressed, each 12-bit value in 16 bits: 25,093,636 bytes per frame is
4080 × 3072 × 2 plus a header, so a quarter of every file is padding. That is
745 MB/s, about 41 GB a minute. The file count is not the problem; the phone takes 30
files a second with room to spare (93 measured).

| Reduction | Size | Cost |
|---|---|---|
| 16:9 crop (4080×2296) | −25% | Easy |
| Packed 12-bit samples instead of 16 | −25% | Our own DNG writer instead of `DngCreator` |
| Lossless JPEG compression, as CinemaDNG cameras use | about −40%, scene-dependent | Our own writer, plus CPU per frame not yet measured |
| All three | about 15 GB/min | Real work |

Post-production on Linux is CLI-capable but slow: each frame is developed
(`darktable-cli` or `rawtherapee-cli` to 16-bit TIFF, or ffmpeg directly), then
encoded with ffmpeg, 1,800 frames per minute of footage.

#### Step 0 results: a song-length take, 2026-10-20 to 10-22

The target is a live studio music video: every camera rolls the whole song (about
four minutes) at 30 fps, open gate, so one shot gives a 16:9 YouTube crop and a 9:16
TikTok crop. The 16:9 crop is therefore off the table, and so is 24 fps. Measured on
the Pixel 10 Pro XL (256 GB, ~172 GB free) with `RawProbeActivity` take mode:

| Question | Result |
|---|---|
| Live preview and RAW together at 30 fps | Yes, zero frames missing at the sensor |
| Frame rate | 29.997 fps with the shutter pinned (1/60, `exposure_ns`/`iso`); 29.74 on auto exposure in a dim room |
| Packed 12-bit writer (`PackedDngWriter`) | Exact for all 12 bits, 18.8 MB a frame, read by ffmpeg |
| Storage stalls | Linux writeback stalls a frame for up to 1 s; a 40-frame shared-memory buffer (`--ei buffer 40`) absorbs them: 200 s with no dropped frame |
| A whole song, uncompressed | **No.** Writing collapsed after ~113 GB (about 205 s): the storage's fast write cache runs out and the drive falls to ~360 MB/s, below the 564 MB/s a 30 fps take needs |
| Heat | Thermal status 3 by 160 s uncompressed; it caused no drops by itself |

Sync-and-delete is not a valid way to test a long take: forcing every frame to flash
and deleting it collapsed the filesystem's throughput within two minutes. Keep the
frames (`--ei keep 99999 --ez sync false`) and delete them after.

#### Lossless JPEG: the fix for the storage cliff

Lossless JPEG (DNG Compression 7, what CinemaDNG cameras write) shrinks the frame
without changing a sample. On the studio gear under its usual lighting a frame came
to **44.4% of the packed size, 8.35 MB**: a four-minute take is about 60 GB at
250 MB/s, under both the cache cliff and the drive's speed after it. Bright flat
scenes compress further (30–34%).

It is done in C, without JNI: `libkanaha_dng.so` (`app/src/main/cpp/dng/`) is a
separate executable the app starts with ProcessBuilder, as it starts httpd, and
feeds over a Unix socket (`DngHelper`). After optimisation one Pixel core encodes
32 frames a second; three threads paced to 30 fps held 30.0 fps for 270 s with the
camera running, thermal status 1 (2026-10-22). Output is checked bit-exact against
the source frames by decoding both with ffmpeg.

**A whole song, 2026-10-23: no dropped frame.** First the frames went to the helper
through a pipe: as the phone warmed, pushing 25 MB a frame through it rose past the
33 ms a frame allows, and a 270 s take dropped up to 5%. Now the helper maps the
app's frame buffers (SharedMemory, descriptors passed once over a Unix socket) and
only a slot number travels per frame. A 270 s take at 30 fps, 1/60, ISO 400, three
encoder threads: 8,100 of 8,100 frames written, 97 GB at 361 MB/s, never fewer than
32 of 40 buffers free, thermal status 2 at most. Compression settles at about 72 ms
a frame warm and reached 82 ms in the last 20 s; for takes much past 4.5 minutes,
use four threads.

**Repeatability, 2026-10-23:** two more 270 s takes on the same scene, one on three
encoder threads and one on four, were both clean: 8,100 of 8,100 frames, at most 7
of 40 buffers in use, thermal status 2. Three clean whole-song takes in a row. Four
threads does not compress a frame faster (they share memory bandwidth) but leaves
more spare capacity late in a long take; it is the default to use.

What a take delivers: a folder of `frame_NNNNNN.dng` (lossless 12-bit, 4080×3072)
and `take.wav` (the iRig's timecode), not one file. Developing the DNGs properly
(the camera's colour matrix, white balance, lens shading) is `darktable-cli` or
`rawtherapee-cli`; ffmpeg reads them but demosaics without the colour data. ffmpeg
then encodes the developed frames and the WAV into one file per take for the edit:
`tools/kanaha-develop`, below.

#### Recording a take: `startRecording` with `raw12`, 2026-10-23

```bash
$CURL -d '{"action":"setExposure","iso":400,"exposure_time_ns":16666667,"fps":30}' \
  "https://$CAMERA:8443/services/CameraControlService/setExposure"
$CURL -d "{\"action\":\"startRecording\",\"clip_name\":\"verse1\",\"raw12\":true,\"start_at\":$AT}" \
  "https://$CAMERA:8443/services/CameraControlService/startRecording"
# ... the take ...
$CURL -d '{"action":"stopRecording"}' "https://$CAMERA:8443/services/CameraControlService/stopRecording"
```

The camera app shows its take screen (preview, frames, drops, free space, heat) for
the take and returns to OpenCamera after. First runs on the Pixel: a 20 s take, 621
of 621 frames, the first frame 129 ms into `take.wav`; a take with `start_at` 5 s
ahead whose first frame was exposed 29 ms after `start_at`, within one frame. The
reply's `max_seconds` is the take's cap from `duration` and free space. Folder
layout, sidecar fields and the file operations: README, "12-bit RAW takes".

A whole song through the API, same day: `startRecording` with `raw12` and
`"duration": 270`, no stop sent. The take ended itself on its duration: 8,112 of
8,112 frames, none dropped or missing at the sensor, 97 GB, 270.6 s of `take.wav`
with the first frame 164 ms into it; OpenCamera back with its camera afterwards.

#### Developing a take: `tools/kanaha-develop`

One command turns a take folder copied off the phone into one movie for the edit:

```bash
tools/kanaha-develop RAW_verse1_20261023_144657/      # -> RAW_verse1_20261023_144657.mov
```

It develops each DNG with LibRaw's `dcraw_emu` (camera white balance and colour
matrix; `--developer darktable` for darktable's processing), pipes the frames to
ffmpeg and writes ProRes 4444 (`--codec hevc10` for a small 10-bit HEVC file). The
audio track is `take.wav`, trimmed so it starts at the first frame. The timecode
track is read from the LTC in `take.wav` (`ltcdump`, x42 ltc-tools), moved to the
first frame using the sidecar's `first_frame_after_first_sample_ms`. A dropped frame
is filled with the one before it, so every camera stays in sync. A folder with fewer frames than
the sidecar says the take wrote is refused (an `adb pull` over Wi-Fi that broke off
loses the end of a take without leaving a gap); `--partial` goes ahead anyway. It
also measures the rate the LTC runs at and warns when it is not 30 fps: the first
whole-song take with the Tentacle (2026-10-25) showed 29.97, which is a generator
or recorder set to 29.97 non-drop, and makes camera and recorder timecode drift
apart by about 8 frames over a song. Developed frames are
deleted as soon as they are encoded, so the scratch space never holds more than a few
GB.

On a 22-core machine a real take develops at 2 to 2.5 frames a second with either
codec, so a 4.5 minute take takes about an hour. ProRes 4444 at 4080×3072 is about
320 MB/s, about 85 GB a take. Checked on a synthetic take: LTC starting at
01:00:00:00 and the first frame 129.3 ms in gave timecode 01:00:00:04 on the file,
and a missing frame was filled. On a real 30 s Pixel take (921 frames, 10.5 GB): 921
frames out, correct colour, audio aligned, 6 to 8 minutes. Copying that take off the
phone with `adb pull` over a USB 2 link ran at 31 MB/s; a whole take needs a USB 3
link. Needs `ffmpeg`, `libraw-bin` (or `darktable`), and `ltc-tools` for
timecode.

To edit the DNGs directly instead (DaVinci Resolve reads a DNG folder as one
CinemaDNG clip), `--stamp-dngs` makes no movie: it writes each frame's timecode
into its DNG as the CinemaDNG `TimeCodes` and `FrameRate` tags, so the edit lines
the take up by timecode without developing it. The phone cannot write these while
recording: it only has the LTC as audio until `take.wav` is decoded after the take.
Stamping appends a new IFD to each file, leaving the picture data alone (LibRaw
decodes a stamped frame bit-identically), and takes seconds for a whole
take.

---

### Suggested order, and why

**Do Part 1 first, and do the half-day probe before Part 1.** The probe answers
whether 10-bit is two days or a week. Part 1 delivers most of the grading benefit
— banding resistance through a LUT, which is the thing that actually shows — at a
fraction of the cost, with no change to the storage story and no change to the
one-file-per-take model.

**Part 2 is compelling and it is not next.** Everything hard about it is real
engineering rather than a locked door, which is unusual and worth saying. But it
is 2–4 weeks, it rewrites the file model, and it caps a shooting day at four
minutes of footage between offloads. It earns its place after 10-bit has been
shot with in anger and the grading benefit is known rather than assumed.


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
- **12-bit not supported**: Kanaha records compressed video via MediaRecorder. 12-bit RAW requires an ImageReader pipeline (DNG capture) — out of scope for the current implementation.
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
