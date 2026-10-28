package org.kanaha.camera;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;

/**
 * How close a 12-bit RAW take's frames are to clipping, measured on the raw sensor
 * values themselves rather than a processed preview: the preview's tone curve hides
 * both clipped highlights and the margin left above them.
 *
 * <p>Reads a sparse grid of whole 2x2 CFA quads (every 16th row and column), so every
 * colour channel is counted and one frame costs about 200 000 reads: little enough to
 * run on the camera thread, as RawTake does on the arming frame and once a second.
 *
 * <p>The three numbers, in getStatus and startRecording's reply under "exposure":
 * <ul>
 * <li>clipped_pct: photosites at the white level. Above about 0.1 the highlights that
 *     matter are burning; lower the shutter time or ISO.
 * <li>headroom_stops: from the brightest 0.1% of photosites up to clipping. Under about
 *     0.3 the next brighter moment will clip.
 * <li>median_stops_below_clip: where the bulk of the frame sits. Much more than 5-6
 *     stops down, shadows will be noisy when lifted.
 * </ul>
 */
final class RawExposure {
    private static final int STEP = 16;

    final double clippedPct;
    final double headroomStops;
    final double medianStopsBelowClip;

    private RawExposure(double clippedPct, double headroomStops, double medianStopsBelowClip) {
        this.clippedPct = clippedPct;
        this.headroomStops = headroomStops;
        this.medianStopsBelowClip = medianStopsBelowClip;
    }

    /**
     * Measure one RAW_SENSOR frame: 16-bit little-endian samples, {@code stride} bytes per
     * row. Reads with absolute gets, so the buffer's position and limit are untouched.
     */
    static RawExposure measure(ByteBuffer frame, int width, int height, int stride, double black, int white) {
        int[] counts = new int[white + 1];
        int total = 0;
        for (int y = 0; y + 1 < height; y += STEP) {
            for (int x = 0; x + 1 < width; x += STEP) {
                for (int dy = 0; dy < 2; dy++) {
                    int row = (y + dy) * stride;
                    for (int dx = 0; dx < 2; dx++) {
                        int i = row + (x + dx) * 2;
                        if (i + 1 >= frame.limit()) continue;
                        int v = (frame.get(i) & 0xff) | (frame.get(i + 1) & 0xff) << 8;
                        counts[Math.min(v, white)]++;
                        total++;
                    }
                }
            }
        }
        if (total == 0) return new RawExposure(0, 0, 0);
        double range = white - black;
        int clipped = counts[white];
        int p999 = percentile(counts, total, 0.999);
        int median = percentile(counts, total, 0.5);
        return new RawExposure(100.0 * clipped / total, stopsBelow(range, p999 - black), stopsBelow(range, median - black));
    }

    private static int percentile(int[] counts, int total, double p) {
        long target = (long) Math.ceil(total * p);
        long seen = 0;
        for (int v = 0; v < counts.length; v++) {
            seen += counts[v];
            if (seen >= target) return v;
        }
        return counts.length - 1;
    }

    /** log2(range / level), with black (or below) reported as 12 stops down. */
    private static double stopsBelow(double range, double level) {
        if (level <= range / 4096) return 12;
        return Math.max(0, Math.log(range / level) / Math.log(2));
    }

    JSONObject toJson() throws JSONException {
        JSONObject j = new JSONObject();
        j.put("clipped_pct", Math.round(clippedPct * 1000) / 1000.0);
        j.put("headroom_stops", Math.round(headroomStops * 10) / 10.0);
        j.put("median_stops_below_clip", Math.round(medianStopsBelowClip * 10) / 10.0);
        return j;
    }
}
