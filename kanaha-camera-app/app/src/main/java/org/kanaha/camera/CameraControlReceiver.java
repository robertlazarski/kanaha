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
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.regex.Pattern;

import net.sourceforge.opencamera.MainActivity;

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

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Received camera control intent");

        if (!CAMERA_CONTROL_ACTION.equals(intent.getAction())) {
            Log.w(TAG, "Unknown action: " + intent.getAction());
            return;
        }

        String action = intent.getStringExtra("action");
        String operationId = intent.getStringExtra("operation_id");

        if (action == null || operationId == null) {
            Log.e(TAG, "Missing required parameters: action or operation_id");
            return;
        }

        Log.d(TAG, String.format("Processing action: %s, operation: %s", action, operationId));

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
                default:
                    Log.e(TAG, "Unknown action: " + action);
                    response = createErrorResponse(operationId, "Unknown action: " + action);
                    break;
            }

            // Write response to file for native C code to read
            writeResponseToFile(context, operationId, response);

        } catch (Exception e) {
            Log.e(TAG, "Error processing camera control action: " + action, e);
            try {
                JSONObject errorResponse = createErrorResponse(operationId, "Exception: " + e.getMessage());
                writeResponseToFile(context, operationId, errorResponse);
            } catch (Exception e2) {
                Log.e(TAG, "Failed to write error response", e2);
            }
        }
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

        Log.i(TAG, String.format("Start recording: clip=%s, quality=%s, duration=%d, format=%s",
                clipName, quality, duration, format));

        try {
            // Get MainActivity instance and start recording
            MainActivity mainActivity = MainActivity.getInstance();
            if (mainActivity == null) {
                return createErrorResponse(operationId, "MainActivity instance not available");
            }

            // Configure recording parameters
            if (quality != null) {
                configureRecordingQuality(mainActivity, quality);
            }

            // Start recording with specified clip name
            boolean success = startRecordingInternal(mainActivity, clipName);

            // Create response JSON
            JSONObject response = new JSONObject();
            response.put("success", success);
            response.put("operation_id", operationId);
            response.put("clip_name", clipName);
            response.put("quality", quality);
            response.put("duration", duration);
            response.put("format", format);

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

            // Wait a bit and verify recording started
            Thread.sleep(1000);

            boolean isRecording = mainActivity.getPreview().isVideoRecording();
            Log.i(TAG, "Recording started: " + isRecording);
            return isRecording;

        } catch (Exception e) {
            Log.e(TAG, "Error in startRecordingInternal", e);
            return false;
        }
    }

    /**
     * Stop recording using OpenCamera MainActivity
     */
    private static boolean stopRecordingInternal(MainActivity mainActivity) {
        try {
            Log.i(TAG, "Stopping recording with OpenCamera integration");

            // Check if actually recording
            if (!mainActivity.getPreview().isVideoRecording()) {
                Log.w(TAG, "Not currently recording, ignoring stop request");
                return true; // Not recording is considered success for stop
            }

            // Stop video recording on UI thread
            mainActivity.runOnUiThread(() -> {
                Log.i(TAG, "Calling stopVideo to stop recording");
                mainActivity.getPreview().stopVideo(false);
            });

            // Wait a bit and verify recording stopped
            Thread.sleep(500);

            boolean stillRecording = mainActivity.getPreview().isVideoRecording();
            Log.i(TAG, "Recording stopped: " + !stillRecording);
            return !stillRecording;

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
     * Configure recording quality settings
     */
    private static void configureRecordingQuality(MainActivity mainActivity, String quality) {
        Log.i(TAG, "Configuring recording quality: " + quality);

        try {
            // TODO: Map quality strings to OpenCamera video quality settings
            // e.g., "4K" -> 3840x2160, "HD" -> 1920x1080, etc.

        } catch (Exception e) {
            Log.e(TAG, "Error configuring recording quality", e);
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