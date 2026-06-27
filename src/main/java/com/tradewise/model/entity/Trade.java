package com.tradewise.model.entity;

import java.time.LocalDateTime;

/**
 * Represents a trade transaction.
 */
public record Trade(
        long id,
        String symbol,
        int quantity,
        double price,
        String side,
        LocalDateTime tradedAt
) {
}

