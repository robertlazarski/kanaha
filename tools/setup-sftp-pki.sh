#!/bin/bash
#
# Kanaha SFTP PKI Setup Script
# Sets up SSH key-based authentication for secure file transfers
#
# Usage: ./setup-sftp-pki.sh [server_id] [hostname] [username]
# Example: ./setup-sftp-pki.sh control robert-inspiron16plus7640.local robert
#

set -e

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
SSH_DIR="/data/data/org.kanaha.camera/files/ssh"
LOCAL_SSH_DIR="/tmp/kanaha-ssh-setup"

SERVER_ID="${1:-control}"
HOSTNAME="${2:-$(hostname).local}"
USERNAME="${3:-$(whoami)}"

echo "=== Kanaha SFTP PKI Setup ==="
echo "Server ID:  $SERVER_ID"
echo "Hostname:   $HOSTNAME"
echo "Username:   $USERNAME"
echo ""

# Create local setup directory
mkdir -p "$LOCAL_SSH_DIR/keys"

# Step 1: Generate SSH key pair
KEY_FILE="$LOCAL_SSH_DIR/keys/${SERVER_ID}.key"
if [[ -f "$KEY_FILE" ]]; then
    echo "Key already exists: $KEY_FILE"
else
    echo "Generating SSH key pair..."
    ssh-keygen -t ed25519 -f "$KEY_FILE" -C "kanaha-camera-${SERVER_ID}" -N ""
fi

# Step 2: Create servers.json config
CONFIG_FILE="$LOCAL_SSH_DIR/servers.json"
echo "Creating servers.json..."
cat > "$CONFIG_FILE" << EOF
{
  "${SERVER_ID}": {
    "host": "${HOSTNAME}",
    "port": 22,
    "username": "${USERNAME}"
  }
}
EOF
echo "Config: $CONFIG_FILE"

# Step 3: Get server host key
KNOWN_HOSTS="$LOCAL_SSH_DIR/known_hosts"
echo "Fetching server host key..."
ssh-keyscan -t ed25519,rsa "$HOSTNAME" 2>/dev/null > "$KNOWN_HOSTS" || {
    echo "Warning: Could not fetch host key (SSH may not be running on $HOSTNAME)"
    echo "You can add it later with: ssh-keyscan $HOSTNAME >> $KNOWN_HOSTS"
}

# Step 4: Ensure SSH server is running
echo ""
echo "Checking SSH server on local machine..."
if systemctl is-active --quiet ssh 2>/dev/null || systemctl is-active --quiet sshd 2>/dev/null; then
    echo "SSH server is running."
else
    echo "SSH server is NOT running!"
    echo "Start with: sudo systemctl start ssh"
    echo ""
fi

# Step 5: Add public key to authorized_keys
echo ""
echo "=== Manual Step Required ==="
echo "Add the public key to your authorized_keys:"
echo ""
echo "  cat ${KEY_FILE}.pub >> ~/.ssh/authorized_keys"
echo ""
read -p "Press Enter after adding the key (or Ctrl+C to abort)..."

# Step 6: Push to Android device
echo ""
echo "Pushing SSH configuration to Android device..."
$ADB shell "run-as org.kanaha.camera mkdir -p $SSH_DIR/keys" 2>/dev/null || \
    $ADB shell "mkdir -p $SSH_DIR/keys"

# Push files using run-as for proper permissions
echo "Pushing private key..."
$ADB push "$KEY_FILE" "/data/local/tmp/"
$ADB shell "run-as org.kanaha.camera cp /data/local/tmp/${SERVER_ID}.key $SSH_DIR/keys/"
$ADB shell "run-as org.kanaha.camera chmod 600 $SSH_DIR/keys/${SERVER_ID}.key"

echo "Pushing servers.json..."
$ADB push "$CONFIG_FILE" "/data/local/tmp/"
$ADB shell "run-as org.kanaha.camera cp /data/local/tmp/servers.json $SSH_DIR/"

if [[ -s "$KNOWN_HOSTS" ]]; then
    echo "Pushing known_hosts..."
    $ADB push "$KNOWN_HOSTS" "/data/local/tmp/"
    $ADB shell "run-as org.kanaha.camera cp /data/local/tmp/known_hosts $SSH_DIR/"
fi

# Cleanup temp files on device
$ADB shell "rm -f /data/local/tmp/${SERVER_ID}.key /data/local/tmp/servers.json /data/local/tmp/known_hosts"

# Verify
echo ""
echo "=== Verifying Setup ==="
$ADB shell "run-as org.kanaha.camera ls -la $SSH_DIR/"
$ADB shell "run-as org.kanaha.camera ls -la $SSH_DIR/keys/"

echo ""
echo "=== Setup Complete ==="
echo ""
echo "Create a destination directory on your machine:"
echo "  mkdir -p ~/kanaha-uploads"
echo ""
echo "Test SFTP transfer with:"
echo "  SSL=\$HOME/repos/kanaha/kanaha-camera-app/app/src/main/assets/ssl"
echo "  curl -sk --cert \$SSL/client.crt --key \$SSL/client.key --cacert \$SSL/ca.crt \\"
echo "       -H 'Content-Type: application/json' \\"
echo "       -d '{\"action\":\"sftp_transfer\",\"storage_server_id\":\"${SERVER_ID}\",\"video_filename\":\"*.mp4\",\"destination_folder\":\"/home/${USERNAME}/kanaha-uploads\"}' \\"
echo "       https://\$(./tools/kanaha-discover.sh --json | jq -r '.[0].hostname'):8443/services/CameraControlService/sftpTransfer"
