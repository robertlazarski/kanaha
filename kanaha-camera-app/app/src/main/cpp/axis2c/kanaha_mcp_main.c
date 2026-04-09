/*
 * Kanaha Camera Control System
 * MCP stdio binary entry point
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * Standalone binary that Claude Desktop launches as a subprocess.
 * Reads MCP JSON-RPC 2.0 requests from stdin, dispatches to camera
 * control operations, writes responses to stdout.
 *
 * Build: linked with camera_control_service.c + kanaha_mcp.c + json-c
 * Install: /data/data/org.kanaha.camera/files/kanaha-mcp
 */

#include "kanaha_mcp.h"

int main(void)
{
    kanaha_run_mcp_stdio();
    return 0;
}
