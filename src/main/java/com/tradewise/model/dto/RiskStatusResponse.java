package com.tradewise.model.dto;

/**
 * Live risk status including P&L and auto kill-switch rule evaluation.
 */
public record RiskStatusResponse(
        int totalTrades,
        double totalBuyValue,
        double totalSellValue,
        double netPnl,
        boolean tradeLimit10Triggered,
        boolean loss10kTriggered,
        boolean profit25kTriggered,
        boolean killSwitchActive,
        String killSwitchReason,
        int configuredMaxTrades,
        double configuredMaxLoss,
        double configuredMaxProfit,
        boolean cooldownActive,
        long cooldownRemainingSeconds,
        String cooldownEndsAt
) {
}

