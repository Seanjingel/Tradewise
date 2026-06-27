package com.tradewise.model.dto;

/**
 * Trade summary response DTO.
 */
public record TradeSummaryResponse(
        int totalTrades,
        double totalNotional
) {
}

