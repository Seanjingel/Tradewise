package com.tradewise.service;

import com.tradewise.config.DhanProperties;
import com.tradewise.model.dto.FundLimitResponse;
import com.tradewise.security.DhanCredentialStore;
import com.tradewise.client.DhanRestClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;

/**
 * Service for managing Dhan fund operations.
 */
@Service
public class DhanFundService {

    private static final Logger log = LoggerFactory.getLogger(DhanFundService.class);
    private static final String FUND_LIMIT_URL = "https://api.dhan.co/v2/fundlimit";

    private final DhanCredentialStore dhanCredentialStore;
    private final DhanProperties dhanProperties;
    private final DhanRestClientFactory restClientFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DhanFundService(
            DhanCredentialStore dhanCredentialStore,
            DhanProperties dhanProperties,
            DhanRestClientFactory restClientFactory
    ) {
        this.dhanCredentialStore = dhanCredentialStore;
        this.dhanProperties = dhanProperties;
        this.restClientFactory = restClientFactory;
    }

    public FundLimitResponse getFundLimit() {
        String accessToken = resolveAccessToken();
        if (accessToken.isBlank()) {
            throw new IllegalStateException("Not logged in. Please login first to view fund details.");
        }

        RestClient restClient = restClientFactory.create();

        String clientId = resolveClientId();

        try {
            log.info("Fetching fund limit from Dhan API");
            String responseBody = restClient.get()
                    .uri(FUND_LIMIT_URL)
                    .headers(headers -> {
                        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
                        headers.set("access-token", accessToken);
                        if (clientId != null && !clientId.isBlank()) {
                            headers.set("client-id", clientId);
                        }
                    })
                    .retrieve()
                    .body(String.class);

            log.info("Dhan fundlimit API responded with HTTP 200");
            log.debug("Dhan fundlimit response: {}", responseBody);

            if (responseBody == null || responseBody.isBlank()) {
                throw new IllegalStateException("Dhan fundlimit API returned empty response");
            }

            return objectMapper.readValue(responseBody, FundLimitResponse.class);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String responseBody = ex.getResponseBodyAsString();
            if (status == 401 || status == 403) {
                throw new IllegalStateException(
                        "HTTP " + status + " — access token is invalid or expired. Please login again.", ex);
            }
            throw new IllegalStateException(
                    "Dhan fundlimit API returned HTTP " + status + ". Response: " + responseBody, ex);
        } catch (RestClientException ex) {
            String cause = buildCauseChain(ex);
            log.error("Error while calling Dhan fundlimit API: {}", cause, ex);
            throw new IllegalStateException(
                    "Could not reach Dhan fundlimit API — " + cause
                            + ". If Postman works from same machine, check Java truststore/TLS settings.", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse Dhan fundlimit response", ex);
        }
    }

    private String resolveAccessToken() {
        if (dhanCredentialStore.hasAccessToken()) {
            return dhanCredentialStore.getAccessToken();
        }
        String token = dhanProperties.getAccessToken();
        return token == null ? "" : token.trim();
    }

    private String resolveClientId() {
        if (dhanCredentialStore.getClientId() != null && !dhanCredentialStore.getClientId().isBlank()) {
            return dhanCredentialStore.getClientId();
        }
        return dhanProperties.getClientId();
    }

    private String buildCauseChain(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable current = ex;
        while (current != null) {
            if (!sb.isEmpty()) {
                sb.append(" → ");
            }
            sb.append(current.getClass().getSimpleName());
            if (current.getMessage() != null) {
                sb.append(": ").append(current.getMessage());
            }
            current = current.getCause();
        }
        return sb.toString();
    }
}

