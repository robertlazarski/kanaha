/*
 * Kanaha Camera Control System
 * MCP (Model Context Protocol) stdio transport
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * Implements JSON-RPC 2.0 over stdin/stdout for camera control operations,
 * following the same MCP pattern as the Axis2/C financial benchmark service.
 *
 * Tools exposed:
 *   - getStatus       (camera battery, storage, GPS, recording state)
 *   - startRecording  (begin video recording with optional scheduled start)
 *   - stopRecording   (stop video recording)
 *   - playTone        (play sync slate tone for multi-camera alignment)
 *   - listFiles       (list recorded video files)
 *   - deleteFiles     (delete recorded files by pattern)
 *   - sftpTransfer    (transfer files to storage server)
 *   - configure       (set camera resolution, fps, codec)
 *   - cleanupFiles    (clean up transferred files)
 *
 * Usage (Claude Desktop claude_desktop_config.json):
 * {
 *   "mcpServers": {
 *     "kanaha-camera": {
 *       "command": "/data/data/org.kanaha.camera/files/kanaha-mcp",
 *       "args": []
 *     }
 *   }
 * }
 *
 * Protocol version: 2024-11-05
 */

#ifndef KANAHA_MCP_H
#define KANAHA_MCP_H

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Run the MCP JSON-RPC 2.0 stdio loop for camera control.
 *
 * Reads newline-delimited JSON requests from stdin, dispatches to
 * camera_control_service_invoke_json_impl(), and writes JSON-RPC 2.0
 * responses to stdout. Returns when stdin reaches EOF.
 *
 * stdout is reserved for MCP protocol — all logging goes to Android
 * logcat via __android_log_print or to a file.
 */
void kanaha_run_mcp_stdio(void);

#ifdef __cplusplus
}
#endif

#endif /* KANAHA_MCP_H */
