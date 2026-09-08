# AGENTS.md — Kanaha Camera Control

## Security threat model

See [docs/SECURITY.md](docs/SECURITY.md) if present for the full model. This file is the
scan-priority guide: where the risk actually is, ranked by exploitability.

## What this is

An Android camera app (OpenCamera-derived) that runs a statically-linked Apache
httpd (mod_ssl + mod_http2 + mod_axis2) **as a child process**, serving
`CameraControlService` as JSON over HTTP/2 with mutual TLS on port 8443.

The native service does **not** control the camera directly. It receives a JSON
request, then forwards the action to a Java `CameraControlReceiver` via an
Android `am broadcast` intent; the Java side does the Camera2 work and file I/O.
SFTP is Java (JSch), not native. There is **no** WAV/media parser here.

Trust model: a client certificate is a full-access credential. The device mints
its own key on-device and is provisioned with a CA-signed cert (no private key
ships in the APK); any holder of a client cert issued by the CA can invoke every
operation.

## Operation modes

Unlike kanaha-audio / kanaha-calcs, the camera **cannot** run headless: Camera2
needs the foreground activity (`camera_available` is false while the app is
backgrounded), so the app must be foregrounded before use. `ApacheService` starts
the httpd with the app and stays `exported="false"`; remote control arrives via
the exported `CameraControlReceiver` (`am broadcast`), not a service start. Like
every Kanaha server app it advertises **mDNS/DNS-SD** (`_https._tcp`, TXT
`api=kanaha-camera-control`) so clients discover it without a static IP.

## Highest-value scan areas

Ranked by exploitability. The C service is a thin dispatch+IPC layer; the real
surface is the trust boundaries around it.

### 1. The exported broadcast receiver (access control)
`CameraControlReceiver` is `exported="true"`. It **must** carry
`android:permission="org.kanaha.camera.permission.CAMERA_CONTROL"` (a signature
permission) — without it, any app on the device can broadcast
`org.kanaha.CAMERA_CONTROL` and drive recording, `listFiles`, `deleteFiles` and
`sftpTransfer` (video exfiltration). Verify the permission is present on the
`<receiver>` and that `onReceive` does not trust unauthenticated extras.

### 2. The native→Java IPC target
`camera_control_service.c` builds an `am broadcast` to a **hardcoded package**
(`org.kanaha.camera/...CameraControlReceiver`). A build with an
`applicationIdSuffix` (e.g. `.probe`) sends to a receiver that does not exist and
the request blocks until the client times out. Any change to the package id,
the action string, or the broadcast construction is security- and
correctness-relevant. Watch for argument/quoting injection into the `am` command
line from caller-supplied fields.

### 3. SFTP host key verification (JSch)
`CameraControlReceiver` uses JSch. It must **fail closed**: a missing or unlisted
`known_hosts` must abort, never set `StrictHostKeyChecking=no`. A regression here
makes every video transfer MITM-able on the LAN.

### 4. Caller-controlled paths and JSON responses
`clip_name`, `pattern`, `filename`, `resolution`, etc. arrive as JSON and reach
the filesystem or are echoed into responses. Verify: `..`/`/` traversal is
rejected (C comments here have over-claimed validation the Java side actually
does — confirm the real enforcement point); caller strings echoed into JSON
responses are escaped (`json_unescape` turns a trailing `\` into a lone
backslash that can break the response).

### 5. Fixed-size response buffer + static adapter
The adapter allocates a fixed response buffer (`MAX_RESPONSE_SIZE`) and provides
the strong `camera_control_service_invoke_json` symbol overriding Axis2/C's weak
stub. Check every writer respects the buffer size, and the returned
`json_object` lifetime.

### 6. TLS configuration
`SSLVerifyClient require` must hold on every path (server, vhost, and each
`<Location>`). The `Include conf/ssl.conf` and `conf/axis2.conf` must **not** be
wrapped in `<IfModule>` — a binary missing mod_ssl must fail to start, not serve
cleartext on 8443. `server-status` at `/status` is gated behind mTLS; keep it so.

### 7. Keys are minted on-device, not shipped (deployment)
Nothing under `assets/ssl/` ships a private key any more: on first run the app
generates its own RSA key + CSR and is provisioned with a CA-signed cert (the CA
key lives off-device, never in the APK). Treat any private key committed to git
or added to `assets/ssl/` as a finding. `allowBackup` must be `false` so keys
under the files dir are not in the backup set.

## Testing
- On-device only: needs Camera2, mTLS, and the httpd child.
- Confirm HTTP/2 is genuinely negotiated (silent fallback is the failure mode):
  `curl -v --http2 --cert client.crt --key client.key --cacert ca.crt https://<dev>:8443/services/CameraControlService | grep -i ALPN` → `h2`.
- `startRecording` needs the camera activity foregrounded (`camera_available:true`).

## Reporting
Report vulnerabilities privately via this repository's GitHub security advisory
form; do not open a public issue.
