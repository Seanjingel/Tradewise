package com.tradewise.model.dto;

/**
 * DTO for daily trading statistics and limits.
 */
public record DailyStatsDto(
        String date,
        int tradesCount,
        double realizedPnl,
        double unrealizedPnl,
        double totalPnl,
        boolean dailyLossLimitHit,
        boolean dailyProfitLimitHit,
        boolean dailyTradesLimitHit,
        boolean tradingLocked,
        String lockedReason,
        long lastResetAt,
        long marketOpenAt,
        // Peak P&L tracking for trailing profit stop
        double peakPnl,
        // Trailing stop info
        boolean trailingStopActive,   // true once profit >= activationLevel
        double trailingStopLevel,     // configuredActivationLevel
        double trailingDrawdown,      // configured drawdown amount
        double trailingDrawdownSoFar  // current drop from peak (peakPnl - totalPnl)
) {}

