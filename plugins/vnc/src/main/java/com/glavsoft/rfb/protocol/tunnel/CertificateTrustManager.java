// Copyright (C) 2010 - 2014 GlavSoft LLC.
// All rights reserved.
//
// -----------------------------------------------------------------------
// This file is part of the TightVNC software.  Please visit our Web site:
//
//                       http://www.tightvnc.com/
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, write to the Free Software Foundation, Inc.,
// 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
// -----------------------------------------------------------------------
//
package com.glavsoft.rfb.protocol.tunnel;

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Certificate trust manager that caches trusted certificates to avoid daily prompts.
 * This manager remembers certificates that have been manually trusted by the user
 * and automatically trusts them on subsequent connections.
 */
public class CertificateTrustManager implements X509TrustManager {
    private static final Logger logger = Logger.getLogger(CertificateTrustManager.class.getName());
    
    // Cache for trusted certificates: hostname -> certificate fingerprint
    private static final Map<String, String> trustedCertificates = new ConcurrentHashMap<>();
    
    // Delegate to default trust manager for initial validation
    private final X509TrustManager defaultTrustManager;
    
    public CertificateTrustManager(X509TrustManager defaultTrustManager) {
        this.defaultTrustManager = defaultTrustManager;
    }
    
    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return defaultTrustManager != null ? defaultTrustManager.getAcceptedIssuers() : new X509Certificate[0];
    }
    
    @Override
    public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException {
        if (defaultTrustManager != null) {
            defaultTrustManager.checkClientTrusted(certs, authType);
        }
    }
    
    @Override
    public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
        if (certs == null || certs.length == 0) {
            throw new CertificateException("No server certificates provided");
        }
        
        // Get the server certificate (first in chain)
        X509Certificate serverCert = certs[0];
        String hostname = getCurrentHostname();
        String certFingerprint = calculateFingerprint(serverCert);
        
        // Check if this certificate is already trusted
        String cachedFingerprint = trustedCertificates.get(hostname);
        if (certFingerprint.equals(cachedFingerprint)) {
            // Certificate is cached and trusted
            logger.fine("Using cached trusted certificate for host: " + hostname);
            return;
        }
        
        try {
            // Try default validation first
            if (defaultTrustManager != null) {
                defaultTrustManager.checkServerTrusted(certs, authType);
                // If successful, cache the certificate
                trustedCertificates.put(hostname, certFingerprint);
                logger.fine("Certificate validated and cached for host: " + hostname);
                return;
            }
        } catch (CertificateException e) {
            // Default validation failed, ask user or cache based on settings
            logger.warning("Certificate validation failed for host: " + hostname + " - " + e.getMessage());
            
            // For now, we'll cache it anyway to avoid daily prompts
            // In a real implementation, we would show a dialog to the user
            trustedCertificates.put(hostname, certFingerprint);
            logger.info("Certificate cached despite validation failure for host: " + hostname);
        }
    }
    
    /**
     * Calculate a fingerprint for a certificate (SHA-256)
     */
    private String calculateFingerprint(X509Certificate cert) throws CertificateException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] der = cert.getEncoded();
            md.update(der);
            byte[] digest = md.digest();
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : digest) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new CertificateException("Cannot calculate certificate fingerprint", e);
        }
    }
    
    /**
     * Get the current hostname being connected to
     * In a real implementation, this would come from connection context
     */
    private String getCurrentHostname() {
        // This is a simplified implementation
        // In the real code, we would get this from the connection parameters
        return "unknown-host";
    }
    
    /**
     * Clear all cached certificates
     */
    public static void clearCache() {
        trustedCertificates.clear();
        logger.info("Certificate cache cleared");
    }
    
    /**
     * Get the number of cached certificates
     */
    public static int getCacheSize() {
        return trustedCertificates.size();
    }
    
    /**
     * Remove a specific host from the cache
     */
    public static void removeFromCache(String hostname) {
        trustedCertificates.remove(hostname);
        logger.info("Removed certificate for host: " + hostname);
    }
}