# Kanaha Camera Control System - Legal Summary

## License

**GPL v3+** (GNU General Public License version 3 or later)

Kanaha is licensed under GPL v3+ because it incorporates OpenCamera, which is GPL v3+ licensed. Under GPL copyleft requirements, derivative works must use a compatible license.

## Third-Party Components

| Component | License | Usage |
|-----------|---------|-------|
| OpenCamera | GPL v3+ | Camera engine |
| Apache Axis2/C | Apache 2.0 | HTTP/2 JSON-RPC services |
| Apache httpd | Apache 2.0 | HTTP/2 server |
| Apache APR | Apache 2.0 | Portable runtime |
| OpenSSL | Apache 2.0 | TLS encryption |
| nghttp2 | MIT | HTTP/2 protocol |
| json-c | MIT | JSON parsing |
| Expat | MIT | XML parsing |

**License Compatibility:** GPL v3 is compatible with Apache 2.0 and MIT licenses for combined works.

## Trademark Compliance

### Project Name: "Kanaha"

- **Origin:** Hawaiian geographic name (Kanaha Beach Park, Maui)
- **Status:** Geographic names are generally not trademarkable
- **Risk Level:** Very Low - comprehensive search found no conflicts in technology sector

### Third-Party Trademarks

The following are trademarks of their respective owners:

- **Apache, Apache Axis2/C, Apache HTTP Server** - The Apache Software Foundation
- **OpenCamera** - Mark Harman

**Compliance:** Kanaha uses these names solely for technical attribution. The project is independent and not affiliated with, endorsed by, or sponsored by any trademark holders.

## Required Files

| File | Purpose |
|------|---------|
| `LICENSE` | GPL v3 full text |
| `NOTICE` | Attribution for all third-party components |
| `TRADEMARKS.md` | Trademark acknowledgments and independence statement |

## Source Code Availability

Per GPL v3 requirements, complete source code is available in this repository. Users who distribute Kanaha or derivative works must also provide source code access.

## Summary

Kanaha Camera Control System is legally ready for public release:

- ✅ **License:** GPL v3+ (required by OpenCamera copyleft)
- ✅ **Attribution:** NOTICE file credits all components
- ✅ **Trademarks:** Proper acknowledgment, no false affiliation claims
- ✅ **Independence:** Clear statement of project independence
- ✅ **Source Code:** Complete source available (GPL compliance)

---

## Initial Commit Message

```
Initial release of Kanaha Camera Control System

Kanaha transforms Android phones into network-controllable cameras with
a secure HTTP/2 API. Control multiple cameras simultaneously using HTTPS
requests with mutual TLS (mTLS) certificate authentication.

Features:
- Multi-camera control via JSON-RPC API
- HTTP/2 + mTLS security (certificate authentication required)
- Wide device support: Android 5.0+ (tested Moto X4 2017 to Pixel 9 Pro 2024)
- SFTP file transfer with SSH key authentication
- mDNS service discovery
- Built in C (Apache httpd + Axis2/C) for native performance

Components:
- Camera engine: OpenCamera (GPL v3+)
- Web services: Apache Axis2/C (Apache 2.0)
- HTTP/2 server: Apache httpd (Apache 2.0)
- TLS: OpenSSL (Apache 2.0)

License: GPL v3+ (required by OpenCamera copyleft)

Documentation:
- docs/MULTI_CAMERA_DEPLOYMENT_SYSTEM.md - Complete system guide
- docs/SECURITY.md - Security model and hardening
- docs/SFTP-FILE-TRANSFER.md - SSH key setup
- docs/ANDROID_APK_BUILDING.md - Build from source

Co-Authored-By: Claude <noreply@anthropic.com>
```
