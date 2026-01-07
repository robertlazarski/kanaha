/*
 * Kanaha Camera Control System
 * Device Identifier Utility
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * Shared utility for generating consistent device identifiers used by:
 * - CertificateService (certificate CN and SANs)
 * - NetworkDiscoveryService (mDNS hostname)
 * - Apache ssl.conf (ServerName)
 *
 * IMPORTANT: Both certificate SANs and mDNS must use the same hostname,
 * otherwise TLS verification will fail when connecting via mDNS name.
 */

package org.kanaha.camera;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * Generates consistent device identifiers for network discovery and certificates.
 *
 * The identifier is used as:
 * - mDNS service name: {identifier}._https._tcp.local
 * - mDNS hostname: {identifier}.local
 * - Certificate SAN: DNS:{identifier}.local
 *
 * Priority order:
 * 1. User-configured device name (most user-friendly)
 * 2. Device model name, sanitized (e.g., "pixel9pro")
 * 3. Android ID-based fallback (guaranteed unique)
 */
public class DeviceIdentifier {
    private static final String TAG = "KanahaDeviceIdentifier";

    // Maximum length for DNS label (RFC 1035)
    private static final int MAX_DNS_LABEL_LENGTH = 63;

    private final Context context;

    // Cached identifier (generated once per instance)
    private String cachedIdentifier;

    public DeviceIdentifier(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Get the device identifier for use in mDNS and certificates.
     *
     * @return DNS-safe identifier (e.g., "pixel9pro", "galaxys24", "kanaha-a1b2c3d4")
     */
    public String getIdentifier() {
        if (cachedIdentifier == null) {
            cachedIdentifier = generateIdentifier();
        }
        return cachedIdentifier;
    }

    /**
     * Get the full .local hostname.
     *
     * @return Full mDNS hostname (e.g., "pixel9pro.local")
     */
    public String getHostname() {
        return getIdentifier() + ".local";
    }

    /**
     * Generate the device identifier using priority-based fallback.
     */
    private String generateIdentifier() {
        String identifier = null;

        // Priority 1: User-configured device name
        identifier = tryGetUserDeviceName();
        if (isValidIdentifier(identifier)) {
            Log.d(TAG, "Using user-configured device name: " + identifier);
            return sanitizeForDns(identifier);
        }

        // Priority 2: Device model name (most recognizable)
        identifier = tryGetModelName();
        if (isValidIdentifier(identifier)) {
            Log.d(TAG, "Using device model name: " + identifier);
            return sanitizeForDns(identifier);
        }

        // Priority 3: Android ID-based fallback (guaranteed unique)
        identifier = generateFromAndroidId();
        Log.d(TAG, "Using Android ID fallback: " + identifier);
        return identifier;
    }

    /**
     * Try to get user-configured device name (Android 7.1+)
     */
    private String tryGetUserDeviceName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            try {
                String name = Settings.Global.getString(
                    context.getContentResolver(), Settings.Global.DEVICE_NAME);
                if (name != null && !name.trim().isEmpty()) {
                    return name.trim();
                }
            } catch (Exception e) {
                Log.d(TAG, "Could not read device name setting", e);
            }
        }
        return null;
    }

    /**
     * Try to get device model name
     */
    private String tryGetModelName() {
        String model = Build.MODEL;
        if (model != null && !model.trim().isEmpty()) {
            return model.trim();
        }
        return null;
    }

    /**
     * Generate identifier from Android ID (fallback)
     */
    private String generateFromAndroidId() {
        try {
            String androidId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);

            if (androidId != null && androidId.length() >= 8) {
                String shortId = androidId.substring(androidId.length() - 8).toLowerCase();
                return "kanaha-" + shortId;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get Android ID", e);
        }

        // Ultimate fallback
        return "kanaha-camera";
    }

    /**
     * Check if identifier is valid (non-null, non-empty, produces valid DNS name)
     */
    private boolean isValidIdentifier(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return false;
        }
        String sanitized = sanitizeForDns(identifier);
        return sanitized != null && !sanitized.isEmpty();
    }

    /**
     * Sanitize a string for use as a DNS hostname label.
     *
     * DNS label rules (RFC 1035):
     * - Max 63 characters
     * - Alphanumeric and hyphens only
     * - Cannot start or end with hyphen
     * - Case-insensitive (we use lowercase)
     *
     * @param input Raw string to sanitize
     * @return DNS-safe string, or null if input produces empty result
     */
    public static String sanitizeForDns(String input) {
        if (input == null) {
            return null;
        }

        String sanitized = input.toLowerCase()
            .replaceAll("[^a-z0-9-]", "")    // Remove invalid characters
            .replaceAll("-+", "-")            // Collapse multiple hyphens
            .replaceAll("^-+|-+$", "");       // Remove leading/trailing hyphens

        if (sanitized.isEmpty()) {
            return null;
        }

        // Enforce max length
        if (sanitized.length() > MAX_DNS_LABEL_LENGTH) {
            sanitized = sanitized.substring(0, MAX_DNS_LABEL_LENGTH);
            // Remove trailing hyphen if truncation created one
            sanitized = sanitized.replaceAll("-+$", "");
        }

        return sanitized;
    }

    /**
     * Static convenience method for one-off identifier generation.
     */
    public static String getIdentifier(Context context) {
        return new DeviceIdentifier(context).getIdentifier();
    }

    /**
     * Static convenience method for one-off hostname generation.
     */
    public static String getHostname(Context context) {
        return new DeviceIdentifier(context).getHostname();
    }
}
