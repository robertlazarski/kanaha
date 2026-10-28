# 12-bit RAW Recording in Kanaha

## BLUF

Kanaha records 12-bit RAW (CinemaDNG frames, losslessly compressed) on phones whose
sensor provides it, and is built for live multicam shoots: takes start on a schedule
from a script, carry timecode from the same generator as the sound recorder, and are
finished into ProRes with ffmpeg and LibRaw. Among open-source camera apps it is, as
far as we know, the only one that records RAW with sound and timecode.

```bash
$CURL -d '{"action":"startRecording","clip_name":"verse1","raw12":true,"duration":270}' \
  "https://$CAMERA:8443/services/CameraControlService/startRecording"
# later, on the computer, after copying the take folder off the phone:
tools/kanaha-develop RAW_verse1_20261025_025807/    # -> RAW_verse1_20261025_025807.mov
```

**What you need to know:**
- **Phones:** the Pixel 10 Pro XL so far. The Moto G Play's RAW is 10-bit and Kanaha
  refuses `raw12` on it with that reason. Most phones give less; see
  [Which phones](#which-phones-the-encoder-and-the-sensor-are-two-routes).
- **Always open gate, at the full sensor:** 4080×3072, 4:3. See
  [Frame size](#frame-size-always-the-full-sensor).
- **A take is a folder, not a file:** one DNG per frame, a WAV and a sidecar. A 4.5
  minute song is 50–100 GB depending on the scene, so one to three songs fit in a
  256 GB Pixel's free space.
- **It must be developed before anything plays it:** about an hour per song on a
  22-core machine, to a ProRes 4444 MOV of about the same size as the DNGs. An editor
  that reads CinemaDNG itself, such as Resolve, can skip this and use the DNGs once
  `--stamp-dngs` has written the timecode into them (see
  [Editing the DNGs directly](#editing-the-dngs-directly-resolve-and-others---stamp-dngs)).
- **Timecode must run at 30 fps.** The F8 and every Tentacle set to 30, not 29.97; the
  post script warns when they are not.

**This is the specialist path.** For most shoots 10-bit HLG video (`"hlg10": true`) is
the better trade: one file per take, ordinary sizes, no developing. See
[OPENGATE.md](OPENGATE.md).

---

## Which phones: the encoder and the sensor are two routes

"Bit depth" means two different things on a phone, and spec sheets only describe the
first:

| Route | What it is | Bit depth |
|---|---|---|
| **Video recording** | The phone's hardware encoder makes H.264/HEVC, HDR or log video | **10-bit at most, on every phone**, the Pixel included |
| **RAW frames** | Camera2 hands the app untouched sensor readings (`RAW_SENSOR`), frame by frame | **Whatever the sensor reads out:** its white level, 4095 for 12-bit, 1023 for 10-bit |

A phone advertised as "10-bit log video" can still deliver 12-bit RAW frames, and
Kanaha's 12-bit takes come from the second route: the frames never pass through the
video encoder. The 12-bit question for a phone is therefore one line:

```sh
adb shell dumpsys media.camera | grep -iE "whiteLevel|RAW_SENSOR"
```

A white level of 4095 on the main camera, and `RAW_SENSOR` at full size with a minimum
frame duration of 33,333,333 ns (30 fps), is what Kanaha needs. `startRecording`
checks both and refuses with the reason otherwise.

| Phone | Video encoder | RAW frames | `raw12` |
|---|---|---|---|
| Pixel 10 Pro XL | 10-bit HLG | **12-bit**, 4080×3072 at 30 fps | Yes |
| Moto G Play 2024 | 8-bit | 10-bit | Refused: "This camera's RAW is not 12-bit (white level 1023)" |

On the Pixel the samples were checked to really carry 12 bits: in a daylight frame
10.5% of them sat above the 10-bit range and their two lowest bits were populated,
which 10-bit data scaled up never has (2026-10-19).

iPhones have no equivalent: iOS gives apps RAW stills (ProRAW) but not RAW frames at
video rates, and Apple's own video route is ProRes and ProRes RAW.

---

## Frame size: always the full sensor

A 12-bit take is always open gate: the sensor's whole 4:3 readout, 4080×3072 (binned
from its 8160×6144 pixels). There is no other size, and `startRecording` ignores
`open_gate` alongside `raw12`, since there is nothing to switch.

It is a larger open gate than video's. Open gate video stops at 2560×1920 because
that is the largest 4:3 frame the phone's encoder takes
([OPENGATE.md](OPENGATE.md#the-encoder-limit-measured-2026-10-19)); RAW frames never go
through the encoder, so a take has 2.5 times the pixels:

| Crop from 4080×3072 | Size | Covers |
|---|---|---|
| 16:9 | 4080×2295 | 4K (3840×2160) without upscaling |
| 9:16 vertical | 1728×3072 | TikTok's 1080×1920 with room to reframe |
| 1:1 | 3072×3072 | |
| 2.39:1 | 4080×1707 | |

`kanaha-develop` delivers the full 4:3 frame; crop it in the edit as for open gate
video ([Reframing in post](OPENGATE.md#reframing-in-post)).

---

## What a take is

CinemaDNG is not a single file like MOV or MP4. A take is a folder:

```
RAW_verse1_20261025_025807/
├── frame_000000.dng ... frame_008113.dng   one per frame, 4080×3072, lossless 12-bit
├── take.wav                                the phone's audio input: with the iRig, the Tentacle's LTC
├── take.gcsv                               the phone's gyroscope, for stabilising with Gyroflow
└── take.kanaha_start.json                  settings, frame counts, and where the first frame sits in take.wav
```

- **Each DNG** holds the sensor readings and the data to develop them: black and white
  levels, both colour and forward matrices, a noise profile, the lens-distortion
  correction and the lens-shading map (OpcodeList2). The
  colour data comes from Android's `DngCreator`; the frames are Kanaha's own writer,
  compressed with lossless JPEG (DNG compression 7, as cinema cameras use), 6–13 MB a
  frame depending on the scene: lossless compression shrinks a plain, dim frame far
  more than a detailed or noisy one.
- **There is no sound or timecode inside the frames.** The sound and the timecode are
  in `take.wav`, and the sidecar's `first_frame_after_first_sample_ms` places the first
  frame in it to well under a millisecond.
- **CinemaDNG timecode is binary, not XML.** The standard carries it as two TIFF tags
  in every frame (`TimeCodes`, `FrameRate`). DNG files can also carry XMP, which is
  XML, and XMP has a timecode field some tools read, but that is not the CinemaDNG
  way. The phone cannot write the tags while recording, since it only has the LTC as
  audio; `tools/kanaha-develop --stamp-dngs` writes them afterwards, for editors that
  read DNG folders directly ([Editing the DNGs directly](#editing-the-dngs-directly-resolve-and-others---stamp-dngs)).

### DNG against ProRes: more data, and more work

**Is a DNG take higher quality than ProRes? Not by being less compressed; by leaving
decisions open.** Measured on one frame of a lit studio scene, against the frame
LibRaw developed from the DNG (PSNR; above about 45 dB the difference is generally
invisible):

| Per frame, 4080×3072 | Size | Difference from the developed frame |
|---|---|---|
| Compressed DNG | 11.5 MB | None: lossless |
| ProRes 4444 (ffmpeg `prores_ks`) | 8.8 MB | 50.6 dB |
| ProRes 4444 (ffmpeg `prores_aw`) | 11.1 MB | 53.6 dB |
| ProRes 422 HQ (`prores_ks`) | 5.9 MB | 47.2 dB |

Every ProRes here is visually lossless, so a ProRes made from a DNG looks the same as
that development of it. What the DNG keeps, and a ProRes cannot, is everything that
development decided:

- **White balance, exposure and colour** can be changed later with no loss, because
  they are applied when the frame is developed, not recorded into it.
- **Highlights.** The DNG holds the sensor's linear 12-bit values up to its clipping
  point; a developed frame is tone-mapped to a display range and highlights beyond
  white are cut. Pulling back a bright window or a stage light needs the DNG.
- **Demosaic and noise reduction** can be redone with better methods or settings as
  tools improve.

On a shot that was exposed and balanced correctly and is graded lightly, none of this
shows. It shows when a shot needs rescuing or a strong grade.

Against the phone's own video, which is the real alternative on a shoot, the
difference is larger and shows in every shot: the take has 2.5 times the pixels of
open gate video (4080×3072 against 2560×1920), 12 bits against 10, and none of the
phone's sharpening, noise reduction and tone mapping baked in.

**The cost is the post work and the storage:** about an hour of developing per song,
the tooling to do it, thousands of files per take, and the largest files of all: the
DNG is bigger than ProRes 4444 on a real scene, and its size varies with the scene
(6–13 MB a frame measured; a frame of nothing but dark sensor noise compresses least
well of all). Compression during the take is what makes a whole song possible: without
it the phone's storage cannot keep up ([Measured results](#measured-results)).

`tools/kanaha-develop` makes one development of each take (the camera's white balance,
LibRaw's standard processing) into ProRes, so the edit gets one ordinary file per take.
The DNGs stay as the camera original; to use their latitude, develop a take again with
different settings.

**Why DNG at all:** BRAW and ProRes RAW are the efficient RAW video formats, but their
encoders exist only inside Blackmagic's and Apple-licensed cameras and recorders;
nothing on Android or Linux can write them. CinemaDNG is the only open RAW video
format.

---

## Recording a take

Pin the exposure first. `startRecording` refuses a 12-bit take on automatic exposure,
or with a shutter slower than 1/40 s, because either one lets the frame rate fall
below 30 fps:

```bash
$CURL -d '{"action":"setExposure","iso":400,"exposure_time_ns":16666667,"fps":30}' \
  "https://$CAMERA:8443/services/CameraControlService/setExposure"
$CURL -d "{\"action\":\"startRecording\",\"clip_name\":\"verse1\",\"raw12\":true,\"duration\":270,\"start_at\":$AT}" \
  "https://$CAMERA:8443/services/CameraControlService/startRecording"
```

- **The take screen.** The app switches to a screen of its own for the take (preview
  plus frames, drops, free space and heat) and returns to OpenCamera after.
- **`start_at`** schedules the first frame. Measured: the first frame 29 ms after the
  requested moment, within one frame.
- **A take ends** on `stopRecording`, on its `duration`, when free space falls below
  6 GB, if its screen is hidden or closed, or if the camera is lost. The reply to
  `stopRecording`, and `getStatus` while it runs (`raw12_take`), give frames written,
  dropped and missing at the sensor, and `ended_by`.
- **White balance** is locked for the take: the gains set with `setExposure`, or the
  automatic balance as it stands when the take arms.
- **Refusals:** a phone without 12-bit RAW, `hlg10` with `raw12`, a recording already
  running, an unpinned or too slow shutter, and less than 30 s of free space.

**Checking exposure.** The take measures its own raw values: on the arming frame,
then once a second. `startRecording`'s reply and `getStatus` (`raw12_take`) carry
them under `exposure`, and the take screen shows them on a second line. The preview's
tone curve can't show clipping or headroom; these numbers come from the sensor values
themselves. To set up, start a take, read the reply, stop it, adjust `setExposure`,
and repeat:

| Field | What it is | Act when |
|---|---|---|
| `clipped_pct` | photosites at the white level | above about 0.1: shorten the shutter or lower ISO |
| `headroom_stops` | brightest 0.1% up to clipping | under about 0.3: the next bright moment clips |
| `median_stops_below_clip` | where most of the frame sits | well past 5–6: shadows get noisy when lifted |

The thresholds are starting points, not measured limits. A lit wall behind the
performer, say, may be worth clipping.

**Starting takes from a gimbal or remote button.** In Settings, More camera
controls, tick "Button starts 12-bit RAW takes". On the camera screen, a press of the
gimbal's Bluetooth button, a shutter remote or a volume key then starts a take (named
`button`) with the pinned exposure, and the next press stops it. Presses in the first
second of a take are ignored, so a double press can't end it at once. A take the
button can't start (exposure not pinned, no space) shows the reason as a message. The
keys it answers to are volume up and down, camera, headset hook and play/pause. To
see what a button sends, run `adb shell getevent -l` and press it.

**Between songs, release the camera.** The idle preview alone keeps about two cores
busy (camera provider, app and camera server) and warms the phone about 0.4 °C a
minute, so a phone left previewing for half an hour starts the song hot. Measured: a
song started after about 30 minutes of preview reached thermal status 4 and dropped
523 of 8,117 frames (6.4%); the next, started cool, stayed at status 2 or below and
dropped none. Send the app to the background after a take and bring it back about a
minute before the next (the camera needs the app in front):

```bash
adb shell input keyevent KEYCODE_HOME
adb shell am start -n org.kanaha.camera/net.sourceforge.opencamera.MainActivity
```

`listFiles`, `deleteFiles`, `cleanupFiles` and `sftpTransfer` treat a take folder as
one recording, and never touch a take that is still recording. Folder layout and
fields: README, "12-bit RAW takes".

---

## Timecode

The sound recorder is the timecode master. In the studio rig a Zoom F8 jams three
Tentacle Sync E units, one per camera; on the Pixel the Tentacle's LTC goes into an
iRig PRO I/O on the USB-C port and is recorded in `take.wav`. Connecting the iRig, its
fragile 7-pin cable, and checking a recording's LTC:
[IRIG_PRO_SMPTE_TIMECODE_SETUP.md](IRIG_PRO_SMPTE_TIMECODE_SETUP.md).

- **Everything at 30 fps.** The cameras shoot 30; LTC at 29.97 (a common default)
  runs 0.1% slow against them, and the camera's timecode drifts from the recorder's by
  about 8 frames over a 4.5 minute song. The first whole-song take with the Tentacle
  (2026-10-25) measured exactly that: by the last frame, its developed MOV's timecode
  track and the LTC in its own audio were 8 frames apart. `kanaha-develop` measures the LTC's rate from the
  spacing of its frames and warns when it is not 30.
- **Re-jam after the F8 restarts.** A Tentacle runs freely from its last jam; if the F8
  comes back up at 00:00:00, its files no longer match the Tentacles.
- **Input level:** LTC is a square wave and decodes even when it clips, but set the
  iRig's gain so it peaks around −6 dBFS.

Measured on the whole-song take: 8,111 consecutive LTC frames across 270 s, no gap or
jump.

---

## Getting takes off the phone

| Route | Measured | A 48 GB song (the 2026-10-25 test) |
|---|---|---|
| `adb pull` over USB, USB 2 link | 31–33 MB/s | 23 minutes |
| `adb pull` or `sftpTransfer` over Wi-Fi, computer on 2.4 GHz | 2–5 MB/s | hours |

- **USB is the route on a shoot day.** The iRig occupies the USB-C port during a take,
  so copying happens between songs. A USB 3 cable and port would raise the rate; check
  the link with `lsusb -t` (480M is USB 2).
- **Wi-Fi speed is set by the slower end:** the measurements above had the computer on
  2.4 GHz at 72 Mb/s while the Pixel was on 5 GHz.
- **`sftpTransfer`** sends a take folder with all its files (verified byte for byte,
  2026-10-25). Give the phone an SFTP-only key on the computer (`restrict,command=
  "/usr/lib/openssh/sftp-server"` in `authorized_keys`); `destination_folder` must be
  an absolute path.
- **Keep the computer awake.** A laptop that suspends mid-copy drops the connection;
  wrap long copies in `systemd-inhibit --what=sleep:idle`.

`kanaha-develop` refuses a folder with fewer frames than the sidecar says the take
wrote, since a copy that broke off loses the end of a take without leaving a gap.

---

## Post: `tools/kanaha-develop`

```bash
tools/kanaha-develop RAW_verse1_20261025_025807/                  # ProRes 4444 MOV
tools/kanaha-develop RAW_verse1_20261025_025807/ --codec hevc10   # small 10-bit HEVC
tools/kanaha-develop RAW_verse1_20261025_025807/ --prores-encoder ks   # ProRes, size capped
```

ffmpeg has two ProRes 4444 encoders. The default, `prores_aw`, is the faster (about an
hour a song) but has no size target, so dark or noisy footage balloons: about 10 MB a
frame on a lit scene, 36 MB on a dark one, 292 GB for a whole dark song.
`--prores-encoder ks` keeps about 9 MB a frame whatever the scene, more slowly
(about 85 minutes a song). Use it for dim or high-ISO songs, or when disk space matters
more than turnaround.

1. **Develops each DNG** with LibRaw's `dcraw_emu`: the camera's white balance and
   colour matrix, AHD demosaic, and the exposure as recorded. LibRaw's default
   auto-brighten is off: it scales each frame by its own histogram, which made frames
   of one dim take differ by 4% and would flicker. The lens-shading map is applied too
   ([Lens shading](#lens-shading)). `--developer darktable` uses darktable's processing
   instead (slower, best highlight handling).
2. **Encodes** the frames with ffmpeg, with `take.wav` as the audio trimmed to start at
   the first frame. A dropped frame is filled with the one before it, so the movie
   keeps the take's timing.
3. **Writes the timecode** of the first frame, read from the LTC in `take.wav`, into
   the MOV's timecode track, where ffmpeg and ffprobe read it.

Frames are developed in parallel a little ahead of ffmpeg and deleted once encoded,
so the scratch space stays at a few GB. On a 22-core machine a real take develops at
2 to 2.5 frames a second: a whole song (8,114 frames) took 69 minutes, with about 9 GB
of memory at its peak. It needs `ffmpeg`, `libraw-bin` (or `darktable`), `python3-numpy` and `ltc-tools`.

**It uses the whole machine.** By default it develops one frame per core (`--jobs`
defaults to the core count) while ffmpeg encodes on threads of its own, so the load
average sits above the core count for the whole run (about 27 on 22 cores) and the
desktop is sluggish. To keep the machine usable, run it with `nice -n 10
tools/kanaha-develop …`, which keeps full speed when nothing else wants the CPU, or
with fewer jobs (`--jobs 16`), which leaves cores free at the cost of speed.

**Watching the result.** The MOV is an editing master, not something to play
directly: decoding 4080×3072 ProRes 4444 is over a gigabyte a second of 12-bit video,
and Linux has no hardware decoder for ProRes. mplayer refuses its 12-bit 4:4:4 output
outright ("Unexpected decoder output format"), and mpv plays it but stutters and reports
"Audio device underrun" as the decoder falls behind. To watch a take, make a small
preview:

```bash
ffmpeg -i RAW_verse1_20261025_025807.mov -vf scale=1440:-2 -c:v libx264 -crf 18 -pix_fmt yuv420p -c:a aac RAW_verse1_preview.mp4
```

or let mpv drop frames to keep up: `mpv --framedrop=decoder RAW_verse1_20261025_025807.mov`.

### Editing the DNGs directly (Resolve and others): `--stamp-dngs`

An editor that opens CinemaDNG folders itself, such as DaVinci Resolve, develops the
frames with its own RAW controls and needs no MOV. What it does need is the timecode
in the frames, which the phone cannot write while recording. This is optional: the
ffmpeg route above never needs it.

```bash
tools/kanaha-develop RAW_verse1_20261025_025807/ --stamp-dngs
```

- **What it writes:** each frame's timecode, read from the LTC in `take.wav`, as the
  CinemaDNG `TimeCodes` and `FrameRate` tags (binary TIFF tags, not XMP). It makes no
  movie.
- **In place:** it edits the DNGs in the take folder, leaving their image data untouched.
  Stamping again is safe, so a take can be re-stamped after fixing a wrong rate.
- **Sound:** `take.wav` holds the LTC, not the music. Sync the recorder's files to the
  frames by timecode in the editor.
- **30 fps LTC**, as for the MOV, or the stamped timecodes drift from the recorder's.
- **Not yet tried in Resolve** (it is not available here on Linux). The tags follow the
  CinemaDNG specification; a report of how Resolve reads them is welcome.

---

### Lens shading

The Pixel's RAW frames are not corrected for vignetting: the corners need about 1.7
stops (×3.3) more than the centre, nearly the same for every colour. Since 2026-10-26
each DNG carries Android's lens-shading map as the DNG gain map (OpcodeList2) that
corrects it; earlier takes have none.

- **`kanaha-develop` applies it.** LibRaw ignores the map, so with LibRaw (the default)
  the script has it write linear values, multiplies each frame by the map and then
  applies LibRaw's usual curve itself; with the map left out this reproduces LibRaw's
  output exactly. The four colours' maps differ by under 3% on the Pixel, so one
  averaged map is applied to every channel. It adds about 8% to a develop.
- **darktable and RawTherapee apply it themselves**, and so should any editor that
  reads the DNGs directly.
- **The phone's map is not always right.** On the same Pixel some takes carry a map
  reaching ×3.3 and others one of ×1.2, which leaves the corners at about half the
  centre's brightness. Neither ISO, exposure, focus, white balance, the scene's light
  nor the shading mode explained it. `kanaha-develop` warns when a take's map is under
  ×2; then develop with the known-good map kept in the repo:
  `--shading-from tools/lens-shading/pixel10proxl_main.json`. darktable and other
  editors use the DNG's own map and cannot be given another.
- **Takes recorded before 2026-10-26** have no map; `--shading-from` takes one from the
  JSON file above, another take or a DNG. The vignette is the lens's, so a map from the
  same phone fits, though it shifts a little with focus distance. `--no-shading` leaves
  the vignette in.

Measured on a plain wall: corners 0.46–0.61 of the centre before, 0.80–1.01 after (the
wall itself was darker towards one corner). On a take with a ×1.2 map, a lit wall's
corners were 0.35–0.53 of the centre with that map and 0.61–0.88 with the JSON map.

### Stabilising with Gyroflow

A take records the phone's gyroscope (`take.gcsv`, several hundred samples a second,
timed from the first frame), and `kanaha-develop` puts it beside the movie as
`<movie>.gcsv`, where [Gyroflow](https://gyroflow.xyz) (open source; a Linux AppImage
on its releases page) finds it when the movie is opened.

1. Open the MOV in Gyroflow; "Motion data" shows the log, orientation `yXZ`.
2. Lens profile → Open file: [`docs/gyroflow/pixel10proxl_main_kanaha12bit.json`](gyroflow/pixel10proxl_main_kanaha12bit.json),
   the Pixel 10 Pro XL's main camera at 4080×3072 (Gyroflow's database has none for
   this phone). Keep Lens correction at 100%.
3. Auto sync. The right offset is about 18 ms (half the exposure plus half the
   readout); delete a sync point that lands far from the others, which happens where
   the picture has nothing to track, such as a plain wall.
4. Stabilization: Dynamic zooming. With "No zooming" there is no room to crop and the
   picture still shakes. Set Advanced → Preview resolution to 1/4 or 1/2; at Full,
   the preview cannot decode 12-bit ProRes in real time and stutters.

For a locked-off camera there is nothing to stabilise and the log can be ignored.

#### How the profile was made

With Gyroflow 1.6.3's calibrator (`Gyroflow.AppImage --calibrator`), from a 12-bit take
of its checkerboard shown full screen on a monitor:

- The whole board in frame, filling various parts of the picture, focus on the screen
  and no clipped whites (ISO 800 here; at ISO 1600 the glare closed the squares'
  corners and nothing was detected).
- Calibrated on a quarter-size proxy, `ffmpeg -i take.mov -an -vf scale=1020:768:flags=area -c:v libx264 -crf 10 -pix_fmt yuv420p proxy.mp4`:
  at full size and half size the board's slight blur defeated detection.
- Calibrator settings: "Plain chessboard pattern" unticked, Processing resolution
  Full (the default 4k squeezes a 4:3 frame to 3840×2160).
- Result: RMS error 0.34 over 15 frames. The calibrator writes the proxy's size into
  the profile and Gyroflow does not rescale it, so the focal length and centre were
  multiplied by 4 and the sizes set to 4080×3072; the distortion coefficients do not
  depend on size.

The board rarely reached the frame's corners, so the fit is extrapolated there. Walls
and edges stayed straight on the test take; a recalibration should move the board into
each corner.

---

## Measured results

All on the Pixel 10 Pro XL (256 GB, Android 17).

| Date | What | Result |
|---|---|---|
| 2026-09-04 | Capability (`dumpsys`) | White level 4095; `RAW_SENSOR`/`RAW12` at 4080×3072, 30 fps, stall 0 |
| 2026-10-19 | Probe | Full-size RAW at 30 fps with no frame missing; samples genuinely 12-bit |
| 2026-10-20 | Uncompressed DNG per frame, 60 s | Kept up: 745 MB/s |
| 2026-10-22 | Uncompressed, a whole song | **Failed:** writes collapsed after ~113 GB when the storage's fast write cache ran out (to ~360 MB/s, below the 564 MB/s needed) |
| 2026-10-22 | Lossless JPEG compression | 44–65% of the packed size, scene-dependent; about 360 MB/s for a 30 fps take |
| 2026-10-23 | Whole-song takes, compressed | Four 270 s takes, 8,100+ of 8,100+ frames each, thermal status 2 at most |
| 2026-10-23 | `start_at` | First frame 29 ms after the requested moment |
| 2026-10-23 | Stops | Home pressed: 319 of 319 frames saved; storage low: 170 of 170 |
| 2026-10-25 | Whole song with LTC through the iRig | 8,114 of 8,114 frames, none missing at the sensor; 8,111 continuous LTC frames |
| 2026-10-25 | `sftpTransfer` of a take | 111 files, byte-identical |
| 2026-10-25 | Whole song copied and developed | 8,116 files over USB in 23 min, checksums matching the phone; developed in 69 min to ProRes, 8,114 frames, audio exactly the video's length; timecode track and audio LTC 8 frames apart by the last frame, the 29.97 drift |
| 2026-10-25 | Two songs, 30 min of idle preview before the first | Started hot: thermal status 4, 523 of 8,117 frames dropped. Started cool: status 2 at most, none dropped |
| 2026-10-25 | Lit studio song developed | 93.5 GB of DNG over USB in 44 min at 34 MB/s, checksums matching; 84 GB of ProRes 4444 in 64 min, 8.5 GB of memory at peak; no flicker (no frame's brightness more than 1% from the one before) |
| 2026-10-26 | Lens-shading map in the DNGs; Gyroflow | Corners need ×3.3 (1.7 stops); applied by `kanaha-develop` (LibRaw) and darktable. A handheld take stabilised in Gyroflow from its own `take.gcsv` |
| 2026-10-27 | Lens-shading map strength | Takes on this day carried maps of ×1.1–1.3 whatever the ISO, exposure, focus, light or shading mode; the 2026-10-26 takes ×3.3. The ×3.3 map kept in `tools/lens-shading` |
| 2026-10-27 | Gyroflow lens profile | Calibrated from a checkerboard take, RMS error 0.34; the handheld take steady with dynamic zooming, sync points 13–19 ms |

Compression is what makes a whole song possible: it keeps the write rate under both the
storage's cache cliff and its speed after it. The probe that produced the early rows,
`RawProbeActivity` (debug builds), is still there for measuring another phone.

---

## How it works

- **`RawTakeActivity`** owns the camera for the take; OpenCamera closes its camera
  while this screen is in front.
- **`RawTake`** captures `RAW_SENSOR` frames into 40 shared-memory buffers (about
  1 GB), which absorb storage stalls of up to a second.
- **`libkanaha_dng.so`** is a native executable the app starts with ProcessBuilder, as
  it starts httpd: no JNI. It maps the same buffers (descriptors passed once over a
  Unix socket), compresses each frame on four threads and writes the DNG; per frame
  only a slot number crosses the socket.
- **`TimecodeAudio`** records `take.wav` and the boot-clock time of its first sample,
  the clock the camera stamps frames with.

Threads and lifetimes: [THREAD_MODEL.md](THREAD_MODEL.md). Why the compressor is C and
everything else Java: [CPP_AND_JAVA_DESIGN.md](CPP_AND_JAVA_DESIGN.md).

---

## Limits

- **One phone so far.** Only the Pixel 10 Pro XL has recorded 12-bit takes; a second
  Pixel of the same model is expected to behave the same, untested.
- **Capacity.** 50–100 GB a song depending on the scene; the phone stops a take below
  6 GB free.
- **Heat.** Thermal status 2 over a whole song when it starts cool; a phone that has
  previewed for half an hour first drops frames ([Recording a take](#recording-a-take)).
  Back-to-back songs without a pause are untested.
- **The USB-C port** carries the iRig during a take, so no external storage and no
  copying until the song is done.
- **Only the main camera** reads out 12 bits on the Pixel; the others give 10.
