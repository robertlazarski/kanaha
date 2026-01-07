/*
 * Kanaha Camera Control System
 * Network Discovery Service (mDNS/DNS-SD)
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * This service registers the camera as a discoverable network service using
 * Android's NSD (Network Service Discovery) API. Clients can find cameras
 * by hostname (e.g., pixel9pro.local) instead of IP address.
 *
 * Benefits:
 * - Zero-config discovery: no static IP required
 * - Hostname-based certificates: CN=pixel9pro.local works across DHCP changes
 * - Multi-camera friendly: discover all cameras on network automatically
 */

package org.kanaha.camera;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Network Discovery Service for mDNS/DNS-SD registration
 *
 * Registers the Kanaha camera control service so it can be discovered by:
 * - Control stations using avahi-browse or dns-sd
 * - Other Kanaha devices for multi-camera coordination
 * - Any mDNS-compatible client
 *
 * Service is registered as: {deviceName}._https._tcp.local
 * Resolvable hostname: {deviceName}.local (e.g., pixel9pro.local)
 */
public class NetworkDiscoveryService {
    private static final String TAG = "KanahaNetworkDiscovery";

    // DNS-SD service type for HTTPS services
    private static final String SERVICE_TYPE = "_https._tcp.";

    // Default HTTPS port - required in DNS-SD even though it's the HTTPS default
    private static final int DEFAULT_PORT = 443;

    private final Context context;
    private final NsdManager nsdManager;
    private final AtomicBoolean isRegistered = new AtomicBoolean(false);
    private final AtomicReference<String> registeredServiceName = new AtomicReference<>(null);

    private NsdManager.RegistrationListener registrationListener;

    /**
     * Callback interface for registration status updates
     */
    public interface DiscoveryCallback {
        void onServiceRegistered(String serviceName, String hostname, int port);
        void onServiceUnregistered(String serviceName);
        void onRegistrationFailed(String serviceName, int errorCode);
    }

    private DiscoveryCallback callback;

    public NetworkDiscoveryService(Context context) {
        this.context = context.getApplicationContext();
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    }

    /**
     * Set callback for registration status updates
     */
    public void setCallback(DiscoveryCallback callback) {
        this.callback = callback;
    }

    /**
     * Register the camera service for network discovery
     *
     * @param port The port the HTTPS service is running on (typically 443)
     * @return true if registration was initiated successfully
     */
    public boolean registerService(int port) {
        if (nsdManager == null) {
            Log.e(TAG, "NsdManager not available on this device");
            return false;
        }

        if (isRegistered.get()) {
            Log.w(TAG, "Service already registered, unregistering first");
            unregisterService();
        }

        String deviceName = generateServiceName();
        Log.i(TAG, "Registering mDNS service: " + deviceName + " on port " + port);

        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(deviceName);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(port);

        // NOTE: Android's NsdServiceInfo.setHostname() is a hidden platform API (api=blocked).
        // Even on API 34+, regular apps cannot set custom mDNS hostnames.
        // The device will use its system hostname (e.g., Android_O6TZB2ZA.local).
        // Clients should use discovery scripts to extract IP from TXT records.
        // See: MULTI_CAMERA_DEPLOYMENT_SYSTEM.md "Android Hostname Limitation" section.
        if (Build.VERSION.SDK_INT < 34) {
            Log.i(TAG, "API " + Build.VERSION.SDK_INT + ": mDNS hostname will be Android system default, " +
                  "clients should use discovery scripts to get IP from TXT record");
        }

        // Add TXT records with device metadata (Android 21+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Map<String, String> attributes = buildServiceAttributes();
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                serviceInfo.setAttribute(entry.getKey(), entry.getValue());
            }
        }

        registrationListener = createRegistrationListener();

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to register mDNS service", e);
            return false;
        }
    }

    /**
     * Register the camera service on default HTTPS port (443)
     */
    public boolean registerService() {
        return registerService(DEFAULT_PORT);
    }

    /**
     * Unregister the camera service from network discovery
     */
    public void unregisterService() {
        if (!isRegistered.get()) {
            Log.d(TAG, "Service not registered, nothing to unregister");
            return;
        }

        if (nsdManager != null && registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener);
                Log.i(TAG, "mDNS service unregistration initiated");
            } catch (Exception e) {
                Log.e(TAG, "Failed to unregister mDNS service", e);
            }
        }
    }

    /**
     * Check if service is currently registered
     */
    public boolean isRegistered() {
        return isRegistered.get();
    }

    /**
     * Get the registered service name (hostname without .local)
     */
    public String getRegisteredServiceName() {
        return registeredServiceName.get();
    }

    /**
     * Get the full mDNS hostname (e.g., pixel9pro.local)
     */
    public String getHostname() {
        String name = registeredServiceName.get();
        return name != null ? name + ".local" : null;
    }

    /**
     * Generate a unique, human-readable service name for this device.
     * Delegates to shared DeviceIdentifier utility for consistency with certificates.
     */
    private String generateServiceName() {
        return DeviceIdentifier.getIdentifier(context);
    }

    /**
     * Build TXT record attributes for service metadata
     */
    private Map<String, String> buildServiceAttributes() {
        Map<String, String> attributes = new HashMap<>();

        // Protocol version
        attributes.put("txtvers", "1");

        // Device name (unique identifier for this camera)
        attributes.put("name", DeviceIdentifier.getIdentifier(context));

        // Device information
        attributes.put("model", Build.MODEL);
        attributes.put("manufacturer", Build.MANUFACTURER);
        attributes.put("android", String.valueOf(Build.VERSION.SDK_INT));

        // Kanaha-specific metadata
        attributes.put("api", "kanaha-camera-control");
        attributes.put("version", getAppVersion());

        // Current IP address (for clients that don't resolve mDNS)
        String ip = getCurrentIpAddress();
        if (ip != null) {
            attributes.put("ip", ip);
        }

        return attributes;
    }

    /**
     * Get current device IP address
     */
    private String getCurrentIpAddress() {
        try {
            for (NetworkInterface intf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (intf.isLoopback() || !intf.isUp()) continue;

                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (addr.isLoopbackAddress()) continue;

                    String hostAddress = addr.getHostAddress();
                    // Return first non-link-local IPv4 address
                    if (hostAddress != null && hostAddress.indexOf(':') < 0 &&
                        !hostAddress.startsWith("169.254.")) {
                        return hostAddress;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get IP address", e);
        }
        return null;
    }

    /**
     * Get app version for TXT record
     */
    private String getAppVersion() {
        try {
            return context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    /**
     * Create NSD registration listener
     */
    private NsdManager.RegistrationListener createRegistrationListener() {
        return new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                // Android may modify the service name to avoid conflicts
                String actualName = serviceInfo.getServiceName();
                registeredServiceName.set(actualName);
                isRegistered.set(true);

                // NOTE: The mDNS hostname will be Android's system hostname (e.g., Android_O6TZB2ZA.local),
                // not our service name. Clients should use discovery to get the IP from TXT records.
                Log.i(TAG, "mDNS service registered: " + actualName);
                Log.i(TAG, "Service type: " + SERVICE_TYPE + " port: " + serviceInfo.getPort());
                Log.i(TAG, "Note: Actual mDNS hostname is Android system default. Use discovery scripts for IP.");

                if (callback != null) {
                    callback.onServiceRegistered(actualName, actualName + ".local", serviceInfo.getPort());
                }
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                isRegistered.set(false);
                registeredServiceName.set(null);

                String errorMsg = getErrorMessage(errorCode);
                Log.e(TAG, "mDNS registration failed: " + errorMsg + " (code " + errorCode + ")");

                if (callback != null) {
                    callback.onRegistrationFailed(serviceInfo.getServiceName(), errorCode);
                }
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                String name = registeredServiceName.getAndSet(null);
                isRegistered.set(false);

                Log.i(TAG, "mDNS service unregistered: " + name);

                if (callback != null) {
                    callback.onServiceUnregistered(name);
                }
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                String errorMsg = getErrorMessage(errorCode);
                Log.e(TAG, "mDNS unregistration failed: " + errorMsg + " (code " + errorCode + ")");
            }
        };
    }

    /**
     * Convert NSD error code to human-readable message
     */
    private String getErrorMessage(int errorCode) {
        switch (errorCode) {
            case NsdManager.FAILURE_ALREADY_ACTIVE:
                return "Service already active";
            case NsdManager.FAILURE_INTERNAL_ERROR:
                return "Internal error";
            case NsdManager.FAILURE_MAX_LIMIT:
                return "Maximum service limit reached";
            default:
                return "Unknown error";
        }
    }
}
