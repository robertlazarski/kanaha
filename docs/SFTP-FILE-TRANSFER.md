# SFTP File Transfer - Kanaha Camera Control System

## Overview

The Kanaha Camera Control System provides secure SFTP file transfer capabilities for
automatically uploading recorded video files from Android camera devices to storage
servers. This enables multi-camera production workflows where footage is centrally
collected without manual intervention.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Control Station (Linux/macOS)                       │
│  ┌─────────────────┐                                                        │
│  │   curl/client   │ ─── HTTP/2+mTLS ──┐                                    │
│  └─────────────────┘                   │                                    │
│                                        │                                    │
│  ┌─────────────────┐                   │                                    │
│  │   SSH Server    │ ◄── SFTP ─────────┼────────┐                           │
│  │  (openssh-server)                   │        │                           │
│  └─────────────────┘                   │        │                           │
└────────────────────────────────────────┼────────┼───────────────────────────┘
                                         │        │
                               ┌─────────▼────────▼─────────┐
                               │     Android Device         │
                               │  ┌───────────────────────┐ │
                               │  │  Apache httpd+Axis2/C │ │ ◄── HTTPS :8443
                               │  │  (CameraControlService)│ │
                               │  └───────────┬───────────┘ │
                               │              │ Intent IPC  │
                               │  ┌───────────▼───────────┐ │
                               │  │ CameraControlReceiver │ │
                               │  │ (Java BroadcastReceiver)│ │
                               │  └───────────┬───────────┘ │
                               │              │ JSch/SFTP   │
                               │  ┌───────────▼───────────┐ │
                               │  │    SSH Client         │ │ ──► SFTP :22
                               │  │ (ed25519 PKI auth)    │ │
                               │  └───────────────────────┘ │
                               └───────────────────────────┘
```

## Security Model

### Authentication Layers

1. **HTTP/2+mTLS** - Client certificates required for API access
2. **SSH PKI** - ed25519 key-based authentication (no passwords)
3. **known_hosts** - Server fingerprint verification

### No Passwords

The system is designed for automated operation with no password prompts:
- API access uses client certificates (mTLS)
- SFTP uses SSH key authentication (ed25519 preferred)

## Setup

### 1. Generate SSH Key Pair

```bash
# Generate ed25519 key for SFTP authentication
ssh-keygen -t ed25519 -f control1.key -C "kanaha-camera-control" -N ""
```

This creates:
- `control1.key` - Private key (stays on camera device)
- `control1.key.pub` - Public key (add to server's authorized_keys)

### 2. Configure Storage Server

```bash
# Add camera's public key to server's authorized_keys
cat control1.key.pub >> ~/.ssh/authorized_keys

# Get server fingerprint for known_hosts
ssh-keyscan your-server.local > known_hosts
```

### 3. Create Server Configuration

Create `servers.json`:
```json
{
  "control1": {
    "host": "your-server.local",
    "port": 22,
    "username": "your-username"
  },
  "production": {
    "host": "storage.lan",
    "port": 22,
    "username": "kanaha_camera"
  }
}
```

### 4. Deploy to Android Device

```bash
# Create SSH directory structure
adb shell "run-as org.kanaha.camera mkdir -p /data/user/0/org.kanaha.camera/files/ssh/keys"

# Push private key
adb push control1.key /data/local/tmp/
adb shell "run-as org.kanaha.camera cp /data/local/tmp/control1.key /data/user/0/org.kanaha.camera/files/ssh/keys/"
adb shell "run-as org.kanaha.camera chmod 600 /data/user/0/org.kanaha.camera/files/ssh/keys/control1.key"

# Push server configuration
adb push servers.json /data/local/tmp/
adb shell "run-as org.kanaha.camera cp /data/local/tmp/servers.json /data/user/0/org.kanaha.camera/files/ssh/"

# Push known_hosts
adb push known_hosts /data/local/tmp/
adb shell "run-as org.kanaha.camera cp /data/local/tmp/known_hosts /data/user/0/org.kanaha.camera/files/ssh/"
```

### Directory Structure on Device

```
/data/user/0/org.kanaha.camera/files/ssh/
├── servers.json              # Server configuration
├── known_hosts               # Verified server fingerprints
└── keys/
    ├── control1.key          # Private key for 'control1' server
    └── production.key        # Private key for 'production' server
```

## API Usage

### SFTP Transfer Endpoint

**URL:** `POST /services/CameraControlService/sftpTransfer`

**Request:**
```json
{
  "action": "sftpTransfer",
  "storage_server_id": "control1",
  "video_filename": "test.mp4",
  "destination_folder": "/tmp"
}
```

**Parameters:**
- `action` - Must be `"sftpTransfer"` (required)
- `storage_server_id` - Server ID from servers.json (required)
- `video_filename` - File name or pattern to transfer (required)
  - Specific file: `"video_001.mp4"`
  - All videos: `"*"`
  - Pattern: `"*.mp4"`
- `destination_folder` - Remote directory path (required)

**Response (Success):**
```json
{
  "success": true,
  "operation_id": "op_1767534526_261452072",
  "files_transferred": 1,
  "bytes_transferred": 10240,
  "timestamp": 1767534529688,
  "message": "Transferred 1 file(s), 10240 bytes"
}
```

**Response (Error):**
```json
{
  "success": false,
  "operation_id": "op_1767534357_999066247",
  "error": "SSH connection failed: UnknownHostKey",
  "timestamp": 1767534358192
}
```

### Example with curl

```bash
SSL=/path/to/ssl/certs

curl -sk --http2 \
  --cert "$SSL/client.crt" \
  --key "$SSL/client.key" \
  --cacert "$SSL/ca.crt" \
  -H "Content-Type: application/json" \
  -d '{
    "action": "sftpTransfer",
    "storage_server_id": "control1",
    "video_filename": "test.mp4",
    "destination_folder": "/tmp"
  }' \
  https://camera-device:8443/services/CameraControlService/sftpTransfer
```

## Transfer Timeouts and Speed Limits

### Timeout Configuration

SFTP transfers have a **20-minute timeout** configured at all layers:

| Layer | Timeout | Configuration |
|-------|---------|---------------|
| Native C (polling) | 20 min | `max_attempts = 12000` (12000 × 100ms) |
| Java (Future.get) | 20 min | `future.get(1200, SECONDS)` |
| curl (client) | 20 min | `--max-time 1200` |

### WiFi Transfer Speed

Typical WiFi SFTP throughput is **~2-3 MB/s** due to SSH protocol overhead.

**Maximum transfer in 20-minute timeout: ~2.4-3.6 GB**

### Recording Size Estimates

| Resolution | Bitrate | 10 min | 20 min | Status |
|------------|---------|--------|--------|--------|
| 1080p 30fps | ~10-20 MB/min | 100-200 MB | 200-400 MB | OK |
| 1080p 60fps | ~15-30 MB/min | 150-300 MB | 300-600 MB | OK |
| 4K 30fps | ~40-80 MB/min | 400-800 MB | 800MB-1.6GB | OK |
| 4K 60fps | ~80-150 MB/min | 800MB-1.5GB | 1.6-3 GB | OK |

### Large File Alternatives

For files exceeding ~3 GB, use USB transfer instead:

```bash
# Direct USB transfer via ADB (faster than WiFi SFTP)
adb pull /storage/emulated/0/DCIM/OpenCamera/VID_*.mp4 /tmp/

# Or with specific device serial
adb -s <serial> pull /storage/emulated/0/DCIM/OpenCamera/*.mp4 /tmp/
```

## Video File Locations

The SFTP transfer looks for video files in these locations (in order):

1. **Internal videos directory** (always accessible):
   `/data/user/0/org.kanaha.camera/files/videos/`

2. **External movies directory** (app-specific):
   `/storage/emulated/0/Android/data/org.kanaha.camera/files/Movies/`

3. **OpenCamera directory** (shared storage):
   `/storage/emulated/0/DCIM/OpenCamera/`

For best reliability, place files to transfer in the internal videos directory.

## Implementation Notes

### Library Choice

The SFTP implementation uses [mwiede's JSch fork](https://github.com/mwiede/jsch):

```gradle
implementation 'com.github.mwiede:jsch:0.2.22'
```

**Why JSch (mwiede fork)?**
- Native ed25519/OpenSSH key format support
- Android-compatible (unlike Apache MINA SSHD)
- Actively maintained with security updates
- Full SSH2 protocol implementation

**Why not Apache MINA SSHD?**
Apache MINA SSHD was evaluated but requires `javax.management` (JMX) which is not
available on Android. The library is designed for server-side Java SE environments.

### Internal Intent IPC

The native Axis2/C layer communicates with Java via Intent broadcasts:

```
Native C (camera_control_service.c)
    │
    ├─ Parses JSON request
    ├─ Validates parameters
    └─ Sends Intent broadcast via `am broadcast`
         │
         ▼
Java (CameraControlReceiver.java)
    │
    ├─ Receives Intent
    ├─ Loads SSH configuration
    ├─ Performs SFTP transfer (background thread)
    └─ Writes response to file for native code to read
```

This architecture avoids JNI complexity and maintains clean separation between
the HTTP/2 server (native) and Android system services (Java).

## Troubleshooting

### Common Errors

**"SSH private key not found"**
```
SSH private key not found: /data/user/0/org.kanaha.camera/files/ssh/keys/control1.key
Generate key: ssh-keygen -t ed25519 -f control1.key -N ""
```
The private key file doesn't exist. Generate and deploy as shown in Setup.

**"Storage server not configured"**
The `storage_server_id` in the request doesn't match any entry in `servers.json`.

**"SSH connection failed: UnknownHostKey"**
The server's host key isn't in `known_hosts`. Run:
```bash
ssh-keyscan your-server.local > known_hosts
```

**"No files found matching"**
The specified video file doesn't exist in any of the video directories.

### Checking Logs

```bash
# View Android logs for SFTP operations
adb logcat -s KanahaCameraReceiver:*

# View Axis2 service logs
adb shell "run-as org.kanaha.camera cat /data/user/0/org.kanaha.camera/files/apache/logs/axis2.log"
```

## Security Model

### Dual-Layer Security: mTLS + SSH PKI

Kanaha implements **two independent PKI systems** for maximum security:

1. **mTLS PKI**: For HTTP/2 camera control API authentication
2. **SSH PKI**: For SFTP file transfer authentication

**Why Two PKI Systems?**
- **Different Protocols**: HTTP/2 uses TLS certificates, SFTP uses SSH keys
- **Defense in Depth**: If one PKI is compromised, the other remains secure
- **Principle of Least Privilege**: Camera control ≠ File transfer permissions
- **Audit Separation**: Different logs for API access vs file transfers

| Aspect | mTLS PKI (API Control) | SSH PKI (File Transfer) |
|--------|------------------------|-------------------------|
| **Purpose** | HTTP/2 camera control | SFTP file transfer |
| **Protocol** | TLS 1.3 certificates | SSH ed25519 keys |
| **Key Location** | `/data/.../apache/ssl/` | `/data/.../ssh/keys/` |
| **Validation** | X.509 certificate chain | SSH known_hosts fingerprints |

### Security-Hardened Approach

**Traditional ~/.ssh Problems:**
- ❌ World-readable: Often misconfigured with 644 permissions
- ❌ Shared Keys: Same key used for multiple purposes
- ❌ No Server Validation: StrictHostKeyChecking often disabled

**Kanaha Security-Hardened Approach:**
- ✅ App Sandboxed: Keys isolated in app-specific directory
- ✅ Purpose-Specific: Separate key per storage server
- ✅ Server Verification: Mandatory known_hosts validation
- ✅ Restricted Paths: Hardcoded safe paths only
- ✅ Batch Mode: No interactive prompts or password fallbacks

### Transfer Parameter Security

- **Fixed Local Directory**: Videos read only from app video directories
- **Predefined SSH Keys**: `/data/data/org.kanaha.camera/files/ssh/` (managed per server_id)
- **Whitelisted Storage Servers**: Configuration file maps server_id to hostname/port
- **File Type Validation**: Only video extensions allowed (.mp4, .mov, .mkv)

## Advanced SSH Configuration

For production deployments with multiple storage servers, you can create a full SSH config file.

### SSH Client Configuration

Create `/data/user/0/org.kanaha.camera/files/ssh/config`:

```ssh
# Security-hardened SSH configuration for SFTP transfers
Host control1
    HostName your-server.local
    User your-username
    Port 22
    IdentityFile /data/user/0/org.kanaha.camera/files/ssh/keys/control1.key
    IdentitiesOnly yes
    StrictHostKeyChecking yes
    UserKnownHostsFile /data/user/0/org.kanaha.camera/files/ssh/known_hosts
    ServerAliveInterval 60
    ServerAliveCountMax 3
    Compression yes

Host production
    HostName storage.production.lan
    User kanaha_camera
    Port 22
    IdentityFile /data/user/0/org.kanaha.camera/files/ssh/keys/production.key
    IdentitiesOnly yes
    StrictHostKeyChecking yes
    UserKnownHostsFile /data/user/0/org.kanaha.camera/files/ssh/known_hosts

Host backup
    HostName backup.lan
    User kanaha_backup
    Port 2222
    IdentityFile /data/user/0/org.kanaha.camera/files/ssh/keys/backup.key
    IdentitiesOnly yes
    StrictHostKeyChecking yes
    UserKnownHostsFile /data/user/0/org.kanaha.camera/files/ssh/known_hosts
```

### Full Directory Structure

```
/data/user/0/org.kanaha.camera/files/ssh/
├── servers.json              # Server configuration (simple mode)
├── config                    # SSH client configuration (advanced mode)
├── known_hosts               # Verified server fingerprints
└── keys/
    ├── control1.key          # Private key for 'control1' server
    ├── control1.key.pub      # Public key (for reference)
    ├── production.key        # Private key for 'production' server
    └── backup.key            # Private key for 'backup' server
```

### Multi-Server Key Generation

```bash
#!/bin/bash
# Generate SSH key pairs for each storage server

ADB="$HOME/Android/Sdk/platform-tools/adb"
SSH_DIR="/data/user/0/org.kanaha.camera/files/ssh"

# Create directory structure
$ADB shell "run-as org.kanaha.camera mkdir -p $SSH_DIR/keys"

# Generate keys locally
ssh-keygen -t ed25519 -f control1.key -C "kanaha-control1" -N ""
ssh-keygen -t ed25519 -f production.key -C "kanaha-production" -N ""
ssh-keygen -t ed25519 -f backup.key -C "kanaha-backup" -N ""

# Deploy private keys to device
for key in control1 production backup; do
    $ADB push ${key}.key /data/local/tmp/
    $ADB shell "run-as org.kanaha.camera cp /data/local/tmp/${key}.key $SSH_DIR/keys/"
    $ADB shell "run-as org.kanaha.camera chmod 600 $SSH_DIR/keys/${key}.key"
done

# Deploy known_hosts
ssh-keyscan your-server.local storage.production.lan backup.lan > known_hosts
$ADB push known_hosts /data/local/tmp/
$ADB shell "run-as org.kanaha.camera cp /data/local/tmp/known_hosts $SSH_DIR/"

echo "SSH keys deployed. Add public keys to each server's authorized_keys."
```

### Server-Side Setup

On each storage server:

```bash
# Create dedicated user for camera uploads
sudo useradd -m -s /bin/bash kanaha_camera
sudo mkdir -p /home/kanaha_camera/.ssh
sudo chmod 700 /home/kanaha_camera/.ssh

# Add camera's public key
cat control1.key.pub >> /home/kanaha_camera/.ssh/authorized_keys
sudo chmod 600 /home/kanaha_camera/.ssh/authorized_keys
sudo chown -R kanaha_camera:kanaha_camera /home/kanaha_camera/.ssh

# Create footage directory
sudo mkdir -p /footage/kanaha
sudo chown kanaha_camera:kanaha_camera /footage/kanaha
```

## Post-Transfer File Cleanup

### The Storage Problem

After SFTP transfers, video files remain on camera devices, consuming storage space. The camera has limited storage, so cleanup is important for extended shoots.

### Cleanup API

Use the `deleteFiles` endpoint after successful transfers:

```bash
# List files first to verify what will be deleted
curl -sk --http2 \
  --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
  -H "Content-Type: application/json" \
  -d '{"action":"listFiles"}' \
  https://camera:8443/services/CameraControlService/listFiles

# Delete specific file after transfer
curl -sk --http2 \
  --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
  -H "Content-Type: application/json" \
  -d '{"action":"deleteFiles","pattern":"VID_20260104_*.mp4"}' \
  https://camera:8443/services/CameraControlService/deleteFiles
```

### Production Workflow Script

```bash
#!/bin/bash
# Transfer and cleanup workflow

SSL=/path/to/ssl/certs
CAMERA="192.168.8.168:8443"
SERVER_ID="control1"

# Step 1: Transfer all videos
echo "Transferring videos..."
curl -sk --http2 \
  --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
  -H "Content-Type: application/json" \
  -d "{\"action\":\"sftpTransfer\",\"storage_server_id\":\"$SERVER_ID\",\"video_filename\":\"*\",\"destination_folder\":\"shoot_$(date +%Y%m%d)\"}" \
  https://$CAMERA/services/CameraControlService/sftpTransfer

# Step 2: Verify transfer success before cleanup
if [ $? -eq 0 ]; then
    echo "Transfer complete. Cleaning up..."
    curl -sk --http2 \
      --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
      -H "Content-Type: application/json" \
      -d '{"action":"deleteFiles","pattern":"*"}' \
      https://$CAMERA/services/CameraControlService/deleteFiles
else
    echo "Transfer failed. Files NOT deleted."
fi
```

## Related Documentation

- [Multi-Camera Deployment System](./MULTI_CAMERA_DEPLOYMENT_SYSTEM.md)
- [Kanaha Camera Control API](./CAMERA-CONTROL-API.md)
- [SSL/TLS Certificate Setup](./SSL-SETUP.md)
