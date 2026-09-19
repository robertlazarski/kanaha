# NDK instead of OpenCamera: considered and rejected

**Decision (2026-09-19):** Kanaha Camera stays a fork of OpenCamera with an
Axis2/C control plane beside it. Rewriting the camera pipeline against the
Android NDK camera and media APIs was evaluated seriously and turned down. This
document records the case for the rewrite, the case against it, and why the
case against wins. It exists so the question is not re-opened without new facts.

## Why the question came up

Kanaha's other apps are C programs with a thin Java supervisor. Kanaha Audio,
for example, captures the microphone through AAudio and calls whisper.cpp
directly; its Java is about 1,000 lines and never touches the audio path
(see `CPP_AND_JAVA_DESIGN.md` in the kanaha-audio repository). Kanaha Camera is
the exception: about 70,000 lines of Java inherited from OpenCamera do the
camera work, and the C service drives them through Intents sent by
`fork()`/`execvp()` across a process boundary.

Three things made the NDK look attractive:

- **Preference and consistency.** One design across the three apps, with the
  camera reachable by a function call like everything else.
- **Licence.** OpenCamera is GPLv3, which makes Kanaha Camera GPLv3 and fences
  its patterns off from Apache-licensed projects. A native recorder written from
  scratch could be Apache 2.0 like the other two apps.
- **10-bit video.** The fork records 10-bit HLG HEVC on the Pixel 10 Pro and
  upstream does not (ticket #1218), which suggested the upstream design might be
  an 8-bit shape Kanaha had outgrown.

## What the NDK version would look like

A headless recorder with no viewfinder:

- `libcamera2ndk` opens the camera, builds the session and issues requests.
- The camera writes straight into an encoder input surface from
  `AMediaCodec_createInputSurface()`; `AMediaMuxer` writes the MP4. No Java
  `Surface` exists anywhere.
- Audio comes from the AAudio capture code already written for Kanaha Audio,
  encoded to AAC by a second `AMediaCodec`, muxed into the same file.
- GPS sidecar, timecode, SFTP and the Axis2/C service are already C.
- Start and stop become function calls inside the service instead of Intents to
  a `BroadcastReceiver`.
- Java shrinks to the same thousand-line supervisor as the audio app: a
  foreground service with the camera and microphone types, permission prompts,
  a wake lock, `ProcessBuilder`.

Estimated at two to four weeks for one device family, after two half-day
probes: opening the camera from the httpd child process under the app's UID,
and recording one HLG10 clip through a native encoder surface.

## The case for the rewrite

- One architecture across all three apps; the camera becomes a direct call.
- The whole app could carry the Apache 2.0 licence, and its patterns could flow
  into Axis2/C upstream the way Kanaha Audio's can.
- A codebase small enough to understand end to end, with only Kanaha's own HTTP
  and MCP surface as its consumer.
- No preview surface in the session, so the concurrency constraint that forced
  the preview into HLG10 (see below) disappears by construction.
- No dependency on decisions made by an upstream project whose scope is wider
  than Kanaha's.

Every point above is true. They do not outweigh what follows.

## The case against, and why it wins

### 1. Thirteen years of device coverage is the asset, and only a user base produces it

OpenCamera has shipped since 2013 to a large installed base across hundreds of
device models. What that buys is not features; it is the accumulated handling
of things that go wrong on real hardware: auto-exposure and focus that misbehave
without a preview-sized stream, HALs that reject stream combinations they
advertise, orientation and sensor-rotation edge cases, capability tables that
lie, encoder profiles that configure and then produce the wrong thing. None of
that is written down anywhere Kanaha could read it. It exists as code paths in
`CameraController2.java` and `Preview.java` that were each added after a
report from a device Kanaha will never see.

The fork's own 10-bit work shows this concretely. The comments carried in from
upstream record two failures on two other devices when the HLG10 profile was
set on the preview: an Ultra HDR stills conflict, and a saturation regression
on a different manufacturer's phone. Kanaha's probe on the Pixel 10 Pro XL
found neither. That is not because the Pixel is better; it is because Kanaha
has one device and upstream has the population. A native recorder would meet
those failures one at a time, in the field, with no prior art.

Kanaha today runs on one phone family. The multi-camera deployment it exists
for is exactly the case that adds phones, and every added phone is a device
OpenCamera has probably already been fixed for.

### 2. The NDK cannot request 10-bit at all

This is the decisive technical fact, verified against the NDK 28 headers:

- `NdkCameraMetadataTags.h` exposes
  `ACAMERA_REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP` and the HLG10, HDR10,
  HDR10+ and Dolby Vision enum values, so native code can *read* what the
  camera supports.
- `NdkCameraDevice.h` offers `ACaptureSessionOutput_create`,
  `ACaptureSessionSharedOutput_create` and
  `ACaptureSessionPhysicalOutput_create`, each taking a window and nothing
  else. There is no native equivalent of the Java
  `OutputConfiguration.setDynamicRangeProfile()`.

Without that setter the camera delivers STANDARD 8-bit to every native output,
whatever the encoder is configured for. The feature that motivated the
question is the one a pure-NDK recorder cannot have. Keeping 10-bit would mean
keeping a Java camera module for session creation anyway, at which point the
rewrite no longer removes Java from the camera path; it only removes
OpenCamera.

### 3. The "no preview" explanation for the fork's 10-bit was wrong

It was tempting to believe the fork got 10-bit because it does not need a
viewfinder. The commits say otherwise:

- Camera2 mode in OpenCamera hardcodes a `TextureView` preview, and it is
  present in every recording session.
- The Pixel 10 Pro XL reports HLG10 as its own only concurrent-use constraint,
  so the preview *had* to accept HLG10 alongside the recorder. The probe on
  2026-09-05 found that it does (`9fd979c`).
- The profile is applied to exactly the preview and recorder surfaces
  (`974bbad`), because applying it to the JPEG `ImageReader` fails session
  configuration in a way indistinguishable from the preview rejecting it.

What actually separates the fork from upstream is scope. Kanaha's 10-bit is
opt-in, forces HEVC, applies only during a recording session, drops video
snapshots for its duration, and refuses the histogram, zebra, focus-peaking and
pre-shot overlays because they read the preview back as an 8-bit bitmap.
Upstream cannot ship a mode that quietly disables half of its viewfinder aids
and a stills feature. Kanaha can, because it drives the camera headlessly and
none of those are on. Being headless made the narrowing *free*; it did not
remove the preview.

And the 10-bit work took one to two days precisely because OpenCamera's Camera2
layer already did everything except set the profile. That is the return on the
years since 2013, measured.

### 4. The rest of a camera app is platform glue Kanaha would have to rebuild

A native recorder has no preview, and a preview will be wanted the first time
an operator needs to check framing on a multi-camera rig. Adding one back means
a Java `Surface` and the overlay drawing that OpenCamera already has. Stills,
electronic stabilisation, orientation metadata, scoped-storage saving and
gallery registration are each small in Java and each a fresh project in C.
`MediaRecorder` alone, which handles video with muxed audio, orientation and
stabilisation in one object, becomes a hand-built codec-and-muxer pipeline.

### 5. Effort with no coverage

Two to four weeks buys a recorder that works on the Pixel 10 Pro and is
untested everywhere else, with 10-bit still dependent on Java. The same weeks
spent on the fork buy features that inherit OpenCamera's coverage for free.

## The licence, stated plainly

The GPL is a real cost and it stays. Kanaha Camera is GPLv3 because OpenCamera
is, and the Axis2/C control plane talks to it across a process boundary so that
the Apache-licensed side and the GPL side remain separate programs. Camera
patterns therefore stay in this repository rather than flowing upstream to
Apache projects; Kanaha Audio, with permissive dependencies only, is the app
whose patterns do.

The fear that upstream might one day go proprietary does not change the
decision. Code already released under the GPL stays under the GPL; the fork
cannot be withdrawn. If upstream stopped publishing, Kanaha would keep the
version it has, which is the version it needs.

## What would reopen the question

- A future NDK adds a native dynamic-range-profile setter on session outputs.
  That removes the hard blocker in point 2, though not points 1, 4 and 5.
- Kanaha's device list shrinks permanently to one Google model and a preview is
  never wanted. Unlikely, given what the app is for.
- A need that OpenCamera's design genuinely cannot serve. 10-bit turned out not
  to be one; a RAW DNG sequence pipeline might be, and it would be evaluated on
  its own.

If any of these happen, the fallback shape is known: a thin Java camera module
of one to two thousand lines that owns session creation and the output
configurations, with encoding, audio, timecode and control in C. That is the
design to build, not a pure-NDK one, and it is not being built now.

## Summary

| | Pro | Con |
|---|---|---|
| Architecture | Matches the other apps | Java still required for 10-bit session setup |
| Licence | Apache 2.0 possible | Fork stays GPL either way; no loss from staying |
| 10-bit | No preview constraint | NDK cannot request any 10-bit profile |
| Device coverage | — | Thirteen years of quirks handled, from a user base Kanaha cannot replicate |
| Effort | Small, understandable codebase | Weeks for one device, then field-debugging what upstream already fixed |

The cons win. Kanaha Camera remains an OpenCamera fork, and the effort that
project has put into device coverage is the reason.
