# Kanaha Camera Control

Kanaha transforms Android phones into network-controllable cameras with a secure HTTP/2 API. Control multiple cameras simultaneously from any device using standard HTTPS requests with mutual TLS authentication.

**Built in C for Performance:** The core server (Apache httpd + Axis2/C) is written entirely in C, delivering native execution speed with minimal memory footprint. This enables Kanaha to run efficiently on devices spanning 7+ years of Android hardware—from a 2017 Moto X4 to a 2024 Pixel 9 Pro—with identical functionality.

## Features

- **Multi-Camera Control** - Start/stop recording on multiple phones simultaneously
- **HTTP/2 + mTLS Security** - Enterprise-grade encryption with certificate authentication
- **Wide Device Support** - Same APK works on Android 5.0+ devices (tested 2017 Moto X4 through 2024 Pixel 9 Pro) [<sup>1</sup>](#notes)
- **SFTP File Transfer** - Secure file retrieval with SSH key authentication
- **mDNS Discovery** - Automatic camera discovery on local network
- **Synchronized Start** - `start_at` parameter fires all cameras at the same UTC millisecond, independent of network delivery timing
- **Software Sync Slate** - `playTone` API plays a synthesized sine wave on all cameras + laptop simultaneously; onset detection in post gives ~1–5 ms inter-camera sync with no hardware
- **GPS Timestamping** - `getStatus` exposes GPS fix time and age for clock quality assessment
- **Recording Start Sidecar** - Writes `kanaha_recording_start.json` at recording start (millisecond precision, GPS time); the post-processing analog of a BWF Time Reference
- **Open Gate Recording** - Full 4:3 native sensor recording on supported devices (2560×1920 on Pixel 9 Pro) with no horizontal or vertical crop; see [Open Gate Recording](docs/OPENGATE.md)
- **Built in C** - Native Apache httpd + Axis2/C for low latency and minimal memory footprint

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

From a computer on the same WiFi network (requires [certificates](#1-generate-certificates)):

```bash
curl -sk https://<phone-ip>:8443/services/CameraControlService/getStatus \
  --cert client.crt --key client.key --cacert ca.crt
```

## Quick Start

### 1. Generate Certificates

Kanaha requires mutual TLS (mTLS) - both server and client authenticate with certificates:

```bash
# Create certificate directory
mkdir -p ~/kanaha-certs && cd ~/kanaha-certs

# Generate CA (Certificate Authority)
openssl genrsa -out ca.key 4096
openssl req -new -x509 -days 3650 -key ca.key -out ca.crt \
  -subj "/CN=Kanaha CA"

# Generate server certificate (for the Android device)
openssl genrsa -out server.key 2048
openssl req -new -key server.key -out server.csr -subj "/CN=kanaha-camera"
openssl x509 -req -days 365 -in server.csr -CA ca.crt -CAkey ca.key \
  -CAcreateserial -out server.crt

# Generate client certificate (for your control station)
openssl genrsa -out client.key 2048
openssl req -new -key client.key -out client.csr -subj "/CN=kanaha-control"
openssl x509 -req -days 365 -in client.csr -CA ca.crt -CAkey ca.key \
  -CAcreateserial -out client.crt
```

### 2. Deploy Certificates to Phone

Copy certificates to the Android device:

```bash
adb push ca.crt server.crt server.key /sdcard/Download/

# Or use the Kanaha app: Settings → Security → Import Certificates
```

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
as tools. The MCP server is a 98 KB native binary — no JVM, no Python, sub-50ms
startup.

See [MCP Documentation](docs/MCP.md) for setup, tool catalog, and live examples
tested on a Pixel 9 Pro.

## API Reference

All endpoints are under `/services/CameraControlService/`. All requests require mTLS client certificates.
Every curl command below has an MCP equivalent — see [MCP docs](docs/MCP.md) for
the JSON-RPC 2.0 format.

| Endpoint | Method | Key Parameters | Description |
|----------|--------|----------------|-------------|
| `/getStatus` | GET/POST | — | Camera state, battery, storage, `timestamp`, `gps_time`, `gps_age_ms` |
| `/startRecording` | POST | `clip_name`, `start_at`, `quality`, `duration`, `format`, `open_gate` | Begin recording; `open_gate: true` records full 4:3 sensor on Pixel 9 Pro; supports scheduled start via `start_at` (UTC epoch ms) |
| `/stopRecording` | POST | — | Stop active recording |
| `/listFiles` | GET/POST | — | List recorded video files with sizes and timestamps |
| `/deleteFiles` | POST | `pattern` | Delete files matching glob pattern |
| `/sftpTransfer` | POST | `storage_server_id`, `video_filename`, `destination_folder` | Push files to remote host via SFTP |
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
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Requirements

- **Android**: 5.0+ (API 21+), ARM64 device
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
| [Legal Review](docs/LEGAL.md) | License compatibility analysis for Apache httpd, Axis2/C, OpenCamera (GPL v3+) |

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

## Acknowledgments

- [OpenCamera](https://opencamera.org.uk/) - Camera engine
- [Apache Axis2/C](https://axis.apache.org/axis2/c/) - Web services framework
- [Apache httpd](https://httpd.apache.org/) - HTTP/2 server
