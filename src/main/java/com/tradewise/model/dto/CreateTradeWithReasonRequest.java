package com.tradewise.model.dto;

/**
 * Request DTO for creating a trade with reason/journal.
 */
public record CreateTradeWithReasonRequest(
        String symbol,
        int quantity,
        double price,
        String side,
        String reason,
        String journal
) {}

