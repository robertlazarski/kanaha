# Changelog

All notable changes to Kanaha are documented here.

## [v1.3.0] — 2026-03-08

### New Features

- **Open Gate Recording** — `startRecording` now accepts `"open_gate": true` to record at the camera's native 4:3 sensor resolution (2560×1920 on Pixel 9 Pro) with no horizontal or vertical crop. Implemented as a photo→video session cycle that reopens the camera at maximum sensor resolution. Moto G phones fall back silently to standard recording. See [OPENGATE.md](docs/OPENGATE.md).

### Bug Fixes

- **`checkSelfPermission` guard** — Added `ActivityCompat.checkSelfPermission` guard before `getLastKnownLocation` calls to fix `SecurityException` on strict permission enforcement (Android 12+).
- **Quality persistence after open gate** — After an open gate session, the next standard `startRecording` call now correctly reopens the camera session to restore the 4K default. Previously the session stayed at 4:3 resolution.
- **`stopRecording` response** — Fixed `stopRecording` to return `{"success": true}` even when the camera is already idle; previously returned an error when called redundantly.

### Security Fixes (from code review)

- **`strtol`/`strtoll` for JSON parsing** — Replaced `atoi`/`atoll` with `strtol`/`strtoll` and `endptr` error checks in `extract_json_int` and `extract_json_long` in the C layer. `atoi`/`atoll` have undefined behavior on overflow and provide no error signaling.
- **Intent extra encoding** — Changed `open_gate` intent extra from `--es "true"/"false"` to `--es "1"/"0"` (and Java-side to `"1".equals(getStringExtra("open_gate"))`) to avoid the `am broadcast --ei`/`--el` typed-extra drop that occurs when `am broadcast` is invoked from within an Android subprocess.

### Documentation

- **OPENGATE.md** — New guide covering: what open gate is, device support matrix, API usage, DaVinci Resolve workflow, LUT color grading, C-layer build process, and BLUF quick-start section.
- **THREAD_MODEL.md** — New reference for the three-threading-context IPC pipeline (C Apache/Axis2 worker thread → Android UI thread `onReceive` → background `KanahaCameraControl` thread), JMM visibility rules, and `CountDownLatch` patterns.
- **README.md** — Added `open_gate` parameter documentation, Open Gate Recording feature bullet, THREAD_MODEL.md and LEGAL.md in docs table, OPENGATE.md in API reference table.

### Scripts

- **`test-triple-camera-workflow.sh`** — Three-camera orchestration script with simultaneous `start_at` scheduling, software slate (`play_slate_all`), SFTP transfer (video + sidecar JSON), and cleanup.

---

## [v1.2.0] — 2026-02-15

### New Features

- **Software Sync Slate** — `playTone` API synthesizes a 1 kHz sine wave via `AudioTrack MODE_STATIC` with 5 ms fades. Combined with `start_at`, fires on all cameras at the same wall-clock millisecond. Onset detection with `ffmpeg` bandpass + silencedetect gives ~1–5 ms inter-camera sync with no hardware.
- **Recording Start Sidecar** — `writeSyncSidecar()` writes `DCIM/OpenCamera/kanaha_recording_start.json` at recording start with ms-precision `recording_start_ms`, `clip_name`, and GPS fix time. Analog of a BWF Time Reference for software sync.
- **NTP RTT clock correction** — `query_camera_clock()` in `parseWithoutLTC.sh` uses min-RTT NTP-style sampling (~100–200 ms accuracy).

### Improvements

- `getStatus` now returns `timestamp`, `gps_time`, `gps_age_ms`, and `gps_provider` fields.
- `start_at` parameter on `startRecording` and `playTone` for synchronized multi-camera scheduling.
- `LocationSupplier` preference: `addCameraStatusDetails()` prefers active 1s GPS subscription over stale OS cache.

---

## [v1.1.0] — 2026-01-xx

- SFTP file transfer (`sftpTransfer` endpoint) with JSch and ed25519 SSH key support.
- mDNS camera discovery.
- Initial multi-camera workflow scripts.

---

## [v1.0.0] — 2025-xx-xx

- Initial release: Apache httpd + Axis2/C HTTP/2 server on Android.
- mTLS mutual certificate authentication.
- `startRecording`, `stopRecording`, `getStatus`, `listFiles`, `deleteFiles` API.
- `start_at` scheduled recording.
