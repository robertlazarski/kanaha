/*
 * Kanaha Camera Control System
 * Frame sampling for on-device clip description
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * Pulls a few frames out of a finished recording with MediaMetadataRetriever
 * and downscales them for inference. Flavor-independent: the foss build never
 * calls it, but it has no dependency on the model client and is unit-testable
 * on any device. See docs/GOOGLE_NANO_INTEGRATION.md.
 */

package org.kanaha.camera;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class FrameSampler {
    private FrameSampler() {}

    /** One sampled frame: where it came from and the (downscaled) pixels. */
    static final class Frame {
        final double position;
        final long timeMs;
        final Bitmap bitmap;
        Frame(double position, long timeMs, Bitmap bitmap) {
            this.position = position;
            this.timeMs = timeMs;
            this.bitmap = bitmap;
        }
    }

    static final int MIN_FRAMES = 1;
    static final int MAX_FRAMES = 8;
    static final int DEFAULT_FRAMES = 3;
    static final int DEFAULT_MAX_DIMENSION = 1280;

    /** Evenly spaced positions in (0, 1): 3 frames give 0.167, 0.5, 0.833. */
    static double[] evenPositions(int count) {
        double[] p = new double[count];
        for (int i = 0; i < count; i++) {
            p[i] = (i + 0.5) / count;
        }
        return p;
    }

    /**
     * Parse "0.1,0.5,0.9" into positions. Returns null when the string is
     * empty; throws IllegalArgumentException when a value is not a number in
     * [0, 1] or the count does not match.
     */
    static double[] parsePositions(String csv, int expectedCount) {
        if (csv == null || csv.trim().isEmpty()) return null;
        String[] parts = csv.split(",");
        if (parts.length != expectedCount) {
            throw new IllegalArgumentException("positions has " + parts.length
                    + " values but frame_count is " + expectedCount);
        }
        double[] p = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            double v;
            try {
                v = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("positions[" + i + "] is not a number");
            }
            if (Double.isNaN(v) || v < 0.0 || v > 1.0) {
                throw new IllegalArgumentException("positions[" + i + "] = " + v + " is outside [0, 1]");
            }
            p[i] = v;
        }
        return p;
    }

    /** Duration in milliseconds, or -1 if the container does not say. */
    static long durationMs(File video) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(video.getAbsolutePath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d != null ? Long.parseLong(d) : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    /**
     * Extract one frame per position. Frames are the nearest sync frame at or
     * before the requested time, downscaled so the longest side is at most
     * maxDimension. The caller owns the bitmaps and must recycle them.
     */
    static List<Frame> sample(File video, double[] positions, int maxDimension) throws Exception {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        List<Frame> out = new ArrayList<>(positions.length);
        try {
            r.setDataSource(video.getAbsolutePath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = d != null ? Long.parseLong(d) : 0;
            for (double pos : positions) {
                long timeMs = (long) (pos * durationMs);
                Bitmap raw = r.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (raw == null) {
                    throw new IllegalStateException("no frame at " + timeMs + " ms");
                }
                out.add(new Frame(pos, timeMs, downscale(raw, maxDimension)));
            }
            return out;
        } catch (Exception e) {
            for (Frame f : out) f.bitmap.recycle();
            throw e;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    /** Scale so the longest side is at most maxDimension; recycles the input if it made a copy. */
    static Bitmap downscale(Bitmap in, int maxDimension) {
        int w = in.getWidth(), h = in.getHeight();
        int longest = Math.max(w, h);
        if (maxDimension <= 0 || longest <= maxDimension) return in;
        double scale = (double) maxDimension / longest;
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        Bitmap out = Bitmap.createScaledBitmap(in, nw, nh, true);
        if (out != in) in.recycle();
        return out;
    }
}
