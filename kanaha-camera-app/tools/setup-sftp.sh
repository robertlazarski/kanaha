#!/bin/bash
#
# Kanaha Camera SFTP Setup Script
# Sets up SSH PKI authentication for SFTP file transfer
#
# Usage: ./setup-sftp.sh <server_id> <username> <hostname> [port]
#
# Example:
#   ./setup-sftp.sh control1 robert robert-pc.local 22
#

set -e

if [[ $# -lt 3 ]]; then
    echo "Usage: $0 <server_id> <username> <hostname> [port]"
    echo ""
    echo "Example: $0 control1 robert my-server.local 22"
    exit 1
fi

SERVER_ID="$1"
USERNAME="$2"
HOSTNAME="$3"
PORT="${4:-22}"

# Find ADB
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
if [[ ! -x "$ADB" ]]; then
    ADB=$(which adb 2>/dev/null || true)
fi
if [[ ! -x "$ADB" ]]; then
    echo "Error: adb not found. Set ADB environment variable or install Android SDK."
    exit 1
fi

# Setup directory
SETUP_DIR="/tmp/kanaha-sftp-setup-$$"
mkdir -p "$SETUP_DIR/keys"
echo "Setup directory: $SETUP_DIR"

# Generate SSH key if not exists
KEY_FILE="$SETUP_DIR/keys/${SERVER_ID}.key"
if [[ ! -f "$KEY_FILE" ]]; then
    echo "Generating ed25519 SSH key for ${SERVER_ID}..."
    ssh-keygen -t ed25519 -f "$KEY_FILE" -C "kanaha-camera-${SERVER_ID}" -N ""
fi

# Create servers.json
SERVERS_JSON="$SETUP_DIR/servers.json"
if [[ -f "$SERVERS_JSON" ]]; then
    # Merge with existing
    echo "Updating existing servers.json..."
else
    echo "{}" > "$SERVERS_JSON"
fi

# Add/update server entry using Python (available on most systems)
python3 - "$SERVERS_JSON" "$SERVER_ID" "$HOSTNAME" "$PORT" "$USERNAME" << 'EOF'
import json
import sys

config_file = sys.argv[1]
server_id = sys.argv[2]
hostname = sys.argv[3]
port = int(sys.argv[4])
username = sys.argv[5]

with open(config_file, 'r') as f:
    config = json.load(f)

config[server_id] = {
    "host": hostname,
    "port": port,
    "username": username
}

with open(config_file, 'w') as f:
    json.dump(config, f, indent=2)

print(f"Added server: {server_id} -> {username}@{hostname}:{port}")
EOF

# Get server fingerprint
echo "Getting server fingerprint..."
KNOWN_HOSTS="$SETUP_DIR/known_hosts"
ssh-keyscan -p "$PORT" "$HOSTNAME" 2>/dev/null > "$KNOWN_HOSTS"
if [[ ! -s "$KNOWN_HOSTS" ]]; then
    echo "Warning: Could not get server fingerprint. Server might not be reachable."
    echo "You can add it later with: ssh-keyscan -p $PORT $HOSTNAME"
fi

# Check device connection
echo ""
echo "Checking Android device connection..."
if ! $ADB devices | grep -q "device$"; then
    echo "Error: No Android device connected."
    echo "Connect a device and run this script again."
    exit 1
fi

# Deploy to device
echo ""
echo "Deploying SSH configuration to device..."

# Create SSH directories
$ADB shell "run-as org.kanaha.camera mkdir -p /data/user/0/org.kanaha.camera/files/ssh/keys" 2>/dev/null || true

# Push private key
$ADB push "$KEY_FILE" /data/local/tmp/
$ADB shell "run-as org.kanaha.camera cp /data/local/tmp/${SERVER_ID}.key /data/user/0/org.kanaha.camera/files/ssh/keys/"
$ADB shell "run-as org.kanaha.camera chmod 600 /data/user/0/org.kanaha.camera/files/ssh/keys/${SERVER_ID}.key"
$ADB shell "rm /data/local/tmp/${SERVER_ID}.key"

# Push servers.json
$ADB push "$SERVERS_JSON" /data/local/tmp/
$ADB shell "run-as org.kanaha.camera cp /data/local/tmp/servers.json /data/user/0/org.kanaha.camera/files/ssh/"
$ADB shell "rm /data/local/tmp/servers.json"

# Push known_hosts
if [[ -s "$KNOWN_HOSTS" ]]; then
    $ADB push "$KNOWN_HOSTS" /data/local/tmp/
    $ADB shell "run-as org.kanaha.camera cp /data/local/tmp/known_hosts /data/user/0/org.kanaha.camera/files/ssh/"
    $ADB shell "rm /data/local/tmp/known_hosts"
fi

echo ""
echo "================================================"
echo "SFTP setup complete for server: $SERVER_ID"
echo "================================================"
echo ""
echo "Public key to add to server's ~/.ssh/authorized_keys:"
echo ""
cat "${KEY_FILE}.pub"
echo ""
echo "Run this command on $HOSTNAME:"
echo "  echo '$(cat ${KEY_FILE}.pub)' >> ~/.ssh/authorized_keys"
echo ""
echo "Test SFTP transfer:"
echo "  curl -sk --http2 \\"
echo "    --cert \$SSL/client.crt --key \$SSL/client.key --cacert \$SSL/ca.crt \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"action\":\"sftpTransfer\",\"storage_server_id\":\"${SERVER_ID}\",\"video_filename\":\"test.mp4\",\"destination_folder\":\"/tmp\"}' \\"
echo "    https://localhost:18443/services/CameraControlService/sftpTransfer"
echo ""

# Cleanup
rm -rf "$SETUP_DIR"
