# Kanaha v1.0.0

Initial public release of Kanaha Camera Control.

## Download

| File | SHA256 |
|------|--------|
| [kanaha-v1.0.0-arm64.apk](link) | `97165a381427226ad59e6d1d1bf634b1fa6f0b65300e58ebb370eca453b39d85` |

## Features

### Camera Control API
- `getStatus` - Query camera state, battery level, storage
- `startRecording` / `stopRecording` - Video recording control
- `listFiles` - List recorded videos
- `deleteFiles` - Remove video files
- `sftpTransfer` - Secure file transfer to remote server

### Security
- HTTP/2 with TLS 1.2/1.3
- Mutual TLS (mTLS) certificate authentication
- SFTP with SSH key authentication for file transfers

### Network
- mDNS service discovery (`_https._tcp`)
- Configurable port (default 8443)
- Works over WiFi and ADB port forwarding

## Compatibility

| Device | Android | Status |
|--------|---------|--------|
| Google Pixel 9 Pro | 15 | Tested |
| Motorola Moto X4 | 9 | Tested |
| Any ARM64 device | 5.0+ (API 21+) | Expected to work |

## Installation

1. Download `kanaha-v1.0.0-arm64.apk`
2. Enable "Install from unknown sources" in Android Settings
3. Install the APK
4. Grant permissions: Camera, Microphone, Storage, Location

## Quick Test

```bash
# Replace with your device's IP
CAMERA="192.168.1.100"

# Get status
curl -sk "https://$CAMERA:8443/services/CameraControlService/getStatus"

# Start recording
curl -sk -X POST -H "Content-Type: application/json" \
  -d '{"action":"startRecording"}' \
  "https://$CAMERA:8443/services/CameraControlService/startRecording"
```

## Known Limitations

- Single-process Apache mode (required for Android)
- HTTP/2 limited to 1 stream per connection for stability
- No Google Play Store distribution (sideload only)

## Documentation

See [docs/](docs/) for complete documentation:
- [Multi-Camera Deployment Guide](docs/MULTI_CAMERA_DEPLOYMENT_SYSTEM.md)
- [SFTP File Transfer Setup](docs/SFTP-FILE-TRANSFER.md)
- [Building from Source](docs/ANDROID_APK_BUILDING.md)
