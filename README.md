# Kanaha Camera Control

Kanaha transforms Android phones into network-controllable cameras with a secure HTTP/2 API. Control multiple cameras simultaneously from any device using standard HTTPS requests with mutual TLS authentication.

**Built in C for Performance:** The core server (Apache httpd + Axis2/C) is written entirely in C, delivering native execution speed with minimal memory footprint. This enables Kanaha to run efficiently on devices spanning nearly a decade of Android hardware—from a 2017 Moto X4 to a 2026 Pixel 10 Pro XL—with identical functionality.

## Features

- **Multi-Camera Control** - Start/stop recording on multiple phones simultaneously
- **HTTP/2 + mTLS Security** - Enterprise-grade encryption with certificate authentication
- **Wide Device Support** - Same APK works on Android 6.0+ (API 23) devices, tested from a 2017 Moto X4 to a 2026 Pixel 10 Pro XL [<sup>1</sup>](#notes)
- **SFTP File Transfer** - Secure file retrieval with SSH key authentication
- **mDNS Discovery** - Automatic camera discovery on local network
- **Synchronized Start** - `start_at` parameter fires all cameras at the same UTC millisecond, independent of network delivery timing
- **Software Sync Slate** - `playTone` API plays a synthesized sine wave on all cameras + laptop simultaneously; onset detection in post gives ~1–5 ms inter-camera sync with no hardware
- **GPS Timestamping** - `getStatus` exposes GPS fix time and age for clock quality assessment
- **Recording Start Sidecar** - Writes `kanaha_recording_start.json` at recording start (millisecond precision, GPS time); the post-processing analog of a BWF Time Reference
- **Open Gate Recording** - Full 4:3 native sensor recording on supported devices (2560×1920 on Pixel 9 Pro) with no horizontal or vertical crop; see [Open Gate Recording](docs/OPENGATE.md)
- **On-Device Clip Description** - `describeClip` samples frames from a finished recording and describes them with Gemini Nano through ML Kit, writing a `<clip>.kanaha.json` sidecar that travels with the video. Opt-in `nano` build flavour; the default `foss` build keeps the operation and reports the model unavailable. See [On-Device Clip Description](docs/GOOGLE_NANO_INTEGRATION.md)
- **MCP (AI Assistant) Support** - [Model Context Protocol](https://modelcontextprotocol.io/) integration lets Claude Desktop and other AI assistants discover and control cameras as tools. 10 camera operations exposed with full parameter schemas. 117 KB native binary, no JVM; see [MCP Documentation](docs/MCP.md)

## Installation

### Option 1: Install Directly on Phone (Easiest)

1. On your Android phone, open: **[Latest Release](../../releases/latest)**
2. Tap `app-debug.apk` to download
3. Tap the downloaded file notification to install
4. If prompted: **Settings → Install unknown apps → Allow** for your browser
5. Tap **Install**, then **Open**

### Option 2: Install via ADB (USB)

```bash
# Download APK from releases page, then:
adb install app-debug.apk
```

### Option 3: Install via ADB (WiFi)

```bash
# On phone: Settings → Developer options → Wireless debugging → Pair
adb pair <phone-ip>:<pair-port>  # Enter pairing code

adb connect <phone-ip>:5555
adb install app-debug.apk
```

### First Launch

1. Open **Kanaha** app
2. Grant permissions: Camera, Microphone, Storage
3. **Android 15 users:** Tap "OK" on the debuggable app warning (this is a debug build)
4. Camera preview appears - the HTTP control server starts automatically on port 8443

### Verify Installation

From a computer on the same WiFi network (requires an mTLS client cert — see [Set up mTLS](#1-set-up-mtls-ca)):

```bash
curl -sk https://<phone-ip>:8443/services/CameraControlService/getStatus \
  --cert client.crt --key client.key --cacert ca.crt
```

## Quick Start

### 1. Set up mTLS (CA)

Kanaha uses mutual TLS. You run a small private CA; **its key never leaves your
machine and is never shipped in the app.** The device generates its own server
key on-device (below) — you only ever sign a CSR.

```bash
mkdir -p ~/kanaha-certs && cd ~/kanaha-certs
openssl genrsa -out ca.key 4096
openssl req -new -x509 -days 3650 -key ca.key -out ca.crt -subj "/CN=Kanaha CA"

# A client certificate for your control station (curl / MCP):
openssl genrsa -out client.key 2048
openssl req -new -key client.key -out client.csr -subj "/CN=kanaha-control"
openssl x509 -req -days 365 -in client.csr -CA ca.crt -CAkey ca.key \
  -CAcreateserial -out client.crt \
  -extfile <(printf "extendedKeyUsage=clientAuth")
```

### 2. Provision the device

On first run the app **mints its own private key and a CSR** (the key never
leaves the device) and refuses to serve until you sign that CSR with your CA and
push the signed cert back:

```bash
# After installing + launching the app once (it writes files/csr/camera.csr):
adb shell "run-as org.kanaha.camera cat files/csr/camera.csr" > camera.csr

# Sign it with your CA (add the phone's IP / mDNS name as SANs):
openssl x509 -req -days 365 -in camera.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out camera.crt \
  -extfile <(printf "subjectAltName=IP:<phone-ip>,DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth,clientAuth")

# Push the signed cert + CA back into the app, then restart it:
adb push camera.crt /data/local/tmp/server.crt && adb push ca.crt /data/local/tmp/ca.crt
adb shell "run-as org.kanaha.camera sh -c 'cp /data/local/tmp/server.crt files/apache/ssl/server.crt && cp /data/local/tmp/ca.crt files/apache/ssl/ca.crt'"
```

The server now starts with mTLS. Provisioning is durable — redo it only after a
fresh install / data clear.

### 3. Start the Camera

Launch Kanaha on your Android device. The camera preview starts automatically and the HTTP/2 server begins listening on port 8443.

### 4. Find Your Camera

```bash
# Via mDNS (Linux)
avahi-browse -rt _https._tcp | grep kanaha

# Or check the device's IP in Android Settings → Network
```

### 5. Control via API

All API calls require mTLS client certificates:

```bash
# Set certificate paths (adjust to your cert location)
SSL=~/kanaha-certs
CAMERA="192.168.1.100"

# Convenience alias used in all examples below
CURL="curl -sk --http2 --cert $SSL/client.crt --key $SSL/client.key --cacert $SSL/ca.crt"
```

#### getStatus

Returns camera state, battery, storage, timestamp, and GPS fix (when available).

```bash
$CURL "https://$CAMERA:8443/services/CameraControlService/getStatus"
```

Response fields:
```json
{
  "success": true,
  "state": "IDLE",
  "is_recording": false,
  "battery_level": 87,
  "storage_available_mb": 12400,
  "timestamp": 1772039424000,
  "gps_time": 1772039423850,
  "gps_age_ms": 150,
  "gps_provider": "gps"
}
```

- `timestamp` — camera's `System.currentTimeMillis()` at response time (ms since epoch). Disciplined by GPS when a fix is active, otherwise by NTP/network.
- `gps_time` — time of the most recent GPS fix (`location.getTime()`), in ms since epoch. Only present when GPS location is available.
- `gps_age_ms` — milliseconds since the GPS fix was obtained. Use this to judge whether the camera's clock is GPS-disciplined.
- `gps_provider` — location provider name (e.g. `"gps"`, `"network"`).

#### startRecording

```bash
# Minimal — start immediately with auto-generated clip name
$CURL -H "Content-Type: application/json" \
  -d '{"action":"startRecording"}' \
  "https://$CAMERA:8443/services/CameraControlService/startRecording"

# With clip name
$CURL -H "Content-Type: application/json" \
  -d '{"action":"startRecording","clip_name":"my_clip"}' \
  "https://$CAMERA:8443/services/CameraControlService/startRecording"

# Scheduled start — all cameras fire at the same wall-clock millisecond
# Compute start_at = 3 seconds from now (laptop clock, ms since epoch)
START_AT=$(( $(date +%s%3N) + 3000 ))

$CURL -H "Content-Type: application/json" \
  -d "{\"action\":\"startRecording\",\"clip_name\":\"sync_test\",\"start_at\":$START_AT}" \
  "https://$CAMERA:8443/services/CameraControlService/startRecording"
```

Request parameters:
- `clip_name` *(optional)* — prefix for the output filename. Default: auto-generated.
- `quality` *(optional)* — video quality hint (e.g. `"high"`, `"low"`). Default: app setting.
- `duration` *(optional)* — recording duration in seconds. `0` = record until stopRecording.
- `format` *(optional)* — container format. Default: `"MP4"`.
- `open_gate` *(optional)* — `true` to record at the camera's native 4:3 sensor resolution (2560×1920 on Pixel 9 Pro) with no crop. The call blocks 3–5 s while the camera session reopens. Moto G phones ignore this flag and record normally. Default: `false`. See [Open Gate Recording](docs/OPENGATE.md).
- `start_at` *(optional)* — UTC epoch milliseconds at which recording should begin. When provided, the camera schedules the start via `Handler.postDelayed()` and returns immediately with `"scheduled": true`. The camera fires at the specified wall-clock time regardless of when the request arrived. Valid range: 50 ms to 30 000 ms in the future. If zero or omitted, recording starts immediately.

Scheduled start response (when `start_at` is provided and valid):
```json
{
  "success": true,
  "scheduled": true,
  "clip_name": "sync_test",
  "start_at": 1772039427000,
  "delay_ms": 2837,
  "timestamp": 1772039424163,
  "operation_id": "abc123"
}
```

#### stopRecording

```bash
$CURL -H "Content-Type: application/json" \
  -d '{"action":"stopRecording"}' \
  "https://$CAMERA:8443/services/CameraControlService/stopRecording"
```

#### listFiles

```bash
$CURL "https://$CAMERA:8443/services/CameraControlService/listFiles"
```

#### deleteFiles

```bash
$CURL -H "Content-Type: application/json" \
  -d '{"action":"deleteFiles","pattern":"VID_20260225*.mp4"}' \
  "https://$CAMERA:8443/services/CameraControlService/deleteFiles"
```

#### sftpTransfer

Pushes files from the camera to a remote host via SFTP. Requires SSH key setup — see [SFTP File Transfer](docs/SFTP-FILE-TRANSFER.md).

```bash
$CURL -H "Content-Type: application/json" \
  -d '{"action":"sftpTransfer","storage_server_id":"control","video_filename":"VID_20260225*.mp4","destination_folder":"/tmp/pixel9pro"}' \
  "https://$CAMERA:8443/services/CameraControlService/sftpTransfer"
```

#### playTone

Plays a synthesized sine wave through the device speaker. Used as a software sync slate — all cameras play the same tone at the same scheduled moment; onset detection in post gives ~1–5 ms inter-camera sync with no hardware.

```bash
# Play immediately — 1 kHz for 500 ms
$CURL -H "Content-Type: application/json" \
  -d '{"action":"playTone","frequency":1000,"duration_ms":500}' \
  "https://$CAMERA:8443/services/CameraControlService/playTone"

# Scheduled — all cameras play at the same wall-clock millisecond
START_AT=$(( $(date +%s%3N) + 3000 ))

for cam in $PIXEL $MOTOG $MOTOG5G; do
  $CURL -H "Content-Type: application/json" \
    -d "{\"action\":\"playTone\",\"frequency\":1000,\"duration_ms\":500,\"start_at\":$START_AT}" \
    "https://$cam:8443/services/CameraControlService/playTone" &
done
# Also play on laptop speaker at the same moment
sleep 2.9 && ffplay -nodisp -autoexit -f lavfi -i "sine=frequency=1000:duration=0.5" 2>/dev/null
wait
```

Request parameters:
- `frequency` *(optional)* — tone frequency in Hz. Default: `1000`. Valid range: 20–20000.
- `duration_ms` *(optional)* — duration in milliseconds. Default: `500`. Valid range: 10–5000.
- `start_at` *(optional)* — UTC epoch milliseconds at which to play. Same mechanism as `startRecording`. If omitted, plays immediately.

The tone is synthesized via `AudioTrack MODE_STATIC` with 5 ms linear fades to prevent click artefacts. Its onset in the recorded audio can be detected to ±1–5 ms accuracy using `ffmpeg`'s bandpass + silencedetect pipeline (see `parseWithoutLTC.sh`).

The workflow script `test-triple-camera-workflow.sh` exposes this as `play_slate_all()`, which automatically writes the `start_at` value to `/tmp/kanaha_slate_at.txt` for `parseWithoutLTC.sh` to read.

#### MCP Tool Discovery

Query the MCP tool catalog to see all available camera operations and their
parameter schemas — this is what Claude Desktop reads to discover your camera:

```bash
# Via the MCP stdio binary on the phone (over ADB)
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | \
  adb shell run-as org.kanaha.camera ./files/kanaha-camera-mcp
```

Response (10 tools with full inputSchema):
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "tools": [
      {"name": "getStatus",      "description": "Get camera status: battery, storage, GPS...",
       "inputSchema": {"type": "object", "properties": {}, "required": []}},
      {"name": "startRecording", "description": "Start video recording. Supports scheduled start...",
       "inputSchema": {"type": "object", "properties": {
         "clip_name": {"type": "string"}, "quality": {"type": "string"},
         "start_at": {"type": "integer"}, "open_gate": {"type": "boolean"}}, "required": []}},
      {"name": "stopRecording",  "description": "Stop the current video recording immediately.", "...": "..."},
      {"name": "playTone",       "description": "Play sync slate tone for multi-camera alignment.", "...": "..."},
      {"name": "listFiles",      "description": "List recorded video files on the device.", "...": "..."},
      {"name": "deleteFiles",    "description": "Delete files matching a glob pattern.", "...": "..."},
      {"name": "sftpTransfer",   "description": "Transfer file via SFTP with Ed25519 SSH key auth.", "...": "..."},
      {"name": "configure",      "description": "Set camera resolution, fps, codec.", "...": "..."},
      {"name": "cleanupFiles",   "description": "Clean up transferred files.", "...": "..."},
      {"name": "describeClip",   "description": "Describe a recording with the on-device model (nano flavor).", "...": "..."}
    ]
  }
}
```

Claude reads this schema and constructs valid requests from natural language —
no documentation, no curl syntax, no certificate management needed by the user.
See [MCP Documentation](docs/MCP.md) for the full tool catalog with complete
`inputSchema` definitions.

#### Workflow Scripts

For complete record-transfer-cleanup workflows, use the included test scripts:

```bash
cd kanaha-camera-app

# Single camera: record 10 seconds, transfer to /tmp, delete from camera
./test-single-camera-workflow.sh workflow --duration 10

# Dual camera: simultaneous recording on Pixel 9 Pro and Moto X4
./test-dual-camera-workflow.sh --duration 10

# Single camera commands
./test-single-camera-workflow.sh status      # Get camera status
./test-single-camera-workflow.sh record      # Start recording
./test-single-camera-workflow.sh stop        # Stop recording
./test-single-camera-workflow.sh list        # List video files
```

See [mTLS Setup Guide](docs/MULTI_CAMERA_DEPLOYMENT_SYSTEM.md#mtls-certificate-architecture) for certificate management and [SFTP Setup](docs/SFTP-FILE-TRANSFER.md) for SSH key configuration.

## MCP (AI Assistant Integration)

Kanaha supports [Model Context Protocol](https://modelcontextprotocol.io/) (MCP),
enabling Claude Desktop and other AI assistants to discover and control cameras
as tools. The MCP server is a 117 KB native binary — no JVM, no Python, sub-50ms
startup.

See [MCP Documentation](docs/MCP.md) for setup, tool catalog, and live examples
tested on a Pixel 9 Pro.

## API Reference

All endpoints are under `/services/CameraControlService/`. All requests require mTLS client certificates.
Every curl command below has an MCP equivalent — see [MCP docs](docs/MCP.md) for
the JSON-RPC 2.0 format.

The URL decides which operation runs. The `"action"` field the examples below
send is optional on this path and, when present, must name the same operation
as the URL; a request that names a different one is refused. MCP clients send
no URL, so for them `"action"` is what selects the tool.

| Endpoint | Method | Key Parameters | Description |
|----------|--------|----------------|-------------|
| `/getStatus` | GET/POST | — | Camera state, battery, storage, `timestamp`, `gps_time`, `gps_age_ms` |
| `/startRecording` | POST | `clip_name`, `start_at`, `quality`, `duration`, `format`, `open_gate` | Begin recording; `open_gate: true` records full 4:3 sensor on Pixel 9 Pro; supports scheduled start via `start_at` (UTC epoch ms) |
| `/stopRecording` | POST | — | Stop active recording |
| `/listFiles` | GET/POST | — | List recorded video files with sizes and timestamps |
| `/deleteFiles` | POST | `pattern` | Delete files matching glob pattern |
| `/sftpTransfer` | POST | `storage_server_id`, `video_filename`, `destination_folder` | Push files to remote host via SFTP |
| `/describeClip` | POST | `video_filename`, `frame_count`, `positions`, `write_sidecar`, `max_dimension` | Describe a recording with the on-device model (Gemini Nano, `nano` flavor only); writes `<basename>.kanaha.json` |
| `/playTone` | POST | `frequency`, `duration_ms`, `start_at` | Play synthesized sine wave for software sync slate |

**`start_at` parameter** (on `startRecording` and `playTone`): Pass a future UTC epoch millisecond timestamp. The camera schedules the action internally and returns immediately. Send to multiple cameras simultaneously — each fires at the same wall-clock time regardless of network delivery timing. See [Quick Start](#5-control-via-api) for curl examples.

**`kanaha_recording_start.json` sidecar**: Written automatically to `DCIM/OpenCamera/` when recording starts. Contains `recording_start_ms` (ms precision), `clip_name`, and GPS fix time if available. Transfer it alongside the video with `sftpTransfer` using `"video_filename":"kanaha_recording_start.json"`. `parseWithoutLTC.sh` reads this for sub-second trim offsets — the software equivalent of a BWF Time Reference.

## Multi-Camera Setup

Control multiple cameras simultaneously with mTLS:

```bash
SSL=~/kanaha-certs

# Scheduled simultaneous start — all cameras fire at the same wall-clock millisecond
# regardless of when the HTTP request arrives. Use start_at to eliminate WiFi RTT skew.
START_AT=$(( $(date +%s%3N) + 3000 ))   # 3 seconds from now

for cam in 192.168.1.{100,101,102}; do
  curl -s --http2 \
    --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
    -H "Content-Type: application/json" \
    -d "{\"action\":\"startRecording\",\"clip_name\":\"sync_test\",\"start_at\":$START_AT}" \
    "https://$cam:8443/services/CameraControlService/startRecording" &
done
wait
echo "All cameras scheduled — firing at $START_AT"
```

The same client certificate works for all cameras when they share a CA.

For three-camera workflows with automatic sync slate, SFTP transfer, and post-processing, use `test-triple-camera-workflow.sh` — it handles address resolution, `start_at` scheduling, software slate (`play_slate_all`), file transfer including the sync sidecar, and cleanup in a single script.

See [GPS Synchronization](docs/GPS.md) for a full explanation of sync accuracy, the `start_at` architecture, software slating, and comparison with SMPTE/LTC hardware.

The IPC pipeline crosses three threading contexts — the C Apache/Axis2 worker thread, the Android main (UI) thread where `onReceive()` lands, and the background `KanahaCameraControl` thread that runs the handlers. See [Threading Model](docs/THREAD_MODEL.md) for the full model, JMM visibility rules, and guidance when adding new action handlers.

## Building from Source

For developers who want to modify the app or native code:

- [APK Build Guide](docs/ANDROID_APK_BUILDING.md) - Building the Android app
- [Cross-Compilation Guide](docs/ANDROID_CROSS_COMPILATION.md) - Building native libraries
- [System Architecture](docs/MULTI_CAMERA_DEPLOYMENT_SYSTEM.md) - Complete system documentation

### Quick Build

```bash
cd kanaha-camera-app

# Both flavours at once
./gradlew assembleDebug
# foss (distributable):  app/build/outputs/apk/foss/debug/app-foss-debug.apk
# nano (private use):    app/build/outputs/apk/nano/debug/app-nano-debug.apk

# Or one at a time
./gradlew assembleFossDebug
./gradlew assembleNanoDebug
```

The `foss` flavour is the default and the only one to distribute. The `nano`
flavour adds Google's proprietary on-device description client, which cannot be
shipped under GPL v3; see [On-Device Clip Description](docs/GOOGLE_NANO_INTEGRATION.md).

## Requirements

- **Android**: 6.0+ (API 23+) for the `foss` build, 8.0+ (API 26+) for `nano`; ARM64 device
- **Permissions**: Camera, Microphone, Storage, Network
- **Network**: WiFi connection (same network as control station)

## Security

Kanaha uses multiple layers of security:

- **mTLS Authentication** - Client certificates required for all API access
- **TLS 1.2/1.3** - Encrypted communications with modern cipher suites
- **Certificate Validation** - Server verifies client certificates against CA
- **No Default Credentials** - Users must generate their own certificate chain

See [Security Documentation](docs/SECURITY.md) for threat model, certificate management, and security hardening.

## Documentation

| Document | Description |
|----------|-------------|
| [Security Guide](docs/SECURITY.md) | Threat model, certificate management, hardening |
| [Multi-Camera Deployment](docs/MULTI_CAMERA_DEPLOYMENT_SYSTEM.md) | Complete system guide, mTLS setup, API reference |
| [SFTP File Transfer](docs/SFTP-FILE-TRANSFER.md) | SSH key setup for secure file retrieval |
| [Open Gate Recording](docs/OPENGATE.md) | Full 4:3 sensor recording, device support, DaVinci Resolve workflow, LUT grading, C layer build process |
| [APK Building](docs/ANDROID_APK_BUILDING.md) | Compiling from source |
| [Cross-Compilation](docs/ANDROID_CROSS_COMPILATION.md) | Building native C libraries |
| [SMPTE Timecode Setup](docs/IRIG_PRO_SMPTE_TIMECODE_SETUP.md) | iRig Pro I/O + Tentacle Sync hardware timecode setup |
| [GPS Synchronization](docs/GPS.md) | GPS/NTP soft sync, `start_at` scheduled recording, software slate (`playTone`), sync sidecar — vs. SMPTE/LTC for consumer and security use cases |
| [Threading Model](docs/THREAD_MODEL.md) | IPC pipeline threading: C Apache/Axis2 worker → Android UI thread → background handler; JMM visibility rules, `CountDownLatch` patterns |
| [MCP (AI Assistant)](docs/MCP.md) | Model Context Protocol integration — 10 camera tools, Claude Desktop config, live Pixel 9 Pro examples |
| [On-Device Clip Description](docs/GOOGLE_NANO_INTEGRATION.md) | `describeClip`: per-clip descriptions from Gemini Nano via ML Kit, the `.kanaha.json` sidecar, the `foss`/`nano` build flavors, and why the `nano` build is private-use only under GPL v3 |
| [C and Java Design](docs/CPP_AND_JAVA_DESIGN.md) | Where Java starts and ends in this app, the Intent seam, and the licence boundary between OpenCamera (GPL) and the Axis2/C service (Apache 2.0) |
| [Why Not the NDK Camera API](docs/NDK_INSTEAD_OF_OPENCAMERA_REJECTED.md) | The case for replacing OpenCamera with NDK camera code, and why fifteen years of device coverage won the argument |
| [Legal Review](docs/LEGAL.md) | License compatibility analysis for Apache httpd, Axis2/C, OpenCamera (GPL v3+), and the proprietary ML Kit client (`nano` flavor only) |

## Architecture

```
Control Station                    Android Device
     |                                  |
     |  HTTPS/HTTP2 + mTLS             |
     | ─────────────────────────────►  |
     |                            [Apache httpd]
     |                                  |
     |                            [Axis2/C JSON-RPC]
     |                                  |
     |                            [CameraControlService]
     |                                  |
     |                            [OpenCamera Engine]
     |                                  |
     |  JSON Response                   |
     | ◄─────────────────────────────  |
```

## Notes

<sup>1</sup> **SMPTE timecode recording** requires a USB audio interface (iRig Pro I/O) which needs ~500mA of USB power. Phones from ~2021+ (Pixel 6 and later) provide sufficient USB-C power and work with a direct connection. Older phones (pre-2020) lack sufficient USB OTG power for the audio interface to enumerate, making them unsuitable for timecode recording. Camera control, video recording, and all other Kanaha features work identically on all supported devices. See [iRig SMPTE Timecode Setup](docs/IRIG_PRO_SMPTE_TIMECODE_SETUP.md) for details.

## License

GPL v3+ (GNU General Public License version 3 or later)

This license is required because Kanaha incorporates OpenCamera, which is GPL v3+ licensed.

The optional `nano` build flavor links Google's proprietary ML Kit client for
`describeClip`. A GPL program may not be distributed with a proprietary library
compiled into it, so `nano` builds are for private use only and are never
published; the default `foss` flavor is the distributable one. See
[docs/GOOGLE_NANO_INTEGRATION.md](docs/GOOGLE_NANO_INTEGRATION.md).

## Acknowledgments

- [OpenCamera](https://opencamera.org.uk/) - Camera engine
- [Apache Axis2/C](https://axis.apache.org/axis2/c/) - Web services framework
- [Apache httpd](https://httpd.apache.org/) - HTTP/2 server
