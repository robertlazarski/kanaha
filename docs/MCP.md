# MCP Support for Kanaha Camera Control

**Summary**: Kanaha gains MCP (Model Context Protocol) support, enabling AI
assistants (Claude Desktop, Claude API, custom agents) to discover and control
Android cameras as tools. The MCP server runs as a native ARM64 binary on the
phone — 98 KB, no JVM, sub-50ms startup.

MCP is JSON-RPC 2.0. Three required methods: `initialize`, `tools/list`,
`tools/call`. The transport is stdio (Claude Desktop launches the binary as a
subprocess). The same camera operations available via HTTP/2 JSON-RPC are
accessible through MCP with identical request/response semantics.

This implementation follows the same MCP pattern as the Apache Axis2/C
financial benchmark service (`finbench_mcp.c` in `axis-axis2-c-core`). The
protocol framing, tool catalog, and dispatch architecture are shared; only the
tool definitions and service handlers differ.

---

## What MCP Adds to Kanaha

Without MCP, controlling cameras requires curl commands with mTLS certificates:

```bash
curl -sk --http2 --cert client.crt --key client.key --cacert ca.crt \
  -H "Content-Type: application/json" \
  -d '{"action":"startRecording","clip_name":"test"}' \
  "https://192.168.1.100:8443/services/CameraControlService/startRecording"
```

With MCP, an AI assistant does this:

> **User**: Start recording on the Pixel with clip name "interview_01"
>
> **Claude**: [calls `startRecording` tool with `{"clip_name":"interview_01"}`]
> Recording started on your Pixel 9 Pro at 4K quality.

The assistant discovered `startRecording` from the tool catalog, knew the
parameters from the `inputSchema`, and constructed the request from natural
language. No curl, no certificates, no URL construction.

---

## MCP Tools (9 camera operations)

| Tool | Description | Key Parameters |
|------|-------------|----------------|
| `getStatus` | Battery, storage, GPS, recording state | — |
| `startRecording` | Begin video recording | `clip_name`, `quality`, `start_at`, `open_gate` |
| `stopRecording` | Stop recording | — |
| `playTone` | Software sync slate tone | `frequency`, `duration_ms`, `start_at` |
| `listFiles` | List recorded video files | `pattern` |
| `deleteFiles` | Delete files by pattern | `pattern` (required) |
| `sftpTransfer` | Transfer to storage server | `storage_server_id`, `video_filename` |
| `configure` | Set resolution, fps, codec | `resolution`, `fps`, `codec` |
| `cleanupFiles` | Clean up transferred files | `cleanup_policy`, `days_threshold` |

All tools have full `inputSchema` with parameter types, descriptions, and
defaults. Claude reads the schema and constructs valid requests without
documentation.

---

## Architecture

```
Claude Desktop (MCP client)
    ↓ stdin (JSON-RPC 2.0)
libkanaha_mcp.so (native ARM64 binary, 98 KB)
    ↓ calls camera_control_service_invoke_json_impl()
camera_control_service.c (action routing)
    ↓ fork/exec "am broadcast" (secure IPC, no shell)
CameraControlReceiver.java (Android BroadcastReceiver)
    ↓ goAsync() + background thread
OpenCamera Camera2 API
    ↓ response file
camera_control_service.c (reads response)
    ↓ stdout (JSON-RPC 2.0)
Claude Desktop (displays result)
```

The MCP binary (`libkanaha_mcp.so`) links only json-c + the camera service
code — no Apache httpd, no OpenSSL, no Axis2/C framework. This keeps it at
98 KB vs the full HTTP server at 4.8 MB.

---

## Relationship to Axis2/C MCP

Kanaha's MCP implementation follows the pattern established in the
`axis-axis2-c-core` financial benchmark service:

| | Financial Benchmark MCP | Kanaha Camera MCP |
|---|---|---|
| Binary | `financial-benchmark-mcp` | `libkanaha_mcp.so` |
| Size | ~500 KB (links Axis2/C libs) | 98 KB (json-c only) |
| Tools | 3 (portfolioVariance, monteCarlo, scenarioAnalysis) | 9 (camera operations) |
| Dispatch | `finbench_*_json_only()` functions | `camera_control_service_invoke_json_impl()` |
| Schemas | Static `finbench_mcp_tool_t[]` array | Static `kanaha_mcp_tool_t[]` array |
| Platform | Linux x86_64, Android ARM64 | Android ARM64 |
| Protocol | MCP 2024-11-05 | MCP 2024-11-05 |

Same MCP client connects to both. The protocol is identical — only the tool
names and parameter schemas differ.

---

## Live Test: Pixel 9 Pro (April 9, 2026)

Tested via ADB USB port forwarding (`adb forward tcp:18443 tcp:8443`):

### getStatus

**curl:**
```bash
$CURL -d '{"action":"getStatus"}' "$BASE/getStatus"
```

**MCP equivalent:**
```json
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
  "name":"getStatus","arguments":{}}}
```

**Response:**
```json
{
    "success": true,
    "status": {
        "device_name": "pixel9pro",
        "device_model": "Pixel 9 Pro",
        "device_manufacturer": "Google",
        "state": "idle",
        "is_recording": false,
        "battery_level": 75,
        "storage_available_mb": 144559,
        "camera_available": true,
        "preview_active": true
    }
}
```

### startRecording

**curl:**
```bash
$CURL -H "Content-Type: application/json" \
  -d '{"action":"startRecording","clip_name":"mcp_test"}' "$BASE/startRecording"
```

**MCP equivalent:**
```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
  "name":"startRecording","arguments":{"clip_name":"mcp_test"}}}
```

**Response:**
```json
{"success": true, "message": "Recording started", "clip_name": "mcp_test", "quality": "4K"}
```

### stopRecording

**curl:**
```bash
$CURL -H "Content-Type: application/json" \
  -d '{"action":"stopRecording"}' "$BASE/stopRecording"
```

**MCP equivalent:**
```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
  "name":"stopRecording","arguments":{}}}
```

**Response:**
```json
{"success": true, "message": "Recording stopped"}
```

### playTone

**curl:**
```bash
$CURL -H "Content-Type: application/json" \
  -d '{"action":"playTone","frequency":1000,"duration_ms":300}' "$BASE/playTone"
```

**MCP equivalent:**
```json
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
  "name":"playTone","arguments":{"frequency":1000,"duration_ms":300}}}
```

**Response:**
```json
{"success": true, "message": "Tone scheduled", "frequency": 1000, "duration_ms": 300}
```

### listFiles

**curl:**
```bash
$CURL -d '{"action":"listFiles"}' "$BASE/listFiles"
```

**MCP equivalent:**
```json
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{
  "name":"listFiles","arguments":{}}}
```

**Response** (39 files, 42 GB on the Pixel 9 Pro):
```json
{
    "success": true,
    "file_count": 39,
    "total_size": 45337897331,
    "files": [
        {"name": "VID_20260409_091740.mp4", "size": 71288201, "modified": "2026-04-09 09:17:54"},
        "..."
    ]
}
```

### configure

**curl:**
```bash
$CURL -H "Content-Type: application/json" \
  -d '{"action":"configure","resolution":"1920x1080","fps":"30","codec":"H264"}' \
  "$BASE/configure"
```

**MCP equivalent:**
```json
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{
  "name":"configure","arguments":{"resolution":"1920x1080","fps":"30","codec":"H264"}}}
```

**Response:**
```json
{"success": true, "message": "Camera configured", "resolution": "1920x1080", "fps": "30", "codec": "H264"}
```

---

## Claude Desktop Configuration

```json
{
  "mcpServers": {
    "kanaha-camera": {
      "command": "/data/data/org.kanaha.camera/files/kanaha-mcp",
      "args": []
    }
  }
}
```

For remote cameras accessible over the network, use the Axis2/Java MCP bridge
pointing at the camera's HTTPS endpoint:

```json
{
  "mcpServers": {
    "kanaha-camera-remote": {
      "command": "java",
      "args": ["-jar", "axis2-mcp-bridge-2.0.1-SNAPSHOT-exe.jar",
               "--base-url", "https://192.168.1.100:8443/axis2-json-api",
               "--keystore", "client-keystore.p12",
               "--truststore", "ca-truststore.p12"]
    }
  }
}
```

---

## Network Discovery

Kanaha cameras register as `_https._tcp` mDNS services with
`api=kanaha-camera-control` in the TXT record. Discover cameras using:

```bash
# Automatic discovery (mDNS first, falls back to port scan)
kanaha-discover.sh

# JSON output for scripts
kanaha-discover.sh --json

# Check specific IP
kanaha-discover.sh --ip 192.168.1.100
```

**Note**: mDNS discovery requires the phone and host to be on the same L2
network with multicast enabled. If the phone has only a link-local IPv6
address (no IPv4 DHCP), use ADB USB port forwarding:

```bash
adb forward tcp:18443 tcp:8443
# Then use https://localhost:18443/... for all API calls
```

---

## Build

The MCP binary is built automatically as part of the Kanaha APK:

```bash
cd kanaha-camera-app
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleDebug
```

Three native binaries are produced:
- `libkanaha-camera-control.so` — shared library (5.6 MB)
- `libkanaha_httpd.so` — HTTP/2 server executable (4.8 MB)
- `libkanaha_mcp.so` — MCP stdio executable (98 KB)

---

## Files

| File | Purpose |
|------|---------|
| `axis2c/kanaha_mcp.h` | Public API (`kanaha_run_mcp_stdio`) |
| `axis2c/kanaha_mcp.c` | MCP stdio loop, 9-tool catalog, dispatch |
| `axis2c/kanaha_mcp_main.c` | Standalone binary entry point |
| `axis2c/camera_control_service.c` | Camera operations (shared with HTTP server) |
| `services/CameraControlService/services.xml` | Operation definitions with `mcpInputSchema` |
