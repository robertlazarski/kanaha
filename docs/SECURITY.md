# Kanaha Camera Security Architecture

This document describes the security architecture of the Kanaha Camera Control System, with a focus on code-level security measures. For transport-level security (mTLS, certificates), see [MULTI_CAMERA_DEPLOYMENT_SYSTEM.md](MULTI_CAMERA_DEPLOYMENT_SYSTEM.md).

## Security Layers Overview

Kanaha implements defense-in-depth with security enforced at multiple layers:

```
┌─────────────────────────────────────────────────────────────────┐
│                      SECURITY LAYERS                            │
├─────────────────────────────────────────────────────────────────┤
│  Layer 1: Network Security (mTLS)                               │
│  ├── Client certificate authentication                          │
│  ├── TLS 1.2+ encryption                                        │
│  └── See: MULTI_CAMERA_DEPLOYMENT_SYSTEM.md                     │
├─────────────────────────────────────────────────────────────────┤
│  Layer 2: Apache HTTP Server                                    │
│  ├── User-Agent filtering (blocks scanning tools)               │
│  ├── Security headers (HSTS, X-Frame-Options, etc.)             │
│  └── See: httpd.conf                                            │
├─────────────────────────────────────────────────────────────────┤
│  Layer 3: Native C Layer (Axis2/C)                              │
│  ├── Path traversal validation                                  │
│  ├── File extension whitelist                                   │
│  └── See: camera_control_service.c                              │
├─────────────────────────────────────────────────────────────────┤
│  Layer 4: Java Application Layer                                │
│  ├── SecurityValidator class                                    │
│  ├── Canonical path verification                                │
│  ├── Input sanitization                                         │
│  └── See: CameraControlReceiver.java                            │
└─────────────────────────────────────────────────────────────────┘
```

## Apache httpd Security Features

Location: `app/src/main/assets/apache/`

### TLS/SSL Configuration (`ssl.conf`)

#### Protocol Security

```apache
# Disable legacy protocols (SSLv3, TLS 1.0, TLS 1.1)
SSLProtocol             all -SSLv3 -TLSv1 -TLSv1.1

# Modern cipher suites only (ECDHE for forward secrecy)
SSLCipherSuite          ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:...

# Disable session tickets (prevents session resumption attacks)
SSLSessionTickets       off

# Disable SSL compression (CRIME attack mitigation)
SSLCompression          off

# Disable insecure renegotiation
SSLInsecureRenegotiation off
```

| Setting | Security Benefit |
|---------|-----------------|
| `SSLProtocol all -SSLv3 -TLSv1 -TLSv1.1` | Only TLS 1.2+ allowed |
| `SSLSessionTickets off` | Prevents session ticket attacks |
| `SSLCompression off` | Prevents CRIME attack |
| `SSLInsecureRenegotiation off` | Prevents renegotiation attacks |

#### Mutual TLS (mTLS) Authentication

```apache
# Require client certificate
SSLVerifyClient         require
SSLVerifyDepth          10

# CA certificate for client verification
SSLCACertificateFile    "ssl/ca.crt"

# Strict mode
SSLOptions              +StrictRequire
```

The server will **reject connections** from clients without a valid certificate signed by the CA.

#### Per-Location Security

```apache
<Location "/services">
    SSLRequireSSL                    # Reject non-SSL connections
    SSLVerifyClient require          # Enforce client cert at this location

    # Anti-caching headers for sensitive data
    Header always set Cache-Control "no-cache, no-store, must-revalidate"
    Header always set Pragma "no-cache"
    Header always set Expires "0"
</Location>
```

### Security Headers (`httpd.conf`)

```apache
# HTTP Strict Transport Security - force HTTPS for 1 year
Header always set Strict-Transport-Security "max-age=31536000; includeSubDomains"

# Prevent MIME type sniffing
Header always set X-Content-Type-Options "nosniff"

# Prevent clickjacking
Header always set X-Frame-Options "DENY"

# XSS filter (legacy browsers)
Header always set X-XSS-Protection "1; mode=block"

# Control referrer information
Header always set Referrer-Policy "strict-origin-when-cross-origin"
```

| Header | Protection |
|--------|-----------|
| `Strict-Transport-Security` | Forces HTTPS, prevents downgrade attacks |
| `X-Content-Type-Options` | Prevents MIME confusion attacks |
| `X-Frame-Options` | Prevents clickjacking |
| `X-XSS-Protection` | Enables browser XSS filters |
| `Referrer-Policy` | Limits referrer information leakage |

### SSL Logging

```apache
# Log SSL protocol, cipher, and client certificate DN
CustomLog "logs/ssl_access_log" \
    "%t %h %{SSL_PROTOCOL}x %{SSL_CIPHER}x \"%r\" %b \"%{SSL_CLIENT_S_DN}x\""
```

This logs:
- Timestamp and client IP
- TLS protocol version used
- Cipher suite negotiated
- Client certificate Distinguished Name (for audit)

### Certificate Information Export

```apache
# Export certificate data to environment variables
SSLOptions +StdEnvVars +ExportCertData
```

This makes client certificate information available to Axis2/C for additional validation if needed.

## Transport Security (mTLS)

All network communications are secured with mutual TLS (mTLS):

- **Client certificates required** - Only clients with valid certificates can connect
- **Certificate Authority (CA)** - Self-signed CA for the camera network
- **HTTP/2 over TLS** - Modern protocol with encryption
- **TLS 1.2+ only** - Legacy protocols disabled

For certificate setup and mTLS configuration, see:
- [MULTI_CAMERA_DEPLOYMENT_SYSTEM.md](MULTI_CAMERA_DEPLOYMENT_SYSTEM.md) - Section "mTLS Certificate System"
- [SFTP-FILE-TRANSFER.md](SFTP-FILE-TRANSFER.md) - SSH PKI for file transfers

## Certificate Model: Shared Client Certificate

Location: `app/src/main/assets/ssl/`

Kanaha uses a **shared client certificate** model where all devices use the same `client.crt` and `client.key`:

```
ssl/
├── ca.crt          # Certificate Authority
├── ca.key          # CA private key (keep secure!)
├── client.crt      # Shared by ALL devices (cameras + control station)
├── client.key      # Shared by ALL devices
├── server.crt      # Server certificate
└── server.key      # Server private key
```

### Why Shared Certificates?

| Factor | Shared Cert | Per-Device Certs |
|--------|-------------|------------------|
| **Complexity** | Simple - one cert to manage | Complex - track cert-to-device mapping |
| **Setup time** | Minutes | Longer initial setup |
| **Device lost** | Regenerate all certs (~5 min) | Revoke one, deploy CRL (~3 min) |
| **Audit trail** | Shows "a client" did X | Shows "pixel9pro" did X |
| **Best for** | Small deployments (2-5 devices) | Large fleets, untrusted locations |

**For a small camera network (2-3 cameras + control station), shared certificates provide adequate security with minimal complexity.**

### Security Considerations

The shared certificate model is **not a vulnerability**:

- mTLS still blocks all unauthorized clients (no cert = no connection)
- Attacker must physically access a device to extract the certificate
- Common in IoT, embedded systems, and mobile apps
- Would not be considered a CVE by security researchers

**Trade-off acknowledged:** If a device is lost, you must regenerate and redeploy certificates to all devices rather than revoking just one.

### Handling a Lost Device (Moto X4 lost at airport)

When a device with the shared certificate is lost or stolen, regenerate all certificates:

**Step 1: Generate new client certificate**

```bash
cd /path/to/ssl

# Generate new client key and certificate
openssl genrsa -out client.key 2048
openssl req -new -key client.key -out client.csr \
    -subj "/CN=kanaha-client/O=Kanaha Production/C=US"
openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key \
    -CAcreateserial -out client.crt -days 365 -sha256

rm client.csr  # Clean up
```

**Step 2: Deploy to all remaining devices**

```bash
ADB=$HOME/Android/Sdk/platform-tools/adb

# Deploy to Pixel 9 Pro (still have this one)
$ADB -s 192.168.8.168:5555 push client.crt /data/local/tmp/
$ADB -s 192.168.8.168:5555 push client.key /data/local/tmp/
$ADB -s 192.168.8.168:5555 shell "run-as org.kanaha.camera cp /data/local/tmp/client.crt files/apache/ssl/"
$ADB -s 192.168.8.168:5555 shell "run-as org.kanaha.camera cp /data/local/tmp/client.key files/apache/ssl/"
$ADB -s 192.168.8.168:5555 shell "run-as org.kanaha.camera chmod 600 files/apache/ssl/client.key"

# Restart the app or reboot device to pick up new cert
$ADB -s 192.168.8.168:5555 shell am force-stop org.kanaha.camera

# Update control station
cp client.crt client.key ~/kanaha-control/ssl/
```

**Step 3: Update your curl/test commands**

```bash
# Test with new certificate
SSL=/path/to/ssl
curl -sk --http2 \
    --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
    https://192.168.8.168:8443/services/CameraControlService/getStatus
```

**Result:** The lost Moto X4's copy of the old certificate no longer works. All your remaining devices have the new certificate.

**Total time:** ~5 minutes for a 3-device network.

### When to Consider Per-Device Certificates

Switch to per-device certificates if:

- You have **5+ devices** where redeployment becomes cumbersome
- Devices are in **remote locations** difficult to access for redeployment
- You need **audit trails** showing exactly which device performed each action
- Devices are in **untrusted physical locations** where loss is expected

For per-device certificate setup, generate unique certs with device-specific CNs:

```bash
# Example: generate cert for specific device
openssl req -new -key pixel9pro.key -out pixel9pro.csr \
    -subj "/CN=pixel9pro.local/O=Kanaha Production/C=US"
```

### CRL Infrastructure (Optional)

The CRL configuration remains in `ssl.conf` for future use if you switch to per-device certificates:

```apache
SSLCARevocationFile     "ssl/ca.crl"
SSLCARevocationCheck    chain
```

With shared certificates, the CRL is not used for individual device revocation - you simply regenerate the shared certificate instead.

## Security Audit Logging

Location: `app/src/main/assets/apache/httpd.conf`

### Audit Log Format

```apache
LogFormat "%t|%h|%{SSL_CLIENT_S_DN}x|%{SSL_CLIENT_VERIFY}x|\"%r\"|%>s|%b" mtls_audit
CustomLog "logs/audit.log" mtls_audit
```

### Log Fields

| Field | Description | Example |
|-------|-------------|---------|
| `%t` | Timestamp | `[06/Jan/2026:12:30:45 +0000]` |
| `%h` | Client IP | `192.168.8.168` |
| `%{SSL_CLIENT_S_DN}x` | Certificate Subject | `CN=kanaha-client,O=Kanaha Production` |
| `%{SSL_CLIENT_VERIFY}x` | Verification Status | `SUCCESS` or `FAILED:reason` |
| `%r` | Request | `POST /services/CameraControlService/startRecording` |
| `%>s` | HTTP Status | `200` |
| `%b` | Response Bytes | `156` |

### Example Log Entry

```
[06/Jan/2026:12:30:45 +0000]|192.168.8.100|CN=kanaha-client,O=Kanaha Production|SUCCESS|"POST /services/CameraControlService/startRecording HTTP/2"|200|156
```

### Shared Certificate Impact on Audit Logs

With the shared client certificate model, all devices show the same certificate subject (`CN=kanaha-client`). Device identification relies on **IP address**:

| IP Address | Device | Location |
|------------|--------|----------|
| `192.168.8.168` | Pixel 9 Pro | Living room |
| `192.168.8.126` | Moto X4 | Garage |
| `192.168.8.100` | Control station | Office laptop |

**Recommendation:** Assign static IPs or DHCP reservations to cameras for consistent identification.

### Use Cases

- **Incident Response**: Identify which device (by IP) made a specific request
- **Access Auditing**: Review what was accessed and when
- **Anomaly Detection**: Spot requests from unexpected IPs or at unusual times
- **Forensics**: Correlate events across multiple cameras by IP and timestamp

### Analyzing Logs for Suspicious Activity

#### Retrieving Logs from Cameras

**Option 1: USB-connected device**

```bash
ADB=$HOME/Android/Sdk/platform-tools/adb

# List connected devices
$ADB devices -l

# Pull audit log directly
$ADB shell "run-as org.kanaha.camera cat /data/user/0/org.kanaha.camera/files/apache/logs/audit.log"

# Save to local file
$ADB shell "run-as org.kanaha.camera cat /data/user/0/org.kanaha.camera/files/apache/logs/audit.log" > audit.log
```

**Option 2: WiFi-connected device (remote camera)**

```bash
ADB=$HOME/Android/Sdk/platform-tools/adb

# Connect to camera over WiFi (camera must have ADB over network enabled)
$ADB connect 192.168.8.168:5555

# Specify device when multiple are connected
$ADB -s 192.168.8.168:5555 shell "run-as org.kanaha.camera cat files/apache/logs/audit.log"
```

**Option 3: Via SSH/SFTP directly**

If the camera is remote and ADB isn't available, connect via SSH using the configured keys:

```bash
# SSH to control station, then SFTP to camera
# (Requires camera to have SSH server - not currently implemented)
# For now, use ADB over WiFi (Option 2) or physical access

# Alternative: use netcat to relay adb over SSH tunnel
ssh user@jumphost -L 5555:camera-ip:5555
$ADB connect localhost:5555
```

> **Note:** The SFTP transfer service intentionally blocks path traversal (e.g., `../logs/`) for security. Log retrieval requires ADB access or a dedicated log endpoint.

**Option 4: Pull all logs at once**

```bash
# Create a script to pull logs from multiple cameras
#!/bin/bash
ADB=$HOME/Android/Sdk/platform-tools/adb
LOG_DIR="$HOME/kanaha-logs/$(date +%Y%m%d)"
mkdir -p "$LOG_DIR"

# Define your cameras
declare -A CAMERAS=(
    ["pixel9pro"]="192.168.8.168"
    ["motox4"]="192.168.8.126"
)

for name in "${!CAMERAS[@]}"; do
    ip="${CAMERAS[$name]}"
    echo "Pulling logs from $name ($ip)..."

    $ADB connect "$ip:5555" 2>/dev/null

    # Pull all relevant logs
    for log in audit.log access_log error_log ssl_access_log; do
        $ADB -s "$ip:5555" shell \
            "run-as org.kanaha.camera cat files/apache/logs/$log 2>/dev/null" \
            > "$LOG_DIR/${name}-${log}" 2>/dev/null
    done
done

echo "Logs saved to $LOG_DIR"
ls -la "$LOG_DIR"
```

#### Parsing the Audit Log

The audit log uses pipe (`|`) delimiters for easy parsing:

```
[timestamp]|client_ip|cert_subject|verify_status|"request"|http_status|bytes
```

**Parse with awk:**

```bash
# Extract specific fields
# Field 1: timestamp, 2: IP, 3: cert CN, 4: verify status, 5: request, 6: status, 7: bytes

# Show just IP and request
awk -F'|' '{print $2, $5}' audit.log

# Filter by HTTP status (e.g., find errors)
awk -F'|' '$6 >= 400 {print $0}' audit.log

# Sum bytes transferred per device
awk -F'|' '{bytes[$3] += $7} END {for (cn in bytes) print cn, bytes[cn]}' audit.log
```

**Parse with Python:**

```python
#!/usr/bin/env python3
import re
from collections import defaultdict

LOG_PATTERN = r'\[([^\]]+)\]\|([^|]+)\|([^|]+)\|([^|]+)\|"([^"]+)"\|(\d+)\|(\d+)'

requests_by_device = defaultdict(list)

with open('audit.log') as f:
    for line in f:
        match = re.match(LOG_PATTERN, line.strip())
        if match:
            timestamp, ip, cert_cn, verify, request, status, bytes_sent = match.groups()
            requests_by_device[cert_cn].append({
                'time': timestamp,
                'ip': ip,
                'request': request,
                'status': int(status)
            })

# Print summary
for device, requests in requests_by_device.items():
    print(f"\n{device}: {len(requests)} requests")
    for req in requests[-5:]:  # Last 5 requests
        print(f"  {req['time']} - {req['request']} [{req['status']}]")
```

**Convert to JSON for further processing:**

```bash
# Convert audit log to JSON (one object per line)
awk -F'|' '{
    gsub(/"/, "\\\"", $5);  # Escape quotes in request
    printf "{\"time\":\"%s\",\"ip\":\"%s\",\"cn\":\"%s\",\"verify\":\"%s\",\"request\":\"%s\",\"status\":%s,\"bytes\":%s}\n",
           $1, $2, $3, $4, $5, $6, $7
}' audit.log > audit.json

# Now you can use jq for queries
cat audit.json | jq -s 'group_by(.cn) | map({device: .[0].cn, count: length})'
```

#### Common Analysis Commands

```bash
# View all requests from a specific IP (device identification)
grep "|192.168.8.126|" audit.log   # Moto X4
grep "|192.168.8.168|" audit.log   # Pixel 9 Pro

# Find all failed certificate verifications
grep -v "SUCCESS" audit.log

# List unique IP addresses that have connected
cut -d'|' -f2 audit.log | sort -u

# Count requests per IP
cut -d'|' -f2 audit.log | sort | uniq -c | sort -rn

# Find requests from unexpected IP addresses (not your known devices)
cut -d'|' -f2 audit.log | grep -v -E "192\.168\.8\.(100|126|168)"

# Show all deleteFiles operations (sensitive!)
grep "deleteFiles" audit.log

# Show all SFTP transfer requests
grep "sftpTransfer" audit.log

# Activity timeline for a specific device (by IP)
grep "|192.168.8.126|" audit.log | cut -d'|' -f1 | sort
```

### Example: Investigating Suspicious Activity

**Scenario:** You notice your Moto X4 camera is missing some video files. You suspect someone may have accessed the camera remotely and deleted footage.

**Step 1: Pull the audit log**

```bash
$ADB -s 192.168.8.126:5555 shell "run-as org.kanaha.camera cat files/apache/logs/audit.log" > motox4-audit.log
```

**Step 2: Find all delete operations**

```bash
$ grep "deleteFiles" motox4-audit.log

[05/Jan/2026:14:22:01 +0000]|192.168.8.100|CN=kanaha-client,O=Kanaha Production|SUCCESS|"POST /services/CameraControlService/deleteFiles HTTP/2"|200|89
[05/Jan/2026:23:47:33 +0000]|192.168.8.168|CN=kanaha-client,O=Kanaha Production|SUCCESS|"POST /services/CameraControlService/deleteFiles HTTP/2"|200|89
[06/Jan/2026:03:15:22 +0000]|10.0.0.99|CN=kanaha-client,O=Kanaha Production|SUCCESS|"POST /services/CameraControlService/deleteFiles HTTP/2"|200|89
```

**Step 3: Identify the anomaly**

The third entry is suspicious:
- **Unusual time**: 3:15 AM (outside normal usage)
- **Unknown IP**: `10.0.0.99` (not on your local network `192.168.8.x`)
- Valid cert but from an **unexpected location**

With shared certificates, all entries show the same CN. The key identifier is the **IP address**.

**Step 4: Investigate the suspicious IP**

```bash
# Find all activity from the unknown IP
$ grep "|10.0.0.99|" motox4-audit.log

[06/Jan/2026:03:12:45 +0000]|10.0.0.99|CN=kanaha-client,O=Kanaha Production|SUCCESS|"POST /services/CameraControlService/listFiles HTTP/2"|200|1847
[06/Jan/2026:03:14:01 +0000]|10.0.0.99|CN=kanaha-client,O=Kanaha Production|SUCCESS|"POST /services/CameraControlService/sftpTransfer HTTP/2"|200|203
[06/Jan/2026:03:15:22 +0000]|10.0.0.99|CN=kanaha-client,O=Kanaha Production|SUCCESS|"POST /services/CameraControlService/deleteFiles HTTP/2"|200|89
```

**Step 5: Reconstruct the attack timeline**

| Time | Action | Interpretation |
|------|--------|----------------|
| 03:12:45 | listFiles | Attacker enumerated available videos |
| 03:14:01 | sftpTransfer | Attacker exfiltrated video files |
| 03:15:22 | deleteFiles | Attacker deleted evidence |

**Step 6: Take action**

1. **Regenerate the shared certificate** (see "Handling a Lost Device" section above)
2. **Check all cameras** for similar activity from this IP
3. **Investigate** how the attacker got the certificate (lost device? extracted from APK?)
4. **Deploy new cert** to all your remaining devices
5. **Block the IP** at router level if possible

### Suspicious Patterns to Watch For

| Pattern | Possible Indication |
|---------|---------------------|
| Requests at unusual hours (2-5 AM) | Unauthorized access |
| IP address outside your network | Stolen cert used remotely |
| Requests from unexpected IP | Device moved or cert copied |
| High volume of listFiles | Reconnaissance |
| deleteFiles after sftpTransfer | Evidence destruction |
| FAILED verification status | Attempted access with old/invalid cert |
| Rapid sequential requests | Automated tool or script |

### Correlating Across Multiple Cameras

If you suspect a network-wide compromise, pull logs from all cameras and correlate:

```bash
# Pull logs from all cameras
for ip in 192.168.8.126 192.168.8.168; do
    $ADB -s $ip:5555 shell "run-as org.kanaha.camera cat files/apache/logs/audit.log" > camera-$ip.log
done

# Find the suspicious IP across all logs
grep "|10.0.0.99|" camera-*.log

# Build timeline of suspicious IP across all cameras
cat camera-*.log | grep "|10.0.0.99|" | sort -t'|' -k1
```

## User-Agent Filtering

Location: `app/src/main/assets/apache/httpd.conf`

The Apache server blocks known security scanning and penetration testing tools:

### Blocked Tools

| Category | Tools |
|----------|-------|
| **Proxies** | Burp Suite, OWASP ZAP, WebScarab |
| **Vulnerability Scanners** | Nikto, Nessus, OpenVAS, Acunetix, AppScan |
| **Web Fuzzers** | wfuzz, ffuf, gobuster, dirbuster, skipfish |
| **SQL Injection** | sqlmap, Havij |
| **Exploitation** | Metasploit, Commix |
| **Brute Force** | Hydra, Medusa |
| **Network Scanners** | Nmap, Masscan |
| **Other** | Nuclei, Jaeles, Arachni, w3af |

### Allowed Tools

Legitimate command-line tools are permitted:
- `curl`, `wget`, `httpie`
- `python-requests`, `libcurl`
- Standard browser user agents

### Configuration

```apache
# Block security scanners
SetEnvIfNoCase User-Agent "burp" blocked_ua=1
SetEnvIfNoCase User-Agent "nikto" blocked_ua=1
# ... (see httpd.conf for full list)

# Block empty user agents
SetEnvIf User-Agent "^$" blocked_ua=1

# Deny blocked requests
<Location "/">
    <RequireAll>
        Require all granted
        Require not env blocked_ua
    </RequireAll>
</Location>
```

## C Layer Security (camera_control_service.c)

Location: `app/src/main/cpp/axis2c/camera_control_service.c`

### Path Traversal Protection

```c
// Reject path traversal attempts
if (strstr(video_filename, "..") || strstr(destination_folder, "..")) {
    LOGE("Security violation: path traversal detected");
    return -1;
}
```

### File Extension Whitelist

Only video file extensions are permitted:

```c
const char* ext = strrchr(video_filename, '.');
if (!ext || (strcmp(ext, ".mp4") != 0 &&
             strcmp(ext, ".mov") != 0 &&
             strcmp(ext, ".mkv") != 0)) {
    LOGE("Security violation: invalid file extension");
    return -1;
}
```

### Validated Operations

| Function | Validations |
|----------|-------------|
| `camera_device_sftp_transfer_impl()` | Path traversal, file extension |
| `camera_device_delete_files_impl()` | Path traversal |
| `camera_device_cleanup_files_impl()` | Path traversal, policy whitelist |

## Java Layer Security (SecurityValidator)

Location: `app/src/main/java/org/kanaha/camera/CameraControlReceiver.java`

The `SecurityValidator` class provides comprehensive input validation as a defense-in-depth measure.

### Input Length Limits

| Parameter | Maximum Length |
|-----------|----------------|
| Filename/Pattern | 255 characters |
| Server ID | 64 characters |
| Destination Path | 1024 characters |

### Path Traversal Detection

Blocked patterns (case-insensitive):

| Pattern | Attack Type |
|---------|-------------|
| `..` | Direct path traversal |
| `%2e%2e` | URL-encoded traversal |
| `%252e%252e` | Double URL-encoded |
| `..%c0%af` | Unicode encoding (IIS) |
| `..%c1%9c` | Unicode encoding variant |
| `.../` | Triple dot |
| `%00` | Null byte injection |

### Injection Pattern Detection

Blocked patterns:

| Category | Patterns |
|----------|----------|
| **JavaScript** | `<script`, `javascript:`, `vbscript:` |
| **Event Handlers** | `onclick`, `onerror`, `onload` |
| **Code Execution** | `eval(`, `expression(` |
| **Template Injection** | `${`, `#{`, `{{` |
| **Shell Injection** | `` ` ``, `$()`, `; `, `| `, `&& `, `|| ` |

### Control Character Filtering

Rejected characters:
- ASCII 0-31 (control characters)
- ASCII 127 (DEL)
- ASCII 128-159 (high control characters)

### Character Whitelist

Filenames must match: `^[a-zA-Z0-9_\-\.\*]+$`

Allowed: letters, numbers, underscore, hyphen, dot, asterisk (for wildcards)

### Canonical Path Verification

Files are verified to be within the allowed directory using canonical paths:

```java
static boolean isPathWithinDirectory(File baseDir, File targetFile) {
    try {
        String canonicalBase = baseDir.getCanonicalPath();
        String canonicalTarget = targetFile.getCanonicalPath();

        return canonicalTarget.startsWith(canonicalBase + File.separator) ||
               canonicalTarget.equals(canonicalBase);
    } catch (IOException e) {
        return false;  // Fail closed
    }
}
```

This defeats:
- Symbolic link attacks
- Path traversal via `..`
- Unicode normalization attacks

### Validated Handlers

| Handler | Validated Parameters |
|---------|---------------------|
| `handleDeleteFiles()` | pattern |
| `handleListFiles()` | pattern (optional) |
| `handleSftpTransfer()` | storage_server_id, video_filename, destination_folder |
| `findFilesToDelete()` | pattern (defense-in-depth) |
| `findFilesToTransfer()` | pattern (defense-in-depth) |

## Security Error Responses

When security validation fails, the system returns a JSON error:

```json
{
    "success": false,
    "operation_id": "op_123456",
    "error": "Path traversal attempt detected in pattern",
    "timestamp": 1704567890123
}
```

Error messages are intentionally generic to avoid information disclosure.

## Logging

Security events are logged for monitoring:

```java
Log.w(TAG, "Security: deleteFiles rejected - " + validationError);
Log.w(TAG, "Security: path traversal blocked in findFilesToDelete: " + pattern);
```

Log tags:
- `SecurityValidator` - Validation class logs
- `KanahaCameraReceiver` - Handler-level security logs

## API Restrictions

### Allowed Patterns for File Operations

| Pattern Type | Example | Valid |
|--------------|---------|-------|
| Specific file | `VID_20260106_120000.mp4` | Yes |
| Wildcard | `*.mp4`, `VID_*.mp4` | Yes |
| Date filter | `2026-01-06` | Yes |
| Today's files | `today` | Yes |
| All videos | `*` | Yes |
| Path traversal | `../etc/passwd` | **No** |
| Absolute path | `/sdcard/file.mp4` | **No** |
| With slashes | `subdir/file.mp4` | **No** |

### SFTP Transfer Restrictions

- **Server ID**: Must be pre-configured in `servers.json`
- **Destination**: Must be absolute path, no traversal
- **Source files**: Must be within video directory

## Security Headers

Location: `app/src/main/assets/apache/httpd.conf`

```apache
Header always set Strict-Transport-Security "max-age=31536000; includeSubDomains"
Header always set X-Content-Type-Options "nosniff"
Header always set X-Frame-Options "DENY"
Header always set X-XSS-Protection "1; mode=block"
Header always set Referrer-Policy "strict-origin-when-cross-origin"
```

## IPC Security (C Layer)

Location: `app/src/main/cpp/axis2c/camera_control_service.c`

### Secure Intent Broadcasting

The C layer communicates with the Java layer via Android Intent broadcasts. This uses a **secure fork/exec pattern** instead of `system()`:

```c
/* INSECURE (removed):
 * system("am broadcast --es pattern '" + user_input + "'");
 * Vulnerable to shell injection: pattern = "'; rm -rf /; '"
 */

/* SECURE (current implementation): */
static int send_intent_broadcast_secure(
    const char* component,
    const char* action,
    const intent_extra_t* extras,  /* Array of {type, key, value} */
    int num_extras
) {
    /* Build argv array directly - no shell parsing */
    argv[i++] = "am";
    argv[i++] = "broadcast";
    argv[i++] = "--es";
    argv[i++] = extras[j].key;
    argv[i++] = extras[j].value;  /* User data passed directly, not parsed */

    pid_t pid = fork();
    if (pid == 0) {
        execvp("/system/bin/am", argv);  /* No shell invocation */
    }
}
```

**Why fork/exec is secure:**

| Approach | Shell Invoked | User Data Parsed | Injection Risk |
|----------|---------------|------------------|----------------|
| `system("cmd " + input)` | Yes | Yes | **HIGH** |
| `fork()` + `execvp(argv[])` | No | No | **None** |

With `execvp()`, arguments are passed as an array directly to the process. Shell metacharacters (`'`, `;`, `|`, etc.) are treated as literal characters.

### IPC Response File Handling

Response files use unpredictable operation IDs:

```c
/* Operation ID includes nanosecond timestamp */
snprintf(buffer, size, "op_%ld_%ld", ts.tv_sec, ts.tv_nsec);
/* Example: op_1704567890_123456789 */
```

**Mitigations:**
- Files created in app-private cache directory
- Deleted immediately after reading
- Android app sandboxing prevents other apps from accessing

## Security Checklist

### For Developers

- [ ] All user input passes through `SecurityValidator`
- [ ] File operations use canonical path verification
- [ ] No direct file paths from user input
- [ ] Error messages don't leak internal paths
- [ ] New handlers follow existing validation patterns
- [ ] IPC uses `send_intent_broadcast_secure()`, never `system()`

### For Deployment

- [ ] mTLS certificates properly configured
- [ ] Shared client certificate (`client.crt`, `client.key`) deployed to all devices
- [ ] CA private key (`ca.key`) stored securely offline
- [ ] SSH keys for SFTP use ed25519
- [ ] `known_hosts` file includes server fingerprints
- [ ] Apache user-agent filtering enabled
- [ ] Audit logging enabled and monitored
- [ ] Document which devices have the shared certificate (for recovery)

## Security Testing Procedure

Location: `test-security-changes.sh`

This section documents how to test security features after building a new APK.

### Prerequisites

1. **Build the APK** with security changes:
   ```bash
   cd ~/repos/kanaha/kanaha-camera-app
   ./gradlew assembleDebug
   ```

2. **Power on phones** and connect to WiFi (192.168.8.x network)

3. **Enable ADB over WiFi** (one-time setup via USB):
   ```bash
   # Connect phone via USB
   adb tcpip 5555
   # Disconnect USB - phone now accepts ADB over WiFi
   ```

### Test Execution Sequence

The test script executes the following steps in order:

#### Step 1: Connect to Devices via ADB

```bash
ADB=$HOME/Android/Sdk/platform-tools/adb
$ADB connect 192.168.8.168:5555  # Pixel 9 Pro
$ADB connect 192.168.8.126:5555  # Moto X4
```

**Note:** If ADB connection fails, the script continues with direct HTTPS testing.

#### Step 2: Install APK (with --install flag)

```bash
APK=~/repos/kanaha/kanaha-camera-app/app/build/outputs/apk/debug/app-debug.apk
$ADB -s 192.168.8.168:5555 install -r "$APK"
$ADB -s 192.168.8.126:5555 install -r "$APK"
```

#### Step 3: Launch Kanaha App

```bash
$ADB -s 192.168.8.168:5555 shell am start -n org.kanaha.camera/net.sourceforge.opencamera.MainActivity
$ADB -s 192.168.8.126:5555 shell am start -n org.kanaha.camera/net.sourceforge.opencamera.MainActivity
```

**IMPORTANT:** If ADB is not connected, you must **manually open the Kanaha app** on each phone before testing. The Apache server only starts when the app is running.

#### Step 4: Wait for Apache to Start

The script waits 10 seconds for Apache httpd to initialize on each device.

#### Step 5: Run Security Tests

For each camera, the following tests are executed:

| Test | Command | Expected Result |
|------|---------|-----------------|
| **1. getStatus** | `curl ... -d '{"action":"getStatus"}'` | `{"success": true, ...}` |
| **2. listFiles** | `curl ... -d '{"action":"listFiles"}'` | `{"success": true, "files": [...]}` |
| **3. Path Traversal** | `curl ... -d '{"action":"deleteFiles","pattern":"../../../etc/passwd"}'` | Error: "path traversal" rejected |
| **4. Shell Injection** | `curl ... -d '{"action":"deleteFiles","pattern":"test; rm -rf /"}'` | Error: invalid characters rejected |
| **5. Script Injection** | `curl ... -d '{"action":"deleteFiles","pattern":"<script>alert(1)</script>"}'` | Error: injection attempt rejected |
| **6. Input Length** | `curl ... -d '{"action":"deleteFiles","pattern":"AAA...(300 chars)"}'` | Error: too long rejected |
| **7. User-Agent Filter** | `curl ... -H "User-Agent: Nikto/2.1.6"` | HTTP 403 Forbidden |
| **8. Audit Log** | `adb shell "run-as org.kanaha.camera cat files/apache/logs/audit.log"` | Log entries exist |
| **9. Request Size** | `curl ... -d '{"action":"getStatus","data":"AAA...(70KB)"}' ` | HTTP 413 Request Too Large |

### Running the Test Script

```bash
cd ~/repos/kanaha/kanaha-camera-app

# Full test with APK installation
./test-security-changes.sh --install

# Test only (APK already installed, app running)
./test-security-changes.sh

# Test single device
./test-security-changes.sh --pixel-only
./test-security-changes.sh --moto-only
```

### Manual Testing Commands

If the script cannot connect, test manually:

```bash
# Setup
SSL=$HOME/repos/kanaha/kanaha-camera-app/app/src/main/assets/ssl
CAMERA="192.168.8.168:8443"  # or 192.168.8.126:8443 for Moto X4

# Test getStatus
curl -sk --http2 \
    --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
    -H "Content-Type: application/json" \
    -d '{"action":"getStatus"}' \
    "https://$CAMERA/services/CameraControlService/getStatus"

# Test path traversal rejection
curl -sk --http2 \
    --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
    -H "Content-Type: application/json" \
    -d '{"action":"deleteFiles","pattern":"../etc/passwd"}' \
    "https://$CAMERA/services/CameraControlService/deleteFiles"

# Test recording (ensure camera preview is visible on phone!)
curl -sk --http2 \
    --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
    -H "Content-Type: application/json" \
    -d '{"action":"startRecording"}' \
    "https://$CAMERA/services/CameraControlService/startRecording"
```

### Expected Test Output

```
==============================================
  Kanaha Security Changes Test Script
==============================================

Step 1: Connecting to devices via ADB...

Connecting to Pixel 9 Pro (192.168.8.168)... Connected
Connecting to Moto X4 (192.168.8.126)... Connected

Step 2: Installing APK...

Installing on Pixel 9 Pro... OK
Installing on Moto X4... OK

Launching Kanaha app on devices...
Waiting 10 seconds for Apache to start...

Step 3: Running Security Tests...

==============================================
  Testing: Pixel 9 Pro (192.168.8.168)
==============================================
Testing HTTPS connectivity... OK

--- Test 1: getStatus ---
PASS: getStatus returned success

--- Test 2: listFiles ---
PASS: listFiles returned success
  Found 3 video file(s)

--- Test 3: Security - Path Traversal Rejection ---
PASS: Path traversal correctly rejected

--- Test 4: Security - Invalid Characters Rejection ---
PASS: Shell injection pattern rejected

--- Test 5: Security - Script Injection Rejection ---
PASS: Script injection correctly rejected

--- Test 6: Security - Oversized Input Rejection ---
PASS: Oversized input correctly rejected

--- Test 7: User-Agent Filtering ---
PASS: Scanner user-agent correctly blocked

--- Test 8: Audit Log Check ---
PASS: Audit log exists and contains entries

--- Test 9: Rate Limiting (LimitRequestBody) ---
PASS: Large request correctly rejected (413)

--- Pixel 9 Pro Tests Complete ---
```

### Troubleshooting

| Issue | Solution |
|-------|----------|
| "Camera not reachable on port 8443" | Open Kanaha app manually on the phone |
| "ADB connection refused" | Enable ADB over WiFi: connect USB, run `adb tcpip 5555` |
| "Certificate verify failed" | Ensure `client.crt`, `client.key`, `ca.crt` exist in ssl/ |
| Tests pass but recording fails | Camera preview must be visible on phone screen |

## Apache httpd Security Configuration Summary

These security measures are implemented via **configuration only** (no code changes required):

| Feature | Configuration | File |
|---------|--------------|------|
| TLS 1.2+ only | `SSLProtocol all -SSLv3 -TLSv1 -TLSv1.1` | ssl.conf |
| Modern ciphers | `SSLCipherSuite ECDHE-...` | ssl.conf |
| Client cert required | `SSLVerifyClient require` | ssl.conf |
| Shared client cert | All devices use same `client.crt` | ssl/ directory |
| Session ticket disabled | `SSLSessionTickets off` | ssl.conf |
| Compression disabled | `SSLCompression off` | ssl.conf |
| HSTS header | `Header always set Strict-Transport-Security` | httpd.conf |
| XSS protection | `Header always set X-XSS-Protection` | httpd.conf |
| Clickjack protection | `Header always set X-Frame-Options` | httpd.conf |
| MIME sniffing protection | `Header always set X-Content-Type-Options` | httpd.conf |
| User-agent filtering | `SetEnvIfNoCase User-Agent "burp" blocked_ua=1` | httpd.conf |
| Anti-caching (API) | `Header always set Cache-Control "no-store"` | ssl.conf |
| Request size limit | `LimitRequestBody 65536` | httpd.conf |
| Header size limit | `LimitRequestFieldSize 4096` | httpd.conf |
| Connection timeout | `Timeout 30` | httpd.conf |
| Bandwidth limit | `SetEnv rate-limit 50` (if mod_ratelimit) | httpd.conf |
| Security audit log | `CustomLog "logs/audit.log" mtls_audit` | httpd.conf |

### Rate Limiting and DoS Protection

Location: `app/src/main/assets/apache/httpd.conf`

#### Core Apache Limits (Always Active)

```apache
# Request size limits
LimitRequestBody 65536        # 64KB max request body
LimitRequestFieldSize 4096    # 4KB max header value
LimitRequestFields 50         # Max 50 headers
LimitRequestLine 4096         # 4KB max URL line

# Connection timeout (Slowloris mitigation)
Timeout 30
```

| Setting | Protection |
|---------|------------|
| `LimitRequestBody` | Prevents oversized payload attacks |
| `LimitRequestFieldSize` | Limits header injection vectors |
| `Timeout 30` | Closes slow/hanging connections |

#### mod_reqtimeout (If Available)

```apache
<IfModule mod_reqtimeout.c>
    RequestReadTimeout header=10-20,MinRate=500
    RequestReadTimeout body=10-30,MinRate=1000
</IfModule>
```

This provides granular timeout control based on data rate, defeating slow-read attacks.

#### mod_ratelimit (If Available)

```apache
<IfModule mod_ratelimit.c>
    <Location "/services">
        SetOutputFilter RATE_LIMIT
        SetEnv rate-limit 50  # 50 KB/s per connection
    </Location>
</IfModule>
```

**Note:** To enable `mod_ratelimit`, rebuild httpd with this module statically linked.

## Why These Protections Are Sufficient

The security measures implemented in Kanaha are comprehensive for its threat model. Several additional security measures were considered but determined not worth the implementation effort. Here's why:

### mTLS Changes the Threat Model

The most important security control is **mutual TLS (mTLS)** authentication. Without a valid client certificate signed by the CA, an attacker cannot:

- Complete the TLS handshake
- Send any HTTP requests
- Probe endpoints for vulnerabilities
- Attempt injection attacks
- Perform DoS attacks via request floods

This fundamentally limits the attack surface to **authorized clients only**. Many traditional web security measures become less critical when unauthorized parties can't even establish a connection.

### Measures Not Worth Additional Effort

| Measure | Why Not Worth It |
|---------|------------------|
| **Rebuilding httpd for mod_ratelimit** | Core Apache `LimitRequest*` and `Timeout` directives already provide DoS protection. With mTLS, attackers can't connect anyway. The effort of cross-compiling additional modules doesn't justify the marginal improvement. |
| **Rebuilding httpd for mod_reqtimeout** | The global `Timeout 30` directive already mitigates Slowloris attacks. Granular per-phase timeouts add complexity without significant benefit for a private camera network. |
| **C layer input validation hardening** | Java's `SecurityValidator` class already catches path traversal, injection attempts, and malformed input before it reaches the C layer. Adding redundant validation in C is defense-in-depth that's unlikely to catch anything the Java layer missed. |
| **Improving User-Agent filtering** | This is fundamentally security-by-obscurity. Attackers can trivially change their User-Agent string. The real protection is mTLS - UA filtering just reduces noise from casual scans. |
| **OCSP Stapling** | Kanaha uses a self-signed CA, not a public CA. OCSP is for checking revocation status with the CA in real-time, but with a private CA we control the CRL directly. OCSP adds complexity and external dependencies for no benefit. |
| **IP allowlisting** | While easy to implement (`Require ip 192.168.x.x`), it reduces deployment flexibility and is redundant with mTLS. Certificate-based authentication is stronger than IP-based because certificates can be revoked individually while IPs are often dynamic. |

### The Remaining Threat: Compromised Legitimate Devices

With mTLS in place, the primary remaining threat is a **compromised or lost legitimate device**. This is why Certificate Revocation (CRL) support is valuable - it allows you to cut off a specific device without affecting others.

The security measures implemented address this threat model appropriately:

1. **Prevention**: mTLS prevents unauthorized access
2. **Detection**: Audit logging tracks which device made each request
3. **Response**: CRL support enables rapid revocation of compromised devices
4. **Containment**: Input validation prevents compromised devices from escalating attacks

### Summary

Kanaha's security posture is appropriate for a private camera control network:

- **Strong authentication** via mTLS
- **Encrypted communications** via TLS 1.2+
- **Input validation** at multiple layers
- **Audit trail** for forensics
- **Revocation capability** for incident response
- **DoS mitigation** via request limits

Additional hardening measures would provide diminishing returns relative to their implementation cost.

## Reporting Security Issues

If you discover a security vulnerability:

1. **Do not** open a public GitHub issue
2. Contact the maintainers privately
3. Provide detailed reproduction steps
4. Allow reasonable time for a fix before disclosure

---

*Last updated: 2026-01-06*
