# Kanaha Camera Control

Kanaha transforms Android phones into network-controllable cameras with a secure HTTP/2 API. Control multiple cameras simultaneously from any device using standard HTTPS requests with mutual TLS authentication.

**Built in C for Performance:** The core server (Apache httpd + Axis2/C) is written entirely in C, delivering native execution speed with minimal memory footprint. This enables Kanaha to run efficiently on devices spanning 7+ years of Android hardware—from a 2017 Moto X4 to a 2024 Pixel 9 Pro—with identical functionality.

## Features

- **Multi-Camera Control** - Start/stop recording on multiple phones simultaneously
- **HTTP/2 + mTLS Security** - Enterprise-grade encryption with certificate authentication
- **Wide Device Support** - Same APK works on Android 5.0+ devices (tested 2017 Moto X4 through 2024 Pixel 9 Pro) [<sup>1</sup>](#notes)
- **SFTP File Transfer** - Secure file retrieval with SSH key authentication
- **mDNS Discovery** - Automatic camera discovery on local network
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
# Set certificate paths
SSL=~/kanaha-certs
CAMERA="192.168.1.100"

# Get camera status
curl -s --http2 \
  --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
  "https://$CAMERA:8443/services/CameraControlService/getStatus"

# Start recording
curl -s --http2 \
  --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
  -H "Content-Type: application/json" -d '{"action":"startRecording"}' \
  "https://$CAMERA:8443/services/CameraControlService/startRecording"

# Stop recording
curl -s --http2 \
  --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
  -H "Content-Type: application/json" -d '{"action":"stopRecording"}' \
  "https://$CAMERA:8443/services/CameraControlService/stopRecording"

# List video files
curl -s --http2 \
  --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
  "https://$CAMERA:8443/services/CameraControlService/listFiles"

# Delete specific files
curl -s --http2 \
  --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
  -H "Content-Type: application/json" \
  -d '{"action":"deleteFiles","filenames":["VID_20260107_120000.mp4"]}' \
  "https://$CAMERA:8443/services/CameraControlService/deleteFiles"

# Transfer files via SFTP (requires SSH key setup on camera)
curl -s --http2 \
  --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
  -H "Content-Type: application/json" \
  -d '{"action":"sftpTransfer","storage_server_id":"control","video_filename":"VID_20260107_120000.mp4","destination_folder":"/tmp"}' \
  "https://$CAMERA:8443/services/CameraControlService/sftpTransfer"
```

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

## API Reference

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/getStatus` | GET/POST | Camera status, battery, storage |
| `/startRecording` | POST | Begin video recording |
| `/stopRecording` | POST | Stop video recording |
| `/listFiles` | GET/POST | List recorded video files |
| `/deleteFiles` | POST | Delete specified files |
| `/sftpTransfer` | POST | Transfer files via SFTP |

All endpoints are under `/services/CameraControlService/`.

## Multi-Camera Setup

Control multiple cameras simultaneously with mTLS:

```bash
SSL=~/kanaha-certs

# Start recording on 3 cameras at once
for cam in 192.168.1.{100,101,102}; do
  curl -s --http2 \
    --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
    -H "Content-Type: application/json" -d '{"action":"startRecording"}' \
    "https://$cam:8443/services/CameraControlService/startRecording" &
done
wait
echo "All cameras recording"
```

The same client certificate works for all cameras when they share a CA.

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
| [APK Building](docs/ANDROID_APK_BUILDING.md) | Compiling from source |
| [Cross-Compilation](docs/ANDROID_CROSS_COMPILATION.md) | Building native C libraries |

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
