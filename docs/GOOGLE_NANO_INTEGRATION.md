# On-device clip description with Gemini Nano

## BLUF

Add one camera operation, `describeClip`, that produces a short English
description of a finished recording using the on-device Gemini Nano model
through Google's ML Kit GenAI Image Description API, and writes it into a
per-clip sidecar next to the video. The description is generated on the phone
and nothing leaves the device. The operation rides the existing
C → `am broadcast` → Java → response-file path unchanged; the model work is
Java because the API is Java/Kotlin only. A second, optional operation,
`describeFrame`, describes what the camera sees right now. The EDL generator in
kanaha-audio can then match audio cues to visual content across cameras without
anyone scrubbing footage.

Status: **phase 1 implemented 2026-09-20** and verified on a Pixel 10 Pro XL
(Android 17, locked bootloader, AICore present): `describeClip` over MCP and
over HTTPS/mTLS, three frames of a real 4K clip described in about 11 s,
sidecar written, `listFiles` flags described clips, `deleteFiles` and
`sftpTransfer` carry the sidecar, the `foss` flavor reports `unavailable`.
Three details differ from the design below and are recorded in "Deviations
from this design" at the end. API is beta and English-only. Supported
devices are Google's list (Pixel 9, 10 and 11 series plus other manufacturers);
unsupported devices return a clean `unavailable` status and everything else
keeps working. The proprietary client library is confined to an opt-in Gradle
flavor; the default build stays fully open and reports `unavailable`.
**Licensing:** this app is GPLv3+ because OpenCamera is. A GPL program may not
be distributed with a proprietary library compiled into it, so the `nano`
build is for private use only and is never published as an APK; the `foss`
build is the distributable one. See "Security and privacy" for the rule and
"What this gives up" for the separate-process design that would lift it.

## The problem this solves

A multi-camera session produces one video per phone plus a sidecar with the
recording start time, GPS time and open-gate flag. kanaha-audio produces the
edit points — where music started, where "next slide please" was said. What is
missing is any record of *what each camera saw*, so choosing which camera to cut
to at a given cue still means opening every file. With a description per clip
(and per sampled frame within it) the cut can be chosen from metadata: an MCP
client or the EDL script matches a cue timestamp to the frame descriptions from
each camera and picks the one that shows the subject.

Secondary uses that fall out for free: searching a shoot in plain language
("the take with the whiteboard"), a framing check before rolling
(`describeFrame`), and captions for the review page.

## Why Gemini Nano, and what it is here

Gemini Nano is Google's on-device model. On supported phones it runs behind the
AICore system service and uses the device's ML accelerator; the app never sees
the model, only the ML Kit client library. For this feature that means:

- **Nothing is uploaded.** Frames go to a system service on the same device.
- **No network after the one-time model download.**
- **English only**, one short description per image (Google's current limits).
- **Beta**: `com.google.mlkit:genai-image-description:1.0.0-beta1`, no SLA.
- **Locked bootloader required** by Google's client; development devices with
  an unlocked bootloader will report `unavailable`.
- **Minimum API 26**; the app's `minSdkVersion` is 23, so the operation must
  guard on `Build.VERSION.SDK_INT >= 26` and report `unavailable` below it.

This is deliberately the *one* place in Kanaha Camera where a model runs from
Java. The C side stays the program; the Java side already exists to satisfy
Android and already does the Camera2 work, and this API has no C surface.
Everything the C service knows is one more action name and one more response.

## Architecture

```
HTTP/2 + mTLS  ─┐                                   Java (org.kanaha.camera)
MCP stdio      ─┤► camera_control_service.c          CameraControlReceiver
                │    action "describeClip"             handleDescribeClip()
                │    fork/exec am broadcast ──────────►   validate inputs
                │      --es action describe_clip          resolve video file
                │      --es operation_id <id>             MediaMetadataRetriever → N bitmaps
                │      --es video_filename <name>         ImageDescriber.runInference() per bitmap
                │      --es frame_count <n>               write <clip>.kanaha.json
                │    poll cache/response_<id>.json ◄───── writeResponseToFile()
                └──► JSON back to the caller
```

Nothing changes in the boundary rules of `docs/THREAD_MODEL.md`: the handler
runs on the receiver's background thread, file I/O is safe there, and
`pendingResult.finish()` stays in the `finally`. Because inference for several
frames plus a possible first-run model download can exceed the broadcast time
budget, `describeClip` follows the pattern `handleSftpTransfer` already uses:
finish the broadcast, keep working on the worker thread, and write the response
file when done. The C side already polls the response file for up to 20
minutes, so no C timeout changes are needed.

## Operations

### `describeClip`

Describe a finished recording from a few sampled frames.

Request (HTTP body or MCP `arguments`):

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `video_filename` | string | yes | — | A file in the video directory. Validated by `SecurityValidator.validateFilenameOrPattern()`; no path separators, no traversal. Exact name, not a pattern. |
| `frame_count` | int | no | 3 | Clamped to 1–8. |
| `positions` | number[] | no | evenly spaced | Fractions of duration in [0, 1]; length must equal `frame_count` if given. |
| `write_sidecar` | bool | no | true | Write `<video basename>.kanaha.json`. |
| `max_dimension` | int | no | 1280 | Longest bitmap side before inference. |

Response:

```json
{
  "success": true,
  "operation_id": "…",
  "video_filename": "VID_20260301_101500.mp4",
  "duration_ms": 184320,
  "model": "gemini-nano/mlkit-image-description",
  "language": "en",
  "frames": [
    {"position": 0.10, "time_ms": 18432,  "description": "A person standing at a lectern in front of a projection screen.", "inference_ms": 1480},
    {"position": 0.50, "time_ms": 92160,  "description": "A close view of a projection screen showing a table of numbers.", "inference_ms": 1350},
    {"position": 0.90, "time_ms": 165888, "description": "A person gesturing toward a screen; a second person seated at a table.", "inference_ms": 1380}
  ],
  "sidecar": "/…/files/VID_20260301_101500.kanaha.json",
  "inference_ms": 4210
}
```

Failure responses use the existing `{"success": false, "error": …}` shape with
a stable `code`:

| `code` | Meaning | Caller action |
|---|---|---|
| `unavailable` | Device, OS or bootloader not supported, or API < 26 | Do not retry. |
| `downloading` | Model download in progress (started by this call or earlier) | Retry later; `getStatus` shows progress. |
| `download_failed` | AICore reported a download failure | Retry once; then treat as `unavailable`. |
| `not_found` | `video_filename` does not exist in the video directory | Fix the name. |
| `frame_extract_failed` | Retriever could not decode a frame | Try other `positions`. |
| `inference_timeout` | A single frame exceeded 30 s | Retry with fewer frames. |

### `describeFrame` (phase 3, optional)

Describe the current preview. Requires the camera activity in the foreground,
exactly like `startRecording`. Captures one frame from the preview
(`TextureView.getBitmap()` on the UI thread via `runOnUiThread` and a
`CountDownLatch`, per the thread-model doc), downscales, runs one inference.
Response is one `frames[]` entry. No sidecar.

### `startRecording` extension: `describe_on_stop` (phase 2)

Boolean, default false. When set, `stopRecording` schedules `describeClip` for
the finished file with default parameters and returns immediately; the sidecar
appears when inference finishes. `getStatus` gains a `description` block:

```json
"description": {"state": "idle|running|done|failed", "clip": "…", "frames_done": 2, "frames_total": 3}
```

### `getStatus` extension

Always present once this feature is built, independent of phase 2:

```json
"on_device_model": {"feature": "image_description", "status": "available|downloadable|downloading|unavailable", "download_percent": 0}
```

## Sidecar file

One file per clip, next to the recording-start sidecar the app already writes:

`<video basename>.kanaha.json`

```json
{
  "clip": "VID_20260301_101500.mp4",
  "recording_start_ms": 1741452000123,
  "description_generated_ms": 1741452301877,
  "model": "gemini-nano/mlkit-image-description",
  "language": "en",
  "inference_ms": 4210,
  "frames": [ {"position": 0.10, "time_ms": 18432, "description": "…", "inference_ms": 1480}, … ]
}
```

`inference_ms` is the time spent in the model call alone, per frame and in
total (the sum of the frames): not frame extraction or downscaling. It is the
same value the `describeClip` response reports.

`recording_start_ms` is copied from `kanaha_recording_start.json` when that file
refers to the same clip, so a consumer can convert `time_ms` to wall-clock and
line it up with kanaha-audio's keyword and event timestamps without a second
lookup. The existing start sidecar is left unchanged.

`listFiles`, `sftpTransfer`, `deleteFiles` and `cleanupFiles` must treat
`*.kanaha.json` as belonging to their video: transfer it with the clip, delete
it with the clip. Today those operations match `*.mp4`; extend the match to the
sidecar with the same basename.

## Build flavors: keep the proprietary client out of the default build

The ML Kit client is closed source, works only on Google's list of devices with
a locked bootloader, and has no desktop build. Everything else in this app is
open, builds on a Linux host, and is meant to be inspectable end to end. Rather
than make the whole APK depend on one vendor library for one operation, the
dependency lives in a Gradle product flavor:

| Flavor | Contains the ML Kit client | `describeClip` behaviour | Purpose |
|---|---|---|---|
| `foss` (default) | no | returns `unavailable`, same as on an unsupported phone | fully open build; eligible for stores that reject proprietary Google libraries |
| `nano` | yes | works on supported devices | opt-in build for phones that have the on-device model |

```groovy
android {
    flavorDimensions "model"
    productFlavors {
        foss { dimension "model"; isDefault true }
        nano { dimension "model" }
    }
}
dependencies {
    nanoImplementation 'com.google.mlkit:genai-image-description:1.0.0-beta1'
}
```

`ClipDescriber` has two source sets: `src/nano/java/…/ClipDescriber.java` wraps
the client; `src/foss/java/…/ClipDescriber.java` has the same signatures and
reports `UNAVAILABLE` from `status()`. `CameraControlReceiver`, the C service,
the MCP catalog and the sidecar format are identical in both flavors, so a
client cannot tell the flavors apart except by the status code — which is the
intended contract: the operation exists everywhere, and the model is present
where the device provides it.

Only the `foss` APK is a release artifact (`kanaha-camera-foss-<ver>.apk`).
The `nano` APK is built locally for the developer's own devices and is never
published — see the license rule under "Security and privacy". CI builds both
to keep them compiling, but archives only `foss`; the instrumented tests for
`describeClip` run against `nano` on a supported device and assert
`unavailable` against `foss` on any device.

## Java implementation

**Dependency** (`kanaha-camera-app/app/build.gradle`, `nano` flavor only — see
above):

```groovy
nanoImplementation 'com.google.mlkit:genai-image-description:1.0.0-beta1'
```

The client returns Guava `ListenableFuture`s in Java; use `.get(timeout, unit)`
on the worker thread, never on the main thread.

**New class** `org.kanaha.camera.ClipDescriber` (keeps `CameraControlReceiver`
from growing further):

```java
final class ClipDescriber implements AutoCloseable {
    ClipDescriber(Context ctx)                       // ImageDescription.getClient(ImageDescriberOptions.builder(ctx).build())
    FeatureStatus status()                           // checkFeatureStatus().get(5, SECONDS)
    void ensureDownloaded(DownloadCallback cb)       // downloadFeature(cb) when DOWNLOADABLE; progress → getStatus block
    String describe(Bitmap bmp)                      // runInference(ImageDescriptionRequest.builder(bmp).build()).get(30, SECONDS).getDescription()
    static List<Bitmap> sampleFrames(File video, double[] positions, int maxDim)  // MediaMetadataRetriever, OPTION_CLOSEST_SYNC
    public void close()                              // ImageDescriber.close()
}
```

**Handler** `handleDescribeClip(Context, Intent)` in `CameraControlReceiver`:

1. Read extras; validate `video_filename` with `SecurityValidator`; clamp
   `frame_count`; parse `positions`.
2. Resolve the file through the existing video-directory lookup (the same
   method `sftpTransfer` uses). Missing → `not_found`.
3. `status()`: `UNAVAILABLE` → `unavailable`; `DOWNLOADABLE` → start download,
   return `downloading`; `DOWNLOADING` → `downloading`; `AVAILABLE` → continue.
4. Finish the broadcast (`pendingResult.finish()`), then on the worker thread:
   extract frames, run inference sequentially, recycle each bitmap after use,
   assemble the response, write the sidecar, `writeResponseToFile()`.
5. One `ImageDescriber` per operation, closed in `finally`. Do not keep a
   static instance: the API is beta and its lifecycle rules may change.

**Feature status at startup.** `ApacheService` (or the activity, on first
resume) calls `status()` once and, if `DOWNLOADABLE`, starts the download so the
first `describeClip` does not pay for it. Log progress; expose it in
`getStatus`. Never block the service start on it.

**Bitmap sizing.** Downscale so the longest side is `max_dimension` (default
1280) before inference; Google documents no hard limit, but large frames cost
time and memory for no gain in a one-sentence description. Use
`inPreferredConfig = RGB_565` only if memory pressure is observed; default ARGB.

## C implementation

`camera_control_service.c`: one more `else if` in the action dispatch, built
exactly like `sftp_transfer`:

```c
else if (strcmp(action, "describe_clip") == 0 || strcmp(action, "describeClip") == 0) {
    /* parse video_filename (required), frame_count, positions (JSON array → "0.1,0.5,0.9"), write_sidecar, max_dimension */
    intent_extra_t extras[] = {
        {"--es", "action", "describe_clip"},
        {"--es", "operation_id", operation_id},
        {"--es", "video_filename", video_filename},
        {"--es", "frame_count", frame_count_str},
        {"--es", "positions", positions_csv},
        {"--es", "write_sidecar", write_sidecar ? "1" : "0"},
        {"--es", "max_dimension", max_dim_str}
    };
    /* broadcast, then wait_for_response_file(operation_id) and pass the JSON through */
}
```

Validation on the C side mirrors the Java side (defense in depth, as the
receiver's own comment puts it): reject `video_filename` containing `/`, `\`
or `..`; clamp `frame_count` to 1–8; reject `positions` outside [0, 1]. The
response is passed through unchanged; `MAX_RESPONSE_SIZE` is ample for eight
one-sentence descriptions.

`kanaha_mcp.c`: add `SCHEMA_DESCRIBE_CLIP` and a row in `kanaha_mcp_tools[]`;
the tool count in the header comment and in `docs/MCP.md` becomes 10 (11 with
`describeFrame`). `services.xml`: add the operation. `README.md` operation
table: add the row.

Schema:

```json
{"type":"object",
 "properties":{
   "video_filename":{"type":"string","description":"Exact file name in the video directory (no path)"},
   "frame_count":{"type":"integer","minimum":1,"maximum":8,"default":3},
   "positions":{"type":"array","items":{"type":"number","minimum":0,"maximum":1},"description":"Fractions of duration; length must equal frame_count"},
   "write_sidecar":{"type":"boolean","default":true},
   "max_dimension":{"type":"integer","default":1280}},
 "required":["video_filename"]}
```

## Consumers

**EDL generation** (kanaha-audio `docs/EDL.md`, `tools/generate-edl.sh`):
after `sftpTransfer` brings each camera's `.mp4` and `.kanaha.json` to the
workstation, the generator has, per camera, the recording start and the
descriptions at known offsets. Matching a cue at wall-clock T to the camera
whose nearest frame description mentions the subject is a string match or an
MCP client's judgment; either way it needs no video decode. This is a change in
the kanaha-audio repository and is out of scope here beyond fixing the sidecar
format.

**MCP clients** get `describeClip` from `tools/list` like every other
operation and can call it after `stopRecording`.

## Security and privacy

- Inputs cross the same validated path as every other operation. `video_filename`
  is validated in both C and Java, `frame_count` and `max_dimension` are clamped
  to their ranges, and `positions` outside [0, 1] or of the wrong length are
  refused rather than corrected.
- Frames are handed to the AICore system service on the same device. No network
  permission is used by this feature; the app declares none for it.
- Descriptions are derived data about video the user already chose to record.
  They live next to the video, move with it over SFTP, and are deleted with it.
  Treat a `.kanaha.json` with the same care as its `.mp4`.
- **License: GPLv3+, and it binds here.** Kanaha Camera is GPLv3+ because it
  incorporates OpenCamera (`LICENSE`, `docs/LEGAL.md`). The ML Kit client is a
  proprietary Google library compiled into the `nano` APK's process. Google's
  terms permit shipping it inside an app; the GPL does not permit distributing
  a GPL program with a non-GPL-compatible library linked into it, and no linking
  exception can be added because the OpenCamera copyright is not this
  project's to license. The GPL's system-library exception covers AICore, which
  is part of the platform, not a client library bundled in the APK. Therefore:
  - the `foss` APK contains no proprietary code and may be distributed;
  - the `nano` APK may be **built and used privately** (the GPL restricts
    distribution, not use) and is **never published, released or handed to a
    third party** — no store listing, no release artifact;
  - publishing the `nano` flavor's source and build configuration is fine.
  Record the client in `docs/LEGAL.md` with this distribution rule, and add
  "Gemini", "Gemini Nano" and "ML Kit" to `TRADEMARKS.md` as Google's marks
  used nominatively, never in an app or feature name.
- **The way to lift the restriction is the repository's own rule.** The
  Axis2/C service stays a separate program from OpenCamera by talking to it
  across a process boundary (see `CPP_AND_JAVA_DESIGN.md`, "the licence
  boundary"). Apply the same rule to the model client: a small separate app,
  its own process and its own license, holds the ML Kit client and offers one
  operation, *describe these images*. The camera app extracts frames with
  framework APIs (`FrameSampler`), hands them over by content URI, and receives
  the text back through `CameraControlReceiver`. Flavors then disappear; the
  camera app reports `unavailable` when the helper is not installed. The GPL
  program contains no proprietary code and the helper is distributable under
  its own terms. This is the recommended phase 1b.
- Google's generative AI APIs are subject to Google's Generative AI Prohibited
  Use Policy. Describing the user's own recordings is within it; the maintainer
  should read the current policy once when adding the dependency and link it
  from `docs/LEGAL.md`.
- No Apache Software Foundation project could accept this dependency (ASF
  policy excludes proprietary libraries). Nothing here is intended to move
  upstream; the Axis2/C side of this feature is one action name and one
  pass-through response, both of which are ordinary.

## Failure behaviour

| Situation | Behaviour |
|---|---|
| Unsupported device or unlocked bootloader | `getStatus` shows `unavailable`; `describeClip` returns `unavailable`; nothing else changes. |
| Model not yet downloaded | First call starts the download and returns `downloading`; `getStatus` shows percent. |
| No foreground activity | `describeClip` does not need one (it reads a file). `describeFrame` does, like `startRecording`. |
| App killed mid-inference | Response file never appears; the C poll times out as it does for any failed operation; no partial sidecar is written (write to a temp name, rename on completion). |
| Non-English scene text | The model describes in English regardless; document it. |

## Testing

- **Unit (JVM):** `positions` parsing and clamping; sidecar JSON shape; basename
  matching for `*.kanaha.json` in list/transfer/delete.
- **Instrumented, supported device:** record a 10-second clip, call
  `describeClip` with defaults, assert three `frames[]` entries with non-empty
  English text, a sidecar next to the video, and `inference_ms` reported. Call
  again with `frame_count: 8`. Call with a bad filename and assert `not_found`
  without any broadcast side effects.
- **Instrumented, unsupported device:** assert `unavailable` from both
  `getStatus` and `describeClip`, and that recording is unaffected.
- **Desktop:** none; this feature is Java-only and has no Linux build.

## Phasing

1. Product flavors `foss` and `nano` with the two `ClipDescriber` source sets;
   `describeClip` on demand; `getStatus.on_device_model`; sidecar; list,
   transfer and delete follow the sidecar. Dependency in `nano` only; LEGAL and
   TRADEMARKS entries; MCP schema; README row; CI builds both flavors.
2. `describe_on_stop` on `startRecording` and the `description` block in
   `getStatus`.
3. `describeFrame`.
4. EDL generator consumes the sidecar (kanaha-audio repository).

## What this gives up, and the C alternative

Every other model in the Kanaha apps is a library with a C header, linked into
the native process and called directly; the same code builds and is tested on a
Linux host. Gemini Nano is neither a library nor a file: it lives inside the
AICore system service and is reachable only through Google's Java/Kotlin
client. There is nothing to link, so for this one operation the C service is a
proxy, and the properties below are traded away on purpose:

| Property | whisper / llama bridge (C) | Gemini Nano (Java client) |
|---|---|---|
| Call cost | function call | fork + `am broadcast` + response-file poll (~100–500 ms; irrelevant after a take ends) |
| Runs on a Linux host | yes, same code | no |
| Testable off-device | yes | no |
| Runs on any phone | yes, CPU | Google's device list, locked bootloader |
| Java involved | none | receiver, client library, `ClipDescriber` |
| Who provides the model | the app, a file in `files/models` | Google, via a system service |

What is kept is the contract: Axis2/C defines the operation, its schema and
validation, and both front doors. A caller cannot tell which backend produced
the description.

### Where Nano sits among the models the Kanaha apps run

Two axes matter: what a model does, and how it is deployed. Whisper and llama
share a deployment (a file you ship, linked through one ggml, called from C on
the CPU) and differ in task; Nano differs from both in deployment and overlaps
llama only at the edges of its task.

| Model | App | Task | Whose model | How it runs | Hardware |
|---|---|---|---|---|---|
| Whisper | kanaha-audio | speech to text, keyword timestamps | the app's, a file under `files/models` | whisper.cpp, C bridge, in-process | CPU (NEON) |
| YAMNet | kanaha-audio | audio event classification | the app's, a file | TensorFlow Lite C API, C bridge, in-process | CPU |
| llama (planned) | kanaha-audio | language; grammar-constrained tool calls | the app's, a file | llama.cpp, C bridge, same ggml as Whisper | CPU |
| Gemini Nano | kanaha-camera | image description (this document); text tasks available | Google's, inside the AICore system service | ML Kit Java client, across the Intent boundary | the device's ML accelerator |
| vision-language model (candidate) | kanaha-camera | image description, the `foss` backend | the app's, a GGUF file | ggml, C bridge, in-process | CPU |

Consequences that follow from the deployment column, not from the task:

- **Memory.** A model the app ships is resident in the app's process; a
  multi-billion-parameter model at 4-bit is several gigabytes and Android's
  low-memory killer treats it as the app's. Nano's weights are loaded and
  shared by the system; the app pays for a client library.
- **Thermal.** In-process models run on the CPU cores and heat the phone under
  sustained load. Nano runs on the accelerator with the system pacing it.
- **Control.** For a model the app ships, the app chooses the weights, the
  quantization, the prompt, the grammar and the device. For Nano the app
  chooses none of these; Google decides the model, its size, its languages and
  which devices have it. That is the trade this document makes for one
  operation, and the reason the orchestrator model stays the app's own.
- **Licence.** A model file is data; the ML Kit client is proprietary code
  linked into the process, which is why the licence rule above exists.
- **Portability.** Every in-process model builds and runs on a Linux host; Nano
  exists only on Google's device list.

Numbers Google publishes for Nano's throughput describe Google's own use of the
model and cannot be measured from a third-party app; they are not repeated
here. Nano's parameter count is not published.

**The C backend.** ggml runs small vision-language models in-process — the
same recipe as the whisper bridge and the planned llama bridge (one ggml, a
`<lib>_bridge.c`, one mutex, models under `files/models`). A ~2B-parameter
model in GGUF would describe a frame in C, on any phone, testable on the
desktop, with the mirror-image costs: CPU only (slower, hotter), a model file of
a gigabyte or more to ship, and a weaker description than Google's.

**Design rule.** `describeClip` is a contract with two possible backends,
selected by build flavor and device:

| Flavor | Backend | Where it makes sense |
|---|---|---|
| `nano` | Java client → AICore → Gemini Nano on the device's accelerator | supported Google-list phones; best quality and speed; proprietary client |
| `foss` today | none — `unavailable` | fully open build |
| `foss` later | C bridge → ggml vision-language model on CPU | any phone; desktop-testable; the app's own model; slower |

The C backend is out of scope for this document beyond reserving its place: it
would be a `vlm_bridge.c` in the camera app's native tree following
kanaha-audio's `docs/CPP_AND_JAVA_DESIGN.md` recipe, dispatched from the same
`describe_clip` action before the intent path is tried. The response shape, the
sidecar and the MCP schema are the same for both; `model` in the response names
which one answered.

## Verify before coding

Two facts in this document come from Google's documentation as read on
2026-09-20 and must be re-checked against the current page when
implementation starts, not trusted from here:

1. **The client artifact version.** `com.google.mlkit:genai-image-description:1.0.0-beta1`
   is a beta coordinate; Google may have published a newer beta or a stable
   release. Use the current one and update this document.
2. **The locked-bootloader requirement.** Google's page states the feature is
   unsupported on devices with an unlocked bootloader. Confirm it still says so,
   because it decides whether development phones can exercise the `nano` flavor
   at all, and therefore how the instrumented tests are run.

## Deviations from this design (as implemented)

- **The sidecar lives in the app's own external files directory**, next to
  `kanaha_recording_start.json`, not next to the video. OpenCamera records into
  `DCIM/OpenCamera`, where scoped storage does not let the app write a
  non-media file. The response and `listFiles` carry the sidecar's path, and
  `deleteFiles` and `sftpTransfer` match it by basename.
- **`recording_start_ms` is matched by time, not name.** The start sidecar's
  `clip_name` is the caller's label; OpenCamera names the file by timestamp.
  The start is copied when the file's modification time minus its duration is
  within a minute of `recording_start_ms`, or when the names do match.
- **The handler runs inline on the receiver's worker thread**, exactly like
  `sftpTransfer`, rather than finishing the broadcast first. That is the
  pattern the app already relies on and it stays within the C side's 20-minute
  poll. Unknown duration (a one-frame capture) describes one frame at time 0
  and says so in a `note` field instead of describing the same frame n times.
- **The `nano` flavor sets `minSdkVersion 26`**: the ML Kit client declares
  it and the manifest merger refuses 23. The `foss` flavor keeps 23.
- **The model client must be built with the application context.** A
  `BroadcastReceiver`'s context may not bind to services, and the client binds
  to AICore; `ClipDescriber` uses `context.getApplicationContext()`.
- **Found on the way, fixed:** the Intent child process (`am broadcast`)
  inherited the MCP server's stdout and wrote two lines of chatter before
  every JSON-RPC reply. The child's stdout is now redirected to stderr.

## Open questions

- Frame selection: evenly spaced positions are a start; a later version could
  skip near-duplicate frames (cheap histogram distance) and spend the budget on
  scene changes.
- The beta API may change method names or the download flow; keep all
  ML Kit calls inside `ClipDescriber` so a rename touches one file.
- Whether to also run the ML Kit Summarization API over a transcript from
  kanaha-audio to produce a one-paragraph clip summary. Same client pattern,
  different phone today; revisit once both sidecars meet on the workstation.

## References

- ML Kit GenAI Image Description API: https://developers.google.com/ml-kit/genai/image-description/android
- ML Kit GenAI overview: https://developers.google.com/ml-kit/genai
- Gemini Nano on Android: https://developer.android.com/ai/gemini-nano
- This repository: `docs/THREAD_MODEL.md` (handler rules), `docs/OPENGATE.md`
  (existing sidecar), `docs/MCP.md` (tool catalog), `AGENTS.md` (validation
  points), `kanaha-camera-app/app/src/main/cpp/axis2c/camera_control_service.c`
  (broadcast and response-file protocol).
