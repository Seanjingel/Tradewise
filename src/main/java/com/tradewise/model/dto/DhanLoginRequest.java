package com.tradewise.model.dto;

/**
 * Dhan login request DTO.
 */
public record DhanLoginRequest(
        String accessToken,
        String clientId
) {
}

