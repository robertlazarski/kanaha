/*
 * Kanaha Camera Control System
 * On-device model status shared by both build flavors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * The status vocabulary that describeClip and getStatus report, and the
 * download progress the nano flavor publishes while the model is being
 * fetched. Lives in the main source set so the receiver, the status block and
 * both ClipDescriber implementations agree on the strings without sharing a
 * flavor-specific class. See docs/GOOGLE_NANO_INTEGRATION.md.
 */

package org.kanaha.camera;

final class OnDeviceModel {
    private OnDeviceModel() {}

    /** Feature name reported in getStatus.on_device_model.feature */
    static final String FEATURE = "image_description";

    /** The model works on this device right now. */
    static final String AVAILABLE = "available";
    /** Supported device; the model has not been downloaded yet. */
    static final String DOWNLOADABLE = "downloadable";
    /** A download is in progress (started by this app or earlier). */
    static final String DOWNLOADING = "downloading";
    /** Unsupported device, OS, bootloader, API level, or a build without the client. */
    static final String UNAVAILABLE = "unavailable";

    /** Stable error codes carried in describeClip failure responses. */
    static final String CODE_UNAVAILABLE = "unavailable";
    static final String CODE_DOWNLOADING = "downloading";
    static final String CODE_DOWNLOAD_FAILED = "download_failed";
    static final String CODE_NOT_FOUND = "not_found";
    static final String CODE_FRAME_EXTRACT_FAILED = "frame_extract_failed";
    static final String CODE_INFERENCE_TIMEOUT = "inference_timeout";

    /** Download progress, 0-100, published by the nano flavor; -1 when unknown. */
    private static volatile int downloadPercent = -1;
    private static volatile long downloadTotalBytes = 0;

    static int getDownloadPercent() { return downloadPercent; }

    static void onDownloadStarted(long totalBytes) {
        downloadTotalBytes = totalBytes;
        downloadPercent = 0;
    }

    static void onDownloadProgress(long bytesDownloaded) {
        long total = downloadTotalBytes;
        if (total > 0) {
            long pct = (bytesDownloaded * 100L) / total;
            downloadPercent = (int) Math.max(0, Math.min(100, pct));
        }
    }

    static void onDownloadCompleted() { downloadPercent = 100; }

    static void onDownloadFailed() { downloadPercent = -1; }
}
