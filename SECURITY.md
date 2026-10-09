# Security Threat Model — Kanaha Camera

## Project Description

Kanaha Camera is an Android camera app, derived from OpenCamera, that can be
controlled over the network. It runs a statically linked **Apache httpd** as a
child process, serving an Axis2/C web service as JSON over HTTP/2 with mutual
TLS on port 8443. A laptop uses it to start and stop recording, list and delete
clips, describe a clip on-device, and send clips to an SFTP server.

The native service does not touch the camera. It validates the request and
forwards it to the app's Java `CameraControlReceiver`, which does the Camera2
work, file handling and SFTP. The forwarding normally runs `am broadcast`. On a
device that refuses `am` from apps, it uses a request file in the app's
private cache instead.

The app is intended for **small trusted LANs**, such as a laptop and a few
phones at a shoot. It is not meant to be exposed to the internet, and every
network endpoint requires a client certificate.

| Component | Version | Role |
|---|---|---|
| Apache httpd | 2.4.69 | HTTP server, mod_ssl + mod_http2 |
| OpenSSL | 3.5.9 | TLS 1.2+, mutual authentication |
| Axis2/C | 2.0.0 | Service container, JSON-RPC dispatch |
| nghttp2 | 1.64.0 | HTTP/2 |
| json-c | 0.18 | JSON parsing |
| JSch (mwiede) | 0.2.22 | SFTP, in Java |
| Bouncy Castle | 1.84 | On-device key and CSR generation |
| ML Kit GenAI | `nano` flavor only | `describeClip` on Gemini Nano, through AICore |

The native libraries are built by
[kanaha-android-deps](https://github.com/robertlazarski/kanaha-android-deps).

## Roles and Trust Levels

| Role | Trust Level | Description |
|---|---|---|
| **Device owner** | Fully trusted | Physical control of the phone. Installs the app, holds the screen lock, can enable adb. |
| **adb user** | Fully trusted | Can `run-as` the app on a debuggable build and read everything it stores. The MCP binary is reached this way. Treated as equivalent to device ownership. |
| **Operator** | Fully trusted | Holds the off-device Kanaha CA key, signs each phone's CSR and issues client certificates. |
| **mTLS client** | Trusted | Holds a client certificate issued by the Kanaha CA. Can invoke every operation: record, list, delete, describe, transfer. |
| **LAN peer** | Untrusted | Can reach port 8443, but is rejected during the TLS handshake without a client certificate. |
| **Other apps on the phone** | Untrusted | Can send broadcasts. They cannot reach `CameraControlReceiver`, which requires a signature-level permission only this app holds. |
| **SFTP server** | Untrusted until verified | Accepted only if its host key is in `known_hosts`. |
| **Recorded media** | Untrusted input | `describeClip` decodes a clip the app recorded, but the clip is chosen by name by the caller. |

There is no privilege separation *within* the mTLS client role. A client
certificate is a full-access credential.

## Security Boundaries

### What IS a security issue

- **Reaching the receiver without the permission.** Any way for another app to
  drive `CameraControlReceiver`, for example a missing or weakened
  `android:permission` on the `<receiver>`, or a second exported entry point.
  Its operations include deleting clips and sending them off the phone.
- **Injection into the native-to-Java forwarding.** Caller-supplied fields
  becoming extra `am` arguments, or changing the target package or action.
  The native side uses `fork`/`execvp` with an argument array, never a shell.
  This also covers the fallback request file: its content must stay data the
  receiver validates, never instructions.
- **Path traversal.** A caller-supplied clip name, pattern or filename that
  reaches outside the video directory. This includes the `.kanaha.json`
  sidecar files, which must follow their clip.
- **Authentication bypass.** Reaching any operation without a client
  certificate, or a configuration where `SSLVerifyClient require` is not
  enforced on some path. `ssl.conf` and `axis2.conf` are deliberately not
  wrapped in `<IfModule>`, so a binary without mod_ssl fails to start instead
  of serving without TLS.
- **SFTP host-key verification failing open.** A regression that accepts an
  unknown host would make every transfer interceptable on the LAN.
- **Memory corruption in the native service.** Overflow of the fixed response
  buffer, JSON lifetime errors in the static service adapter, or unescaped
  caller strings breaking the response.
- **Key disclosure.** A private key in the APK or in git, a key returned by an
  API or written to a log, or a key reachable through backup (`allowBackup` is
  `false`).
- **`describeClip` crashing or leaking.** A malformed clip must fail with an
  error, not crash the receiver. In the `foss` flavor, any path that sends
  frames off-device is a finding.
- **HTTP/2 protocol abuse** that crashes or exhausts the server. The limits in
  `http2-performance.conf` (`H2MaxSessionStreams 1`, `LimitRequestFieldSize
  4096`) exist for this reason.

### What is NOT a security issue

- **Physical access to an unlocked device, and adb access.** By design,
  `run-as` on a debuggable build grants app-level access, and the MCP
  integration depends on it.
- **The `nano` flavor sending frames to AICore.** `describeClip` hands frames
  to Google's on-device AICore service. Nothing leaves the phone. The `foss`
  flavor has no ML Kit dependency at all.
- **Certificate revocation not yet wired up.** Each phone mints its own key
  and receives a one-year certificate signed by the Kanaha CA. A lost phone is
  handled by letting its certificate expire, or by reissuing the CA and
  re-provisioning. CRL/OCSP is a pre-production item, not a vulnerability.
- **The camera must be in the foreground.** Android gives the camera only to
  the app on screen, so recording fails while the app is in the background.
  That is a platform rule, not a denial of service.
- **Exposure to a hostile network.** The model assumes a private LAN.
  Publishing port 8443 to the internet is a deployment decision outside it.
- **Vulnerabilities in Android or an unmodified upstream library.** Report
  those upstream. Report here if this app *uses* one unsafely or ships a
  version known to be affected.
- **Network-layer denial of service,** such as SYN floods.

## Architecture and Attack Surface

### Request path

```
mTLS client (laptop)
   │  HTTP/2 + client certificate, port 8443
   ▼
Apache httpd child process  (mod_ssl → mod_http2 → mod_axis2)
   │
   ▼
camera_control_service.c   validate, then forward
   │  fork/execvp `am broadcast` with an explicit component
   │  (or, where `am` is refused: a request file in cache/ipc_requests/,
   │   picked up by CameraIpcPoller and sent as an in-app broadcast)
   ▼
CameraControlReceiver  (signature permission)  ──► Camera2, files, JSch, AICore
   │
   ▼
cache/response_<op>.json  ──► read back by the native service
```

The MCP binary, `kanaha-camera-mcp`, is a second entry point to the same
operations. It is reached over adb stdio with `run-as`, has no TLS, and
relies on adb for access control.

### Attack surface by component

| Component | Exposure | Notes |
|---|---|---|
| `camera_control_service.c` | **Untrusted JSON** | Action dispatch, name validation, `am` argument construction, request files. |
| `axis2_static_service_adapter.c` | Framework boundary | Fixed response buffer; overrides Axis2/C's weak dispatch symbol. |
| `CameraControlReceiver.java` | Broadcast extras | Permission-gated; the real enforcement point for file names (`SecurityValidator`). |
| `CameraIpcPoller.java` | App-private files | Only used where `am` is refused. Request files are writable only by the app's own uid. |
| `FrameSampler.java` | Recorded media | Decodes a clip in the app process for `describeClip`. |
| JSch SFTP | Outbound network | Host-key checking against `known_hosts`, failing closed. |
| `kanaha_mcp.c` | adb stdio | Same operations, no TLS. |
| Apache + Axis2/C + OpenSSL | Network | Upstream code linked statically, so it stays current only by relinking and releasing the app. |

## Deployment Hardening

See [docs/SECURITY.md](docs/SECURITY.md) for the full security architecture:

- the httpd configuration and security headers
- per-device certificate provisioning
- audit logging and API restrictions
- the security testing procedure

## Dependency Security

The app ships its native libraries inside the APK, so an upstream fix does not
reach a phone until the app is relinked and released:

| Component | Minimum | Reason |
|---|---|---|
| Apache httpd | 2.4.69 | HTTP/2 double-free and HTTP/2 Bomb fixes (2.4.67/2.4.68) |
| OpenSSL | 3.5.9 | 3.5 LTS; 3.0 and 3.2 no longer receive fixes |
| nghttp2 | 1.64.0 | HTTP/2 protocol handling |
| Axis2/C | 2.0.0 | Current release |

To update: raise the pin in kanaha-android-deps, rebuild it, then run this
repository's `build-android.sh` and build the APK.

## Reporting Security Issues

**Do not open a public issue for a security vulnerability.**

Report privately through GitHub's security advisory form on this repository
(*Security* → *Report a vulnerability*). That opens a private thread visible
only to the maintainer.

Please include:

- The affected component and file
- A description of the issue and its impact
- Steps to reproduce, ideally with the JSON request or broadcast that triggers it
- The device and Android version, if relevant

Expect an initial response within a few days. This is a personal project rather
than a funded one, so please allow reasonable time for a fix before public
disclosure. Credit will be given in the release notes unless you prefer
otherwise.
