/*
 * Kanaha Camera Control System
 * Apache httpd Service
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * This service manages the Apache httpd server with mod_axis2 for HTTP/2+mTLS
 * camera control. It runs as a foreground service to ensure reliable operation.
 *
 * ARCHITECTURE: No JNI Bridge - Apache httpd runs as separate native process
 */

package org.kanaha.camera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.os.PowerManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Apache httpd Service for HTTP/2+mTLS Camera Control
 *
 * This service:
 * 1. Manages Apache httpd server lifecycle via system() calls
 * 2. Configures HTTP/2+mTLS settings
 * 3. Deploys SSL certificates
 * 4. Monitors server status
 *
 * NO JNI is used - Apache httpd is managed as a separate native process
 * that communicates with the Android layer via Internal Intent IPC.
 */
public class ApacheService extends Service {
    private static final String TAG = "KanahaApacheService";
    private static final String CHANNEL_ID = "kanaha_apache_service";
    private static final int NOTIFICATION_ID = 1001;

    private final IBinder binder = new ApacheServiceBinder();
    private PowerManager.WakeLock wakeLock;
    private boolean isServerRunning = false;
    private String configDirectory;
    private String documentRoot;
    private Process apacheProcess;
    private NetworkDiscoveryService networkDiscovery;

    /**
     * Service binder for client communication
     */
    public class ApacheServiceBinder extends Binder {
        public ApacheService getService() {
            return ApacheService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Apache service created");

        createNotificationChannel();
        acquireWakeLock();
        setupDirectories();
        deployConfiguration();
        initializeNetworkDiscovery();
    }

    /**
     * Initialize mDNS network discovery service
     */
    private void initializeNetworkDiscovery() {
        networkDiscovery = new NetworkDiscoveryService(this);
        networkDiscovery.setCallback(new NetworkDiscoveryService.DiscoveryCallback() {
            @Override
            public void onServiceRegistered(String serviceName, String hostname, int port) {
                Log.i(TAG, "Camera discoverable at: https://" + hostname + ":" + port);
                updateNotification("Camera ready: " + hostname);
            }

            @Override
            public void onServiceUnregistered(String serviceName) {
                Log.i(TAG, "Camera no longer discoverable: " + serviceName);
            }

            @Override
            public void onRegistrationFailed(String serviceName, int errorCode) {
                Log.w(TAG, "mDNS registration failed, clients must use IP address");
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Apache service start command received");

        if (intent != null) {
            String action = intent.getAction();
            if ("org.kanaha.camera.START_APACHE_HTTPD".equals(action)) {
                startHttpdServer();
            } else if ("org.kanaha.camera.STOP_APACHE_HTTPD".equals(action)) {
                stopHttpdServer();
            } else if ("org.kanaha.camera.RESTART_APACHE_HTTPD".equals(action)) {
                restartHttpdServer();
            }
        }

        // Start foreground service with notification
        startForeground(NOTIFICATION_ID, createServiceNotification());

        // Return START_STICKY to restart service if killed
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Apache service destroying");

        // Unregister from mDNS discovery
        if (networkDiscovery != null) {
            networkDiscovery.unregisterService();
        }

        stopHttpdServer();
        releaseWakeLock();

        super.onDestroy();
    }

    /**
     * Start Apache httpd server via native process (NO JNI)
     */
    public boolean startHttpdServer() {
        Log.i(TAG, "Starting Apache httpd server");

        if (isServerRunning) {
            // Verify the native process is actually alive before claiming "already running"
            if (isHttpdRunning()) {
                Log.i(TAG, "Apache httpd server is already running");
                return true;
            }
            // Native process died but flag was stale - clean up and restart
            Log.w(TAG, "Server was marked as running but native process is dead, restarting");
            cleanupDeadProcess();
        }

        try {
            // Deploy SSL certificates first (before validation checks them)
            deployCertificates();

            // Validate configuration
            if (!validateConfiguration()) {
                Log.e(TAG, "Configuration validation failed");
                return false;
            }

            // Start Apache httpd as native process
            // This will eventually launch the native Apache httpd compiled for Android
            boolean success = startApacheHttpdProcess();

            if (success) {
                isServerRunning = true;
                Log.i(TAG, "Apache httpd server started successfully");

                // Update notification
                updateNotification("Apache httpd server running on port 8443");

                // Verify server is actually running
                if (!verifyServerStatus()) {
                    Log.w(TAG, "Server start reported success but verification failed");
                }

                // Register for mDNS discovery (allows clients to find us by hostname)
                // Port 8443 - Android apps cannot bind to privileged ports (< 1024) without root
                if (networkDiscovery != null) {
                    networkDiscovery.registerService(8443);
                }

            } else {
                Log.e(TAG, "Failed to start Apache httpd server");
            }

            return success;

        } catch (Exception e) {
            Log.e(TAG, "Exception starting Apache httpd server", e);
            return false;
        }
    }

    /**
     * Stop Apache httpd server
     */
    public boolean stopHttpdServer() {
        Log.i(TAG, "Stopping Apache httpd server");

        if (!isServerRunning) {
            Log.i(TAG, "Apache httpd server is not running");
            return true;
        }

        try {
            boolean success = stopApacheHttpdProcess();

            if (success) {
                isServerRunning = false;
                Log.i(TAG, "Apache httpd server stopped successfully");

                // Update notification
                updateNotification("Apache httpd server stopped");

            } else {
                Log.e(TAG, "Failed to stop Apache httpd server");
            }

            return success;

        } catch (Exception e) {
            Log.e(TAG, "Exception stopping Apache httpd server", e);
            return false;
        }
    }

    /**
     * Restart Apache httpd server (for certificate reloading)
     */
    public boolean restartHttpdServer() {
        Log.i(TAG, "Restarting Apache httpd server for certificate reload");

        try {
            // Stop server if running
            if (isHttpdRunning()) {
                if (!stopHttpdServer()) {
                    Log.e(TAG, "Failed to stop Apache httpd before restart");
                    return false;
                }

                // Give server time to fully shutdown
                Thread.sleep(2000);
            }

            // Start server with new configuration/certificates
            boolean success = startHttpdServer();

            if (success) {
                Log.i(TAG, "Apache httpd server restarted successfully");
                // Update notification
                updateNotification("Apache httpd server restarted with new certificates");
            } else {
                Log.e(TAG, "Failed to restart Apache httpd server");
            }

            return success;

        } catch (Exception e) {
            Log.e(TAG, "Exception restarting Apache httpd server", e);
            return false;
        }
    }

    /**
     * Check if Apache httpd server is running.
     * Verifies the actual native process state, not just the boolean flag.
     */
    public boolean isHttpdRunning() {
        try {
            // Check if Apache process is alive
            if (apacheProcess != null) {
                try {
                    int exitCode = apacheProcess.exitValue();
                    // Process has terminated
                    Log.i(TAG, "Apache process terminated with exit code: " + exitCode);
                    isServerRunning = false;
                    apacheProcess = null;
                    return false;
                } catch (IllegalThreadStateException e) {
                    // Process is still running
                    return true;
                }
            }

            // No process reference - server cannot be running regardless of flag state
            if (isServerRunning) {
                Log.w(TAG, "Stale isServerRunning flag detected (no process reference), resetting");
                isServerRunning = false;
            }
            return false;

        } catch (Exception e) {
            Log.e(TAG, "Exception checking server status", e);
            return false;
        }
    }

    /**
     * Clean up state from a dead native httpd process.
     * Called when isServerRunning is true but the native process is no longer alive.
     */
    private void cleanupDeadProcess() {
        if (apacheProcess != null) {
            try {
                apacheProcess.destroyForcibly();
            } catch (Exception e) {
                Log.d(TAG, "Error destroying stale process reference", e);
            }
            apacheProcess = null;
        }
        isServerRunning = false;
    }

    /**
     * Get server status information
     */
    public String getServerStatus() {
        try {
            if (isHttpdRunning()) {
                return String.format(
                    "Apache httpd server running\nConfig: %s\nDocument Root: %s\nPorts: 8443 (HTTPS/HTTP2)",
                    configDirectory, documentRoot);
            } else {
                return "Apache httpd server stopped";
            }

        } catch (Exception e) {
            Log.e(TAG, "Error getting server status", e);
            return "Server status unavailable";
        }
    }

    // ========================================================================
    // Private Helper Methods (NO JNI - Pure Android/Java)
    // ========================================================================

    /**
     * Start Apache httpd process via ProcessBuilder
     *
     * Launches the real Apache httpd binary (libhttpd.so) that was cross-compiled
     * for Android. Uses mod_ssl for mTLS and mod_http2 for HTTP/2 support.
     */
    private boolean startApacheHttpdProcess() {
        try {
            Log.i(TAG, "Starting Apache httpd process");

            // Kill any orphaned httpd processes from a previous service instance.
            // When Android force-stops the app, the native child process launched via
            // ProcessBuilder may survive as an orphan (reparented to init), still
            // holding port 8443 and blocking a fresh start.
            killOrphanedHttpdProcesses();

            // Note: SSL certificates already deployed in startHttpdServer()

            // Get the native library directory where httpd is installed
            String nativeLibDir = getApplicationInfo().nativeLibraryDir;
            String executablePath = nativeLibDir + "/libhttpd.so";

            // Check if executable exists
            java.io.File executable = new java.io.File(executablePath);
            if (!executable.exists()) {
                Log.e(TAG, "Apache httpd not found: " + executablePath);

                // List available files in native lib directory for debugging
                java.io.File nativeDir = new java.io.File(nativeLibDir);
                String[] files = nativeDir.list();
                if (files != null) {
                    Log.i(TAG, "Files in " + nativeLibDir + ":");
                    for (String file : files) {
                        Log.i(TAG, "  - " + file);
                    }
                }

                return false;
            }

            // Ensure executable has execute permission
            if (!executable.canExecute()) {
                Log.w(TAG, "Setting execute permission on: " + executablePath);
                executable.setExecutable(true);
            }

            // Apache httpd configuration file path
            String apacheDir = getFilesDir().getAbsolutePath() + "/apache";
            String configPath = apacheDir + "/conf/httpd.conf";

            // Build the command for Apache httpd
            // -f: config file path
            // -d: ServerRoot directory
            // -X: debug mode (single process, no fork)
            String[] command = {
                executablePath,
                "-f", configPath,
                "-d", apacheDir,
                "-X"  // Single-process mode for Android
            };

            Log.i(TAG, "Launching: " + String.join(" ", command));

            // Set up ProcessBuilder
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(new java.io.File(apacheDir));
            processBuilder.redirectErrorStream(true);

            // Set environment variables
            java.util.Map<String, String> env = processBuilder.environment();
            env.put("LD_LIBRARY_PATH", nativeLibDir);
            env.put("HOME", getFilesDir().getAbsolutePath());

            // Start the process
            apacheProcess = processBuilder.start();
            Log.i(TAG, "Process started with PID: " + getProcessId(apacheProcess));

            // Start a thread to read process output (for logging)
            startOutputReader(apacheProcess);

            // Wait briefly and check if process is still running
            Thread.sleep(1000);

            if (apacheProcess.isAlive()) {
                Log.i(TAG, "Apache httpd process started successfully on port 8443");
                return true;
            } else {
                int exitCode = apacheProcess.exitValue();
                Log.e(TAG, "Apache httpd process exited immediately with code: " + exitCode);
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error starting Apache httpd process", e);
            return false;
        }
    }

    /**
     * Deploy SSL certificates from assets to filesystem
     */
    private void deployCertificates() {
        try {
            File sslDir = new File(getFilesDir(), "apache/ssl");
            sslDir.mkdirs();

            // Deploy certificates from assets/ssl/
            deployAssetToFile("ssl/ca.crt", new File(sslDir, "ca.crt"));
            deployAssetToFile("ssl/server.crt", new File(sslDir, "server.crt"));
            deployAssetToFile("ssl/server.key", new File(sslDir, "server.key"));
            deployAssetToFile("ssl/ca.crl", new File(sslDir, "ca.crl"));

            Log.i(TAG, "SSL certificates deployed to: " + sslDir.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Error deploying certificates", e);
        }
    }

    /**
     * Get process ID (using reflection for compatibility)
     */
    private long getProcessId(Process process) {
        try {
            // Try Java 9+ method first
            java.lang.reflect.Method pidMethod = process.getClass().getMethod("pid");
            return (Long) pidMethod.invoke(process);
        } catch (Exception e) {
            // Fall back to reflection for older Android versions
            try {
                java.lang.reflect.Field field = process.getClass().getDeclaredField("pid");
                field.setAccessible(true);
                return field.getInt(process);
            } catch (Exception e2) {
                return -1;
            }
        }
    }

    /**
     * Start a thread to read and log process output
     */
    private void startOutputReader(final Process process) {
        new Thread(() -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.i(TAG + "-native", line);
                }
            } catch (java.io.IOException e) {
                Log.d(TAG, "Output reader finished");
            }
        }, "ApacheOutputReader").start();
    }

    /**
     * Kill any orphaned Apache httpd processes from a previous service instance.
     * This can happen when the Android process is force-stopped but the native
     * child process survives as an orphan reparented to init.
     */
    private void killOrphanedHttpdProcesses() {
        try {
            String nativeLibDir = getApplicationInfo().nativeLibraryDir;
            String pattern = nativeLibDir + "/libhttpd.so";

            // Use pkill directly via ProcessBuilder args to avoid sh -c shell injection
            ProcessBuilder pb = new ProcessBuilder("pkill", "-9", "-f", pattern);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
            }
            // pkill exits 0 if processes killed, 1 if none found — both are fine
            Log.d(TAG, "Orphaned httpd process cleanup completed");
        } catch (Exception e) {
            Log.d(TAG, "Orphaned httpd cleanup: " + e.getMessage());
        }
    }

    /**
     * Stop Apache httpd process
     */
    private boolean stopApacheHttpdProcess() {
        try {
            if (apacheProcess != null) {
                Log.i(TAG, "Terminating Apache httpd process");

                // Graceful shutdown
                apacheProcess.destroy();

                // Wait for process to terminate
                boolean terminated = apacheProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);

                if (!terminated) {
                    Log.w(TAG, "Apache process did not terminate gracefully, force killing");
                    apacheProcess.destroyForcibly();
                }

                apacheProcess = null;
            }

            Log.i(TAG, "Apache httpd process stopped");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error stopping Apache httpd process", e);
            return false;
        }
    }

    /**
     * Setup directory structure for Apache httpd
     */
    private void setupDirectories() {
        try {
            File dataDir = getFilesDir();
            configDirectory = new File(dataDir, "apache/conf").getAbsolutePath();
            documentRoot = new File(dataDir, "apache/htdocs").getAbsolutePath();

            // Create directories
            new File(configDirectory).mkdirs();
            new File(documentRoot).mkdirs();
            new File(dataDir, "apache/logs").mkdirs();
            new File(dataDir, "apache/ssl").mkdirs();

            // Create Axis2/C repository directories
            new File(dataDir, "apache/axis2c").mkdirs();
            new File(dataDir, "apache/axis2c/services").mkdirs();
            new File(dataDir, "apache/axis2c/services/CameraControlService").mkdirs();
            new File(dataDir, "apache/axis2c/modules").mkdirs();

            // Deploy htdocs content
            deployAssetToFile("apache/htdocs/index.html", new File(documentRoot, "index.html"));

            Log.i(TAG, "Apache directories created: config=" + configDirectory + ", root=" + documentRoot);

        } catch (Exception e) {
            Log.e(TAG, "Error setting up directories", e);
        }
    }

    /**
     * Deploy Apache httpd configuration files
     */
    private void deployConfiguration() {
        Log.i(TAG, "Deploying Apache httpd configuration");

        try {
            // Deploy httpd.conf with hostname substitution
            deployConfigWithHostname("apache/httpd.conf", new File(configDirectory, "httpd.conf"));

            // Deploy SSL configuration with dynamic hostname substitution
            deploySSLConfiguration();

            // Deploy HTTP/2 performance configuration
            deployAssetToFile("apache/http2-performance.conf", new File(configDirectory, "http2-performance.conf"));

            // Deploy MIME types
            deployAssetToFile("apache/mime.types", new File(configDirectory, "mime.types"));

            // Deploy Axis2/C configuration
            deployAssetToFile("apache/axis2.conf", new File(configDirectory, "axis2.conf"));

            // Deploy Axis2/C repository files
            File axis2cDir = new File(getFilesDir(), "apache/axis2c");
            deployAssetToFile("axis2c/axis2.xml", new File(axis2cDir, "axis2.xml"));
            deployAssetToFile("axis2c/services/CameraControlService/services.xml",
                new File(axis2cDir, "services/CameraControlService/services.xml"));

            Log.i(TAG, "Configuration files deployed successfully");

        } catch (Exception e) {
            Log.e(TAG, "Error deploying configuration", e);
        }
    }

    /**
     * Deploy configuration file with hostname substitution.
     * Replaces {{KANAHA_HOSTNAME}} placeholder with actual device hostname.
     */
    private void deployConfigWithHostname(String assetPath, File targetFile) throws IOException {
        String hostname = DeviceIdentifier.getHostname(this);
        Log.i(TAG, "Deploying " + assetPath + " with hostname: " + hostname);

        try (InputStream inputStream = getAssets().open(assetPath)) {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder template = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                template.append(line).append("\n");
            }

            String config = template.toString().replace("{{KANAHA_HOSTNAME}}", hostname);

            targetFile.getParentFile().mkdirs();
            try (java.io.FileWriter writer = new java.io.FileWriter(targetFile)) {
                writer.write(config);
            }

            Log.d(TAG, "Configuration deployed: " + targetFile.getAbsolutePath());
        }
    }

    /**
     * Deploy SSL configuration with dynamic hostname substitution.
     * Replaces {{KANAHA_HOSTNAME}} placeholder with actual device hostname.
     */
    private void deploySSLConfiguration() throws IOException {
        String hostname = DeviceIdentifier.getHostname(this);
        Log.i(TAG, "Deploying SSL configuration with hostname: " + hostname);

        try (InputStream inputStream = getAssets().open("apache/ssl.conf")) {
            // Read template as string
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder template = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                template.append(line).append("\n");
            }

            // Substitute placeholder
            String config = template.toString().replace("{{KANAHA_HOSTNAME}}", hostname);

            // Write to target file
            File targetFile = new File(configDirectory, "ssl.conf");
            try (java.io.FileWriter writer = new java.io.FileWriter(targetFile)) {
                writer.write(config);
            }

            Log.d(TAG, "SSL configuration deployed with ServerName: " + hostname + ":8443");

        } catch (IOException e) {
            Log.e(TAG, "Failed to deploy SSL configuration", e);
            throw e;
        }
    }

    /**
     * Deploy asset file to filesystem
     */
    private void deployAssetToFile(String assetPath, File targetFile) throws IOException {
        try (InputStream inputStream = getAssets().open(assetPath);
             FileOutputStream outputStream = new FileOutputStream(targetFile)) {

            byte[] buffer = new byte[8192];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, length);
            }

            Log.d(TAG, "Deployed asset: " + assetPath + " -> " + targetFile.getAbsolutePath());

        } catch (IOException e) {
            Log.e(TAG, "Failed to deploy asset: " + assetPath, e);

            // Create placeholder file if asset doesn't exist
            try {
                targetFile.getParentFile().mkdirs();
                targetFile.createNewFile();
                Log.w(TAG, "Created placeholder file: " + targetFile.getAbsolutePath());
            } catch (IOException e2) {
                throw e; // Re-throw original exception
            }
        }
    }

    /**
     * Validate Apache httpd configuration
     */
    private boolean validateConfiguration() {
        try {
            // Check that configuration files exist
            File httpdConf = new File(configDirectory, "httpd.conf");
            File sslConf = new File(configDirectory, "ssl.conf");

            if (!httpdConf.exists() || !sslConf.exists()) {
                Log.e(TAG, "Required configuration files missing");
                return false;
            }

            // Check that certificate files exist and are valid
            if (!validateCertificates()) {
                Log.w(TAG, "SSL certificate validation failed - attempting auto-deployment");

                // Try to auto-deploy certificates using CertificateService
                if (!triggerCertificateDeployment()) {
                    // For initial testing, allow server to start without mTLS
                    // HTTP server will run on port 8443 without TLS
                    Log.w(TAG, "Failed to auto-deploy certificates - running WITHOUT mTLS for testing");
                    Log.w(TAG, "SECURITY WARNING: mTLS is disabled - not suitable for production");
                }
            }

            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error validating configuration", e);
            return false;
        }
    }

    /**
     * Verify server status after startup
     */
    private boolean verifyServerStatus() {
        try {
            // Give server time to start
            Thread.sleep(1000);

            // Check if process is running
            return isHttpdRunning();

        } catch (Exception e) {
            Log.e(TAG, "Error verifying server status", e);
            return false;
        }
    }

    /**
     * Acquire wake lock to keep service running
     */
    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Kanaha::ApacheHttpdService"
            );
            wakeLock.acquire();
            Log.d(TAG, "Wake lock acquired");

        } catch (Exception e) {
            Log.e(TAG, "Error acquiring wake lock", e);
        }
    }

    /**
     * Release wake lock
     */
    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "Wake lock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing wake lock", e);
        }
    }

    /**
     * Create notification channel for foreground service
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Kanaha Apache Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Manages Apache httpd server for camera control");
            channel.setSound(null, null);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * Create service notification
     */
    private Notification createServiceNotification() {
        return createServiceNotification("Apache httpd service starting...");
    }

    /**
     * Create service notification with custom message
     */
    private Notification createServiceNotification(String message) {
        Intent notificationIntent = new Intent(this, ApacheService.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Kanaha Camera Control")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_media_play) // TODO: Use custom icon
            .setContentIntent(pendingIntent)
            .build();
    }

    /**
     * Update notification with new message
     */
    private void updateNotification(String message) {
        try {
            NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            Notification notification = createServiceNotification(message);
            notificationManager.notify(NOTIFICATION_ID, notification);

        } catch (Exception e) {
            Log.e(TAG, "Error updating notification", e);
        }
    }

    /**
     * Validate SSL certificates for mTLS authentication
     */
    private boolean validateCertificates() {
        try {
            File serverCert = new File(getFilesDir(), "apache/ssl/server.crt");
            File serverKey = new File(getFilesDir(), "apache/ssl/server.key");
            File caCert = new File(getFilesDir(), "apache/ssl/ca.crt");

            // Check if certificate files exist
            if (!serverCert.exists() || !serverKey.exists() || !caCert.exists()) {
                Log.w(TAG, "Certificate files missing - server.crt: " + serverCert.exists() +
                    ", server.key: " + serverKey.exists() + ", ca.crt: " + caCert.exists());
                return false;
            }

            // Check certificate file sizes (basic validation)
            if (serverCert.length() < 100 || serverKey.length() < 100 || caCert.length() < 100) {
                Log.w(TAG, "Certificate files appear to be too small or empty");
                return false;
            }

            // TODO: Add proper X.509 certificate parsing and validation
            // For now, basic file existence and size checks are sufficient

            Log.i(TAG, "SSL certificate validation passed");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error validating certificates", e);
            return false;
        }
    }

    /**
     * Trigger certificate deployment via CertificateService
     */
    private boolean triggerCertificateDeployment() {
        try {
            Log.i(TAG, "Triggering automatic certificate deployment");

            // Send Intent to CertificateService to deploy certificates
            Intent deployIntent = new Intent("org.kanaha.camera.DEPLOY_CERTIFICATES");
            deployIntent.setPackage(getPackageName());

            // Use sendBroadcast for internal communication
            sendBroadcast(deployIntent);

            // Give some time for certificate deployment
            Thread.sleep(5000);

            // Re-validate certificates after deployment
            return validateCertificates();

        } catch (Exception e) {
            Log.e(TAG, "Error triggering certificate deployment", e);
            return false;
        }
    }
}