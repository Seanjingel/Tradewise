package com.tradewise.ui;

/**
 * Table row model for positions/PnL shown in the JavaFX UI.
 */
public class PositionRow {
    public final String symbol;
    public final int buyQuantity;
    public final int sellQuantity;
    public final int netQuantity;
    public final double markPrice;
    public final double realizedPnl;
    public final double unrealizedPnl;
    public final double totalPnl;

    public PositionRow(String symbol, int buyQuantity, int sellQuantity, int netQuantity, double markPrice,
                       double realizedPnl, double unrealizedPnl, double totalPnl) {
        this.symbol = symbol;
        this.buyQuantity = buyQuantity;
        this.sellQuantity = sellQuantity;
        this.netQuantity = netQuantity;
        this.markPrice = markPrice;
        this.realizedPnl = realizedPnl;
        this.unrealizedPnl = unrealizedPnl;
        this.totalPnl = totalPnl;
    }
}

