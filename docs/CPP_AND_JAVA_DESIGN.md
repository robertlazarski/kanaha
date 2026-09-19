# C and Java in Kanaha Camera: where Java starts and where it ends

Kanaha Camera is the one Kanaha app where Java does the work. About 70,000
lines of Java inherited from OpenCamera drive the camera, and about 4,000 lines
of Kanaha's own Java run the server supervisor and the command receiver. The C
side is about 2,600 lines: an Axis2/C service that turns HTTP/2 and MCP
requests into commands for that Java, and a stub httpd entry point.

This is the mirror image of Kanaha Audio, where C is the program and Java is a
thousand-line supervisor (see `CPP_AND_JAVA_DESIGN.md` in the kanaha-audio
repository). Both apps use the same rule: Java is kept to what Android forces
through Java. In the camera app, that turns out to be most of the app, and
this document explains why, and exactly where the line falls.

## The shape: two processes, one Intent between them

```
Java, app process (org.kanaha.camera)        Java, ":apache_httpd" process        C child process (same UID)
────────────────────────────────────────      ──────────────────────────────       ────────────────────────────────
net.sourceforge.opencamera.MainActivity       org.kanaha.camera.ApacheService      libhttpd.so  (Apache httpd -X)
  Preview, CameraController2, MediaRecorder     foreground, type=dataSync            mod_ssl + mod_http2 + mod_axis2
  ~70,000 lines, ~40 lines touched by Kanaha    deploys config + certs               axis2_json_rpc_msg_recv
                                                CertProvisioning                       └─ static registry → adapter
org.kanaha.camera.CameraControlReceiver         ProcessBuilder ──exec───────────▶        └─ camera_control_service
  exported, guarded by a custom permission      NetworkDiscoveryService (mDNS)               (1,138 lines of C)
  SecurityValidator                                                                             │
  calls MainActivity.getInstance()...                                                           │ fork()/execvp()
  writes <operation_id>.json      ◀──── am broadcast --es action ... --es operation_id ... ─────┘
                                  ────── C polls for the response file every 100 ms ──────────▶

                                                                                     libkanaha_mcp.so → files/kanaha-camera-mcp
                                                                                       same service code, JSON-RPC over stdio,
                                                                                       still reaches Java through the same Intent
```

The C side never links against Java and Java never loads C. There is no
`System.loadLibrary` and no `native` method in either the Kanaha code or the
OpenCamera code. The seam is an Android Intent, sent by executing
`/system/bin/am` with an argument array, and a JSON file written by Java and
read by C. Every request that changes camera state crosses that seam once in
each direction.

## What each side owns

### Java, inherited: OpenCamera (~70,000 lines in the built app)

Everything that touches the camera hardware or the media pipeline:

- `CameraController2.java` (8,895 lines): the Camera2 session, capture
  requests, capability tables, the 10-bit HLG output configuration.
- `Preview.java` (9,389 lines): the `TextureView` preview surface, video
  profiles, recording start and stop, orientation.
- `MainActivity.java`, `MyApplicationInterface.java`, `ImageSaver.java`,
  `MediaRecorder` wiring, scoped-storage saving, HDR and panorama processing,
  the UI, Bluetooth remote control.

Kanaha's changes inside this code are small and marked: about forty lines
across nine files, nearly all of them the 10-bit HLG session work in
`CameraController2`, `Preview` and `VideoProfile`. The fork is OpenCamera with
a setting added, not a rewrite.

### Java, Kanaha's own (~4,000 lines, `org.kanaha.camera`)

| File | Lines | Role | Required by Android? |
|---|---|---|---|
| `CameraControlReceiver.java` | 1,906 | Receives the C side's Intent, validates every parameter (`SecurityValidator`: length limits, path-traversal and injection patterns, character whitelist), calls the matching `MainActivity` method, writes the JSON response file. Includes the SFTP transfer and file operations that run in Java. | **Yes.** Only Java can call OpenCamera, and a `BroadcastReceiver` is the mechanism Android provides for a process to be told to do something. |
| `ApacheService.java` | 927 | Foreground service in its own `:apache_httpd` process. Deploys the Apache config set and certificates, launches `libhttpd.so` with `ProcessBuilder`, kills orphans, registers mDNS. | The foreground service and the launch: **yes**. Deployment and mDNS: **no**, convenience. |
| `PerformanceTestService.java` | 536 | HTTP/2 benchmarking and monitoring. | **No.** Tooling. |
| `NetworkDiscoveryService.java` | 333 | `NsdManager` registration so clients find the camera by hostname. | **No.** Movable to C. |
| `DeviceIdentifier.java` | 208 | One hostname used for both the certificate SAN and the mDNS name, so TLS verification by discovered name works. | **No.** Movable to C. |
| `CertProvisioning.java` | 136 | Mints the device keypair and CSR on first run (BouncyCastle); the key never leaves the device. | **No.** OpenSSL is already in the httpd. |

### C (~2,600 lines, `app/src/main/cpp`)

| File | Lines | Role |
|---|---|---|
| `axis2c/camera_control_service.c` | 1,138 | The Axis2/C service. Parses the JSON request, dispatches on `action` (`startRecording`, `stopRecording`, `getStatus`, `listFiles`, `deleteFiles`, `cleanupFiles`, `configure`, `playTone`, `sftpTransfer`), and for each builds an Intent with `fork()`/`execvp()` and waits for the response file. |
| `axis2c/kanaha_mcp.c`, `kanaha_mcp_main.c` | 633 | The MCP server: nine tools with schemas, JSON-RPC 2.0 over stdio, dispatching to the same service function. |
| `apache-httpd/apache_httpd_android.c` | 596 | Android specifics for the httpd binary. |
| `axis2c/axis2_static_service_adapter.c` | 166 | The strong symbol `camera_control_service_invoke_json` that overrides the weak stub in Axis2/C core, converting json-c objects to and from the service's string interface. |
| `main.c` | 64 | Entry point. |

The C is C11 with no C++ anywhere, built by CMake through Gradle's
`externalNativeBuild` against the cross-compiled Axis2/C, APR, OpenSSL, nghttp2
and json-c static archives. The other two Kanaha apps use a shell script for the
same link; the camera app predates that convention.

## Why Java does the work here

The audio app captures the microphone in C because AAudio is a complete
native-first API and everything after the samples arrive is DSP. The camera has
no such shape:

- **The camera pipeline is a Java pipeline.** The preview is a `TextureView`,
  which is a Java view. Video recording is `MediaRecorder`, which handles
  encoding, audio muxing, orientation and stabilisation in one Java object.
  Saving goes through scoped storage and the gallery, both Java APIs. The 10-bit
  HLG profile is set with `OutputConfiguration.setDynamicRangeProfile()`, which
  has no native equivalent in the NDK (verified against NDK 28; see
  `NDK_INSTEAD_OF_OPENCAMERA_REJECTED.md`).
- **The value is in the accumulated Java.** OpenCamera's Camera2 layer carries
  more than a decade of device-specific handling. Kanaha's 10-bit HLG support
  took one to two days because that layer already did everything except set the
  profile.
- **The camera permission and foreground state are Java concerns.** The
  `CAMERA` runtime permission is requested through an Activity, and Android
  gives the camera only to a foreground app. OpenCamera's `MainActivity` is
  that foreground app; it must be on screen for any command to succeed.

So Java is not where the Kanaha code *chose* to be; it is where the camera is.
What Kanaha chose was to keep the C side a control plane rather than a
reimplementation.

## The seam, in detail

The round trip for one `startRecording`:

1. httpd receives the HTTP/2 request over mTLS (or the MCP binary receives a
   `tools/call` on stdin). mod_axis2 hands the JSON to `axis2_json_rpc_msg_recv`,
   which finds `CameraControlService` in the static registry and calls the
   adapter's strong symbol.
2. `camera_control_service.c` matches the action, generates an
   `operation_id`, and calls `send_intent_and_wait_for_response()`.
3. That function forks and execs `/system/bin/am broadcast --user 0 -n
   org.kanaha.camera/.CameraControlReceiver -a org.kanaha.CAMERA_CONTROL --es
   action start_recording --es operation_id <id> ...`. Arguments go straight
   into `argv`; no shell ever sees caller data. This is the security property
   the whole IPC design exists for (see `HTTP2_ANDROID.md` in axis2-c-core,
   "Android IPC Security").
4. `CameraControlReceiver.onReceive()` runs in the app process. The receiver
   is exported so that `am` can reach it, and guarded by the custom permission
   `org.kanaha.camera.permission.CAMERA_CONTROL` so nothing else can.
   `SecurityValidator` rejects anything malformed before it touches the camera.
5. The receiver calls the matching method on `MainActivity.getInstance()`,
   which is OpenCamera, and writes `{"success": ..., "operation_id": ...}` to
   a file named by the operation id.
6. The C side polls for that file every 100 ms (up to 20 minutes, sized for
   large SFTP transfers), reads it, unlinks it, and builds the HTTP or MCP
   response.

Two consequences of this design are worth knowing:

- **Java is on the runtime path for every command.** In the audio app the MCP
  binary works with the app process dead, because the work is in C. Here the
  MCP binary still needs OpenCamera's activity in the foreground, because the
  work is in Java. `run-as` starts the C binary under the app's UID, but the
  camera is opened by Java.
- **The two halves are separate programs, which is also the licence boundary.**
  OpenCamera is GPLv3, so the app is GPLv3; the Axis2/C service is Apache 2.0.
  Communicating by Intent across processes keeps them separate works, which is
  why the axis2-c-core documentation labels this app's IPC "fork/execvp Intent
  IPC (GPL boundary)" against the audio app's "direct function call".

## The supervisor, for comparison with the other apps

`ApacheService` is the same supervisor pattern as the audio and calcs apps,
with two differences forced by this app:

- It runs in a separate process, `:apache_httpd`, so that the httpd child and
  the camera activity are supervised independently and a restart of one does
  not take down the other.
- Its foreground-service type is `dataSync`, not `camera`: the service owns the
  network server, and the camera itself belongs to the activity.

The launch is otherwise identical: `<nativeLibraryDir>/libhttpd.so -f
<conf> -d <ServerRoot> -X`, with `LD_LIBRARY_PATH` and `HOME` in the
environment, stdout mirrored to logcat. `libhttpd.so` is an executable named to
survive APK extraction, not a shared library. `libkanaha_mcp.so` arrives the same
way and is copied to `files/kanaha-camera-mcp` on service start.

## Summary: the boundary in one table

| Concern | Lives in | Because |
|---|---|---|
| Camera open, capture session, 10-bit profile | Java (OpenCamera) | Camera2 and its output configuration are Java APIs; the NDK cannot set the profile |
| Preview, recording, encoding, muxing, saving | Java (OpenCamera) | `TextureView`, `MediaRecorder`, scoped storage |
| Camera permission and foreground state | Java (OpenCamera activity) | Activity API; camera requires a foreground app |
| Command validation and dispatch into OpenCamera | Java (Kanaha receiver) | Only Java can call OpenCamera; `BroadcastReceiver` is the entry |
| Foreground service, notification, launching httpd | Java (Kanaha supervisor) | `startForeground`, `ProcessBuilder` |
| Certificates, mDNS, benchmarking, device naming | Java today | Convenience; movable to C |
| HTTP/2, TLS, mTLS, routing | C | Apache httpd and mod_axis2 |
| Request parsing, action dispatch, Intent IPC, response assembly | C | The Axis2/C service |
| MCP server | C | Same service code, stdio transport |

In the audio app, Java starts at the manifest and ends at
`ProcessBuilder.start()`. In the camera app, Java starts at the manifest, ends
at `ProcessBuilder.start()` for the server, and then starts again at
`CameraControlReceiver.onReceive()` for every command, because that is where
the camera is.
