/*
 * Kanaha Camera Control System
 * On-device image description stub (foss flavor)
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * The default build carries no proprietary model client. This class has the
 * same signatures as the nano flavor's and reports UNAVAILABLE, so the
 * operation, the receiver, the C service and the MCP catalog are identical in
 * both builds and a client can tell them apart only by the status code, which
 * is the contract: the operation exists everywhere, the model is present where
 * the device and the build provide it. See docs/GOOGLE_NANO_INTEGRATION.md.
 */

package org.kanaha.camera;

import android.content.Context;
import android.graphics.Bitmap;

import java.util.concurrent.TimeoutException;

final class ClipDescriber implements AutoCloseable {
    static final String MODEL_ID = "none";
    static final String LANGUAGE = "en";
    static final boolean BUILT_WITH_MODEL = false;

    ClipDescriber(Context context) {}

    String status() { return OnDeviceModel.UNAVAILABLE; }

    void startDownload() {}

    String describe(Bitmap bitmap) throws TimeoutException, Exception {
        throw new IllegalStateException("this build carries no on-device model");
    }

    @Override
    public void close() {}
}
