package com.tradewise.ui;

/**
 * Table row model for trades shown in the JavaFX UI.
 */
public class TradeRow {
    public final long id;
    public final String symbol;
    public final int quantity;
    public final double price;
    public final String side;
    public final String tradedAt;

    public TradeRow(long id, String symbol, int quantity, double price, String side, String tradedAt) {
        this.id = id;
        this.symbol = symbol;
        this.quantity = quantity;
        this.price = price;
        this.side = side;
        this.tradedAt = tradedAt;
    }
}

