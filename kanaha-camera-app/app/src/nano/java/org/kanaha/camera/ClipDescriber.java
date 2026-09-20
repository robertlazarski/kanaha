/*
 * Kanaha Camera Control System
 * On-device image description through Gemini Nano (nano flavor)
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * The only place in this app where a model runs from Java, and the only file
 * that touches the ML Kit GenAI client. The API is beta and Java/Kotlin only;
 * keeping every call here means a rename touches one file. The foss flavor
 * has a class of the same name and signatures that reports UNAVAILABLE.
 * See docs/GOOGLE_NANO_INTEGRATION.md.
 *
 * Frames are handed to the AICore system service on the same device; nothing
 * is uploaded. Google's client refuses devices with an unlocked bootloader,
 * unsupported models, and API < 26, all of which surface as UNAVAILABLE.
 */

package org.kanaha.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.genai.common.DownloadCallback;
import com.google.mlkit.genai.common.FeatureStatus;
import com.google.mlkit.genai.common.GenAiException;
import com.google.mlkit.genai.imagedescription.ImageDescriber;
import com.google.mlkit.genai.imagedescription.ImageDescriberOptions;
import com.google.mlkit.genai.imagedescription.ImageDescription;
import com.google.mlkit.genai.imagedescription.ImageDescriptionRequest;
import com.google.mlkit.genai.imagedescription.ImageDescriptionResult;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ClipDescriber implements AutoCloseable {
    private static final String TAG = "ClipDescriber";

    /** Reported as "model" in responses and sidecars. */
    static final String MODEL_ID = "gemini-nano/mlkit-image-description";
    /** Reported as "language"; the API describes in English only today. */
    static final String LANGUAGE = "en";
    /** True in this flavor: the client library is present. */
    static final boolean BUILT_WITH_MODEL = true;

    private static final long STATUS_TIMEOUT_S = 5;
    private static final long INFERENCE_TIMEOUT_S = 30;

    private final ImageDescriber describer;

    ClipDescriber(Context context) {
        if (Build.VERSION.SDK_INT < 26) {
            describer = null;   // status() reports UNAVAILABLE below API 26
        } else {
            // The receiver's context is restricted and may not bind to services;
            // the client binds to the AICore service, so it needs the application
            // context. Passing the receiver context fails with "BroadcastReceiver
            // components are not allowed to bind to services".
            ImageDescriberOptions options =
                    ImageDescriberOptions.builder(context.getApplicationContext()).build();
            describer = ImageDescription.getClient(options);
        }
    }

    /** One of OnDeviceModel.AVAILABLE / DOWNLOADABLE / DOWNLOADING / UNAVAILABLE. */
    String status() {
        if (describer == null) return OnDeviceModel.UNAVAILABLE;
        try {
            ListenableFuture<Integer> f = describer.checkFeatureStatus();
            int s = f.get(STATUS_TIMEOUT_S, TimeUnit.SECONDS);
            switch (s) {
                case FeatureStatus.AVAILABLE:    return OnDeviceModel.AVAILABLE;
                case FeatureStatus.DOWNLOADABLE: return OnDeviceModel.DOWNLOADABLE;
                case FeatureStatus.DOWNLOADING:  return OnDeviceModel.DOWNLOADING;
                default:                         return OnDeviceModel.UNAVAILABLE;
            }
        } catch (Exception e) {
            Log.w(TAG, "checkFeatureStatus failed: " + e.getMessage());
            return OnDeviceModel.UNAVAILABLE;
        }
    }

    /**
     * Start the model download when the status is DOWNLOADABLE. Returns at
     * once; progress is published through OnDeviceModel for getStatus.
     */
    void startDownload() {
        if (describer == null) return;
        try {
            describer.downloadFeature(new DownloadCallback() {
                @Override public void onDownloadStarted(long bytesToDownload) {
                    Log.i(TAG, "model download started: " + bytesToDownload + " bytes");
                    OnDeviceModel.onDownloadStarted(bytesToDownload);
                }
                @Override public void onDownloadProgress(long totalBytesDownloaded) {
                    OnDeviceModel.onDownloadProgress(totalBytesDownloaded);
                }
                @Override public void onDownloadCompleted() {
                    Log.i(TAG, "model download completed");
                    OnDeviceModel.onDownloadCompleted();
                }
                @Override public void onDownloadFailed(GenAiException e) {
                    Log.w(TAG, "model download failed: " + e.getMessage());
                    OnDeviceModel.onDownloadFailed();
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "downloadFeature failed to start: " + e.getMessage());
            OnDeviceModel.onDownloadFailed();
        }
    }

    /**
     * Describe one frame. Blocks for up to INFERENCE_TIMEOUT_S on the calling
     * (worker) thread; never call on the main thread.
     */
    String describe(Bitmap bitmap) throws TimeoutException, Exception {
        if (describer == null) throw new IllegalStateException("model unavailable");
        ImageDescriptionRequest request = ImageDescriptionRequest.builder(bitmap).build();
        ListenableFuture<ImageDescriptionResult> f = describer.runInference(request);
        ImageDescriptionResult result = f.get(INFERENCE_TIMEOUT_S, TimeUnit.SECONDS);
        String text = result != null ? result.getDescription() : null;
        return text != null ? text.trim() : "";
    }

    @Override
    public void close() {
        if (describer != null) {
            try { describer.close(); } catch (Exception ignored) {}
        }
    }
}
