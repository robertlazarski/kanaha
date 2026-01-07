# Kanaha Camera Control

Kanaha transforms Android phones into network-controllable cameras with a secure HTTP/2 API. Control multiple cameras simultaneously from any device using standard HTTPS requests with mutual TLS authentication.

**Built in C for Performance:** The core server (Apache httpd + Axis2/C) is written entirely in C, delivering native execution speed with minimal memory footprint. This enables Kanaha to run efficiently on devices spanning 7+ years of Android hardware—from a 2017 Moto X4 to a 2024 Pixel 9 Pro—with identical functionality.

## Features

- **Multi-Camera Control** - Start/stop recording on multiple phones simultaneously
- **HTTP/2 + mTLS Security** - Enterprise-grade encryption with certificate authentication
- **Wide Device Support** - Same APK works on Android 5.0+ devices (tested 2017 Moto X4 through 2024 Pixel 9 Pro)
- **SFTP File Transfer** - Secure file retrieval with SSH key authentication
- **mDNS Discovery** - Automatic camera discovery on local network
- **Built in C** - Native Apache httpd + Axis2/C for low latency and minimal memory footprint

## Installation

### Download Pre-built APK

1. Download the latest APK from [Releases](../../releases)
2. On your Android device: **Settings → Security → Enable "Install from unknown sources"**
3. Open the downloaded APK to install
4. Grant permissions when prompted: Camera, Microphone, Storage, Location

### Verify Download (Optional)

```bash
# Verify SHA256 checksum
sha256sum kanaha-v1.0.0-arm64.apk
# Compare with checksum in release notes
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

## License

GPL v3+ (GNU General Public License version 3 or later)

This license is required because Kanaha incorporates OpenCamera, which is GPL v3+ licensed.

## Acknowledgments

- [OpenCamera](https://opencamera.org.uk/) - Camera engine
- [Apache Axis2/C](https://axis.apache.org/axis2/c/) - Web services framework
- [Apache httpd](https://httpd.apache.org/) - HTTP/2 server
