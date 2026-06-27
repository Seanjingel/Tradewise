package com.tradewise.model.dto;

/**
 * Represents a single order entry from the Dhan /v2/orders API.
 */
public record OrderResponse(
        String orderId,
        String exchangeOrderId,
        String orderStatus,
        String transactionType,
        String exchangeSegment,
        String productType,
        String orderType,
        String validity,
        String tradingSymbol,
        String securityId,
        int quantity,
        int filledQty,
        int remainingQuantity,
        double price,
        double triggerPrice,
        double averageTradedPrice,
        String createTime,
        String updateTime,
        String omsErrorDescription
) {
}

