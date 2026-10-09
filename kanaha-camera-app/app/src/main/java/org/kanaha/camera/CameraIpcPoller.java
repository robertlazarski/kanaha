/*
 * Kanaha Camera Control System
 * Request-file fallback for camera control IPC
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * The native server reaches CameraControlReceiver by running `am broadcast`.
 * Some device builds refuse `am` from apps (a Motorola Android 14 build exits
 * 255 for every activity-manager shell command), so on such a device the native
 * side writes the request to cache/ipc_requests/ instead and leaves the marker
 * cache/ipc_am_refused. This poller sends those requests as in-app broadcasts,
 * the same intent `am` would have sent, so the receiver is unchanged.
 *
 * On a device where `am` works the marker never appears and each tick is one
 * file check. Paths must match IPC_* in camera_control_service.c.
 */

package org.kanaha.camera;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

class CameraIpcPoller {
    private static final String TAG = "KanahaIpcPoller";
    private static final String CAMERA_CONTROL_ACTION = "org.kanaha.CAMERA_CONTROL";
    static final String IPC_REQUEST_DIR = "ipc_requests";
    static final String IPC_AM_REFUSED_FILE = "ipc_am_refused";
    private static final long POLL_MS = 500;
    /* Older than this, a request's caller has long since given up (it waits
     * 5 s for pickup), so one found at startup is dropped, not run. */
    private static final long STALE_MS = 60_000;

    private final Context context;
    private final File requestDir;
    private final File amRefusedMarker;
    private volatile boolean running;
    private Thread thread;

    CameraIpcPoller(Context context) {
        this.context = context.getApplicationContext();
        File cache = context.getCacheDir();
        this.requestDir = new File(cache, IPC_REQUEST_DIR);
        this.amRefusedMarker = new File(cache, IPC_AM_REFUSED_FILE);
    }

    synchronized void start() {
        if (running) return;
        running = true;
        dropStaleRequests();
        thread = new Thread(this::loop, "camera-ipc-poller");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void loop() {
        boolean announced = false;
        while (running) {
            if (amRefusedMarker.exists()) {
                if (!announced) {
                    Log.i(TAG, "This device refuses `am` from apps; serving camera "
                            + "control from request files");
                    announced = true;
                }
                pollOnce();
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void pollOnce() {
        File[] files = requestDir.listFiles((dir, name) ->
                name.startsWith("op_") && name.endsWith(".json"));
        if (files == null) return;
        for (File f : files) {
            String body;
            try {
                body = readFile(f);
            } catch (IOException e) {
                continue;   // withdrawn between listing and reading
            }
            // Claim it. If the delete fails the native side withdrew the
            // request after its pickup timeout, and its caller has failed.
            if (!f.delete()) continue;
            dispatch(f.getName(), body);
        }
    }

    // FileInputStream rather than java.nio.file.Files, which needs API 26;
    // the foss flavor supports API 23.
    private static String readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void dispatch(String name, String body) {
        try {
            JSONObject req = new JSONObject(body);
            Intent intent = new Intent(CAMERA_CONTROL_ACTION);
            intent.setComponent(new ComponentName(context, CameraControlReceiver.class));
            JSONObject strings = req.optJSONObject("strings");
            if (strings != null) {
                for (Iterator<String> it = strings.keys(); it.hasNext(); ) {
                    String key = it.next();
                    intent.putExtra(key, strings.getString(key));
                }
            }
            JSONObject ints = req.optJSONObject("ints");
            if (ints != null) {
                for (Iterator<String> it = ints.keys(); it.hasNext(); ) {
                    String key = it.next();
                    intent.putExtra(key, Integer.parseInt(ints.getString(key)));
                }
            }
            // A real broadcast, not a direct onReceive() call: the receiver
            // uses goAsync(). The app holds the receiver's own permission.
            context.sendBroadcast(intent);
            Log.i(TAG, "Dispatched " + name + ": " + intent.getStringExtra("action"));
        } catch (Exception e) {
            Log.e(TAG, "Dropped malformed request " + name, e);
        }
    }

    private void dropStaleRequests() {
        File[] files = requestDir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - STALE_MS;
        for (File f : files) {
            if (f.lastModified() < cutoff && f.delete()) {
                Log.w(TAG, "Dropped stale request " + f.getName());
            }
        }
    }
}
