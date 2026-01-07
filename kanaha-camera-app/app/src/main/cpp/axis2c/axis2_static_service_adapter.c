/*
 * Kanaha Camera Control System
 * Axis2/C Static Service Adapter
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * This adapter bridges the Kanaha camera service to the Axis2/C static
 * service registry. It converts between Axis2/C's framework interface
 * and Kanaha's standalone service interface.
 *
 * ARCHITECTURE:
 *   Axis2/C framework -> This adapter -> Kanaha camera service
 *
 * The Axis2/C static registry expects:
 *   json_object* camera_control_service_invoke_json(
 *       const axutil_env_t *env, json_object *json_request);
 *
 * Kanaha provides:
 *   int camera_control_service_invoke_json_impl(
 *       const char* json_request, char* json_response, size_t response_size);
 *
 * LICENSING: Apache 2.0 (compatible with both Axis2/C and Kanaha GPL)
 */

#include <axutil_env.h>
#include <axutil_log.h>
#include <json-c/json.h>
#include <string.h>

/* Maximum response size for JSON responses */
#define MAX_RESPONSE_SIZE 65536

/*
 * Forward declaration of Kanaha's service implementation.
 * This function is implemented in camera_control_service.c
 * Note: We rename to avoid symbol conflict with the adapter function.
 */
extern int camera_control_service_invoke_json_impl(
    const char* json_request,
    char* json_response,
    size_t response_size);

/**
 * Axis2/C Static Service Registry entry point for CameraControlService
 *
 * This function is called by the Axis2/C static service registry when
 * a request comes in for the CameraControlService.
 *
 * @param env Axis2/C environment (may be NULL for static services)
 * @param json_request The JSON-RPC request object (from json-c)
 * @return JSON-RPC response object (caller takes ownership)
 */
json_object* camera_control_service_invoke_json(
    const axutil_env_t *env,
    json_object *json_request)
{
    const char *request_str = NULL;
    char *response_buffer = NULL;
    json_object *response_obj = NULL;
    int result;

    if (env && env->log) {
        AXIS2_LOG_INFO(env->log, AXIS2_LOG_SI,
            "[Axis2/C Adapter] camera_control_service_invoke_json called");
    }

    if (!json_request) {
        /* Return JSON-RPC error for null request */
        response_obj = json_object_new_object();
        json_object *error = json_object_new_object();
        json_object_object_add(error, "code", json_object_new_int(-32600));
        json_object_object_add(error, "message", json_object_new_string("Invalid Request"));
        json_object_object_add(response_obj, "jsonrpc", json_object_new_string("2.0"));
        json_object_object_add(response_obj, "error", error);
        json_object_object_add(response_obj, "id", json_object_new_null());
        return response_obj;
    }

    /* Convert json_object to string for Kanaha service */
    request_str = json_object_to_json_string_ext(json_request, JSON_C_TO_STRING_PLAIN);
    if (!request_str) {
        if (env && env->log) {
            AXIS2_LOG_ERROR(env->log, AXIS2_LOG_SI,
                "[Axis2/C Adapter] Failed to serialize request to string");
        }
        response_obj = json_object_new_object();
        json_object *error = json_object_new_object();
        json_object_object_add(error, "code", json_object_new_int(-32603));
        json_object_object_add(error, "message", json_object_new_string("Internal error"));
        json_object_object_add(response_obj, "jsonrpc", json_object_new_string("2.0"));
        json_object_object_add(response_obj, "error", error);
        json_object_object_add(response_obj, "id", json_object_new_null());
        return response_obj;
    }

    /* Allocate response buffer */
    response_buffer = (char *)malloc(MAX_RESPONSE_SIZE);
    if (!response_buffer) {
        if (env && env->log) {
            AXIS2_LOG_ERROR(env->log, AXIS2_LOG_SI,
                "[Axis2/C Adapter] Failed to allocate response buffer");
        }
        response_obj = json_object_new_object();
        json_object *error = json_object_new_object();
        json_object_object_add(error, "code", json_object_new_int(-32603));
        json_object_object_add(error, "message", json_object_new_string("Internal error"));
        json_object_object_add(response_obj, "jsonrpc", json_object_new_string("2.0"));
        json_object_object_add(response_obj, "error", error);
        json_object_object_add(response_obj, "id", json_object_new_null());
        return response_obj;
    }

    memset(response_buffer, 0, MAX_RESPONSE_SIZE);

    if (env && env->log) {
        AXIS2_LOG_INFO(env->log, AXIS2_LOG_SI,
            "[Axis2/C Adapter] Calling Kanaha service implementation");
    }

    /* Call Kanaha's service implementation */
    result = camera_control_service_invoke_json_impl(
        request_str, response_buffer, MAX_RESPONSE_SIZE);

    if (result != 0) {
        if (env && env->log) {
            AXIS2_LOG_ERROR(env->log, AXIS2_LOG_SI,
                "[Axis2/C Adapter] Kanaha service returned error: %d", result);
        }
        free(response_buffer);
        response_obj = json_object_new_object();
        json_object *error = json_object_new_object();
        json_object_object_add(error, "code", json_object_new_int(-32603));
        json_object_object_add(error, "message", json_object_new_string("Service error"));
        json_object_object_add(response_obj, "jsonrpc", json_object_new_string("2.0"));
        json_object_object_add(response_obj, "error", error);
        json_object_object_add(response_obj, "id", json_object_new_null());
        return response_obj;
    }

    /* Parse response string back to json_object */
    response_obj = json_tokener_parse(response_buffer);
    free(response_buffer);

    if (!response_obj) {
        if (env && env->log) {
            AXIS2_LOG_ERROR(env->log, AXIS2_LOG_SI,
                "[Axis2/C Adapter] Failed to parse service response as JSON");
        }
        response_obj = json_object_new_object();
        json_object *error = json_object_new_object();
        json_object_object_add(error, "code", json_object_new_int(-32603));
        json_object_object_add(error, "message", json_object_new_string("Invalid response"));
        json_object_object_add(response_obj, "jsonrpc", json_object_new_string("2.0"));
        json_object_object_add(response_obj, "error", error);
        json_object_object_add(response_obj, "id", json_object_new_null());
        return response_obj;
    }

    if (env && env->log) {
        AXIS2_LOG_INFO(env->log, AXIS2_LOG_SI,
            "[Axis2/C Adapter] Successfully processed request via Kanaha service");
    }

    return response_obj;
}
