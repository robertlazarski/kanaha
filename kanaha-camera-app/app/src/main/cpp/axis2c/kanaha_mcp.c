/*
 * Kanaha Camera Control System
 * MCP stdio transport — JSON-RPC 2.0 loop for camera operations
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * Adapted from the Apache Axis2/C financial benchmark MCP implementation
 * (finbench_mcp.c, Apache 2.0 licensed). This file is an independent
 * implementation under GPL-3.0-or-later, following the same MCP protocol
 * patterns.
 *
 * Three required MCP methods:
 *   initialize  — protocol version + server info + capabilities
 *   tools/list  — camera tool catalog with full inputSchema
 *   tools/call  — dispatches to camera_control_service_invoke_json_impl()
 */

#include "kanaha_mcp.h"

#include <json-c/json.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "KanahaMCP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) fprintf(stderr, __VA_ARGS__)
#define LOGE(...) fprintf(stderr, __VA_ARGS__)
#endif

/* ============================================================================
 * Protocol constants
 * ============================================================================
 */

#define KANAHA_MCP_PROTOCOL_VERSION   "2024-11-05"
#define KANAHA_MCP_SERVER_NAME        "kanaha-camera-control"
#define KANAHA_MCP_SERVER_VERSION     "1.0.0"

/* JSON-RPC 2.0 error codes */
#define MCP_ERR_PARSE_ERROR       -32700
#define MCP_ERR_INVALID_REQUEST   -32600
#define MCP_ERR_METHOD_NOT_FOUND  -32601
#define MCP_ERR_INVALID_PARAMS    -32602
#define MCP_ERR_INTERNAL_ERROR    -32603

/* Maximum request size */
#define MAX_MCP_REQUEST_BYTES   (1 * 1024 * 1024)  /* 1 MB — camera ops are small */
#define MCP_LINE_INITIAL_CAP    4096

/* Response buffer for camera service */
#define CAMERA_RESPONSE_SIZE    65536

/* ============================================================================
 * External: camera service entry point (from camera_control_service.c)
 * ============================================================================
 */

extern int camera_control_service_invoke_json_impl(
    const char* json_request,
    char* json_response,
    size_t response_size);

/* ============================================================================
 * Tool catalog — input schemas for all 9 camera operations
 * ============================================================================
 */

static const char SCHEMA_GET_STATUS[] =
    "{"
    "\"type\":\"object\","
    "\"properties\":{},"
    "\"required\":[],"
    "\"description\":\"No parameters required. Returns camera battery level, "
        "storage space, GPS timestamp, recording state, and device info.\""
    "}";

static const char SCHEMA_START_RECORDING[] =
    "{"
    "\"type\":\"object\","
    "\"properties\":{"
        "\"clip_name\":{\"type\":\"string\","
            "\"description\":\"Name for the video clip. Default: default_clip\"},"
        "\"quality\":{\"type\":\"string\","
            "\"description\":\"Recording quality: 4K, 1080p, 720p. Default: 4K\"},"
        "\"duration\":{\"type\":\"integer\","
            "\"description\":\"Maximum recording duration in seconds. Default: 1800 (30 min)\"},"
        "\"format\":{\"type\":\"string\","
            "\"description\":\"Video container format: MP4, MKV. Default: MP4\"},"
        "\"start_at\":{\"type\":\"integer\","
            "\"description\":\"UTC epoch millisecond for scheduled start. "
                "Enables frame-accurate multi-camera sync — each camera counts down "
                "to the same wall-clock time regardless of network delay. "
                "Valid range: 50ms to 30000ms in the future. 0 = start immediately.\"},"
        "\"open_gate\":{\"type\":\"boolean\","
            "\"description\":\"Record at full 4:3 sensor resolution (2560x1920 on Pixel 9 Pro). "
                "Preserves all sensor rows for maximum reframing flexibility in post. "
                "Takes 3-5 seconds to reopen camera session. Default: false\"}"
    "},"
    "\"required\":[]"
    "}";

static const char SCHEMA_STOP_RECORDING[] =
    "{"
    "\"type\":\"object\","
    "\"properties\":{},"
    "\"required\":[],"
    "\"description\":\"Stops the current recording immediately.\""
    "}";

static const char SCHEMA_PLAY_TONE[] =
    "{"
    "\"type\":\"object\","
    "\"properties\":{"
        "\"frequency\":{\"type\":\"integer\","
            "\"description\":\"Tone frequency in Hz. Default: 1000\"},"
        "\"duration_ms\":{\"type\":\"integer\","
            "\"description\":\"Tone duration in milliseconds. Default: 500\"},"
        "\"start_at\":{\"type\":\"integer\","
            "\"description\":\"UTC epoch millisecond for scheduled playback. "
                "Used for software sync slate — all cameras play the tone at the "
                "same wall-clock time. Post-processing detects waveform onset "
                "to ±1-5ms accuracy. 0 = play immediately.\"}"
    "},"
    "\"required\":[]"
    "}";

static const char SCHEMA_LIST_FILES[] =
    "{"
    "\"type\":\"object\","
    "\"properties\":{"
        "\"pattern\":{\"type\":\"string\","
            "\"description\":\"Glob pattern to filter files. Default: * (all files)\"}"
    "},"
    "\"required\":[]"
    "}";

static const char SCHEMA_DELETE_FILES[] =
    "{"
    "\"type\":\"object\","
    "\"properties\":{"
        "\"pattern\":{\"type\":\"string\","
            "\"description\":\"Glob pattern for files to delete. Required.\"}"
    "},"
    "\"required\":[\"pattern\"]"
    "}";

static const char SCHEMA_SFTP_TRANSFER[] =
    "{"
    "\"type\":\"object\","
    "\"properties\":{"
        "\"storage_server_id\":{\"type\":\"string\","
            "\"description\":\"Identifier for the destination storage server\"},"
        "\"video_filename\":{\"type\":\"string\","
            "\"description\":\"Name of the video file to transfer\"},"
        "\"destination_folder\":{\"type\":\"string\","
            "\"description\":\"Destination folder on the storage server\"}"
    "},"
    "\"required\":[\"storage_server_id\",\"video_filename\"]"
    "}";

static const char SCHEMA_CONFIGURE[] =
    "{"
    "\"type\":\"object\","
    "\"properties\":{"
        "\"resolution\":{\"type\":\"string\","
            "\"description\":\"Video resolution: 3840x2160, 1920x1080, 1280x720. Default: 1920x1080\"},"
        "\"fps\":{\"type\":\"string\","
            "\"description\":\"Frames per second: 30, 60. Default: 30\"},"
        "\"codec\":{\"type\":\"string\","
            "\"description\":\"Video codec: H264, HEVC. Default: H264\"}"
    "},"
    "\"required\":[]"
    "}";

static const char SCHEMA_CLEANUP_FILES[] =
    "{"
    "\"type\":\"object\","
    "\"properties\":{"
        "\"cleanup_policy\":{\"type\":\"string\","
            "\"description\":\"Policy: transferred_only, all, older_than. Default: transferred_only\"},"
        "\"days_threshold\":{\"type\":\"integer\","
            "\"description\":\"For older_than policy, delete files older than N days. Default: 30\"},"
        "\"file_pattern\":{\"type\":\"string\","
            "\"description\":\"Glob pattern to match. Default: *.mp4\"}"
    "},"
    "\"required\":[]"
    "}";

/* ============================================================================
 * Tool catalog struct
 * ============================================================================
 */

typedef struct {
    const char *name;
    const char *description;
    const char *input_schema_json;
} kanaha_mcp_tool_t;

static const kanaha_mcp_tool_t kanaha_mcp_tools[] = {
    {
        "getStatus",
        "Get camera status: battery level, available storage, GPS timestamp and "
        "accuracy, current recording state, device model, and Android version. "
        "Use this to check if the camera is ready before starting operations.",
        SCHEMA_GET_STATUS
    },
    {
        "startRecording",
        "Start video recording. Supports scheduled start via start_at parameter "
        "for frame-accurate multi-camera synchronization — each camera counts down "
        "to the same UTC wall-clock millisecond regardless of WiFi delivery timing. "
        "Supports open gate recording (full 4:3 sensor) on compatible devices.",
        SCHEMA_START_RECORDING
    },
    {
        "stopRecording",
        "Stop the current video recording immediately.",
        SCHEMA_STOP_RECORDING
    },
    {
        "playTone",
        "Play a synthesized sine wave tone through the phone speaker. Used as a "
        "software sync slate for multi-camera alignment — all cameras play the tone "
        "at the same scheduled time (start_at). Post-processing detects waveform "
        "onset in each recording's audio track to ±1-5ms inter-camera accuracy.",
        SCHEMA_PLAY_TONE
    },
    {
        "listFiles",
        "List recorded video files on the device. Returns filenames, sizes, "
        "timestamps, and transfer status.",
        SCHEMA_LIST_FILES
    },
    {
        "deleteFiles",
        "Delete recorded video files matching a glob pattern.",
        SCHEMA_DELETE_FILES
    },
    {
        "sftpTransfer",
        "Transfer a video file to a remote storage server via SFTP with Ed25519 "
        "SSH key authentication. Also transfers the companion sidecar metadata file.",
        SCHEMA_SFTP_TRANSFER
    },
    {
        "configure",
        "Configure camera recording settings: resolution, frame rate, and codec. "
        "Changes take effect on the next startRecording call.",
        SCHEMA_CONFIGURE
    },
    {
        "cleanupFiles",
        "Clean up video files from the device based on a policy: transferred files "
        "only, all files, or files older than a threshold.",
        SCHEMA_CLEANUP_FILES
    },
    { NULL, NULL, NULL }  /* sentinel */
};

/* ============================================================================
 * JSON-RPC 2.0 output helpers
 * ============================================================================
 */

static void mcp_write_result(json_object *id_obj, json_object *result_obj)
{
    json_object *response = json_object_new_object();
    json_object_object_add(response, "jsonrpc", json_object_new_string("2.0"));

    json_object_get(id_obj);
    json_object_object_add(response, "id", id_obj);
    json_object_object_add(response, "result", result_obj);

    printf("%s\n",
        json_object_to_json_string_ext(response, JSON_C_TO_STRING_PLAIN));
    fflush(stdout);

    json_object_put(response);
}

static void mcp_write_error(json_object *id_obj, int code, const char *message)
{
    json_object *error_obj = json_object_new_object();
    json_object_object_add(error_obj, "code",    json_object_new_int(code));
    json_object_object_add(error_obj, "message", json_object_new_string(message));

    json_object *response = json_object_new_object();
    json_object_object_add(response, "jsonrpc", json_object_new_string("2.0"));

    if (id_obj) {
        json_object_get(id_obj);
        json_object_object_add(response, "id", id_obj);
    } else {
        json_object_object_add(response, "id", json_object_new_null());
    }

    json_object_object_add(response, "error", error_obj);

    printf("%s\n",
        json_object_to_json_string_ext(response, JSON_C_TO_STRING_PLAIN));
    fflush(stdout);

    json_object_put(response);
}

/* ============================================================================
 * MCP method handlers
 * ============================================================================
 */

static json_object *mcp_handle_initialize(void)
{
    json_object *result = json_object_new_object();
    json_object_object_add(result, "protocolVersion",
        json_object_new_string(KANAHA_MCP_PROTOCOL_VERSION));

    json_object *capabilities = json_object_new_object();
    json_object_object_add(capabilities, "tools", json_object_new_object());
    json_object_object_add(result, "capabilities", capabilities);

    json_object *server_info = json_object_new_object();
    json_object_object_add(server_info, "name",
        json_object_new_string(KANAHA_MCP_SERVER_NAME));
    json_object_object_add(server_info, "version",
        json_object_new_string(KANAHA_MCP_SERVER_VERSION));
    json_object_object_add(result, "serverInfo", server_info);

    return result;
}

static json_object *mcp_handle_tools_list(void)
{
    json_object *tools_array = json_object_new_array();

    for (const kanaha_mcp_tool_t *tool = kanaha_mcp_tools;
         tool->name != NULL; tool++) {
        json_object *tool_obj = json_object_new_object();

        json_object_object_add(tool_obj, "name",
            json_object_new_string(tool->name));
        json_object_object_add(tool_obj, "description",
            json_object_new_string(tool->description));

        json_object *schema = json_tokener_parse(tool->input_schema_json);
        if (!schema) {
            schema = json_object_new_object();
            json_object_object_add(schema, "type",
                json_object_new_string("object"));
        }
        json_object_object_add(tool_obj, "inputSchema", schema);

        json_object_array_add(tools_array, tool_obj);
    }

    json_object *result = json_object_new_object();
    json_object_object_add(result, "tools", tools_array);
    return result;
}

/**
 * Dispatch tools/call to the camera service.
 *
 * The camera service expects a JSON string with "action" and parameters.
 * MCP sends params.name (tool name) and params.arguments (parameters).
 * We merge them: {"action":"<name>", ...arguments}.
 */
static json_object *mcp_handle_tools_call(
    json_object  *params,
    int          *out_code,
    const char  **out_msg)
{
    *out_code = 0;
    *out_msg  = NULL;

    if (!params || !json_object_is_type(params, json_type_object)) {
        *out_code = MCP_ERR_INVALID_PARAMS;
        *out_msg  = "params must be an object with name and arguments fields";
        return NULL;
    }

    /* Extract tool name */
    json_object *name_obj = NULL;
    if (!json_object_object_get_ex(params, "name", &name_obj)
            || !json_object_is_type(name_obj, json_type_string)) {
        *out_code = MCP_ERR_INVALID_PARAMS;
        *out_msg  = "params.name is required and must be a string";
        return NULL;
    }
    const char *tool_name = json_object_get_string(name_obj);

    /* Verify tool exists in catalog */
    int found = 0;
    for (const kanaha_mcp_tool_t *t = kanaha_mcp_tools; t->name; t++) {
        if (strcmp(t->name, tool_name) == 0) { found = 1; break; }
    }
    if (!found) {
        *out_code = MCP_ERR_METHOD_NOT_FOUND;
        *out_msg  = "Unknown tool. Available: getStatus, startRecording, "
                    "stopRecording, playTone, listFiles, deleteFiles, "
                    "sftpTransfer, configure, cleanupFiles";
        return NULL;
    }

    /* Build the JSON request for camera_control_service_invoke_json_impl.
     * The camera service expects: {"action":"<name>", "param1":"val1", ...}
     * MCP gives us: params.arguments = {"param1":"val1", ...}
     * We create a new object merging action + arguments. */
    json_object *request = json_object_new_object();
    json_object_object_add(request, "action",
        json_object_new_string(tool_name));

    /* Merge arguments into request */
    json_object *args_obj = NULL;
    if (json_object_object_get_ex(params, "arguments", &args_obj)
            && json_object_is_type(args_obj, json_type_object)) {
        json_object_object_foreach(args_obj, key, val) {
            json_object_get(val);  /* bump refcount — request takes ownership */
            json_object_object_add(request, key, val);
        }
    }

    const char *request_str = json_object_to_json_string_ext(
        request, JSON_C_TO_STRING_PLAIN);

    LOGI("MCP tools/call: %s -> %s", tool_name, request_str);

    /*
     * SECURITY: The camera service is responsible for sanitizing all
     * user-provided string arguments (filenames, glob patterns) to prevent
     * path traversal. This MCP layer validates tool names only.
     */

    /* Heap-allocate response buffer — avoids 64KB stack allocation */
    char *response_buf = malloc(CAMERA_RESPONSE_SIZE);
    if (!response_buf) {
        *out_code = MCP_ERR_INTERNAL_ERROR;
        *out_msg  = "Failed to allocate response buffer";
        json_object_put(request);
        return NULL;
    }
    memset(response_buf, 0, CAMERA_RESPONSE_SIZE);

    int rc = camera_control_service_invoke_json_impl(
        request_str, response_buf, CAMERA_RESPONSE_SIZE);

    json_object_put(request);

    if (rc != 0 && response_buf[0] == '\0') {
        free(response_buf);
        *out_code = MCP_ERR_INTERNAL_ERROR;
        *out_msg  = "Camera operation failed with no response";
        return NULL;
    }

    /* Wrap in MCP content envelope */
    json_object *content_item = json_object_new_object();
    json_object_object_add(content_item, "type",
        json_object_new_string("text"));
    json_object_object_add(content_item, "text",
        json_object_new_string(response_buf));

    json_object *content_array = json_object_new_array();
    json_object_array_add(content_array, content_item);

    json_object *result = json_object_new_object();
    json_object_object_add(result, "content", content_array);

    free(response_buf);
    return result;
}

/* ============================================================================
 * Line reader (adapted from finbench_mcp.c, simplified — no Axis2 allocator)
 * ============================================================================
 */

static int mcp_read_line(char **buf_inout, size_t *cap_inout, size_t *out_len)
{
    char   *buf = *buf_inout;
    size_t  cap = *cap_inout;
    size_t  len = 0;
    int     c;

    while ((c = getchar()) != EOF) {
        if (c == '\n') {
            if (len > 0 && buf[len - 1] == '\r') len--;
            buf[len] = '\0';
            *out_len = len;
            return 1;
        }

        if (len >= MAX_MCP_REQUEST_BYTES) {
            while ((c = getchar()) != EOF && c != '\n') {}
            mcp_write_error(NULL, MCP_ERR_INVALID_REQUEST,
                "Request exceeds maximum size (1 MB)");
            *out_len = 0;
            return 0;
        }

        if (len + 1 >= cap) {
            size_t new_cap = cap * 2;
            char *new_buf = realloc(buf, new_cap);
            if (!new_buf) {
                mcp_write_error(NULL, MCP_ERR_INTERNAL_ERROR,
                    "Failed to grow line buffer");
                while ((c = getchar()) != EOF && c != '\n') {}
                *out_len = 0;
                return 0;
            }
            buf = new_buf;
            cap = new_cap;
            *buf_inout = buf;
            *cap_inout = cap;
        }

        buf[len++] = (char)c;
    }

    *out_len = 0;
    return 0;  /* EOF */
}

/* ============================================================================
 * Public API: stdio loop
 * ============================================================================
 */

void kanaha_run_mcp_stdio(void)
{
    size_t  line_cap = MCP_LINE_INITIAL_CAP;
    char   *line_buf = malloc(line_cap);
    size_t  line_len = 0;

    if (!line_buf) {
        mcp_write_error(NULL, MCP_ERR_INTERNAL_ERROR,
            "Failed to allocate initial line buffer");
        return;
    }

    LOGI("Kanaha MCP stdio server starting (protocol %s)",
        KANAHA_MCP_PROTOCOL_VERSION);

    while (mcp_read_line(&line_buf, &line_cap, &line_len)) {
        if (line_len == 0) continue;

        json_object *req = json_tokener_parse(line_buf);
        if (!req) {
            mcp_write_error(NULL, MCP_ERR_PARSE_ERROR,
                "Parse error: invalid JSON");
            continue;
        }

        /* No "id" = notification — silently consume */
        json_object *id_obj = NULL;
        if (!json_object_object_get_ex(req, "id", &id_obj)) {
            json_object_put(req);
            continue;
        }

        /* Extract method */
        json_object *method_obj = NULL;
        if (!json_object_object_get_ex(req, "method", &method_obj)
                || !json_object_is_type(method_obj, json_type_string)) {
            mcp_write_error(id_obj, MCP_ERR_INVALID_REQUEST,
                "method is required and must be a string");
            json_object_put(req);
            continue;
        }
        const char *method = json_object_get_string(method_obj);

        /* Extract optional params */
        json_object *params_obj = NULL;
        json_object_object_get_ex(req, "params", &params_obj);

        /* Dispatch */
        if (strcmp(method, "initialize") == 0) {
            mcp_write_result(id_obj, mcp_handle_initialize());

        } else if (strcmp(method, "tools/list") == 0) {
            mcp_write_result(id_obj, mcp_handle_tools_list());

        } else if (strcmp(method, "tools/call") == 0) {
            int         err_code = 0;
            const char *err_msg  = NULL;
            json_object *result  = mcp_handle_tools_call(
                params_obj, &err_code, &err_msg);
            if (result) {
                mcp_write_result(id_obj, result);
            } else {
                mcp_write_error(id_obj, err_code,
                    err_msg ? err_msg : "Internal error");
            }

        } else {
            mcp_write_error(id_obj, MCP_ERR_METHOD_NOT_FOUND,
                "Method not found");
        }

        json_object_put(req);
    }

    free(line_buf);
    LOGI("Kanaha MCP stdio server exiting");
}
