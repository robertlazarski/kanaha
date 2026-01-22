/*
 * Kanaha Camera Control System
 * Axis2/C Camera Control Service Implementation
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * This file follows the same architecture as the Axis2/C userguide samples:
 * - camera_control_service_invoke_json() - Entry point (matches server pattern)
 * - camera_device_*_impl() - Android-specific implementations
 *
 * ARCHITECTURE: Matches Axis2/C server-side pattern
 * Flow: HTTP client -> camera_control_service_invoke_json() -> action routing -> camera_device_*_impl()
 * The architecture pattern is inspired by Apache Axis2/C (Apache 2.0 licensed),
 * but this implementation is entirely independent and GPL-compatible.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <errno.h>
#include <sys/wait.h>
#include <android/log.h>

#define LOG_TAG "KanahaCameraService"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Internal Intent IPC constants
#define CAMERA_CONTROL_ACTION "org.kanaha.CAMERA_CONTROL"
// Use /data/user/0/ path which is the actual location on modern Android
// (/data/data/ is a symlink that may not resolve correctly in native context)
#define RESPONSE_FILE_PREFIX "/data/user/0/org.kanaha.camera/cache/response_"

// Maximum number of intent extra arguments
#define MAX_INTENT_EXTRAS 20

/* ========================================================================
 * SECURE IPC - No Shell Invocation
 * ========================================================================
 *
 * SECURITY: We use fork()/execvp() instead of system() to prevent
 * command injection attacks. The system() function invokes a shell
 * which parses the command string, making it vulnerable to shell
 * metacharacter injection even with quoted arguments.
 *
 * With execvp(), arguments are passed as an array directly to the
 * process, bypassing shell parsing entirely.
 * ======================================================================== */

/**
 * Intent extra parameter (key-value pair with type)
 */
typedef struct {
    const char* type;   /* "--es" for string, "--ei" for int, etc. */
    const char* key;
    const char* value;
} intent_extra_t;

/**
 * Secure intent broadcast - uses fork/exec instead of system()
 *
 * SECURITY: This function bypasses shell parsing entirely by using
 * execvp() with an argument array. User-controlled data cannot escape
 * into shell metacharacters.
 *
 * @param component   Target component (e.g., "org.kanaha.camera/.CameraControlReceiver")
 * @param action      Intent action string
 * @param extras      Array of intent extras (NULL-terminated or use num_extras)
 * @param num_extras  Number of extras in array
 * @return 0 on success, -1 on failure
 */
static int send_intent_broadcast_secure(
    const char* component,
    const char* action,
    const intent_extra_t* extras,
    int num_extras
) {
    /*
     * Build argument array for execvp()
     * Format: am broadcast --user 0 -n <component> -a <action> [extras...]
     *
     * Each extra adds 3 arguments: --es key value (for strings)
     */

    /* Calculate total args: am broadcast --user 0 -n comp -a action + extras + NULL */
    int base_args = 8;  /* am, broadcast, --user, 0, -n, component, -a, action */
    int extra_args = num_extras * 3;  /* type, key, value for each */
    int total_args = base_args + extra_args + 1;  /* +1 for NULL terminator */

    char** argv = (char**)malloc(total_args * sizeof(char*));
    if (!argv) {
        LOGE("Failed to allocate argv array");
        return -1;
    }

    int i = 0;
    argv[i++] = "am";
    argv[i++] = "broadcast";
    argv[i++] = "--user";
    argv[i++] = "0";
    argv[i++] = "-n";
    argv[i++] = (char*)component;
    argv[i++] = "-a";
    argv[i++] = (char*)action;

    /* Add extras */
    for (int j = 0; j < num_extras; j++) {
        argv[i++] = (char*)extras[j].type;
        argv[i++] = (char*)extras[j].key;
        argv[i++] = (char*)extras[j].value;
    }
    argv[i] = NULL;

    /* Log the command for debugging */
    LOGI("Executing secure intent broadcast to %s", component);
    LOGD("Action: %s, Extras: %d", action, num_extras);

    /* Fork and exec */
    pid_t pid = fork();

    if (pid < 0) {
        LOGE("fork() failed: %s", strerror(errno));
        free(argv);
        return -1;
    }

    if (pid == 0) {
        /* Child process - execute am command */
        execvp("/system/bin/am", argv);
        /* If execvp returns, it failed */
        LOGE("execvp() failed: %s", strerror(errno));
        _exit(127);
    }

    /* Parent process - wait for child */
    free(argv);

    int status;
    if (waitpid(pid, &status, 0) < 0) {
        LOGE("waitpid() failed: %s", strerror(errno));
        return -1;
    }

    if (WIFEXITED(status)) {
        int exit_code = WEXITSTATUS(status);
        if (exit_code == 0) {
            LOGI("Intent broadcast completed successfully");
            return 0;
        } else {
            LOGE("Intent broadcast failed with exit code: %d", exit_code);
            return -1;
        }
    } else if (WIFSIGNALED(status)) {
        LOGE("Intent broadcast killed by signal: %d", WTERMSIG(status));
        return -1;
    }

    return -1;
}

/* ========================================================================
 * HELPER FUNCTIONS
 * ======================================================================== */

static void generate_operation_id(char* buffer, size_t buffer_size) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    snprintf(buffer, buffer_size, "op_%ld_%ld", ts.tv_sec, ts.tv_nsec);
}

/**
 * Send intent and wait for response file
 *
 * @param operation_id  Unique operation ID for response correlation
 * @param extras        Array of intent extras
 * @param num_extras    Number of extras
 * @return 0 on success, -1 on failure (including Java-side validation failures)
 */
static int send_intent_and_wait_for_response(
    const char* operation_id,
    const intent_extra_t* extras,
    int num_extras
) {
    LOGI("send_intent_and_wait_for_response called with operation_id: %s", operation_id);

    int result = send_intent_broadcast_secure(
        "org.kanaha.camera/org.kanaha.camera.CameraControlReceiver",
        CAMERA_CONTROL_ACTION,
        extras,
        num_extras
    );

    if (result != 0) {
        LOGE("Failed to send intent broadcast");
        return -1;
    }

    char response_file[256];
    snprintf(response_file, sizeof(response_file), "%s%s.json", RESPONSE_FILE_PREFIX, operation_id);

    int attempts = 0;
    /* Increase timeout to 20 minutes (12000 attempts × 100ms) to allow time for large
     * 4K video file transfers over WiFi. Java side has 20 min timeout for SFTP. */
    const int max_attempts = 12000;

    while (attempts < max_attempts) {
        if (access(response_file, F_OK) == 0) {
            LOGD("Response file found: %s", response_file);

            /* Read response and check for success field */
            FILE* file = fopen(response_file, "r");
            if (file) {
                char response_buffer[4096];
                size_t bytes_read = fread(response_buffer, 1, sizeof(response_buffer) - 1, file);
                fclose(file);

                LOGI("Read %zu bytes from response file", bytes_read);

                if (bytes_read > 0) {
                    response_buffer[bytes_read] = '\0';
                    LOGI("Response file content: %s", response_buffer);

                    /* Check if response indicates failure */
                    if (strstr(response_buffer, "\"success\": false") ||
                        strstr(response_buffer, "\"success\":false")) {
                        LOGE("Java layer returned failure - rejecting: %s", response_buffer);
                        unlink(response_file);
                        return -1;
                    }
                    LOGI("Response indicates success, continuing");
                } else {
                    LOGE("Failed to read response file content");
                }
            } else {
                LOGE("Failed to open response file: %s", response_file);
            }
            return 0;
        }
        usleep(100000);
        attempts++;
    }

    LOGE("Timeout waiting for response file: %s", response_file);
    return -1;
}

/* Unescape JSON string in place */
static void json_unescape(char* str) {
    char* src = str;
    char* dst = str;
    while (*src) {
        if (*src == '\\' && *(src + 1)) {
            src++;
            switch (*src) {
                case '/': *dst++ = '/'; break;
                case 'n': *dst++ = '\n'; break;
                case 'r': *dst++ = '\r'; break;
                case 't': *dst++ = '\t'; break;
                case '"': *dst++ = '"'; break;
                case '\\': *dst++ = '\\'; break;
                default: *dst++ = *src; break;
            }
            src++;
        } else {
            *dst++ = *src++;
        }
    }
    *dst = '\0';
}

/* Simple JSON string extraction helper */
static const char* extract_json_string(const char* json, const char* key, char* buffer, size_t buffer_size) {
    char search_key[128];
    snprintf(search_key, sizeof(search_key), "\"%s\":\"", key);

    const char* start = strstr(json, search_key);
    if (!start) return NULL;

    start += strlen(search_key);
    const char* end = strchr(start, '"');
    if (!end || (size_t)(end - start) >= buffer_size) return NULL;

    strncpy(buffer, start, end - start);
    buffer[end - start] = '\0';

    /* Unescape JSON escape sequences like \/ -> / */
    json_unescape(buffer);

    return buffer;
}

static int extract_json_int(const char* json, const char* key, int default_value) {
    char search_key[128];
    snprintf(search_key, sizeof(search_key), "\"%s\":", key);

    const char* start = strstr(json, search_key);
    if (!start) return default_value;

    start += strlen(search_key);
    while (*start == ' ') start++;

    return atoi(start);
}

/* Create JSON response helpers */
static void create_success_response(char* buffer, size_t size, const char* message) {
    snprintf(buffer, size, "{\"success\":true,\"message\":\"%s\"}", message);
}

static void create_error_response(char* buffer, size_t size, const char* error) {
    snprintf(buffer, size, "{\"success\":false,\"error\":\"%s\"}", error);
}

/* ========================================================================
 * FORWARD DECLARATIONS - Android-specific implementations
 * ======================================================================== */

int camera_device_start_recording_impl(const char* clip_name, const char* quality, int duration, const char* format);
int camera_device_stop_recording_impl(void);
int camera_device_get_status_impl(char* status_json, size_t buffer_size);
int camera_device_init_impl(void);
void camera_device_cleanup_impl(void);
int camera_device_configure_impl(const char* resolution, const char* fps, const char* codec);
int camera_device_sftp_transfer_impl(const char* storage_server_id, const char* video_filename, const char* destination_folder);
int camera_device_cleanup_files_impl(const char* cleanup_policy, int days_threshold, const char* file_pattern);
int camera_device_delete_files_impl(const char* pattern);
int camera_device_list_files_impl(const char* pattern, char* json_response, size_t response_size);

/* ========================================================================
 * SERVICE ENTRY POINT - Matches Axis2/C server-side pattern
 * ======================================================================== */

/**
 * JSON Service Entry Point - Processes camera control requests
 *
 * This function follows the same pattern as the server-side Axis2/C service:
 * - Extracts "action" from JSON request
 * - Routes to appropriate camera operation
 * - Returns JSON response
 *
 * @param json_request  JSON string containing the request
 * @param json_response Buffer for JSON response
 * @param response_size Size of response buffer
 * @return 0 on success, -1 on error
 */
int camera_control_service_invoke_json_impl(
    const char* json_request,
    char* json_response,
    size_t response_size
) {
    LOGI("camera_control_service_invoke_json_impl called");
    LOGD("Request: %s", json_request ? json_request : "NULL");

    if (!json_request || !json_response || response_size == 0) {
        LOGE("Invalid parameters");
        return -1;
    }

    /* Extract action from JSON request */
    char action[64];
    if (!extract_json_string(json_request, "action", action, sizeof(action))) {
        create_error_response(json_response, response_size, "Missing 'action' parameter");
        return -1;
    }

    LOGI("Processing camera action: %s", action);

    /* Route to appropriate camera operation - match both snake_case and camelCase */
    if (strcmp(action, "start_recording") == 0 || strcmp(action, "startRecording") == 0) {
        char clip_name[256] = "default_clip";
        char quality[64] = "4K";
        char format[32] = "MP4";
        int duration = 1800;

        extract_json_string(json_request, "clip_name", clip_name, sizeof(clip_name));
        extract_json_string(json_request, "quality", quality, sizeof(quality));
        extract_json_string(json_request, "format", format, sizeof(format));
        duration = extract_json_int(json_request, "duration", 1800);

        int result = camera_device_start_recording_impl(clip_name, quality, duration, format);

        if (result == 0) {
            snprintf(json_response, response_size,
                "{\"success\":true,\"message\":\"Recording started\",\"clip_name\":\"%s\",\"quality\":\"%s\"}",
                clip_name, quality);
        } else {
            create_error_response(json_response, response_size, "Failed to start recording");
        }
    }
    else if (strcmp(action, "stop_recording") == 0 || strcmp(action, "stopRecording") == 0) {
        int result = camera_device_stop_recording_impl();

        if (result == 0) {
            create_success_response(json_response, response_size, "Recording stopped");
        } else {
            create_error_response(json_response, response_size, "Failed to stop recording");
        }
    }
    else if (strcmp(action, "get_status") == 0 || strcmp(action, "getStatus") == 0) {
        LOGI("Matched get_status/getStatus action, calling camera_device_get_status_impl");
        char status_buffer[1024];
        int result = camera_device_get_status_impl(status_buffer, sizeof(status_buffer));
        LOGI("camera_device_get_status_impl returned: %d", result);

        if (result == 0) {
            snprintf(json_response, response_size, "{\"success\":true,\"status\":%s}", status_buffer);
        } else {
            create_error_response(json_response, response_size, "Failed to get status");
        }
    }
    else if (strcmp(action, "configure") == 0 || strcmp(action, "configure_settings") == 0) {
        char resolution[64] = "1920x1080";
        char fps[16] = "30";
        char codec[32] = "H.264";

        extract_json_string(json_request, "resolution", resolution, sizeof(resolution));
        extract_json_string(json_request, "fps", fps, sizeof(fps));
        extract_json_string(json_request, "codec", codec, sizeof(codec));

        int result = camera_device_configure_impl(resolution, fps, codec);

        if (result == 0) {
            snprintf(json_response, response_size,
                "{\"success\":true,\"message\":\"Camera configured\",\"resolution\":\"%s\",\"fps\":\"%s\",\"codec\":\"%s\"}",
                resolution, fps, codec);
        } else {
            create_error_response(json_response, response_size, "Failed to configure camera");
        }
    }
    else if (strcmp(action, "list_files") == 0 || strcmp(action, "listFiles") == 0) {
        LOGI("Matched list_files/listFiles action");
        char pattern[256] = "";
        extract_json_string(json_request, "pattern", pattern, sizeof(pattern));

        int result = camera_device_list_files_impl(pattern, json_response, response_size);
        // Response is already set by list_files_impl
        if (result != 0) {
            create_error_response(json_response, response_size, "Failed to list files");
        }
    }
    else if (strcmp(action, "delete_files") == 0 || strcmp(action, "deleteFiles") == 0) {
        LOGI("Matched delete_files/deleteFiles action");
        char pattern[256] = "";
        extract_json_string(json_request, "pattern", pattern, sizeof(pattern));

        int result = camera_device_delete_files_impl(pattern);

        if (result == 0) {
            create_success_response(json_response, response_size, "Files deleted");
        } else {
            create_error_response(json_response, response_size, "Failed to delete files");
        }
    }
    else if (strcmp(action, "sftp_transfer") == 0 || strcmp(action, "sftpTransfer") == 0) {
        char storage_server_id[128] = "";
        char video_filename[256] = "";
        char destination_folder[256] = "";

        extract_json_string(json_request, "storage_server_id", storage_server_id, sizeof(storage_server_id));
        extract_json_string(json_request, "video_filename", video_filename, sizeof(video_filename));
        extract_json_string(json_request, "destination_folder", destination_folder, sizeof(destination_folder));

        int result = camera_device_sftp_transfer_impl(storage_server_id, video_filename, destination_folder);

        if (result == 0) {
            create_success_response(json_response, response_size, "File transferred successfully");
        } else {
            create_error_response(json_response, response_size, "Failed to transfer file");
        }
    }
    else if (strcmp(action, "cleanup_files") == 0 || strcmp(action, "cleanupFiles") == 0) {
        char cleanup_policy[64] = "";
        char file_pattern[256] = "";
        int days_threshold = 0;

        extract_json_string(json_request, "cleanup_policy", cleanup_policy, sizeof(cleanup_policy));
        extract_json_string(json_request, "file_pattern", file_pattern, sizeof(file_pattern));
        days_threshold = extract_json_int(json_request, "days_threshold", 0);

        int result = camera_device_cleanup_files_impl(cleanup_policy, days_threshold, file_pattern);

        if (result == 0) {
            create_success_response(json_response, response_size, "Cleanup completed");
        } else {
            create_error_response(json_response, response_size, "Failed to cleanup files");
        }
    }
    else {
        char error_msg[256];
        snprintf(error_msg, sizeof(error_msg), "Unknown action: %s", action);
        create_error_response(json_response, response_size, error_msg);
        return -1;
    }

    LOGD("Response: %s", json_response);
    return 0;
}

/* ========================================================================
 * SERVICE LIFECYCLE FUNCTIONS
 * ======================================================================== */

/**
 * Initialize camera control service
 */
int camera_control_service_init(void) {
    LOGI("camera_control_service_init called");
    return camera_device_init_impl();
}

/**
 * Cleanup camera control service
 */
void camera_control_service_cleanup(void) {
    LOGI("camera_control_service_cleanup called");
    camera_device_cleanup_impl();
}

/* ========================================================================
 * ANDROID-SPECIFIC IMPLEMENTATIONS (camera_device_*_impl)
 * These use Internal Intent IPC to communicate with Java/OpenCamera
 * ======================================================================== */

/**
 * Start camera recording implementation for Android
 * Uses Internal Intent IPC (NO JNI) to communicate with CameraControlReceiver
 *
 * SECURITY: Uses secure fork/exec IPC - no shell injection possible
 */
int camera_device_start_recording_impl(
    const char* clip_name,
    const char* quality,
    int duration,
    const char* format
) {
    LOGI("camera_device_start_recording_impl called");
    LOGI("  clip_name: %s", clip_name ? clip_name : "NULL");
    LOGI("  quality: %s", quality ? quality : "NULL");
    LOGI("  duration: %d", duration);
    LOGI("  format: %s", format ? format : "NULL");

    if (!clip_name || !quality) {
        LOGE("Invalid parameters: clip_name or quality is NULL");
        return -1;
    }

    char operation_id[64];
    generate_operation_id(operation_id, sizeof(operation_id));

    LOGD("Generated operation ID: %s", operation_id);

    /* Convert duration to string for intent extra */
    char duration_str[16];
    snprintf(duration_str, sizeof(duration_str), "%d", duration);

    /* Build intent extras array - no shell parsing, immune to injection */
    intent_extra_t extras[] = {
        {"--es", "action", "start_recording"},
        {"--es", "operation_id", operation_id},
        {"--es", "clip_name", clip_name},
        {"--es", "quality", quality},
        {"--ei", "duration", duration_str},
        {"--es", "format", format ? format : "MP4"}
    };

    LOGI("Sending secure intent broadcast to start recording");

    int result = send_intent_and_wait_for_response(operation_id, extras, 6);
    if (result == 0) {
        LOGI("Recording started successfully via secure IPC");
    } else {
        LOGE("Failed to start recording via secure IPC");
    }

    return result;
}

/**
 * Stop camera recording implementation for Android
 * Uses Internal Intent IPC (NO JNI) to communicate with CameraControlReceiver
 *
 * SECURITY: Uses secure fork/exec IPC - no shell injection possible
 */
int camera_device_stop_recording_impl(void) {
    LOGI("camera_device_stop_recording_impl called");

    char operation_id[64];
    generate_operation_id(operation_id, sizeof(operation_id));

    LOGD("Generated operation ID: %s", operation_id);

    intent_extra_t extras[] = {
        {"--es", "action", "stop_recording"},
        {"--es", "operation_id", operation_id}
    };

    LOGI("Sending secure intent broadcast to stop recording");

    int result = send_intent_and_wait_for_response(operation_id, extras, 2);
    if (result == 0) {
        LOGI("Recording stopped successfully via secure IPC");
    } else {
        LOGE("Failed to stop recording via secure IPC");
    }

    return result;
}

/**
 * Get camera status implementation for Android
 * Uses Internal Intent IPC (NO JNI) to communicate with CameraControlReceiver
 *
 * SECURITY: Uses secure fork/exec IPC - no shell injection possible
 */
int camera_device_get_status_impl(char* status_json, size_t buffer_size) {
    LOGI("camera_device_get_status_impl called");

    if (!status_json || buffer_size == 0) {
        LOGE("Invalid parameters: status_json is NULL or buffer_size is 0");
        return -1;
    }

    char operation_id[64];
    generate_operation_id(operation_id, sizeof(operation_id));

    LOGD("Generated operation ID: %s", operation_id);

    intent_extra_t extras[] = {
        {"--es", "action", "get_status"},
        {"--es", "operation_id", operation_id}
    };

    LOGI("Sending secure intent broadcast to get status");

    int result = send_intent_and_wait_for_response(operation_id, extras, 2);
    if (result != 0) {
        LOGE("Failed to get status via secure IPC");
        return -1;
    }

    char response_file[256];
    snprintf(response_file, sizeof(response_file), "%s%s.json", RESPONSE_FILE_PREFIX, operation_id);

    FILE* file = fopen(response_file, "r");
    if (!file) {
        LOGE("Failed to open response file: %s", response_file);
        return -1;
    }

    size_t bytes_read = fread(status_json, 1, buffer_size - 1, file);
    fclose(file);

    if (bytes_read == 0) {
        LOGE("No data read from response file");
        return -1;
    }

    status_json[bytes_read] = '\0';
    unlink(response_file);

    LOGI("Status retrieved successfully via secure IPC. Response: %s", status_json);
    return 0;
}

/**
 * Initialize camera device for Android
 */
int camera_device_init_impl(void) {
    LOGI("camera_device_init_impl called");
    LOGI("Camera device initialized successfully");
    return 0;
}

/**
 * Cleanup camera device for Android
 */
void camera_device_cleanup_impl(void) {
    LOGI("camera_device_cleanup_impl called");
    LOGI("Camera device cleanup completed");
}

/**
 * Configure camera settings for Android
 */
int camera_device_configure_impl(
    const char* resolution,
    const char* fps,
    const char* codec
) {
    LOGI("camera_device_configure_impl called");
    LOGI("  resolution: %s", resolution ? resolution : "NULL");
    LOGI("  fps: %s", fps ? fps : "NULL");
    LOGI("  codec: %s", codec ? codec : "NULL");

    LOGI("Camera configuration completed successfully");
    return 0;
}

/**
 * SFTP file transfer implementation for Android (Security-Hardened)
 * Uses Internal Intent IPC (NO JNI) to communicate with CameraControlReceiver
 */
int camera_device_sftp_transfer_impl(
    const char* storage_server_id,
    const char* video_filename,
    const char* destination_folder
) {
    LOGI("camera_device_sftp_transfer_impl called (security-hardened version)");
    LOGI("  storage_server_id: %s", storage_server_id ? storage_server_id : "NULL");
    LOGI("  video_filename: %s", video_filename ? video_filename : "NULL");
    LOGI("  destination_folder: %s", destination_folder ? destination_folder : "NULL");

    if (!storage_server_id || strlen(storage_server_id) == 0) {
        LOGE("Invalid parameter: storage_server_id is NULL or empty");
        return -1;
    }

    if (!video_filename || strlen(video_filename) == 0) {
        LOGE("Invalid parameter: video_filename is NULL or empty");
        return -1;
    }

    if (!destination_folder || strlen(destination_folder) == 0) {
        LOGE("Invalid parameter: destination_folder is NULL or empty");
        return -1;
    }

    if (strstr(video_filename, "..") || strstr(destination_folder, "..")) {
        LOGE("Security violation: path traversal detected in parameters");
        return -1;
    }

    if (strcmp(video_filename, "*") != 0) {
        const char* ext = strrchr(video_filename, '.');
        if (!ext || (strcmp(ext, ".mp4") != 0 && strcmp(ext, ".mov") != 0 && strcmp(ext, ".mkv") != 0)) {
            if (strstr(video_filename, "*.mp4") == NULL &&
                strstr(video_filename, "*.mov") == NULL &&
                strstr(video_filename, "*.mkv") == NULL &&
                strchr(video_filename, '*') != NULL) {
                LOGE("Security violation: invalid wildcard pattern. Must be *.mp4, *.mov, or *.mkv");
                return -1;
            } else if (strchr(video_filename, '*') == NULL) {
                LOGE("Security violation: invalid file extension. Only .mp4, .mov, .mkv allowed");
                return -1;
            }
        }
    }

    char operation_id[64];
    generate_operation_id(operation_id, sizeof(operation_id));

    LOGD("Generated operation ID: %s", operation_id);

    /* Build intent extras array - secure, no shell parsing */
    intent_extra_t extras[] = {
        {"--es", "action", "sftp_transfer"},
        {"--es", "operation_id", operation_id},
        {"--es", "storage_server_id", storage_server_id},
        {"--es", "video_filename", video_filename},
        {"--es", "destination_folder", destination_folder}
    };

    LOGI("Sending secure intent broadcast for SFTP transfer");

    int result = send_intent_and_wait_for_response(operation_id, extras, 5);
    if (result == 0) {
        LOGI("File transferred successfully via secure IPC");
    } else {
        LOGE("Failed to transfer file via secure IPC");
    }

    return result;
}

/**
 * File cleanup implementation for Android (Security-Hardened)
 * Uses Internal Intent IPC (NO JNI) to communicate with CameraControlReceiver
 */
int camera_device_cleanup_files_impl(
    const char* cleanup_policy,
    int days_threshold,
    const char* file_pattern
) {
    LOGI("camera_device_cleanup_files_impl called (security-hardened version)");
    LOGI("  cleanup_policy: %s", cleanup_policy ? cleanup_policy : "NULL");
    LOGI("  days_threshold: %d", days_threshold);
    LOGI("  file_pattern: %s", file_pattern ? file_pattern : "NULL");

    if (!cleanup_policy || strlen(cleanup_policy) == 0) {
        LOGE("Invalid parameter: cleanup_policy is NULL or empty");
        return -1;
    }

    if (strcmp(cleanup_policy, "older_than_days") != 0 &&
        strcmp(cleanup_policy, "after_successful_transfer") != 0 &&
        strcmp(cleanup_policy, "all_videos") != 0 &&
        strcmp(cleanup_policy, "by_pattern") != 0) {
        LOGE("Security violation: invalid cleanup_policy");
        return -1;
    }

    if (strcmp(cleanup_policy, "older_than_days") == 0) {
        if (days_threshold <= 0 || days_threshold > 365) {
            LOGE("Invalid parameter: days_threshold must be between 1 and 365");
            return -1;
        }
    }

    if (strcmp(cleanup_policy, "by_pattern") == 0) {
        if (!file_pattern || strlen(file_pattern) == 0) {
            LOGE("Invalid parameter: file_pattern is required for by_pattern policy");
            return -1;
        }

        if (strstr(file_pattern, "..")) {
            LOGE("Security violation: path traversal detected in file_pattern");
            return -1;
        }
    }

    char operation_id[64];
    generate_operation_id(operation_id, sizeof(operation_id));

    LOGD("Generated operation ID: %s", operation_id);

    int result;
    char days_str[16];

    if (strcmp(cleanup_policy, "older_than_days") == 0) {
        snprintf(days_str, sizeof(days_str), "%d", days_threshold);
        intent_extra_t extras[] = {
            {"--es", "action", "cleanup_files"},
            {"--es", "operation_id", operation_id},
            {"--es", "cleanup_policy", cleanup_policy},
            {"--ei", "days_threshold", days_str}
        };
        LOGI("Sending secure intent broadcast for file cleanup (older_than_days)");
        result = send_intent_and_wait_for_response(operation_id, extras, 4);
    } else if (strcmp(cleanup_policy, "by_pattern") == 0) {
        intent_extra_t extras[] = {
            {"--es", "action", "cleanup_files"},
            {"--es", "operation_id", operation_id},
            {"--es", "cleanup_policy", cleanup_policy},
            {"--es", "file_pattern", file_pattern}
        };
        LOGI("Sending secure intent broadcast for file cleanup (by_pattern)");
        result = send_intent_and_wait_for_response(operation_id, extras, 4);
    } else {
        intent_extra_t extras[] = {
            {"--es", "action", "cleanup_files"},
            {"--es", "operation_id", operation_id},
            {"--es", "cleanup_policy", cleanup_policy}
        };
        LOGI("Sending secure intent broadcast for file cleanup");
        result = send_intent_and_wait_for_response(operation_id, extras, 3);
    }

    if (result == 0) {
        LOGI("File cleanup completed successfully via secure IPC");
    } else {
        LOGE("Failed to complete file cleanup via secure IPC");
    }

    return result;
}

/**
 * Delete files implementation for Android
 * Pattern supports: specific file, wildcards (*.mp4), "today", date (YYYY-MM-DD)
 * Uses Internal Intent IPC (NO JNI) to communicate with CameraControlReceiver
 *
 * SECURITY: Uses secure fork/exec IPC - no shell injection possible
 */
int camera_device_delete_files_impl(const char* pattern) {
    LOGI("camera_device_delete_files_impl called");
    LOGI("  pattern: %s", pattern ? pattern : "NULL");

    if (!pattern || strlen(pattern) == 0) {
        LOGE("Invalid parameter: pattern is NULL or empty");
        return -1;
    }

    /* Security: Reject path traversal */
    if (strstr(pattern, "..")) {
        LOGE("Security violation: path traversal detected in pattern");
        return -1;
    }

    char operation_id[64];
    generate_operation_id(operation_id, sizeof(operation_id));

    LOGD("Generated operation ID: %s", operation_id);

    intent_extra_t extras[] = {
        {"--es", "action", "delete_files"},
        {"--es", "operation_id", operation_id},
        {"--es", "pattern", pattern}
    };

    LOGI("Sending secure intent broadcast to delete files");

    int result = send_intent_and_wait_for_response(operation_id, extras, 3);
    if (result == 0) {
        LOGI("Files deleted successfully via secure IPC");
    } else {
        LOGE("Failed to delete files via secure IPC");
    }

    return result;
}

/**
 * List files implementation for Android
 * Returns list of video files with metadata
 * Uses Internal Intent IPC (NO JNI) to communicate with CameraControlReceiver
 *
 * SECURITY: Uses secure fork/exec IPC - no shell injection possible
 */
int camera_device_list_files_impl(const char* pattern, char* json_response, size_t response_size) {
    LOGI("camera_device_list_files_impl called");
    LOGI("  pattern: %s", pattern ? pattern : "NULL");

    if (!json_response || response_size == 0) {
        LOGE("Invalid parameters: json_response is NULL or response_size is 0");
        return -1;
    }

    char operation_id[64];
    generate_operation_id(operation_id, sizeof(operation_id));

    LOGD("Generated operation ID: %s", operation_id);

    int result;
    if (pattern && strlen(pattern) > 0) {
        intent_extra_t extras[] = {
            {"--es", "action", "list_files"},
            {"--es", "operation_id", operation_id},
            {"--es", "pattern", pattern}
        };
        LOGI("Sending secure intent broadcast to list files (with pattern)");
        result = send_intent_and_wait_for_response(operation_id, extras, 3);
    } else {
        intent_extra_t extras[] = {
            {"--es", "action", "list_files"},
            {"--es", "operation_id", operation_id}
        };
        LOGI("Sending secure intent broadcast to list files");
        result = send_intent_and_wait_for_response(operation_id, extras, 2);
    }

    if (result != 0) {
        LOGE("Failed to list files via secure IPC");
        return -1;
    }

    /* Read response from file */
    char response_file[256];
    snprintf(response_file, sizeof(response_file), "%s%s.json", RESPONSE_FILE_PREFIX, operation_id);

    FILE* file = fopen(response_file, "r");
    if (!file) {
        LOGE("Failed to open response file: %s", response_file);
        return -1;
    }

    size_t bytes_read = fread(json_response, 1, response_size - 1, file);
    fclose(file);

    if (bytes_read == 0) {
        LOGE("No data read from response file");
        return -1;
    }

    json_response[bytes_read] = '\0';
    unlink(response_file);

    LOGI("Files listed successfully via secure IPC");
    return 0;
}

/*
 * ARCHITECTURE: Matches Axis2/C Server-Side Pattern
 *
 * This implementation follows the same architecture as the Axis2/C userguide
 * sample services (CameraControlService, LoginService, TestwsService, etc.):
 *
 * Server-side:
 *   mod_axis2 -> axis2_json_rpc_msg_recv -> dlsym("*_invoke_json") -> service
 *
 * Android (this file):
 *   HTTP client -> camera_control_service_invoke_json() -> camera_device_*_impl()
 *
 * The key insight is that on Android, we don't need the axis2_json_rpc_msg_recv
 * layer because we're not doing dynamic service discovery. We call the service
 * entry point (camera_control_service_invoke_json) directly.
 *
 * Benefits of this architecture:
 * - Action routing code can be synced from server-side Axis2/C
 * - Only camera_device_*_impl() functions are Android-specific
 * - Single file instead of two files with duplicated logic
 * - Follows the Axis2/C userguide sample pattern exactly
 */
