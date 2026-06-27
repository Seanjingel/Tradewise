package com.tradewise.client;

import com.tradewise.config.DhanProperties;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * Factory for creating configured HttpClient instances.
 */
@Component
public class DhanHttpClientFactory {

    private final DhanProperties dhanProperties;

    public DhanHttpClientFactory(DhanProperties dhanProperties) {
        this.dhanProperties = dhanProperties;
    }

    public HttpClient create() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(dhanProperties.getTimeoutSeconds()));

        SSLParameters sslParameters = new SSLParameters();
        if (dhanProperties.getTlsProtocol() != null && !dhanProperties.getTlsProtocol().isBlank()) {
            sslParameters.setProtocols(new String[]{dhanProperties.getTlsProtocol()});
        }
        builder.sslParameters(sslParameters);

        if (dhanProperties.isInsecureSsl()) {
            builder.sslContext(createInsecureSslContext());
        }

        return builder.build();
    }

    private SSLContext createInsecureSslContext() {
        try {
            TrustManager[] trustAllManagers = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllManagers, new SecureRandom());
            return sslContext;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not initialize insecure SSL context", ex);
        }
    }
}

