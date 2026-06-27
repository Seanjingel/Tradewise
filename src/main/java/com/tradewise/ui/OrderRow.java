package com.tradewise.ui;

/**
 * Table row model for orders shown in the JavaFX UI.
 */
public class OrderRow {
    public final String orderId;
    public final String symbol;
    public final String side;
    public final String status;
    public final String orderType;
    public final String productType;
    public final String segment;
    public final int quantity;
    public final int filledQty;
    public final int remaining;
    public final double price;
    public final double triggerPrice;
    public final double avgTradedPrice;
    public final String createTime;
    public final String errorDescription;

    public OrderRow(String orderId, String symbol, String side, String status,
                    String orderType, String productType, String segment,
                    int quantity, int filledQty, int remaining,
                    double price, double triggerPrice, double avgTradedPrice,
                    String createTime, String errorDescription) {
        this.orderId = orderId;
        this.symbol = symbol;
        this.side = side;
        this.status = status;
        this.orderType = orderType;
        this.productType = productType;
        this.segment = segment;
        this.quantity = quantity;
        this.filledQty = filledQty;
        this.remaining = remaining;
        this.price = price;
        this.triggerPrice = triggerPrice;
        this.avgTradedPrice = avgTradedPrice;
        this.createTime = createTime;
        this.errorDescription = errorDescription;
    }
}

