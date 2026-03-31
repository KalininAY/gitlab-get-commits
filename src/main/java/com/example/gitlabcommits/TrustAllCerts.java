package com.example.gitlabcommits;

import javax.net.ssl.*;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;

/**
 * Globally disables SSL certificate verification for the JVM.
 * Call TrustAllCerts.install() once at application startup.
 * Needed for GitLab instances with self-signed or corporate CA certificates
 * not present in the JDK truststore.
 */
public class TrustAllCerts {

    private static final TrustManager[] TRUST_ALL = new TrustManager[]{
        new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        }
    };

    public static void install() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, TRUST_ALL, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
            SSLContext.setDefault(ctx);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            System.err.println("TrustAllCerts: failed to install: " + e.getMessage());
        }
    }
}
