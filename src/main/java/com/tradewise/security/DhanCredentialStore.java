package com.tradewise.security;

import org.springframework.stereotype.Component;

/**
 * Thread-safe credential store for Dhan authentication.
 */
@Component
public class DhanCredentialStore {

    private volatile String accessToken = "";
    private volatile String clientId = "";

    /**
     * Update the stored credentials.
     */
    public synchronized void update(String accessToken, String clientId) {
        this.accessToken = accessToken == null ? "" : accessToken.trim();
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    /**
     * Clear credentials from current session.
     */
    public synchronized void clear() {
        this.accessToken = "";
        this.clientId = "";
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getClientId() {
        return clientId;
    }

    public boolean hasAccessToken() {
        return accessToken != null && !accessToken.isBlank();
    }
}
