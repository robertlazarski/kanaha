/*
 * Kanaha Camera Control System
 * Certificate Authority Management
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * This class handles CA certificate generation, device certificate signing,
 * and PKI infrastructure for multi-camera mTLS authentication.
 */

package org.kanaha.camera;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

// BouncyCastle imports
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
 * Certificate Authority for Kanaha Camera Control System
 *
 * Manages PKI infrastructure for multi-camera mTLS authentication:
 * 1. CA certificate generation and management
 * 2. Device certificate signing
 * 3. Client certificate generation for Ubuntu control center
 * 4. Certificate revocation and renewal
 */
public class CertificateAuthority {
    private static final String TAG = "KanahaCertAuth";

    // CA certificate configuration
    private static final String CA_SUBJECT = "CN=Kanaha Camera Control CA, OU=Certificate Authority, O=Kanaha Project, C=US";
    private static final int CA_VALIDITY_YEARS = 5;
    private static final int DEVICE_VALIDITY_YEARS = 1;

    private final Context context;
    private X509Certificate caCertificate;
    private PrivateKey caPrivateKey;

    public CertificateAuthority(Context context) {
        this.context = context;
    }

    /**
     * Initialize Certificate Authority
     * Creates CA certificate and private key if they don't exist
     */
    public boolean initializeCA() {
        Log.i(TAG, "Initializing Certificate Authority");

        try {
            File caDir = getCACertificateDirectory();
            caDir.mkdirs();

            File caCertFile = new File(caDir, "ca.crt");
            File caKeyFile = new File(caDir, "ca.key");

            if (caCertFile.exists() && caKeyFile.exists()) {
                Log.i(TAG, "CA certificate and key already exist, loading...");
                // TODO: Load existing CA certificate and key
                // For now, regenerate
            }

            // Generate CA certificate and key
            if (!generateCACertificate()) {
                Log.e(TAG, "Failed to generate CA certificate");
                return false;
            }

            // Export CA certificate and key
            if (!exportCACertificate()) {
                Log.e(TAG, "Failed to export CA certificate");
                return false;
            }

            Log.i(TAG, "Certificate Authority initialized successfully");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error initializing Certificate Authority", e);
            return false;
        }
    }

    /**
     * Generate CA certificate and private key
     */
    private boolean generateCACertificate() {
        try {
            Log.i(TAG, "Generating CA certificate and private key");

            // Generate CA key pair
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(4096, new SecureRandom()); // 4096-bit for CA
            KeyPair caKeyPair = keyPairGenerator.generateKeyPair();

            // Certificate validity period (5 years for CA)
            Date notBefore = new Date();
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(notBefore);
            calendar.add(Calendar.YEAR, CA_VALIDITY_YEARS);
            Date notAfter = calendar.getTime();

            // Create CA subject
            X500Name caSubject = new X500Name(CA_SUBJECT);

            // Generate unique serial number
            BigInteger serialNumber = new BigInteger(128, new SecureRandom());

            // Create CA certificate builder
            X509v3CertificateBuilder caCertBuilder = new JcaX509v3CertificateBuilder(
                caSubject,                    // issuer (self-signed)
                serialNumber,                 // serial number
                notBefore,                    // not valid before
                notAfter,                     // not valid after
                caSubject,                    // subject (same as issuer)
                caKeyPair.getPublic()        // public key
            );

            // Add CA extensions

            // Basic Constraints - this IS a CA certificate
            caCertBuilder.addExtension(
                Extension.basicConstraints,
                true,  // critical
                new BasicConstraints(true) // is CA, no path length limit
            );

            // Key Usage - for certificate signing
            caCertBuilder.addExtension(
                Extension.keyUsage,
                true,  // critical
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature)
            );

            // Create content signer
            ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(caKeyPair.getPrivate());

            // Build CA certificate
            X509CertificateHolder caCertHolder = caCertBuilder.build(contentSigner);

            // Convert to X509Certificate
            JcaX509CertificateConverter certConverter = new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME);

            caCertificate = certConverter.getCertificate(caCertHolder);
            caPrivateKey = caKeyPair.getPrivate();

            Log.i(TAG, "CA certificate generated successfully");
            Log.i(TAG, String.format("CA DN: %s", caCertificate.getSubjectDN().toString()));
            Log.i(TAG, String.format("CA valid from: %s to: %s",
                caCertificate.getNotBefore(), caCertificate.getNotAfter()));

            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error generating CA certificate", e);
            return false;
        }
    }

    /**
     * Export CA certificate and private key to PEM files
     */
    private boolean exportCACertificate() {
        try {
            File caDir = getCACertificateDirectory();

            // Export CA certificate
            File caCertFile = new File(caDir, "ca.crt");
            try (FileWriter writer = new FileWriter(caCertFile)) {
                JcaPEMWriter pemWriter = new JcaPEMWriter(writer);
                pemWriter.writeObject(caCertificate);
                pemWriter.close();
            }

            // Export CA private key
            File caKeyFile = new File(caDir, "ca.key");
            try (FileWriter writer = new FileWriter(caKeyFile)) {
                JcaPEMWriter pemWriter = new JcaPEMWriter(writer);
                pemWriter.writeObject(caPrivateKey);
                pemWriter.close();
            }

            Log.i(TAG, String.format("CA certificate exported to: %s", caCertFile.getAbsolutePath()));
            Log.i(TAG, String.format("CA private key exported to: %s", caKeyFile.getAbsolutePath()));

            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error exporting CA certificate", e);
            return false;
        }
    }

    /**
     * Sign device certificate with CA
     */
    public X509Certificate signDeviceCertificate(KeyPair deviceKeyPair, String deviceId, String ipAddress) {
        try {
            Log.i(TAG, String.format("Signing device certificate for: %s, IP: %s", deviceId, ipAddress));

            if (caCertificate == null || caPrivateKey == null) {
                Log.e(TAG, "CA not initialized");
                return null;
            }

            // Certificate validity period (1 year for devices)
            Date notBefore = new Date();
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(notBefore);
            calendar.add(Calendar.YEAR, DEVICE_VALIDITY_YEARS);
            Date notAfter = calendar.getTime();

            // Create device subject
            X500Name deviceSubject = new X500Name(String.format(
                "CN=Kanaha Camera %s, OU=Camera Control, O=Kanaha Project, C=US",
                deviceId
            ));

            // Get CA issuer name
            X500Name caIssuer = new X500Name(caCertificate.getSubjectX500Principal().getName());

            // Generate unique serial number
            BigInteger serialNumber = new BigInteger(128, new SecureRandom());

            // Create device certificate builder
            X509v3CertificateBuilder deviceCertBuilder = new JcaX509v3CertificateBuilder(
                caIssuer,                     // issuer (CA)
                serialNumber,                 // serial number
                notBefore,                    // not valid before
                notAfter,                     // not valid after
                deviceSubject,                // subject
                deviceKeyPair.getPublic()     // public key
            );

            // Add device certificate extensions

            // Basic Constraints - not a CA certificate
            deviceCertBuilder.addExtension(
                Extension.basicConstraints,
                true,  // critical
                new BasicConstraints(false) // not a CA
            );

            // Key Usage - for server authentication
            deviceCertBuilder.addExtension(
                Extension.keyUsage,
                true,  // critical
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment)
            );

            // Subject Alternative Names (SANs)
            List<GeneralName> altNames = new ArrayList<>();

            // Add IP address SAN
            if (ipAddress != null && !ipAddress.isEmpty()) {
                altNames.add(new GeneralName(GeneralName.iPAddress, ipAddress));
            }

            // Add common names
            altNames.add(new GeneralName(GeneralName.dNSName, "localhost"));
            altNames.add(new GeneralName(GeneralName.dNSName, deviceId + ".local"));
            altNames.add(new GeneralName(GeneralName.dNSName, deviceId + ".kanaha.local"));

            if (!altNames.isEmpty()) {
                GeneralNames subjectAltNames = new GeneralNames(altNames.toArray(new GeneralName[0]));
                deviceCertBuilder.addExtension(
                    Extension.subjectAlternativeName,
                    false, // not critical
                    subjectAltNames
                );
            }

            // Create content signer using CA private key
            ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(caPrivateKey);

            // Build device certificate
            X509CertificateHolder deviceCertHolder = deviceCertBuilder.build(contentSigner);

            // Convert to X509Certificate
            JcaX509CertificateConverter certConverter = new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME);

            X509Certificate deviceCertificate = certConverter.getCertificate(deviceCertHolder);

            Log.i(TAG, "Device certificate signed successfully");
            Log.i(TAG, String.format("Device certificate DN: %s", deviceCertificate.getSubjectDN().toString()));
            Log.i(TAG, String.format("Device certificate issuer: %s", deviceCertificate.getIssuerDN().toString()));

            return deviceCertificate;

        } catch (Exception e) {
            Log.e(TAG, "Error signing device certificate", e);
            return null;
        }
    }

    /**
     * Generate client certificate for Ubuntu control center
     */
    public ClientCertificate generateClientCertificate(String clientId) {
        try {
            Log.i(TAG, String.format("Generating client certificate for: %s", clientId));

            if (caCertificate == null || caPrivateKey == null) {
                Log.e(TAG, "CA not initialized");
                return null;
            }

            // Generate client key pair
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048, new SecureRandom());
            KeyPair clientKeyPair = keyPairGenerator.generateKeyPair();

            // Certificate validity period (1 year)
            Date notBefore = new Date();
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(notBefore);
            calendar.add(Calendar.YEAR, 1);
            Date notAfter = calendar.getTime();

            // Create client subject
            X500Name clientSubject = new X500Name(String.format(
                "CN=Kanaha Control Center %s, OU=Camera Control, O=Kanaha Project, C=US",
                clientId
            ));

            // Get CA issuer name
            X500Name caIssuer = new X500Name(caCertificate.getSubjectX500Principal().getName());

            // Generate unique serial number
            BigInteger serialNumber = new BigInteger(128, new SecureRandom());

            // Create client certificate builder
            X509v3CertificateBuilder clientCertBuilder = new JcaX509v3CertificateBuilder(
                caIssuer,                     // issuer (CA)
                serialNumber,                 // serial number
                notBefore,                    // not valid before
                notAfter,                     // not valid after
                clientSubject,                // subject
                clientKeyPair.getPublic()     // public key
            );

            // Add client certificate extensions

            // Basic Constraints - not a CA certificate
            clientCertBuilder.addExtension(
                Extension.basicConstraints,
                true,  // critical
                new BasicConstraints(false) // not a CA
            );

            // Key Usage - for client authentication
            clientCertBuilder.addExtension(
                Extension.keyUsage,
                true,  // critical
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment)
            );

            // Create content signer using CA private key
            ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(caPrivateKey);

            // Build client certificate
            X509CertificateHolder clientCertHolder = clientCertBuilder.build(contentSigner);

            // Convert to X509Certificate
            JcaX509CertificateConverter certConverter = new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME);

            X509Certificate clientCertificate = certConverter.getCertificate(clientCertHolder);

            Log.i(TAG, "Client certificate generated successfully");
            Log.i(TAG, String.format("Client certificate DN: %s", clientCertificate.getSubjectDN().toString()));

            return new ClientCertificate(clientCertificate, clientKeyPair.getPrivate());

        } catch (Exception e) {
            Log.e(TAG, "Error generating client certificate", e);
            return null;
        }
    }

    /**
     * Get CA certificate for distribution
     */
    public X509Certificate getCACertificate() {
        return caCertificate;
    }

    /**
     * Get CA certificate in PEM format for distribution
     */
    public String getCACertificatePEM() {
        try {
            if (caCertificate == null) {
                return null;
            }

            StringWriter stringWriter = new StringWriter();
            JcaPEMWriter pemWriter = new JcaPEMWriter(stringWriter);
            pemWriter.writeObject(caCertificate);
            pemWriter.close();

            return stringWriter.toString();

        } catch (Exception e) {
            Log.e(TAG, "Error converting CA certificate to PEM", e);
            return null;
        }
    }

    /**
     * Get CA certificate directory
     */
    private File getCACertificateDirectory() {
        return new File(context.getFilesDir(), "ca");
    }

    /**
     * Client certificate container
     */
    public static class ClientCertificate {
        public final X509Certificate certificate;
        public final PrivateKey privateKey;

        public ClientCertificate(X509Certificate certificate, PrivateKey privateKey) {
            this.certificate = certificate;
            this.privateKey = privateKey;
        }

        public String getCertificatePEM() {
            try {
                StringWriter stringWriter = new StringWriter();
                JcaPEMWriter pemWriter = new JcaPEMWriter(stringWriter);
                pemWriter.writeObject(certificate);
                pemWriter.close();
                return stringWriter.toString();
            } catch (Exception e) {
                return null;
            }
        }

        public String getPrivateKeyPEM() {
            try {
                StringWriter stringWriter = new StringWriter();
                JcaPEMWriter pemWriter = new JcaPEMWriter(stringWriter);
                pemWriter.writeObject(privateKey);
                pemWriter.close();
                return stringWriter.toString();
            } catch (Exception e) {
                return null;
            }
        }
    }
}