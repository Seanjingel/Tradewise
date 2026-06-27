package com.tradewise.model.dto;

/**
 * Position summary row for UI.
 */
public record PositionPnlResponse(
        String symbol,
        int buyQuantity,
        int sellQuantity,
        int netQuantity,
        double averageBuyPrice,
        double averageSellPrice,
        double realizedPnl,
        double markPrice,
        double unrealizedPnl,
        double totalPnl
) {
}

