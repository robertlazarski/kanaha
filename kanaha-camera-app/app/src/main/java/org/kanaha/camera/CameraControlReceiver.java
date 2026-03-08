/*
 * Kanaha Camera Control System
 * Camera Control Broadcast Receiver
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * This class handles Internal Intent broadcasts from the native Axis2/C layer
 * and bridges them to OpenCamera functionality. It implements the IPC pattern
 * from the 2019 OpenCameraStudio fork.
 *
 * ARCHITECTURE: No JNI Bridge - Uses Internal Intent IPC Only
 */

package org.kanaha.camera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import android.media.CamcorderProfile;
import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.preview.Preview;

/**
 * Security validation constants and patterns
 * Defense-in-depth: validates input even though C layer also validates
 */
class SecurityValidator {
    private static final String TAG = "SecurityValidator";

    // Maximum lengths for input parameters
    static final int MAX_FILENAME_LENGTH = 255;
    static final int MAX_PATTERN_LENGTH = 255;
    static final int MAX_PATH_LENGTH = 1024;
    static final int MAX_SERVER_ID_LENGTH = 64;

    // Allowed characters for filenames (alphanumeric, underscore, hyphen, dot)
    private static final Pattern SAFE_FILENAME_PATTERN =
        Pattern.compile("^[a-zA-Z0-9_\\-\\.\\*]+$");

    // Allowed characters for server IDs (alphanumeric, underscore, hyphen)
    private static final Pattern SAFE_SERVER_ID_PATTERN =
        Pattern.compile("^[a-zA-Z0-9_\\-]+$");

    // Date pattern for delete operations (YYYY-MM-DD)
    private static final Pattern DATE_PATTERN =
        Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    // Patterns that indicate path traversal attacks
    private static final String[] PATH_TRAVERSAL_PATTERNS = {
        "..",           // Direct traversal
        "%2e%2e",       // URL encoded ..
        "%252e%252e",   // Double URL encoded
        "..%c0%af",     // Unicode encoding
        "..%c1%9c",     // Unicode encoding variant
        ".../",         // Triple dot
        "....//",       // Quadruple dot
        "%00",          // Null byte injection
        "\0",           // Null character
    };

    // Patterns that indicate injection attacks
    private static final String[] INJECTION_PATTERNS = {
        "<script",      // JavaScript injection
        "javascript:",  // JavaScript protocol
        "vbscript:",    // VBScript protocol
        "onclick",      // Event handlers
        "onerror",
        "onload",
        "eval(",        // JavaScript eval
        "expression(",  // CSS expression
        "${",           // Template injection
        "#{",           // Template injection variant
        "{{",           // Template injection variant
        "`",            // Backtick (command substitution)
        "$()",          // Shell command substitution
        "; ",           // Command separator
        "| ",           // Pipe
        "&& ",          // Command chaining
        "|| ",          // Command chaining
    };

    /**
     * Validate a filename or pattern for security issues
     * @return null if valid, error message if invalid
     */
    static String validateFilenameOrPattern(String input, String paramName) {
        if (input == null || input.isEmpty()) {
            return paramName + " is null or empty";
        }

        // Length check
        if (input.length() > MAX_FILENAME_LENGTH) {
            Log.w(TAG, "Security: " + paramName + " exceeds max length: " + input.length());
            return paramName + " exceeds maximum length of " + MAX_FILENAME_LENGTH;
        }

        // Convert to lowercase for case-insensitive pattern matching
        String lowerInput = input.toLowerCase();

        // Path traversal check
        for (String pattern : PATH_TRAVERSAL_PATTERNS) {
            if (lowerInput.contains(pattern.toLowerCase())) {
                Log.w(TAG, "Security: path traversal detected in " + paramName + ": " + input);
                return "Path traversal attempt detected in " + paramName;
            }
        }

        // Injection attack check
        for (String pattern : INJECTION_PATTERNS) {
            if (lowerInput.contains(pattern.toLowerCase())) {
                Log.w(TAG, "Security: injection pattern detected in " + paramName + ": " + input);
                return "Invalid characters in " + paramName;
            }
        }

        // Control character check (ASCII 0-31 except tab/newline which are already blocked)
        for (char c : input.toCharArray()) {
            if (c < 32 && c != '\t' && c != '\n' && c != '\r') {
                Log.w(TAG, "Security: control character detected in " + paramName);
                return "Invalid control character in " + paramName;
            }
            // Also block DEL and high control characters
            if (c == 127 || (c >= 128 && c <= 159)) {
                Log.w(TAG, "Security: invalid character detected in " + paramName);
                return "Invalid character in " + paramName;
            }
        }

        // For special keywords, allow them
        if (input.equalsIgnoreCase("today") || input.equals("*")) {
            return null;  // Valid
        }

        // For date patterns, validate format
        if (DATE_PATTERN.matcher(input).matches()) {
            return null;  // Valid date pattern
        }

        // For filenames and patterns, validate allowed characters
        if (!SAFE_FILENAME_PATTERN.matcher(input).matches()) {
            // Check if it's a wildcard pattern like "*.mp4"
            if (input.contains("*")) {
                String withoutWildcard = input.replace("*", "");
                if (!withoutWildcard.isEmpty() && !SAFE_FILENAME_PATTERN.matcher(withoutWildcard.replace("*", "x")).matches()) {
                    Log.w(TAG, "Security: invalid characters in " + paramName + ": " + input);
                    return "Invalid characters in " + paramName + ". Allowed: letters, numbers, underscore, hyphen, dot";
                }
            } else {
                Log.w(TAG, "Security: invalid characters in " + paramName + ": " + input);
                return "Invalid characters in " + paramName + ". Allowed: letters, numbers, underscore, hyphen, dot";
            }
        }

        return null;  // Valid
    }

    /**
     * Validate a server ID for security issues
     * @return null if valid, error message if invalid
     */
    static String validateServerId(String serverId) {
        if (serverId == null || serverId.isEmpty()) {
            return "Server ID is null or empty";
        }

        if (serverId.length() > MAX_SERVER_ID_LENGTH) {
            Log.w(TAG, "Security: server ID exceeds max length: " + serverId.length());
            return "Server ID exceeds maximum length of " + MAX_SERVER_ID_LENGTH;
        }

        if (!SAFE_SERVER_ID_PATTERN.matcher(serverId).matches()) {
            Log.w(TAG, "Security: invalid characters in server ID: " + serverId);
            return "Invalid characters in server ID. Allowed: letters, numbers, underscore, hyphen";
        }

        return null;  // Valid
    }

    /**
     * Validate a destination folder path
     * @return null if valid, error message if invalid
     */
    static String validateDestinationFolder(String path) {
        if (path == null || path.isEmpty()) {
            return "Destination folder is null or empty";
        }

        if (path.length() > MAX_PATH_LENGTH) {
            Log.w(TAG, "Security: destination path exceeds max length: " + path.length());
            return "Destination path exceeds maximum length of " + MAX_PATH_LENGTH;
        }

        String lowerPath = path.toLowerCase();

        // Path traversal check
        for (String pattern : PATH_TRAVERSAL_PATTERNS) {
            if (lowerPath.contains(pattern.toLowerCase())) {
                Log.w(TAG, "Security: path traversal detected in destination: " + path);
                return "Path traversal attempt detected in destination folder";
            }
        }

        // Must be absolute path
        if (!path.startsWith("/")) {
            Log.w(TAG, "Security: destination must be absolute path: " + path);
            return "Destination folder must be an absolute path";
        }

        return null;  // Valid
    }

    /**
     * Verify that a file is within the allowed base directory
     * Uses canonical paths to defeat path traversal
     * @return true if safe, false if outside base directory
     */
    static boolean isPathWithinDirectory(File baseDir, File targetFile) {
        try {
            String canonicalBase = baseDir.getCanonicalPath();
            String canonicalTarget = targetFile.getCanonicalPath();

            // Target must be within base directory
            return canonicalTarget.startsWith(canonicalBase + File.separator) ||
                   canonicalTarget.equals(canonicalBase);
        } catch (IOException e) {
            Log.e(TAG, "Security: failed to resolve canonical path", e);
            return false;  // Fail closed
        }
    }

    /**
     * Verify that a filename resolves to a path within the allowed directory
     * @return true if safe, false if would escape directory
     */
    static boolean isFilenameSafe(File baseDir, String filename) {
        if (filename == null || filename.isEmpty()) {
            return false;
        }

        // Quick check for obvious traversal
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return false;
        }

        File targetFile = new File(baseDir, filename);
        return isPathWithinDirectory(baseDir, targetFile);
    }
}

/**
 * Camera Control Receiver for Internal Intent IPC
 *
 * This receiver processes camera control commands from the native Axis2/C layer
 * via Internal Intent broadcasts and forwards them to OpenCamera MainActivity.
 *
 * NO JNI is used - all communication is via Android Intent broadcasts that stay
 * within the same APK, following the proven 2019 OpenCameraStudio pattern.
 */
public class CameraControlReceiver extends BroadcastReceiver {
    private static final String TAG = "KanahaCameraReceiver";
    private static final String CAMERA_CONTROL_ACTION = "org.kanaha.CAMERA_CONTROL";

    // Track whether the last recording used open gate so we know when a session
    // reopen is needed to reset back to 16:9 quality.
    private static volatile boolean lastRecordingWasOpenGate = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Received camera control intent");

        if (!CAMERA_CONTROL_ACTION.equals(intent.getAction())) {
            Log.w(TAG, "Unknown action: " + intent.getAction());
            return;
        }

        final String action = intent.getStringExtra("action");
        final String operationId = intent.getStringExtra("operation_id");

        if (action == null || operationId == null) {
            Log.e(TAG, "Missing required parameters: action or operation_id");
            return;
        }

        Log.d(TAG, String.format("Processing action: %s, operation: %s", action, operationId));

        // Run handlers on a background thread via goAsync().
        //
        // onReceive() is called on the main (UI) thread. Camera2 open/close callbacks are
        // also dispatched via the main thread's Looper. If we block the main thread with
        // Thread.sleep() (as handlers do while waiting for reopenCamera()), those callbacks
        // are queued but never executed — the camera never reopens.
        //
        // goAsync() extends the broadcast lifetime; the background thread calls
        // pendingResult.finish() when done. runOnUiThread() inside handlers now correctly
        // POSTs to the main thread (which is free) instead of executing inline.
        final BroadcastReceiver.PendingResult pendingResult = goAsync();
        new Thread(() -> {
            try {
                JSONObject response;
                switch (action) {
                    case "start_recording":
                    case "startRecording":
                        response = handleStartRecording(context, intent);
                        break;
                    case "stop_recording":
                    case "stopRecording":
                        response = handleStopRecording(context, intent);
                        break;
                    case "get_status":
                    case "getStatus":
                        response = handleGetStatus(context, intent);
                        break;
                    case "configure":
                        response = handleConfigure(context, intent);
                        break;
                    case "sftp_transfer":
                    case "sftpTransfer":
                        response = handleSftpTransfer(context, intent);
                        break;
                    case "delete_files":
                    case "deleteFiles":
                        response = handleDeleteFiles(context, intent);
                        break;
                    case "list_files":
                    case "listFiles":
                        response = handleListFiles(context, intent);
                        break;
                    case "play_tone":
                    case "playTone":
                        response = handlePlayTone(context, intent);
                        break;
                    default:
                        Log.e(TAG, "Unknown action: " + action);
                        response = createErrorResponse(operationId, "Unknown action: " + action);
                        break;
                }
                writeResponseToFile(context, operationId, response);
            } catch (Exception e) {
                Log.e(TAG, "Error processing camera control action: " + action, e);
                try {
                    writeResponseToFile(context, operationId,
                            createErrorResponse(operationId, "Exception: " + e.getMessage()));
                } catch (Exception e2) {
                    Log.e(TAG, "Failed to write error response", e2);
                }
            } finally {
                pendingResult.finish();
            }
        }, "KanahaCameraControl").start();
    }

    /**
     * Handle start recording request from native layer
     */
    private JSONObject handleStartRecording(Context context, Intent intent) throws JSONException {
        String operationId = intent.getStringExtra("operation_id");
        String clipName = intent.getStringExtra("clip_name");
        String quality = intent.getStringExtra("quality");
        int duration = intent.getIntExtra("duration", 1800); // Default 30 minutes
        String format = intent.getStringExtra("format");
        boolean openGate = "true".equalsIgnoreCase(intent.getStringExtra("open_gate"));

        // Optional scheduled start: start_at is a Unix epoch ms timestamp on this device's clock
        String startAtStr = intent.getStringExtra("start_at");
        long startAt = 0;
        if (startAtStr != null && !startAtStr.isEmpty() && !startAtStr.equals("0")) {
            try { startAt = Long.parseLong(startAtStr); } catch (NumberFormatException ignored) {}
        }

        Log.i(TAG, String.format("Start recording: clip=%s, quality=%s, duration=%d, format=%s, open_gate=%b, start_at=%d",
                clipName, quality, duration, format, openGate, startAt));

        try {
            // Get MainActivity instance and start recording
            MainActivity mainActivity = MainActivity.getInstance();
            if (mainActivity == null) {
                return createErrorResponse(operationId, "MainActivity instance not available");
            }

            // Open gate overrides any explicit quality: find best native-ratio resolution and reopen camera.
            // Must happen before startRecordingInternal; configureOpenGate polls for camera readiness.
            // When not using open gate, always reset quality to avoid persisting the 4:3 open-gate
            // preference from a previous call (SharedPreferences survives across recordings).
            if (openGate) {
                configureOpenGate(mainActivity);
                lastRecordingWasOpenGate = true;
            } else {
                configureRecordingQuality(mainActivity, quality != null ? quality : "4K");
                lastRecordingWasOpenGate = false;
            }

            long now = System.currentTimeMillis();
            long delayMs = startAt > 0 ? startAt - now : 0;

            // Scheduled start: if start_at is between 50ms and 30s in the future, schedule it
            if (delayMs >= 50 && delayMs <= 30000) {
                final MainActivity activity = mainActivity;
                final String clip = clipName;
                final boolean og = openGate;
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    long fireTime = System.currentTimeMillis();
                    Log.i(TAG, "Scheduled recording start firing at " + fireTime);
                    writeSyncSidecar(context, clip, fireTime, og);
                    startRecordingInternal(activity, clip);
                }, delayMs);

                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("scheduled", true);
                response.put("operation_id", operationId);
                response.put("clip_name", clipName);
                response.put("start_at", startAt);
                response.put("delay_ms", delayMs);
                response.put("timestamp", now);
                response.put("open_gate", openGate);
                response.put("message", "Recording scheduled");
                return response;
            }

            // Immediate start (no start_at or already past)
            writeSyncSidecar(context, clipName, System.currentTimeMillis(), openGate);
            boolean success = startRecordingInternal(mainActivity, clipName);

            // Create response JSON
            JSONObject response = new JSONObject();
            response.put("success", success);
            response.put("operation_id", operationId);
            response.put("clip_name", clipName);
            response.put("quality", quality);
            response.put("duration", duration);
            response.put("format", format);
            response.put("open_gate", openGate);

            if (success) {
                response.put("message", "Recording started successfully");
            } else {
                response.put("error", "Failed to start recording");
            }

            return response;

        } catch (Exception e) {
            Log.e(TAG, "Error starting recording", e);
            return createErrorResponse(operationId, "Exception starting recording: " + e.getMessage());
        }
    }

    /**
     * Handle stop recording request from native layer
     */
    private JSONObject handleStopRecording(Context context, Intent intent) throws JSONException {
        String operationId = intent.getStringExtra("operation_id");

        Log.i(TAG, "Stop recording: operation=" + operationId);

        try {
            MainActivity mainActivity = MainActivity.getInstance();
            if (mainActivity == null) {
                return createErrorResponse(operationId, "MainActivity instance not available");
            }

            boolean success = stopRecordingInternal(mainActivity);

            // Create response JSON
            JSONObject response = new JSONObject();
            response.put("success", success);
            response.put("operation_id", operationId);

            if (success) {
                response.put("message", "Recording stopped successfully");
            } else {
                response.put("error", "Failed to stop recording");
            }

            return response;

        } catch (Exception e) {
            Log.e(TAG, "Error stopping recording", e);
            return createErrorResponse(operationId, "Exception stopping recording: " + e.getMessage());
        }
    }

    /**
     * Handle get status request from native layer
     */
    private JSONObject handleGetStatus(Context context, Intent intent) throws JSONException {
        String operationId = intent.getStringExtra("operation_id");

        Log.d(TAG, "Get status: operation=" + operationId);

        try {
            MainActivity mainActivity = MainActivity.getInstance();

            JSONObject response = new JSONObject();
            response.put("operation_id", operationId);

            // Always include device identification
            response.put("device_name", DeviceIdentifier.getIdentifier(context));
            response.put("device_model", android.os.Build.MODEL);
            response.put("device_manufacturer", android.os.Build.MANUFACTURER);

            if (mainActivity == null) {
                response.put("success", false);
                response.put("error", "MainActivity not available");
                response.put("state", "unknown");
            } else {
                boolean isRecording = isRecordingActive(mainActivity);
                String cameraState = isRecording ? "recording" : "idle";

                response.put("success", true);
                response.put("state", cameraState);
                response.put("is_recording", isRecording);
                response.put("timestamp", System.currentTimeMillis());

                // Add additional status information
                addCameraStatusDetails(response, mainActivity);
            }

            return response;

        } catch (Exception e) {
            Log.e(TAG, "Error getting status", e);
            return createErrorResponse(operationId, "Exception getting status: " + e.getMessage());
        }
    }

    /**
     * Handle configure request from native layer
     */
    private JSONObject handleConfigure(Context context, Intent intent) throws JSONException {
        String operationId = intent.getStringExtra("operation_id");
        String resolution = intent.getStringExtra("resolution");
        String fps = intent.getStringExtra("fps");
        String codec = intent.getStringExtra("codec");

        Log.i(TAG, String.format("Configure: operation=%s, resolution=%s, fps=%s, codec=%s",
                operationId, resolution, fps, codec));

        try {
            MainActivity mainActivity = MainActivity.getInstance();
            if (mainActivity == null) {
                return createErrorResponse(operationId, "MainActivity instance not available");
            }

            boolean success = configureCameraSettings(mainActivity, resolution, fps, codec);

            JSONObject response = new JSONObject();
            response.put("success", success);
            response.put("operation_id", operationId);
            response.put("resolution", resolution);
            response.put("fps", fps);
            response.put("codec", codec);

            if (success) {
                response.put("message", "Camera configured successfully");
            } else {
                response.put("error", "Failed to configure camera");
            }

            return response;

        } catch (Exception e) {
            Log.e(TAG, "Error configuring camera", e);
            return createErrorResponse(operationId, "Exception configuring camera: " + e.getMessage());
        }
    }

    /**
     * Handle SFTP file transfer request from native layer
     * Runs SFTP transfer in background thread to avoid NetworkOnMainThreadException
     *
     * SECURITY: Input validation applied before processing
     */
    private JSONObject handleSftpTransfer(Context context, Intent intent) throws JSONException {
        final String operationId = intent.getStringExtra("operation_id");
        final String storageServerId = intent.getStringExtra("storage_server_id");
        final String videoFilename = intent.getStringExtra("video_filename");
        final String destinationFolder = intent.getStringExtra("destination_folder");

        Log.i(TAG, String.format("SFTP transfer: server=%s, file=%s, dest=%s",
                storageServerId, videoFilename, destinationFolder));

        // Security validation - validate all parameters before processing
        String validationError;

        validationError = SecurityValidator.validateServerId(storageServerId);
        if (validationError != null) {
            Log.w(TAG, "Security: sftpTransfer rejected - " + validationError);
            return createErrorResponse(operationId, validationError);
        }

        validationError = SecurityValidator.validateFilenameOrPattern(videoFilename, "video_filename");
        if (validationError != null) {
            Log.w(TAG, "Security: sftpTransfer rejected - " + validationError);
            return createErrorResponse(operationId, validationError);
        }

        validationError = SecurityValidator.validateDestinationFolder(destinationFolder);
        if (validationError != null) {
            Log.w(TAG, "Security: sftpTransfer rejected - " + validationError);
            return createErrorResponse(operationId, validationError);
        }

        // Validate parameters first (on main thread)
        final StorageServerConfig serverConfig = getStorageServerConfig(context, storageServerId);
        if (serverConfig == null) {
            return createErrorResponse(operationId,
                    "Storage server not configured: " + storageServerId);
        }

        // Find video files to transfer
        File videoDir = getVideoDirectory(context);
        final File[] filesToTransfer = findFilesToTransfer(videoDir, videoFilename);

        if (filesToTransfer == null || filesToTransfer.length == 0) {
            return createErrorResponse(operationId,
                    "No files found matching: " + videoFilename);
        }

        // Execute SFTP transfer in background thread
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<JSONObject> future = executor.submit(() -> {
            try {
                // Perform SFTP transfer (network operation - must be off main thread)
                SftpTransferResult result = performSftpTransfer(
                        serverConfig, filesToTransfer, destinationFolder);

                JSONObject response = new JSONObject();
                response.put("success", result.success);
                response.put("operation_id", operationId);
                response.put("files_transferred", result.filesTransferred);
                response.put("bytes_transferred", result.bytesTransferred);
                response.put("timestamp", System.currentTimeMillis());

                if (result.success) {
                    response.put("message", String.format("Transferred %d file(s), %d bytes",
                            result.filesTransferred, result.bytesTransferred));
                } else {
                    response.put("error", result.errorMessage);
                }

                // Write response to file for native code polling
                writeResponseToFile(context, operationId, response);
                return response;

            } catch (Exception e) {
                Log.e(TAG, "Error in background SFTP transfer", e);
                JSONObject errorResponse = createErrorResponse(operationId, "SFTP transfer failed: " + e.getMessage());
                writeResponseToFile(context, operationId, errorResponse);
                return errorResponse;
            }
        });

        // Wait for result with timeout (native code is also polling)
        // Use 20 minutes (1200s) to allow time for large 4K video file transfers over WiFi
        try {
            return future.get(1200, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            Log.e(TAG, "SFTP transfer timed out (20 min)", e);
            return createErrorResponse(operationId, "SFTP transfer timed out");
        } catch (Exception e) {
            Log.e(TAG, "Error waiting for SFTP transfer", e);
            return createErrorResponse(operationId, "Error waiting for SFTP transfer: " + e.getMessage());
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Handle delete files request from native layer
     * Supports patterns: specific file, wildcards (*.mp4), "today", or date (2026-01-04)
     *
     * SECURITY: Input validation applied before processing
     */
    private JSONObject handleDeleteFiles(Context context, Intent intent) throws JSONException {
        String operationId = intent.getStringExtra("operation_id");
        String pattern = intent.getStringExtra("pattern");  // e.g., "*.mp4", "today", "2026-01-04", "real_video.mp4"

        Log.i(TAG, String.format("Delete files: pattern=%s", pattern));

        // Security validation
        String validationError = SecurityValidator.validateFilenameOrPattern(pattern, "pattern");
        if (validationError != null) {
            Log.w(TAG, "Security: deleteFiles rejected - " + validationError);
            return createErrorResponse(operationId, validationError);
        }

        try {
            File videoDir = getVideoDirectory(context);
            File[] filesToDelete = findFilesToDelete(videoDir, pattern);

            if (filesToDelete == null || filesToDelete.length == 0) {
                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("operation_id", operationId);
                response.put("files_deleted", 0);
                response.put("message", "No files found matching pattern: " + pattern);
                return response;
            }

            int deleted = 0;
            long bytesFreed = 0;
            java.util.List<String> deletedFiles = new java.util.ArrayList<>();

            for (File file : filesToDelete) {
                long fileSize = file.length();
                String fileName = file.getName();
                if (file.delete()) {
                    deleted++;
                    bytesFreed += fileSize;
                    deletedFiles.add(fileName);
                    Log.i(TAG, "Deleted: " + fileName + " (" + fileSize + " bytes)");
                } else {
                    Log.w(TAG, "Failed to delete: " + fileName);
                }
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("operation_id", operationId);
            response.put("files_deleted", deleted);
            response.put("bytes_freed", bytesFreed);
            response.put("deleted_files", new org.json.JSONArray(deletedFiles));
            response.put("timestamp", System.currentTimeMillis());
            response.put("message", String.format("Deleted %d file(s), freed %d bytes", deleted, bytesFreed));

            return response;

        } catch (Exception e) {
            Log.e(TAG, "Error deleting files", e);
            return createErrorResponse(operationId, "Exception deleting files: " + e.getMessage());
        }
    }

    /**
     * Handle list files request from native layer
     * Returns list of video files with metadata (size, date)
     *
     * SECURITY: Input validation applied before processing
     */
    private JSONObject handleListFiles(Context context, Intent intent) throws JSONException {
        String operationId = intent.getStringExtra("operation_id");
        String pattern = intent.getStringExtra("pattern");  // Optional filter pattern

        Log.i(TAG, String.format("List files: pattern=%s", pattern));

        // Security validation (pattern is optional, so only validate if provided)
        if (pattern != null && !pattern.isEmpty() && !pattern.equals("*")) {
            String validationError = SecurityValidator.validateFilenameOrPattern(pattern, "pattern");
            if (validationError != null) {
                Log.w(TAG, "Security: listFiles rejected - " + validationError);
                return createErrorResponse(operationId, validationError);
            }
        }

        try {
            File videoDir = getVideoDirectory(context);
            File[] files;

            if (pattern == null || pattern.isEmpty() || pattern.equals("*")) {
                files = videoDir.listFiles((dir, name) ->
                        name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".mkv"));
            } else {
                files = findFilesToDelete(videoDir, pattern);
            }

            org.json.JSONArray fileList = new org.json.JSONArray();
            long totalSize = 0;

            if (files != null) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);

                for (File file : files) {
                    JSONObject fileInfo = new JSONObject();
                    fileInfo.put("name", file.getName());
                    fileInfo.put("size", file.length());
                    fileInfo.put("modified", sdf.format(new java.util.Date(file.lastModified())));
                    fileInfo.put("modified_timestamp", file.lastModified());
                    fileList.put(fileInfo);
                    totalSize += file.length();
                }
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("operation_id", operationId);
            response.put("directory", videoDir.getAbsolutePath());
            response.put("file_count", fileList.length());
            response.put("total_size", totalSize);
            response.put("files", fileList);
            response.put("timestamp", System.currentTimeMillis());

            return response;

        } catch (Exception e) {
            Log.e(TAG, "Error listing files", e);
            return createErrorResponse(operationId, "Exception listing files: " + e.getMessage());
        }
    }

    /**
     * Find files to delete based on pattern
     * Supports: specific filename, wildcards (*.mp4), "today", date string (2026-01-04)
     *
     * SECURITY: Uses canonical path validation to prevent directory traversal
     */
    private File[] findFilesToDelete(File directory, String pattern) {
        if (directory == null || !directory.exists()) {
            return new File[0];
        }

        // Security: Additional defense-in-depth check for path traversal
        // This catches anything that slipped past the initial validation
        if (pattern.contains("..") || pattern.contains("/") || pattern.contains("\\")) {
            Log.w(TAG, "Security: path traversal blocked in findFilesToDelete: " + pattern);
            return new File[0];
        }

        // Handle "today" pattern - files modified today
        if (pattern.equalsIgnoreCase("today")) {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            final long todayStart = cal.getTimeInMillis();

            return directory.listFiles((dir, name) -> {
                File f = new File(dir, name);
                return f.isFile() && f.lastModified() >= todayStart &&
                       (name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".mkv"));
            });
        }

        // Handle date pattern (YYYY-MM-DD)
        if (pattern.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
                java.util.Date date = sdf.parse(pattern);
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTime(date);
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
                cal.set(java.util.Calendar.MINUTE, 0);
                cal.set(java.util.Calendar.SECOND, 0);
                cal.set(java.util.Calendar.MILLISECOND, 0);
                final long dayStart = cal.getTimeInMillis();
                cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
                final long dayEnd = cal.getTimeInMillis();

                return directory.listFiles((dir, name) -> {
                    File f = new File(dir, name);
                    long modified = f.lastModified();
                    return f.isFile() && modified >= dayStart && modified < dayEnd &&
                           (name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".mkv"));
                });
            } catch (Exception e) {
                Log.e(TAG, "Error parsing date pattern: " + pattern, e);
                return new File[0];
            }
        }

        // Handle wildcard patterns
        if (pattern.equals("*")) {
            return directory.listFiles((dir, name) ->
                    name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".mkv"));
        }

        if (pattern.contains("*")) {
            final String prefix = pattern.substring(0, pattern.indexOf("*"));
            final String suffix = pattern.substring(pattern.indexOf("*") + 1);
            return directory.listFiles((dir, name) ->
                    name.startsWith(prefix) && name.endsWith(suffix));
        }

        // Specific filename - with canonical path validation
        File file = new File(directory, pattern);

        // Security: Verify file is within the allowed directory using canonical paths
        if (!SecurityValidator.isPathWithinDirectory(directory, file)) {
            Log.w(TAG, "Security: file path escapes directory: " + pattern);
            return new File[0];
        }

        return file.exists() ? new File[]{file} : new File[0];
    }

    // ========================================================================
    // SFTP Transfer Implementation
    // ========================================================================

    /**
     * Storage server configuration (SSH key-based authentication)
     *
     * SSH directory structure:
     * /data/data/org.kanaha.camera/files/ssh/
     * ├── config                    # SSH client configuration
     * ├── known_hosts               # Verified server fingerprints
     * └── keys/
     *     ├── {server_id}.key       # Private key for server
     *     └── {server_id}.key.pub   # Public key for server
     */
    private static class StorageServerConfig {
        String host;
        int port;
        String username;
        String privateKeyPath;
        String knownHostsPath;

        StorageServerConfig(String host, int port, String username, String privateKeyPath, String knownHostsPath) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.privateKeyPath = privateKeyPath;
            this.knownHostsPath = knownHostsPath;
        }
    }

    /**
     * SFTP transfer result
     */
    private static class SftpTransferResult {
        boolean success;
        int filesTransferred;
        long bytesTransferred;
        String errorMessage;

        SftpTransferResult(boolean success, int files, long bytes, String error) {
            this.success = success;
            this.filesTransferred = files;
            this.bytesTransferred = bytes;
            this.errorMessage = error;
        }
    }

    /**
     * Get storage server configuration using SSH PKI infrastructure
     *
     * SSH directory structure:
     * /data/data/org.kanaha.camera/files/ssh/
     * ├── config                    # SSH client configuration (optional)
     * ├── known_hosts               # Verified server fingerprints
     * ├── keys/
     * │   ├── {server_id}.key       # Private key for server
     * │   └── {server_id}.key.pub   # Public key for server
     * └── servers.json              # Server configuration
     *
     * servers.json format:
     * {
     *   "control": {"host": "robert-inspiron16plus7640.local", "port": 22, "username": "robert"},
     *   "production": {"host": "storage.lan", "port": 22, "username": "kanaha_camera"}
     * }
     *
     * Setup:
     *   # Generate SSH key pair for server
     *   ssh-keygen -t ed25519 -f control.key -C "kanaha-camera-control" -N ""
     *
     *   # Push to device
     *   adb shell mkdir -p /data/data/org.kanaha.camera/files/ssh/keys
     *   adb push control.key /data/data/org.kanaha.camera/files/ssh/keys/
     *   adb push servers.json /data/data/org.kanaha.camera/files/ssh/
     *
     *   # Add public key to server's authorized_keys
     *   cat control.key.pub >> ~/.ssh/authorized_keys
     *
     *   # Add server fingerprint to known_hosts
     *   ssh-keyscan robert-inspiron16plus7640.local > known_hosts
     *   adb push known_hosts /data/data/org.kanaha.camera/files/ssh/
     */
    private StorageServerConfig getStorageServerConfig(Context context, String serverId) {
        File sshDir = new File(context.getFilesDir(), "ssh");
        File keysDir = new File(sshDir, "keys");
        File configFile = new File(sshDir, "servers.json");
        File privateKeyFile = new File(keysDir, serverId + ".key");
        File knownHostsFile = new File(sshDir, "known_hosts");

        // Check if SSH directory structure exists
        if (!sshDir.exists()) {
            Log.e(TAG, "SSH directory not found: " + sshDir.getAbsolutePath());
            Log.e(TAG, "Run setup: adb shell mkdir -p " + keysDir.getAbsolutePath());
            return null;
        }

        // Check if private key exists
        if (!privateKeyFile.exists()) {
            Log.e(TAG, "SSH private key not found: " + privateKeyFile.getAbsolutePath());
            Log.e(TAG, "Generate key: ssh-keygen -t ed25519 -f " + serverId + ".key -N \"\"");
            return null;
        }

        // Read server configuration
        if (!configFile.exists()) {
            Log.e(TAG, "SSH servers config not found: " + configFile.getAbsolutePath());
            return null;
        }

        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(configFile);
            byte[] data = new byte[(int) configFile.length()];
            fis.read(data);
            fis.close();

            JSONObject servers = new JSONObject(new String(data, "UTF-8"));
            if (!servers.has(serverId)) {
                Log.w(TAG, "Server not found in config: " + serverId);
                return null;
            }

            JSONObject server = servers.getJSONObject(serverId);
            String host = server.getString("host");
            int port = server.optInt("port", 22);
            String username = server.getString("username");

            Log.i(TAG, "Loaded SSH config: " + serverId + " -> " + username + "@" + host);
            return new StorageServerConfig(
                    host, port, username,
                    privateKeyFile.getAbsolutePath(),
                    knownHostsFile.exists() ? knownHostsFile.getAbsolutePath() : null
            );

        } catch (Exception e) {
            Log.e(TAG, "Error reading SSH config", e);
            return null;
        }
    }

    /**
     * Get video directory for recorded files
     * Checks multiple locations in order of preference
     */
    private File getVideoDirectory(Context context) {
        // First check app's internal files directory (always accessible)
        File internalVideos = new File(context.getFilesDir(), "videos");
        if (internalVideos.exists() && internalVideos.listFiles() != null && internalVideos.listFiles().length > 0) {
            Log.d(TAG, "Using internal videos directory: " + internalVideos.getAbsolutePath());
            return internalVideos;
        }

        // Then try OpenCamera's default video directory (where videos are actually recorded)
        File dcim = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM);
        File openCameraDir = new File(dcim, "OpenCamera");
        if (openCameraDir.exists() && openCameraDir.listFiles() != null && openCameraDir.listFiles().length > 0) {
            Log.d(TAG, "Using OpenCamera directory (has files): " + openCameraDir.getAbsolutePath());
            return openCameraDir;
        }

        // Then try app's external files directory
        File externalMovies = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES);
        if (externalMovies != null && externalMovies.exists() &&
                externalMovies.listFiles() != null && externalMovies.listFiles().length > 0) {
            Log.d(TAG, "Using external movies directory: " + externalMovies.getAbsolutePath());
            return externalMovies;
        }

        // Return OpenCamera directory even if empty (preferred location)
        if (openCameraDir.exists()) {
            Log.d(TAG, "Using OpenCamera directory: " + openCameraDir.getAbsolutePath());
            return openCameraDir;
        }

        // Last resort: create internal videos directory
        internalVideos.mkdirs();
        Log.d(TAG, "Created internal videos directory: " + internalVideos.getAbsolutePath());
        return internalVideos;
    }

    /**
     * Find files matching the pattern
     *
     * SECURITY: Uses canonical path validation to prevent directory traversal
     */
    private File[] findFilesToTransfer(File directory, String pattern) {
        if (directory == null || !directory.exists()) {
            return new File[0];
        }

        // Security: Additional defense-in-depth check for path traversal
        if (pattern.contains("..") || pattern.contains("/") || pattern.contains("\\")) {
            Log.w(TAG, "Security: path traversal blocked in findFilesToTransfer: " + pattern);
            return new File[0];
        }

        if (pattern.equals("*")) {
            // All video files
            return directory.listFiles((dir, name) ->
                    name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".mkv"));
        } else if (pattern.contains("*")) {
            // Wildcard pattern like "prefix*.mp4" or "*.mp4"
            final String prefix = pattern.substring(0, pattern.indexOf("*"));
            final String suffix = pattern.substring(pattern.indexOf("*") + 1);
            return directory.listFiles((dir, name) ->
                    name.startsWith(prefix) && name.endsWith(suffix));
        } else {
            // Specific file - with canonical path validation
            File file = new File(directory, pattern);

            // Security: Verify file is within the allowed directory
            if (!SecurityValidator.isPathWithinDirectory(directory, file)) {
                Log.w(TAG, "Security: file path escapes directory: " + pattern);
                return new File[0];
            }

            return file.exists() ? new File[]{file} : new File[0];
        }
    }

    /**
     * Perform SFTP transfer using JSch with SSH key authentication
     *
     * Note: Apache MINA SSHD was evaluated but doesn't work on Android
     * because it requires javax.management (JMX) which is not available.
     *
     * Using mwiede's JSch fork (com.github.mwiede:jsch) which provides:
     * - Full SSH2 protocol implementation
     * - Native ed25519/OpenSSH key support (unlike original JSch)
     * - Active maintenance and security updates
     * - Android compatibility
     */
    private SftpTransferResult performSftpTransfer(
            StorageServerConfig config,
            File[] files,
            String destinationFolder
    ) {
        com.jcraft.jsch.Session session = null;
        com.jcraft.jsch.ChannelSftp channelSftp = null;
        int filesTransferred = 0;
        long bytesTransferred = 0;

        try {
            com.jcraft.jsch.JSch jsch = new com.jcraft.jsch.JSch();

            // Load SSH private key (PKI authentication - no passwords)
            Log.i(TAG, "Loading SSH key: " + config.privateKeyPath);
            jsch.addIdentity(config.privateKeyPath);

            // Load known_hosts for server verification
            if (config.knownHostsPath != null) {
                Log.i(TAG, "Loading known_hosts: " + config.knownHostsPath);
                jsch.setKnownHosts(config.knownHostsPath);
            }

            // Configure session
            session = jsch.getSession(config.username, config.host, config.port);

            java.util.Properties sessionConfig = new java.util.Properties();
            if (config.knownHostsPath != null) {
                // Strict host key checking when known_hosts is available
                sessionConfig.put("StrictHostKeyChecking", "yes");
            } else {
                // Warn but allow connection without known_hosts (first-time setup)
                Log.w(TAG, "No known_hosts file - host key verification disabled");
                sessionConfig.put("StrictHostKeyChecking", "no");
            }
            // Prefer ed25519 keys
            sessionConfig.put("PreferredAuthentications", "publickey");
            session.setConfig(sessionConfig);

            // Connect with timeout
            session.setTimeout(30000);
            Log.i(TAG, "Connecting to " + config.username + "@" + config.host + ":" + config.port);
            session.connect();
            Log.i(TAG, "SSH connection established");

            // Open SFTP channel
            channelSftp = (com.jcraft.jsch.ChannelSftp) session.openChannel("sftp");
            channelSftp.connect();
            Log.i(TAG, "SFTP channel opened");

            // Ensure destination directory exists
            try {
                channelSftp.cd(destinationFolder);
            } catch (com.jcraft.jsch.SftpException e) {
                // Directory doesn't exist, try to create it
                Log.i(TAG, "Creating directory: " + destinationFolder);
                mkdirRecursive(channelSftp, destinationFolder);
                channelSftp.cd(destinationFolder);
            }

            // Transfer each file
            for (File file : files) {
                Log.i(TAG, "Transferring: " + file.getName() + " (" + file.length() + " bytes)");
                channelSftp.put(file.getAbsolutePath(), file.getName());
                filesTransferred++;
                bytesTransferred += file.length();
                Log.i(TAG, "Transferred: " + file.getName());
            }

            Log.i(TAG, "SFTP transfer complete: " + filesTransferred + " files, " + bytesTransferred + " bytes");
            return new SftpTransferResult(true, filesTransferred, bytesTransferred, null);

        } catch (com.jcraft.jsch.JSchException e) {
            Log.e(TAG, "SSH/SFTP connection error", e);
            return new SftpTransferResult(false, filesTransferred, bytesTransferred,
                    "SSH connection failed: " + e.getMessage());
        } catch (com.jcraft.jsch.SftpException e) {
            Log.e(TAG, "SFTP transfer error", e);
            return new SftpTransferResult(false, filesTransferred, bytesTransferred,
                    "SFTP transfer failed: " + e.getMessage());
        } finally {
            if (channelSftp != null && channelSftp.isConnected()) {
                channelSftp.disconnect();
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    /**
     * Recursively create directory path on SFTP server
     */
    private void mkdirRecursive(com.jcraft.jsch.ChannelSftp sftp, String path)
            throws com.jcraft.jsch.SftpException {
        String[] folders = path.split("/");
        StringBuilder currentPath = new StringBuilder();

        for (String folder : folders) {
            if (folder.isEmpty()) continue;
            currentPath.append("/").append(folder);
            try {
                sftp.cd(currentPath.toString());
            } catch (com.jcraft.jsch.SftpException e) {
                sftp.mkdir(currentPath.toString());
            }
        }
    }

    // ========================================================================
    // Helper Methods for OpenCamera Integration
    // ========================================================================

    /**
     * Start recording using OpenCamera MainActivity
     */
    private static boolean startRecordingInternal(MainActivity mainActivity, String clipName) {
        try {
            Log.i(TAG, "Starting recording with OpenCamera integration");

            // Check if already recording
            if (mainActivity.getPreview().isVideoRecording()) {
                Log.w(TAG, "Already recording, ignoring start request");
                return true; // Already recording is considered success
            }

            // Ensure we're in video mode
            if (!mainActivity.getPreview().isVideo()) {
                Log.i(TAG, "Switching to video mode");
                // Switch to video mode by calling switchVideo
                mainActivity.runOnUiThread(() -> {
                    mainActivity.clickedSwitchVideo(null);
                });
                // Give it a moment to switch
                Thread.sleep(500);
            }

            // Start video recording on UI thread
            mainActivity.runOnUiThread(() -> {
                Log.i(TAG, "Calling takePicture to start video recording");
                mainActivity.takePicture(false);
            });

            // Poll for recording start: after open-gate reopenCamera(), Camera2 needs up to
            // ~3-4s to reconfigure streams. Check every 500ms for up to 5s total.
            boolean isRecording = false;
            for (int i = 0; i < 10 && !isRecording; i++) {
                Thread.sleep(500);
                isRecording = mainActivity.getPreview().isVideoRecording();
                if (!isRecording) {
                    Log.d(TAG, "Waiting for recording to start, attempt " + (i + 1));
                }
            }
            Log.i(TAG, "Recording started: " + isRecording);
            return isRecording;

        } catch (Exception e) {
            Log.e(TAG, "Error in startRecordingInternal", e);
            return false;
        }
    }

    /**
     * Stop recording using OpenCamera MainActivity
     *
     * Uses the same UI-thread CountDownLatch polling pattern as configureOpenGate():
     * isVideoRecording() is a UI-thread field — reading it from a background thread
     * without synchronisation has Java Memory Model visibility issues and may return
     * stale true even after stopVideo() has completed.
     */
    private static boolean stopRecordingInternal(MainActivity mainActivity) {
        try {
            Log.i(TAG, "Stopping recording with OpenCamera integration");

            // Check if actually recording — via UI thread to avoid JMM stale read
            CountDownLatch checkLatch = new CountDownLatch(1);
            AtomicBoolean wasRecording = new AtomicBoolean(false);
            mainActivity.runOnUiThread(() -> {
                wasRecording.set(mainActivity.getPreview().isVideoRecording());
                checkLatch.countDown();
            });
            checkLatch.await(1, TimeUnit.SECONDS);

            if (!wasRecording.get()) {
                Log.w(TAG, "Not currently recording, ignoring stop request");
                return true; // Not recording is considered success for stop
            }

            // Post stopVideo to UI thread and return immediately
            mainActivity.runOnUiThread(() -> {
                Log.i(TAG, "Calling stopVideo to stop recording");
                mainActivity.getPreview().stopVideo(false);
            });

            // Poll for stop confirmation ON THE UI THREAD.
            // stopVideo() triggers Camera2 state changes via the Looper; reading
            // isVideoRecording() from the background thread is unreliable until the
            // UI-thread handler has processed the stop callback.
            boolean stopped = false;
            for (int i = 0; i < 10; i++) {
                Thread.sleep(500);
                CountDownLatch latch = new CountDownLatch(1);
                AtomicBoolean notRecording = new AtomicBoolean(false);
                mainActivity.runOnUiThread(() -> {
                    notRecording.set(!mainActivity.getPreview().isVideoRecording());
                    latch.countDown();
                });
                latch.await(1, TimeUnit.SECONDS);
                if (notRecording.get()) {
                    Log.i(TAG, "Recording stopped after " + ((i + 1) * 500) + "ms");
                    stopped = true;
                    break;
                }
                Log.d(TAG, "Waiting for recording to stop, attempt " + (i + 1));
            }

            Log.i(TAG, "stopRecordingInternal: success=" + stopped);
            return stopped;

        } catch (Exception e) {
            Log.e(TAG, "Error in stopRecordingInternal", e);
            return false;
        }
    }

    /**
     * Check if camera is currently recording
     */
    private static boolean isRecordingActive(MainActivity mainActivity) {
        try {
            return mainActivity.getPreview().isVideoRecording();
        } catch (Exception e) {
            Log.e(TAG, "Error checking recording state", e);
            return false;
        }
    }

    /**
     * Configure recording quality by mapping human-readable strings ("4K", "1080p", etc.)
     * or direct OpenCamera quality strings to the video quality preference.
     * Applied before startRecordingInternal; preference is consumed by OpenCamera on the next
     * setupCameraParameters() call (i.e., after reopenCamera or on the next recording start
     * if the camera session already matches).
     */
    private static void configureRecordingQuality(MainActivity mainActivity, String quality) {
        Log.i(TAG, "configureRecordingQuality: " + quality);
        try {
            Preview preview = mainActivity.getPreview();
            if (preview == null || quality == null) return;

            // Map common human-readable names to target width x height
            int targetW = 0, targetH = 0;
            String q = quality.toUpperCase().trim();
            if (q.equals("4K") || q.equals("UHD") || q.equals("2160P")) {
                targetW = 3840; targetH = 2160;
            } else if (q.equals("1080P") || q.equals("FHD")) {
                targetW = 1920; targetH = 1080;
            } else if (q.equals("720P") || q.equals("HD")) {
                targetW = 1280; targetH = 720;
            } else if (q.equals("480P") || q.equals("SD")) {
                targetW = 640; targetH = 480;
            } else {
                // Pass through as a native OpenCamera quality string if supported
                List<String> supported = preview.getVideoQualityHander().getSupportedVideoQuality();
                if (supported != null && supported.contains(quality)) {
                    final String direct = quality;
                    mainActivity.runOnUiThread(() ->
                        mainActivity.getApplicationInterface().setVideoQualityPref(direct));
                } else {
                    Log.w(TAG, "configureRecordingQuality: unrecognised quality string: " + quality);
                }
                return;
            }

            // Find quality string whose resolved resolution best matches target (exact first, then closest)
            List<String> supportedQualities = preview.getVideoQualityHander().getSupportedVideoQuality();
            if (supportedQualities == null) return;
            String matched = null;
            int closestDiff = Integer.MAX_VALUE;
            int targetArea = targetW * targetH;
            for (String qs : supportedQualities) {
                CamcorderProfile profile = preview.getCamcorderProfile(qs);
                if (profile == null) continue;
                if (profile.videoFrameWidth == targetW && profile.videoFrameHeight == targetH) {
                    matched = qs;
                    break;
                }
                int diff = Math.abs(profile.videoFrameWidth * profile.videoFrameHeight - targetArea);
                if (diff < closestDiff) { closestDiff = diff; matched = qs; }
            }
            if (matched != null) {
                final String finalQuality = matched;
                Log.i(TAG, "configureRecordingQuality: " + quality + " -> " + finalQuality);
                mainActivity.runOnUiThread(() ->
                    mainActivity.getApplicationInterface().setVideoQualityPref(finalQuality));

                // If the last recording used open gate, the camera session is still configured
                // at the 4:3 quality. setVideoQualityPref() only takes effect on session reopen,
                // so trigger a photo→video cycle to apply the new quality before recording starts.
                if (lastRecordingWasOpenGate) {
                    Log.i(TAG, "configureRecordingQuality: reopening session to reset from open gate quality");
                    mainActivity.runOnUiThread(() -> {
                        if (preview.isVideo()) mainActivity.clickedSwitchVideo(null); // video → photo
                    });
                    Thread.sleep(1500);
                    mainActivity.runOnUiThread(() -> {
                        if (!preview.isVideo()) mainActivity.clickedSwitchVideo(null); // photo → video
                    });
                    // Poll for camera readiness on UI thread
                    for (int i = 0; i < 14; i++) {
                        Thread.sleep(500);
                        CountDownLatch latch = new CountDownLatch(1);
                        AtomicBoolean ready = new AtomicBoolean(false);
                        mainActivity.runOnUiThread(() -> {
                            ready.set(preview.getCameraController() != null && preview.isVideo());
                            latch.countDown();
                        });
                        latch.await(1, TimeUnit.SECONDS);
                        if (ready.get()) {
                            Log.i(TAG, "configureRecordingQuality: session reopen complete (" + ((i + 1) * 500) + "ms)");
                            break;
                        }
                    }
                    Thread.sleep(300);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error configuring recording quality", e);
        }
    }

    /**
     * Configures open gate recording: selects the best available video resolution whose
     * aspect ratio matches the sensor's native ratio (4:3 ± 2%), resets digital zoom to 1x
     * so the full active sensor area is read out, and reopens the camera to apply settings.
     *
     * Open gate captures all pixels the sensor has (no horizontal/vertical crop), giving
     * ~33% more vertical resolution than 16:9 on a native 4:3 sensor (e.g. Pixel 9 Pro).
     * This is only useful on devices whose Camera2 HAL exposes a 4:3 video resolution —
     * the Pixel 9 Pro does; the Moto G phones in this rig do not.
     *
     * Implementation notes:
     *  - Does NOT set SCALER_CROP_REGION: at zoom=1 (default) Camera2 already uses the
     *    full SENSOR_INFO_ACTIVE_ARRAY_SIZE, so just selecting a 4:3 resolution is sufficient.
     *  - Uses clickedSwitchVideo (photo→video cycle) instead of reopenCamera() directly;
     *    direct reopenCamera() calls across multiple attempts leave camera_controller null.
     *  - Polls for readiness via runOnUiThread + CountDownLatch: camera_controller is a
     *    UI-thread field with no volatile guarantee; background-thread reads are unreliable.
     */
    private static void configureOpenGate(MainActivity mainActivity) {
        Log.i(TAG, "configureOpenGate: searching for native-ratio video resolution");
        try {
            Preview preview = mainActivity.getPreview();
            if (preview == null) {
                Log.e(TAG, "configureOpenGate: preview is null");
                return;
            }
            List<String> supportedQualities = preview.getVideoQualityHander().getSupportedVideoQuality();
            if (supportedQualities == null || supportedQualities.isEmpty()) {
                Log.e(TAG, "configureOpenGate: no supported video qualities available");
                return;
            }

            // Target 4:3 (the native aspect ratio of sensors like the Pixel 9 Pro Samsung GNK).
            // Epsilon ±2% covers minor sensor binning or HAL rounding variations.
            final double TARGET_RATIO = 4.0 / 3.0;
            final double EPSILON = 0.02;
            String bestQuality = null;
            int bestArea = 0;

            for (String qs : supportedQualities) {
                try {
                    CamcorderProfile profile = preview.getCamcorderProfile(qs);
                    if (profile == null) continue;
                    int w = profile.videoFrameWidth;
                    int h = profile.videoFrameHeight;
                    if (w <= 0 || h <= 0) continue;
                    double ratio = (double) w / h;
                    if (Math.abs(ratio - TARGET_RATIO) <= EPSILON) {
                        int area = w * h;
                        if (area > bestArea) {
                            bestArea = area;
                            bestQuality = qs;
                            Log.i(TAG, "configureOpenGate: candidate " + w + "x" + h
                                    + " ratio=" + String.format("%.4f", ratio)
                                    + " quality=" + qs);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "configureOpenGate: skipping quality " + qs, e);
                }
            }

            if (bestQuality == null) {
                Log.w(TAG, "configureOpenGate: no 4:3 resolution found — "
                        + "this device does not support open gate via Camera2");
                return;
            }

            CamcorderProfile best = preview.getCamcorderProfile(bestQuality);
            Log.i(TAG, "configureOpenGate: selected " + best.videoFrameWidth
                    + "x" + best.videoFrameHeight + " quality=" + bestQuality);

            final String finalQuality = bestQuality;

            // Step 1: Set quality preference and switch to photo mode if currently in video.
            // We use clickedSwitchVideo (OpenCamera's official state-machine transition) rather
            // than reopenCamera() directly, which can leave the camera in an unrecoverable null
            // state across successive calls.
            mainActivity.runOnUiThread(() -> {
                mainActivity.getApplicationInterface().setVideoQualityPref(finalQuality);
                if (preview.getCameraController() != null) {
                    preview.getCameraController().resetZoom();
                }
                if (preview.isVideo()) {
                    Log.d(TAG, "configureOpenGate: switching to photo mode");
                    mainActivity.clickedSwitchVideo(null); // video → photo, closes video session
                }
            });

            // Wait for photo mode to stabilize before switching back.
            Thread.sleep(2000);

            // Step 2: Switch back to video mode — this triggers reopenCamera() inside
            // OpenCamera which calls setupCamera() and picks up the new quality preference.
            mainActivity.runOnUiThread(() -> {
                if (!preview.isVideo()) {
                    Log.d(TAG, "configureOpenGate: switching back to video mode");
                    mainActivity.clickedSwitchVideo(null); // photo → video with new quality
                }
            });

            // Step 3: Poll for camera readiness ON THE UI THREAD.
            // camera_controller is written on the UI thread; reading it from a background
            // thread without synchronization has Java Memory Model visibility issues.
            // Using runOnUiThread + CountDownLatch ensures we see the authoritative value.
            boolean cameraReady = false;
            for (int i = 0; i < 20; i++) {
                Thread.sleep(500);
                CountDownLatch latch = new CountDownLatch(1);
                AtomicBoolean ready = new AtomicBoolean(false);
                mainActivity.runOnUiThread(() -> {
                    ready.set(preview.getCameraController() != null && preview.isVideo());
                    latch.countDown();
                });
                latch.await(1, TimeUnit.SECONDS);
                if (ready.get()) {
                    Log.i(TAG, "configureOpenGate: camera ready in video mode ("
                            + ((i + 1) * 500) + "ms after video switch)");
                    cameraReady = true;
                    break;
                }
                Log.d(TAG, "configureOpenGate: waiting for camera ready, attempt " + (i + 1));
            }
            if (!cameraReady) {
                Log.e(TAG, "configureOpenGate: camera not ready after 10s — aborting");
                return;
            }

            // Allow startCameraPreview() to fully settle on the UI thread.
            Thread.sleep(500);
            Log.i(TAG, "configureOpenGate: reopen wait complete, ready to record");

        } catch (Exception e) {
            Log.e(TAG, "configureOpenGate: error", e);
        }
    }

    /**
     * Configure camera settings
     */
    private static boolean configureCameraSettings(MainActivity mainActivity, String resolution, String fps, String codec) {
        Log.i(TAG, String.format("Configuring camera: resolution=%s, fps=%s, codec=%s", resolution, fps, codec));

        try {
            // TODO: Integrate with OpenCamera settings configuration
            // This would modify the camera preview and recording settings

            return true; // Placeholder success

        } catch (Exception e) {
            Log.e(TAG, "Error configuring camera settings", e);
            return false;
        }
    }

    /**
     * Handle playTone request — plays a sine wave through the speaker at a scheduled time.
     * Used as a software sync slate: the tone onset in the recording audio track gives a
     * precise reference point for aligning multiple cameras in post-production.
     *
     * Parameters (via Intent extras):
     *   frequency    - tone frequency in Hz (default 1000)
     *   duration_ms  - tone duration in ms (default 150)
     *   start_at     - UTC epoch ms to fire; 0 or absent = play immediately
     */
    private JSONObject handlePlayTone(Context context, Intent intent) throws JSONException {
        String operationId = intent.getStringExtra("operation_id");
        int frequency  = intent.getIntExtra("frequency",  1000);
        int durationMs = intent.getIntExtra("duration_ms", 150);

        String startAtStr = intent.getStringExtra("start_at");
        long startAt = 0;
        if (startAtStr != null && !startAtStr.isEmpty() && !startAtStr.equals("0")) {
            try { startAt = Long.parseLong(startAtStr); } catch (NumberFormatException ignored) {}
        }

        long now = System.currentTimeMillis();
        long delayMs = startAt > 0 ? startAt - now : 0;

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("operation_id", operationId);
        response.put("frequency", frequency);
        response.put("duration_ms", durationMs);
        response.put("timestamp", now);

        if (delayMs < 0 || delayMs > 30000) {
            // start_at already passed or too far in future — play immediately
            delayMs = 0;
        }

        final int finalFreq = frequency;
        final int finalDur  = durationMs;
        final long fireAt   = now + delayMs;

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Log.i(TAG, "playTone firing at " + System.currentTimeMillis()
                    + " (target " + fireAt + ") freq=" + finalFreq + "Hz dur=" + finalDur + "ms");
            playToneNow(finalFreq, finalDur);
        }, delayMs);

        if (startAt > 0) {
            response.put("scheduled", true);
            response.put("fire_at", fireAt);
            response.put("delay_ms", delayMs);
        }

        return response;
    }

    /**
     * Synthesise and play a sine-wave tone through the device speaker on the calling thread.
     * Uses AudioTrack MODE_STATIC so the entire buffer is enqueued before play() —
     * minimising the gap between postDelayed() firing and actual audio output.
     *
     * Short linear fades (5 ms) prevent click artefacts at the start and end of the tone.
     * The onset of the resulting waveform in the recording can be detected to ~1 ms accuracy.
     */
    private static void playToneNow(int frequencyHz, int durationMs) {
        final int sampleRate  = 44100;
        final int numSamples  = sampleRate * durationMs / 1000;
        final int fadeSamples = Math.min(sampleRate * 5 / 1000, numSamples / 4); // 5 ms fade

        short[] samples = new short[numSamples];
        double rad = 2.0 * Math.PI * frequencyHz / sampleRate;
        for (int i = 0; i < numSamples; i++) {
            double amp = 1.0;
            if (i < fadeSamples)              amp = (double) i / fadeSamples;
            else if (i > numSamples - fadeSamples) amp = (double)(numSamples - i) / fadeSamples;
            samples[i] = (short)(Short.MAX_VALUE * amp * Math.sin(rad * i));
        }

        AudioTrack track = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(numSamples * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build();

        track.write(samples, 0, numSamples);
        track.play();
        try { Thread.sleep(durationMs + 50); } catch (InterruptedException ignored) {}
        track.stop();
        track.release();
    }

    /**
     * Write a JSON sidecar file capturing the exact recording start time in milliseconds.
     *
     * Analog of the BWF Time Reference embedded in broadcast WAV files (parseLTC.sh reads it
     * via "sndfile-info --broadcast f8.wav | grep 'Time ref'" to compute f8_start_timecode).
     * MP4 files only carry creation_time at 1-second resolution; this sidecar gives ms precision.
     *
     * File: DCIM/OpenCamera/kanaha_recording_start.json
     * Content:
     *   recording_start_ms  — System.currentTimeMillis() at the moment recording starts
     *   clip_name           — requested clip name prefix
     *   gps_time            — Location.getTime() of most recent GPS fix (ms since epoch)
     *   gps_age_ms          — age of the GPS fix at recording start
     *
     * parseWithoutLTC.sh reads this file from the camera transfer directory to compute
     * sub-second trim offsets: laptop_ms = recording_start_ms - clock_offset_ms.
     * Combined with NTP RTT correction this yields ~100–200 ms accuracy; with a software
     * slate it improves to ~1–5 ms.
     */
    private void writeSyncSidecar(Context context, String clipName, long recordingStartMs, boolean openGate) {
        try {
            JSONObject sidecar = new JSONObject();
            sidecar.put("recording_start_ms", recordingStartMs);
            sidecar.put("clip_name", clipName != null ? clipName : "");
            sidecar.put("open_gate", openGate);

            // Add GPS fix time if available (same pattern as addCameraStatusDetails)
            MainActivity mainActivity = MainActivity.getInstance();
            if (mainActivity != null) {
                try {
                    Location gpsLoc = null;
                    net.sourceforge.opencamera.LocationSupplier locationSupplier =
                            mainActivity.getLocationSupplier();
                    if (locationSupplier != null) {
                        gpsLoc = locationSupplier.getLocation();
                    }
                    if (gpsLoc == null
                            && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                               == PackageManager.PERMISSION_GRANTED) {
                        LocationManager lm = (LocationManager)
                                mainActivity.getSystemService(Context.LOCATION_SERVICE);
                        if (lm != null) {
                            gpsLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                        }
                    }
                    if (gpsLoc != null && gpsLoc.getTime() > 0) {
                        sidecar.put("gps_time", gpsLoc.getTime());
                        sidecar.put("gps_age_ms", recordingStartMs - gpsLoc.getTime());
                    }
                } catch (SecurityException ignored) {}
            }

            // Write to app's own external files dir — no scoped storage permission needed
            // from BroadcastReceiver context. Path:
            //   /storage/emulated/0/Android/data/org.kanaha.camera/files/kanaha_recording_start.json
            // Pull via ADB: adb pull /storage/emulated/0/Android/data/org.kanaha.camera/files/kanaha_recording_start.json
            File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir(); // fallback to internal
            if (!dir.exists()) dir.mkdirs();
            File sidecarFile = new File(dir, "kanaha_recording_start.json");
            try (FileWriter fw = new FileWriter(sidecarFile)) {
                fw.write(sidecar.toString(2));
            }
            Log.i(TAG, "Sync sidecar written: " + sidecarFile.getAbsolutePath()
                    + "  recording_start_ms=" + recordingStartMs);
        } catch (Exception e) {
            Log.w(TAG, "Failed to write sync sidecar (non-fatal)", e);
        }
    }

    /**
     * Add detailed camera status information to response
     */
    private static void addCameraStatusDetails(JSONObject response, MainActivity mainActivity) throws JSONException {
        try {
            // Camera availability
            boolean cameraAvailable = mainActivity.getPreview() != null &&
                                      mainActivity.getPreview().getCameraController() != null;
            response.put("camera_available", cameraAvailable);

            // Preview state
            boolean previewActive = mainActivity.getPreview() != null &&
                                    mainActivity.getPreview().isPreviewStarted();
            response.put("preview_active", previewActive);

            // Video mode
            boolean isVideoMode = mainActivity.getPreview() != null &&
                                  mainActivity.getPreview().isVideo();
            response.put("video_mode", isVideoMode);

            // Battery level (from Android system)
            android.content.Intent batteryIntent = mainActivity.registerReceiver(null,
                new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent != null) {
                int level = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                int batteryPct = (int) ((level / (float) scale) * 100);
                response.put("battery_level", batteryPct);
            } else {
                response.put("battery_level", -1);
            }

            // Storage available
            java.io.File storageDir = mainActivity.getExternalFilesDir(null);
            if (storageDir != null) {
                long availableBytes = storageDir.getFreeSpace();
                long availableMB = availableBytes / (1024 * 1024);
                response.put("storage_available_mb", availableMB);
            } else {
                response.put("storage_available_mb", -1);
            }

            // GPS time — prefer LocationSupplier (active 1-second subscription when geotagging
            // is enabled) over getLastKnownLocation (OS cache, potentially hours stale).
            try {
                Location gpsLoc = null;

                // LocationSupplier runs requestLocationUpdates(GPS_PROVIDER, 1000, 0, listener)
                // when the user has enabled "Store GPS location" in OpenCamera settings.
                net.sourceforge.opencamera.LocationSupplier locationSupplier =
                    mainActivity.getLocationSupplier();
                if (locationSupplier != null) {
                    gpsLoc = locationSupplier.getLocation();
                }

                // Fallback: OS-cached last known location (may be stale if geotagging is off)
                if (gpsLoc == null
                        && mainActivity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                           == PackageManager.PERMISSION_GRANTED) {
                    LocationManager lm = (LocationManager) mainActivity.getSystemService(Context.LOCATION_SERVICE);
                    gpsLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                }

                if (gpsLoc != null && gpsLoc.getTime() > 0) {
                    response.put("gps_time", gpsLoc.getTime());
                    response.put("gps_age_ms", System.currentTimeMillis() - gpsLoc.getTime());
                    response.put("gps_provider", gpsLoc.getProvider());
                }
            } catch (SecurityException e) {
                Log.d(TAG, "GPS location permission not available");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error getting camera status details", e);
            response.put("camera_available", false);
            response.put("preview_active", false);
            response.put("battery_level", -1);
            response.put("storage_available_mb", -1);
        }
    }

    /**
     * Write response JSON to file for native C code to read
     */
    private void writeResponseToFile(Context context, String operationId, JSONObject response) {
        try {
            File cacheDir = context.getCacheDir();
            File responseFile = new File(cacheDir, "response_" + operationId + ".json");

            try (FileWriter writer = new FileWriter(responseFile)) {
                writer.write(response.toString());
            }

            Log.d(TAG, "Response written to file: " + responseFile.getAbsolutePath());

        } catch (IOException e) {
            Log.e(TAG, "Error writing response to file", e);
        }
    }

    /**
     * Create error response JSON
     */
    private static JSONObject createErrorResponse(String operationId, String errorMessage) throws JSONException {
        JSONObject response = new JSONObject();
        response.put("success", false);
        response.put("operation_id", operationId);
        response.put("error", errorMessage);
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }
}