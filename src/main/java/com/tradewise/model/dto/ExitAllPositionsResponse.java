package com.tradewise.model.dto;

/**
 * Response returned after requesting Dhan to exit all positions/orders.
 */
public record ExitAllPositionsResponse(
        String status,
        String message
) {
}

