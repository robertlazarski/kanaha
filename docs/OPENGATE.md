# Open Gate Recording in Kanaha

## BLUF

Add `"open_gate": true` to a `startRecording` request. The camera reopens at its native 4:3 sensor resolution (2560×1920 on Pixel 9 Pro; **4032×3024** on Pixel 10 Pro XL) and records with no horizontal or vertical crop. In post, reframe the 4:3 footage to any delivery ratio — 16:9, 2.39:1, 9:16, 1:1 — without upscaling.

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
- **Pixel 10 Pro**: First Pixel to unlock DCG as a Camera2-accessible feature (as of late 2025). Third-party apps can access 12-bit RAW at 4080×3072 — see the 12-bit section below, where this was measured on the device rather than taken from a spec sheet.
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

## Pixel 10 Pro — Recommended Upgrade for Kanaha

**The recommended A-camera upgrade.** The Pixel 10 Pro is the first Pixel where the HAL is unlocked for third-party Camera2 apps — 10-bit HLG and 12-bit DCG are accessible to Kanaha directly, not just the stock camera app. This is the meaningful quality ceiling lift for the Claude + ffmpeg post pipeline.

| Feature | Pixel 9 Pro | Pixel 10 Pro |
|---|---|---|
| Sensor | Samsung GNK, 1/1.31", 50MP | Samsung GNK successor, 1/1.31", 50MP |
| Native aspect ratio | 4:3 | 4:3 |
| Open gate resolution (Camera2) | 2560×1920 (confirmed) | **4032×3024** for video; 4080×3072 for RAW (measured 2026-09-04) |
| 10-bit HDR via Camera2 (third-party) | **No** — HAL-locked to stock Pixel Camera | **Yes** — unlocked |
| 12-bit RAW open gate via Camera2 | **No** — HAL-locked | **Yes** — DCG unlocked |
| DCG (Dual Conversion Gain) | Hardware present; software-locked for third-party | **Unlocked** — accessible to any Camera2 app |
| ADB / developer experience | Standard Pixel | Identical |
| Moto G compatibility | Unchanged | Unchanged |

**What this means for the Claude + ffmpeg pipeline:**
- `lut3d` grading works on better source — 10-bit HLG gives real tonal headroom vs. 8-bit compressed highlights
- `zscale + tonemap` HDR-to-SDR delivery becomes meaningful (you have actual HDR to deliver from)
- 12-bit DCG: 14+ stops of dynamic range means shadow detail in snow scenes that 8-bit simply clips; `curves` and `eq` grading has real information to work with

**Note**: Kanaha currently records compressed video (H.264/HEVC), not RAW DNG. 12-bit RAW support would require an ImageReader pipeline — a separate workstream. The immediate gain is 10-bit HLG video via HEVC Main 10, which requires adding that profile to OpenCamera's codec selection (see [OpenCamera ticket #1218](https://sourceforge.net/p/opencamera/tickets/1218/)).

---

## Testing the Pixel 10 Pro XL

**Rig status, September 2026:** the Pixel 9 Pro has been traded in. The
Pixel 10 Pro XL is the A-camera. Every Pixel 9 Pro row in the tables above is
historical — kept because it explains why the upgrade was worth making, not
because that phone is still in the rig.

The section above was written as a recommendation. This one is the procedure for
confirming it on the actual hardware, in the order that fails cheapest first.

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

1. **Sustained write rate.** UFS can burst far above this; holding 564 MB/s for
   the length of a take, through the filesystem, with thirty file creations a
   second, is a different question. Not measured — the phone dropped off the bus
   before the probe ran. Worth measuring before anything else, because if
   sustained write cannot hold the rate, nothing downstream matters.
2. **`DngCreator` is the likely wall.** It assembles a TIFF/DNG container on the
   CPU, per image, and was designed for someone pressing the shutter. Thirty a
   second is not what it is for. This is the piece most likely to fail first, and
   the one with no easy answer short of writing frames raw and building DNGs
   afterwards.
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

**The one measurement that would move this from "compelling" to "scheduled":**

```sh
adb shell "dd if=/dev/zero of=/data/local/tmp/spd bs=1048576 count=2000 conv=fsync"
adb shell "rm -f /data/local/tmp/spd"
```

If sustained write comfortably exceeds 600 MB/s, the remaining work is
`DngCreator` throughput and the file model, both tractable. If it does not, the
ceiling is the hardware and the honest answer is shorter takes or a lower
resolution.


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
