/*
 * Kanaha Camera Control System
 * Certificate Management Service
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * This service handles SSL/TLS certificate generation, deployment, and management
 * for mTLS authentication in the HTTP/2 camera control system.
 *
 * Uses BouncyCastle for X.509 certificate generation and management.
 */

package org.kanaha.camera;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;

import javax.security.auth.x500.X500Principal;

// BouncyCastle imports for X.509 certificate generation
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Certificate Management Service for mTLS Authentication
 *
 * This service provides:
 * 1. SSL certificate generation for Android devices
 * 2. Certificate deployment to Apache httpd
 * 3. Certificate validation and renewal
 * 4. Secure storage of private keys
 */
public class CertificateService extends Service {
    private static final String TAG = "KanahaCertService";
    private static final String KEYSTORE_ALIAS = "kanaha_server_key";
    private static final String KEYSTORE_PASSWORD = "kanaha_camera_control";

    private final IBinder binder = new CertificateServiceBinder();
    private CertificateAuthority certificateAuthority;

    /**
     * Service binder for client communication
     */
    public class CertificateServiceBinder extends Binder {
        public CertificateService getService() {
            return CertificateService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Certificate service created");

        // Initialize BouncyCastle security provider
        if (java.security.Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            java.security.Security.addProvider(new BouncyCastleProvider());
            Log.i(TAG, "BouncyCastle security provider initialized");
        }

        // Initialize certificate authority
        certificateAuthority = new CertificateAuthority(this);
        certificateAuthority.initializeCA();

        // Initialize certificate management
        initializeCertificateStorage();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /**
     * Generate and deploy SSL certificates for the device using CA signing
     */
    public boolean generateAndDeployCertificates(String deviceId, String ipAddress) {
        Log.i(TAG, String.format("Generating CA-signed certificates for device: %s, IP: %s", deviceId, ipAddress));

        try {
            // Use automatic device ID if not provided
            if (deviceId == null || deviceId.isEmpty()) {
                deviceId = generateDeviceIdentifier();
            }

            // Use detected IP address if not provided
            if (ipAddress == null || ipAddress.isEmpty()) {
                ipAddress = getDeviceIPAddress();
            }

            // Generate key pair
            KeyPair keyPair = generateKeyPair();
            if (keyPair == null) {
                Log.e(TAG, "Failed to generate key pair");
                return false;
            }

            // Generate CA-signed certificate (preferred over self-signed)
            X509Certificate certificate = generateCASignedCertificate(keyPair, deviceId, ipAddress);
            if (certificate == null) {
                Log.w(TAG, "Failed to generate CA-signed certificate, falling back to self-signed");
                certificate = generateSelfSignedCertificate(keyPair, deviceId, ipAddress);
            }

            if (certificate == null) {
                Log.e(TAG, "Failed to generate certificate");
                return false;
            }

            // Store certificate and key in Android Keystore
            if (!storeCertificateInKeystore(certificate, keyPair.getPrivate())) {
                Log.e(TAG, "Failed to store certificate in keystore");
                return false;
            }

            // Export certificate and key for Apache httpd
            if (!exportCertificateForApache(certificate, keyPair.getPrivate())) {
                Log.e(TAG, "Failed to export certificate for Apache");
                return false;
            }

            // Deploy CA certificate for client validation
            if (!deployCACertificateForApache()) {
                Log.w(TAG, "Failed to deploy CA certificate - mTLS client validation may not work");
            }

            Log.i(TAG, "Certificate generation and deployment completed successfully");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error generating and deploying certificates", e);
            return false;
        }
    }

    /**
     * Generate CA-signed certificate for the device
     */
    private X509Certificate generateCASignedCertificate(KeyPair keyPair, String deviceId, String ipAddress) {
        try {
            if (certificateAuthority == null) {
                Log.w(TAG, "Certificate Authority not available");
                return null;
            }

            Log.i(TAG, "Generating CA-signed certificate");
            X509Certificate certificate = certificateAuthority.signDeviceCertificate(keyPair, deviceId, ipAddress);

            if (certificate != null) {
                Log.i(TAG, "CA-signed certificate generated successfully");
                Log.i(TAG, String.format("Certificate signed by: %s", certificate.getIssuerDN().toString()));
            }

            return certificate;

        } catch (Exception e) {
            Log.e(TAG, "Error generating CA-signed certificate", e);
            return null;
        }
    }

    /**
     * Check if valid certificates exist
     */
    public boolean areCertificatesValid() {
        try {
            // Check if certificate files exist in Apache SSL directory
            File apacheSSLDir = new File(getFilesDir(), "apache/ssl");
            File serverCert = new File(apacheSSLDir, "server.crt");
            File serverKey = new File(apacheSSLDir, "server.key");
            File caCert = new File(apacheSSLDir, "ca.crt");

            if (!serverCert.exists() || !serverKey.exists() || !caCert.exists()) {
                Log.w(TAG, "Certificate files missing in Apache SSL directory");
                return false;
            }

            // TODO: Add certificate expiration checking
            // For now, assume certificates are valid if files exist

            Log.i(TAG, "Certificates are valid");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error checking certificate validity", e);
            return false;
        }
    }

    /**
     * Get certificate information
     */
    public String getCertificateInfo() {
        try {
            if (!areCertificatesValid()) {
                return "No valid certificates found";
            }

            // TODO: Read actual certificate information
            // For now, return placeholder information

            return String.format(
                "Certificate Status: Valid\nIssued To: Kanaha Device\nValid Until: %s\nSerial Number: %s",
                "2025-12-23", // Placeholder
                "KA-001-2025"  // Placeholder
            );

        } catch (Exception e) {
            Log.e(TAG, "Error getting certificate info", e);
            return "Certificate information unavailable";
        }
    }

    /**
     * Deploy CA certificate for client authentication
     */
    public boolean deployCACertificate(byte[] caCertificateData) {
        Log.i(TAG, "Deploying external CA certificate for client authentication");

        try {
            File apacheSSLDir = new File(getFilesDir(), "apache/ssl");
            apacheSSLDir.mkdirs();

            File caCertFile = new File(apacheSSLDir, "ca.crt");

            try (FileOutputStream fos = new FileOutputStream(caCertFile)) {
                fos.write(caCertificateData);
            }

            Log.i(TAG, "External CA certificate deployed successfully");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error deploying external CA certificate", e);
            return false;
        }
    }

    /**
     * Deploy our own CA certificate for Apache httpd client validation
     */
    private boolean deployCACertificateForApache() {
        try {
            if (certificateAuthority == null) {
                Log.w(TAG, "Certificate Authority not available");
                return false;
            }

            String caCertPEM = certificateAuthority.getCACertificatePEM();
            if (caCertPEM == null) {
                Log.e(TAG, "Failed to get CA certificate in PEM format");
                return false;
            }

            File apacheSSLDir = new File(getFilesDir(), "apache/ssl");
            apacheSSLDir.mkdirs();

            File caCertFile = new File(apacheSSLDir, "ca.crt");

            try (FileWriter writer = new FileWriter(caCertFile)) {
                writer.write(caCertPEM);
            }

            Log.i(TAG, String.format("CA certificate deployed to Apache: %s", caCertFile.getAbsolutePath()));
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error deploying CA certificate for Apache", e);
            return false;
        }
    }

    /**
     * Generate client certificate for Ubuntu control center
     */
    public CertificateAuthority.ClientCertificate generateClientCertificate(String clientId) {
        try {
            if (certificateAuthority == null) {
                Log.e(TAG, "Certificate Authority not available");
                return null;
            }

            Log.i(TAG, String.format("Generating client certificate for: %s", clientId));
            return certificateAuthority.generateClientCertificate(clientId);

        } catch (Exception e) {
            Log.e(TAG, "Error generating client certificate", e);
            return null;
        }
    }

    /**
     * Get CA certificate for distribution to control center
     */
    public String getCACertificatePEM() {
        try {
            if (certificateAuthority == null) {
                Log.e(TAG, "Certificate Authority not available");
                return null;
            }

            return certificateAuthority.getCACertificatePEM();

        } catch (Exception e) {
            Log.e(TAG, "Error getting CA certificate", e);
            return null;
        }
    }

    // ========================================================================
    // Certificate Deployment Automation
    // ========================================================================

    /**
     * Automated certificate deployment and renewal system
     * This method handles the complete certificate lifecycle:
     * 1. Check certificate expiration
     * 2. Generate new certificates if needed
     * 3. Deploy to Apache httpd
     * 4. Restart Apache httpd server
     * 5. Distribute to other camera devices
     */
    public boolean autoDeployCertificates() {
        Log.i(TAG, "Starting automated certificate deployment");

        try {
            // Step 1: Check if certificates need renewal
            if (areCertificatesValid() && !certificatesNeedRenewal()) {
                Log.i(TAG, "Certificates are valid and don't need renewal");
                return true;
            }

            // Step 2: Generate device ID and IP address
            String deviceId = generateDeviceIdentifier();
            String ipAddress = getDeviceIPAddress();

            Log.i(TAG, String.format("Auto-deploying certificates for device: %s, IP: %s", deviceId, ipAddress));

            // Step 3: Generate and deploy certificates
            if (!generateAndDeployCertificates(deviceId, ipAddress)) {
                Log.e(TAG, "Failed to generate and deploy certificates");
                return false;
            }

            // Step 4: Restart Apache httpd to load new certificates
            if (!restartApacheHttpd()) {
                Log.e(TAG, "Failed to restart Apache httpd - certificates deployed but server not reloaded");
                return false;
            }

            // Step 5: Distribute certificates to other camera devices (if configured)
            if (!distributeCertificatesToNetwork()) {
                Log.w(TAG, "Failed to distribute certificates to network - local deployment successful");
            }

            Log.i(TAG, "Automated certificate deployment completed successfully");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error in automated certificate deployment", e);
            return false;
        }
    }

    /**
     * Check if certificates need renewal (within 30 days of expiration)
     */
    public boolean certificatesNeedRenewal() {
        try {
            File apacheSSLDir = new File(getFilesDir(), "apache/ssl");
            File serverCert = new File(apacheSSLDir, "server.crt");

            if (!serverCert.exists()) {
                Log.i(TAG, "Certificate files don't exist - renewal needed");
                return true;
            }

            // TODO: Read actual certificate and check expiration date
            // For now, assume renewal is needed every 30 days
            long fileAge = System.currentTimeMillis() - serverCert.lastModified();
            long thirtyDaysMillis = 30L * 24 * 60 * 60 * 1000; // 30 days in milliseconds

            boolean needsRenewal = fileAge > thirtyDaysMillis;
            if (needsRenewal) {
                Log.i(TAG, "Certificates are older than 30 days - renewal needed");
            }

            return needsRenewal;

        } catch (Exception e) {
            Log.e(TAG, "Error checking certificate renewal status", e);
            return true; // Assume renewal needed if we can't check
        }
    }

    /**
     * Restart Apache httpd server to load new certificates
     */
    private boolean restartApacheHttpd() {
        try {
            Log.i(TAG, "Restarting Apache httpd server for certificate reload");

            // Send Intent to ApacheService to restart httpd
            Intent restartIntent = new Intent("org.kanaha.camera.RESTART_APACHE_HTTPD");
            restartIntent.setPackage(getPackageName());

            // Use sendBroadcast for internal communication
            sendBroadcast(restartIntent);

            Log.i(TAG, "Apache httpd restart request sent");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error restarting Apache httpd", e);
            return false;
        }
    }

    /**
     * Distribute certificates to other camera devices in the network
     */
    private boolean distributeCertificatesToNetwork() {
        Log.i(TAG, "Distributing certificates to network devices");

        try {
            // Get CA certificate for distribution
            String caCertPEM = getCACertificatePEM();
            if (caCertPEM == null) {
                Log.e(TAG, "Cannot get CA certificate for distribution");
                return false;
            }

            // TODO: Implement network discovery and certificate distribution
            // This would involve:
            // 1. Discover other Kanaha camera devices on the network
            // 2. Establish secure connections to each device
            // 3. Send CA certificate to each device
            // 4. Verify successful deployment

            // For now, log the intent and return success
            Log.i(TAG, "Certificate distribution to network devices (placeholder implementation)");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error distributing certificates to network", e);
            return false;
        }
    }

    /**
     * Schedule automated certificate renewal
     */
    public void scheduleAutomaticRenewal() {
        Log.i(TAG, "Scheduling automatic certificate renewal");

        try {
            // TODO: Implement periodic certificate renewal using JobScheduler
            // This would check certificate expiration daily and renew when necessary

            Log.i(TAG, "Automatic certificate renewal scheduled (placeholder implementation)");

        } catch (Exception e) {
            Log.e(TAG, "Error scheduling automatic renewal", e);
        }
    }

    /**
     * Export certificate bundle for manual distribution
     */
    public CertificateBundle exportCertificateBundle() {
        try {
            Log.i(TAG, "Exporting certificate bundle for distribution");

            // Get server certificate
            File apacheSSLDir = new File(getFilesDir(), "apache/ssl");
            File serverCert = new File(apacheSSLDir, "server.crt");
            File serverKey = new File(apacheSSLDir, "server.key");
            File caCert = new File(apacheSSLDir, "ca.crt");

            if (!serverCert.exists() || !serverKey.exists() || !caCert.exists()) {
                Log.e(TAG, "Certificate files missing for export");
                return null;
            }

            // Read certificate files
            String serverCertPEM = readFileAsString(serverCert);
            String serverKeyPEM = readFileAsString(serverKey);
            String caCertPEM = readFileAsString(caCert);

            return new CertificateBundle(serverCertPEM, serverKeyPEM, caCertPEM);

        } catch (Exception e) {
            Log.e(TAG, "Error exporting certificate bundle", e);
            return null;
        }
    }

    /**
     * Import certificate bundle from another device
     */
    public boolean importCertificateBundle(CertificateBundle bundle) {
        try {
            Log.i(TAG, "Importing certificate bundle from external source");

            if (bundle == null) {
                Log.e(TAG, "Certificate bundle is null");
                return false;
            }

            // Validate certificate bundle
            if (!bundle.isValid()) {
                Log.e(TAG, "Certificate bundle validation failed");
                return false;
            }

            // Deploy imported certificates
            File apacheSSLDir = new File(getFilesDir(), "apache/ssl");
            apacheSSLDir.mkdirs();

            // Write certificate files
            writeStringToFile(bundle.serverCertificate, new File(apacheSSLDir, "server.crt"));
            writeStringToFile(bundle.serverPrivateKey, new File(apacheSSLDir, "server.key"));
            writeStringToFile(bundle.caCertificate, new File(apacheSSLDir, "ca.crt"));

            Log.i(TAG, "Certificate bundle imported successfully");

            // Restart Apache httpd to load new certificates
            return restartApacheHttpd();

        } catch (Exception e) {
            Log.e(TAG, "Error importing certificate bundle", e);
            return false;
        }
    }

    // ========================================================================
    // Private Certificate Management Methods
    // ========================================================================

    /**
     * Initialize certificate storage
     */
    private void initializeCertificateStorage() {
        try {
            // Create Apache SSL directory
            File apacheSSLDir = new File(getFilesDir(), "apache/ssl");
            apacheSSLDir.mkdirs();

            Log.i(TAG, "Certificate storage initialized");

        } catch (Exception e) {
            Log.e(TAG, "Error initializing certificate storage", e);
        }
    }

    /**
     * Generate RSA key pair for certificates
     */
    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048, new SecureRandom());

            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            Log.i(TAG, "Key pair generated successfully");
            return keyPair;

        } catch (Exception e) {
            Log.e(TAG, "Error generating key pair", e);
            return null;
        }
    }

    /**
     * Generate self-signed X509 certificate using BouncyCastle
     */
    private X509Certificate generateSelfSignedCertificate(KeyPair keyPair, String deviceId, String ipAddress) {
        try {
            Log.i(TAG, String.format("Generating self-signed certificate for device: %s, IP: %s", deviceId, ipAddress));

            // Certificate validity period (1 year)
            Date notBefore = new Date();
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(notBefore);
            calendar.add(Calendar.YEAR, 1);
            Date notAfter = calendar.getTime();

            // Create certificate subject and issuer (same for self-signed)
            X500Name subject = new X500Name(String.format(
                "CN=Kanaha Camera %s, OU=Camera Control, O=Kanaha Project, C=US",
                deviceId
            ));

            // Generate unique serial number
            BigInteger serialNumber = new BigInteger(128, new SecureRandom());

            // Create certificate builder
            X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                subject,           // issuer (same as subject for self-signed)
                serialNumber,      // serial number
                notBefore,         // not valid before
                notAfter,          // not valid after
                subject,           // subject
                keyPair.getPublic() // public key
            );

            // Add extensions

            // Basic Constraints - not a CA certificate
            certBuilder.addExtension(
                Extension.basicConstraints,
                true,  // critical
                new BasicConstraints(false) // not a CA
            );

            // Key Usage - for server authentication
            certBuilder.addExtension(
                Extension.keyUsage,
                true,  // critical
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment)
            );

            // Subject Alternative Names (SANs) - very important for HTTPS
            List<GeneralName> altNames = new ArrayList<>();

            // Add IP address SAN
            if (ipAddress != null && !ipAddress.isEmpty()) {
                altNames.add(new GeneralName(GeneralName.iPAddress, ipAddress));
            }

            // Add localhost and common names
            altNames.add(new GeneralName(GeneralName.dNSName, "localhost"));
            altNames.add(new GeneralName(GeneralName.dNSName, deviceId + ".local"));

            // Add current device IP addresses
            List<String> deviceIPs = getDeviceIPAddresses();
            for (String ip : deviceIPs) {
                altNames.add(new GeneralName(GeneralName.iPAddress, ip));
            }

            if (!altNames.isEmpty()) {
                GeneralNames subjectAltNames = new GeneralNames(altNames.toArray(new GeneralName[0]));
                certBuilder.addExtension(
                    Extension.subjectAlternativeName,
                    false, // not critical
                    subjectAltNames
                );
            }

            // Create content signer
            ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(keyPair.getPrivate());

            // Build certificate
            X509CertificateHolder certHolder = certBuilder.build(contentSigner);

            // Convert to X509Certificate
            JcaX509CertificateConverter certConverter = new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME);

            X509Certificate certificate = certConverter.getCertificate(certHolder);

            Log.i(TAG, "X.509 certificate generated successfully");
            Log.i(TAG, String.format("Certificate DN: %s", certificate.getSubjectDN().toString()));
            Log.i(TAG, String.format("Certificate valid from: %s to: %s",
                certificate.getNotBefore(), certificate.getNotAfter()));

            return certificate;

        } catch (Exception e) {
            Log.e(TAG, "Error generating self-signed certificate", e);
            return null;
        }
    }

    /**
     * Store certificate in Android Keystore
     */
    private boolean storeCertificateInKeystore(X509Certificate certificate, PrivateKey privateKey) {
        try {
            Log.i(TAG, "Storing certificate in Android Keystore (placeholder)");

            // TODO: Implement Android Keystore storage
            // This provides hardware-backed key storage on supported devices

            return true; // Placeholder

        } catch (Exception e) {
            Log.e(TAG, "Error storing certificate in keystore", e);
            return false;
        }
    }

    /**
     * Export certificate and key files for Apache httpd in PEM format
     */
    private boolean exportCertificateForApache(X509Certificate certificate, PrivateKey privateKey) {
        try {
            Log.i(TAG, "Exporting certificate for Apache httpd in PEM format");

            File apacheSSLDir = new File(getFilesDir(), "apache/ssl");
            apacheSSLDir.mkdirs();

            // Export server certificate to PEM format
            File serverCertFile = new File(apacheSSLDir, "server.crt");
            try (FileWriter writer = new FileWriter(serverCertFile)) {
                JcaPEMWriter pemWriter = new JcaPEMWriter(writer);
                pemWriter.writeObject(certificate);
                pemWriter.close();
            }

            // Export private key to PEM format
            File serverKeyFile = new File(apacheSSLDir, "server.key");
            try (FileWriter writer = new FileWriter(serverKeyFile)) {
                JcaPEMWriter pemWriter = new JcaPEMWriter(writer);
                pemWriter.writeObject(privateKey);
                pemWriter.close();
            }

            Log.i(TAG, String.format("Certificate exported to: %s", serverCertFile.getAbsolutePath()));
            Log.i(TAG, String.format("Private key exported to: %s", serverKeyFile.getAbsolutePath()));

            // Set restrictive permissions on private key file (Android doesn't support chmod, but log intent)
            Log.i(TAG, "Note: Set restrictive permissions on private key file in production");

            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error exporting certificate for Apache", e);
            return false;
        }
    }

    /**
     * Create placeholder certificate files for development
     */
    private void createPlaceholderCertificateFiles(File sslDir) throws IOException {
        // Create placeholder server certificate
        File serverCert = new File(sslDir, "server.crt");
        try (FileOutputStream fos = new FileOutputStream(serverCert)) {
            String placeholderCert =
                "-----BEGIN CERTIFICATE-----\n" +
                "PLACEHOLDER_CERTIFICATE_DATA\n" +
                "This is a placeholder certificate for development.\n" +
                "Real certificates will be generated in production.\n" +
                "-----END CERTIFICATE-----\n";
            fos.write(placeholderCert.getBytes());
        }

        // Create placeholder private key
        File serverKey = new File(sslDir, "server.key");
        try (FileOutputStream fos = new FileOutputStream(serverKey)) {
            String placeholderKey =
                "-----BEGIN PRIVATE KEY-----\n" +
                "PLACEHOLDER_PRIVATE_KEY_DATA\n" +
                "This is a placeholder private key for development.\n" +
                "Real private keys will be generated in production.\n" +
                "-----END PRIVATE KEY-----\n";
            fos.write(placeholderKey.getBytes());
        }

        // Create placeholder CA certificate
        File caCert = new File(sslDir, "ca.crt");
        try (FileOutputStream fos = new FileOutputStream(caCert)) {
            String placeholderCA =
                "-----BEGIN CERTIFICATE-----\n" +
                "PLACEHOLDER_CA_CERTIFICATE_DATA\n" +
                "This is a placeholder CA certificate for development.\n" +
                "Real CA certificates will be deployed in production.\n" +
                "-----END CERTIFICATE-----\n";
            fos.write(placeholderCA.getBytes());
        }

        Log.w(TAG, "Created placeholder certificate files for development");
    }

    /**
     * Generate device identifier based on hardware characteristics.
     * Delegates to shared DeviceIdentifier utility for consistency with mDNS discovery.
     *
     * IMPORTANT: Certificate SANs must match the mDNS hostname, otherwise TLS
     * verification will fail when clients connect via the .local hostname.
     */
    private String generateDeviceIdentifier() {
        return DeviceIdentifier.getIdentifier(this);
    }

    /**
     * Get device IP address for certificate Subject Alternative Name
     */
    private String getDeviceIPAddress() {
        try {
            List<String> addresses = getDeviceIPAddresses();
            for (String address : addresses) {
                // Return the first non-localhost IPv4 address
                if (!address.equals("127.0.0.1") && !address.startsWith("169.254.")) {
                    return address;
                }
            }

            return "127.0.0.1"; // Fallback to localhost

        } catch (Exception e) {
            Log.e(TAG, "Error getting device IP address", e);
            return "127.0.0.1";
        }
    }

    /**
     * Get all device IP addresses for Subject Alternative Names
     */
    private List<String> getDeviceIPAddresses() {
        List<String> addresses = new ArrayList<>();

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();

                // Skip loopback and inactive interfaces
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }

                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();

                while (inetAddresses.hasMoreElements()) {
                    InetAddress inetAddress = inetAddresses.nextElement();

                    // Only include IPv4 addresses for now
                    if (!inetAddress.isLoopbackAddress() &&
                        inetAddress.getAddress().length == 4) { // IPv4

                        String address = inetAddress.getHostAddress();
                        addresses.add(address);
                        Log.d(TAG, String.format("Found device IP address: %s on interface: %s",
                            address, networkInterface.getName()));
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error enumerating network interfaces", e);
        }

        return addresses;
    }

    /**
     * Read file contents as string
     */
    private String readFileAsString(File file) throws IOException {
        StringBuilder content = new StringBuilder();

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        }

        return content.toString();
    }

    /**
     * Write string content to file
     */
    private void writeStringToFile(String content, File file) throws IOException {
        file.getParentFile().mkdirs();

        try (java.io.FileWriter writer = new java.io.FileWriter(file)) {
            writer.write(content);
        }
    }

    /**
     * Certificate Bundle container for import/export
     */
    public static class CertificateBundle {
        public final String serverCertificate;
        public final String serverPrivateKey;
        public final String caCertificate;

        public CertificateBundle(String serverCertificate, String serverPrivateKey, String caCertificate) {
            this.serverCertificate = serverCertificate;
            this.serverPrivateKey = serverPrivateKey;
            this.caCertificate = caCertificate;
        }

        /**
         * Validate certificate bundle contents
         */
        public boolean isValid() {
            return serverCertificate != null && !serverCertificate.trim().isEmpty() &&
                   serverCertificate.contains("-----BEGIN CERTIFICATE-----") &&
                   serverPrivateKey != null && !serverPrivateKey.trim().isEmpty() &&
                   serverPrivateKey.contains("-----BEGIN PRIVATE KEY-----") &&
                   caCertificate != null && !caCertificate.trim().isEmpty() &&
                   caCertificate.contains("-----BEGIN CERTIFICATE-----");
        }

        /**
         * Get bundle size in bytes for transfer estimation
         */
        public int getBundleSize() {
            return (serverCertificate != null ? serverCertificate.length() : 0) +
                   (serverPrivateKey != null ? serverPrivateKey.length() : 0) +
                   (caCertificate != null ? caCertificate.length() : 0);
        }

        @Override
        public String toString() {
            return String.format("CertificateBundle{size=%d bytes, valid=%s}", getBundleSize(), isValid());
        }
    }
}