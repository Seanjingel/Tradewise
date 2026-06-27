package com.tradewise.model.dto;

/**
 * Kill switch status response.
 * reason values: MANUAL, TRADE_LIMIT, LOSS_LIMIT, PROFIT_LIMIT, NONE
 */
public record KillSwitchStatusResponse(
        boolean active,
        String reason,
        String message
) {
}

