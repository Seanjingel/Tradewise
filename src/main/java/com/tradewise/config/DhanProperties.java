package com.tradewise.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dhan API configuration properties.
 */
@ConfigurationProperties(prefix = "tradewise.dhan")
public class DhanProperties {

    private boolean enabled;
    private String tradesUrl = "https://api.dhan.co/v2/trades";
    private String positionsUrl = "https://api.dhan.co/v2/positions";
    private String ordersUrl = "https://api.dhan.co/v2/orders";
    private String accessToken = "";
    private String clientId = "";
    private int timeoutSeconds = 15;
    private boolean insecureSsl = false;
    private String tlsProtocol = "TLSv1.2";
    private int maxTrades = 10;
    private double maxLoss = 10_000.0;
    private double maxProfit = 25_000.0;
    private int cooldownMinutesAfterExit = 15;
    // Daily limits (separate from per-trade limits)
    private int maxDailyTrades = 0; // 0 = unlimited
    private double maxDailyLoss = 0.0; // 0 = unlimited
    private double maxDailyProfit = 0.0; // 0 = unlimited
    private String marketOpenTime = "09:15"; // IST format HH:mm

    // Auto-exit open positions when a loss/profit limit is hit (default: true)
    // When true: EXIT ALL open positions immediately when any limit is breached
    // This prevents further loss/profit movement on open positions after the limit is hit
    private boolean autoExitOnLimit = true;

    // Trailing profit stop - protects peak profits from volatility
    // Example: activationLevel=20000, drawdown=5000 means:
    //   Once profit hits ₹20k, if it then drops ₹5k from peak → EXIT + LOCK
    // Set to 0.0 to disable
    private double profitTrailingActivationLevel = 0.0; // Profit level to activate trailing (0=disabled)
    private double profitTrailingDrawdown = 0.0;        // How much drop from peak triggers exit (0=disabled)
    /**
     * How long (in seconds) to cache the last successful Dhan trades/positions response.
     * Prevents multiple backend endpoints from independently hammering the Dhan API
     * within the same UI poll cycle. Defaults to 30 s (well above the 5-second UI refresh
     * and the Dhan rate-limit window).
     */
    private int cacheTtlSeconds = 30;

    /**
     * Separate, shorter cache TTL for orders only.
     * Orders change immediately after a buy/sell is placed, so they need a much
     * shorter TTL than trades/positions. Defaults to 3 s so new orders appear
     * within ~3 seconds on the UI even with aggressive auto-refresh.
     */
    private int ordersCacheTtlSeconds = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTradesUrl() {
        return tradesUrl;
    }

    public void setTradesUrl(String tradesUrl) {
        this.tradesUrl = tradesUrl;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getPositionsUrl() {
        return positionsUrl;
    }

    public void setPositionsUrl(String positionsUrl) {
        this.positionsUrl = positionsUrl;
    }

    public String getOrdersUrl() {
        return ordersUrl;
    }

    public void setOrdersUrl(String ordersUrl) {
        this.ordersUrl = ordersUrl;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean isInsecureSsl() {
        return insecureSsl;
    }

    public void setInsecureSsl(boolean insecureSsl) {
        this.insecureSsl = insecureSsl;
    }

    public String getTlsProtocol() {
        return tlsProtocol;
    }

    public void setTlsProtocol(String tlsProtocol) {
        this.tlsProtocol = tlsProtocol;
    }

    public int getMaxTrades() {
        return maxTrades;
    }

    public void setMaxTrades(int maxTrades) {
        this.maxTrades = maxTrades;
    }

    public double getMaxLoss() {
        return maxLoss;
    }

    public void setMaxLoss(double maxLoss) {
        this.maxLoss = maxLoss;
    }

    public double getMaxProfit() {
        return maxProfit;
    }

    public void setMaxProfit(double maxProfit) {
        this.maxProfit = maxProfit;
    }

    public int getCooldownMinutesAfterExit() {
        return cooldownMinutesAfterExit;
    }

    public void setCooldownMinutesAfterExit(int cooldownMinutesAfterExit) {
        this.cooldownMinutesAfterExit = cooldownMinutesAfterExit;
    }

    public int getMaxDailyTrades() {
        return maxDailyTrades;
    }

    public void setMaxDailyTrades(int maxDailyTrades) {
        this.maxDailyTrades = maxDailyTrades;
    }

    public double getMaxDailyLoss() {
        return maxDailyLoss;
    }

    public void setMaxDailyLoss(double maxDailyLoss) {
        this.maxDailyLoss = maxDailyLoss;
    }

    public double getMaxDailyProfit() {
        return maxDailyProfit;
    }

    public void setMaxDailyProfit(double maxDailyProfit) {
        this.maxDailyProfit = maxDailyProfit;
    }

    public String getMarketOpenTime() {
        return marketOpenTime;
    }

    public void setMarketOpenTime(String marketOpenTime) {
        this.marketOpenTime = marketOpenTime;
    }

    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(int cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public int getOrdersCacheTtlSeconds() {
        return ordersCacheTtlSeconds;
    }

    public void setOrdersCacheTtlSeconds(int ordersCacheTtlSeconds) {
        this.ordersCacheTtlSeconds = ordersCacheTtlSeconds;
    }

    public boolean isAutoExitOnLimit() {
        return autoExitOnLimit;
    }

    public void setAutoExitOnLimit(boolean autoExitOnLimit) {
        this.autoExitOnLimit = autoExitOnLimit;
    }

    public double getProfitTrailingActivationLevel() {
        return profitTrailingActivationLevel;
    }

    public void setProfitTrailingActivationLevel(double profitTrailingActivationLevel) {
        this.profitTrailingActivationLevel = profitTrailingActivationLevel;
    }

    public double getProfitTrailingDrawdown() {
        return profitTrailingDrawdown;
    }

    public void setProfitTrailingDrawdown(double profitTrailingDrawdown) {
        this.profitTrailingDrawdown = profitTrailingDrawdown;
    }
}
