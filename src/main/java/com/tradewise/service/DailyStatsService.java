package com.tradewise.service;

import com.tradewise.model.dto.DailyStatsDto;
import com.tradewise.config.DhanProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service for managing daily trading statistics, limits, and trading locks.
 * Resets at market open time (default 09:15 IST).
 *
 * KEY FEATURES:
 * 1. Tracks daily P&L against configurable loss/profit/trade limits
 * 2. Tracks LIVE P&L from open positions (updated every position refresh)
 * 3. Trailing profit stop - exits when profit drops from peak by configured amount
 * 4. Auto-exit positions on limit breach (via callback to TradeService)
 */
@Service
public class DailyStatsService {

    private static final Logger log = LoggerFactory.getLogger(DailyStatsService.class);

    private final DhanProperties dhanProperties;

    // Daily reset time (market open) - configurable in properties
    private volatile LocalTime marketOpenTime = LocalTime.of(9, 15);

    // Daily counters
    private AtomicInteger dailyTradesCount = new AtomicInteger(0);
    private AtomicReference<Double> dailyRealizedPnl = new AtomicReference<>(0.0);
    private AtomicReference<Double> dailyUnrealizedPnl = new AtomicReference<>(0.0);

    // Limit flags
    private AtomicBoolean dailyLossLimitHit = new AtomicBoolean(false);
    private AtomicBoolean dailyProfitLimitHit = new AtomicBoolean(false);
    private AtomicBoolean dailyTradesLimitHit = new AtomicBoolean(false);
    private AtomicBoolean tradingLocked = new AtomicBoolean(false);
    private AtomicReference<String> lockReason = new AtomicReference<>("NONE");

    // PEAK P&L TRACKING for trailing profit stop
    private AtomicReference<Double> peakPnl = new AtomicReference<>(0.0);
    private AtomicBoolean trailingStopActive = new AtomicBoolean(false);

    // Track last reset date
    private volatile LocalDate lastResetDate = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private volatile long lastResetAt = System.currentTimeMillis();

    // Callback to trigger auto-exit positions (wired from TradeService)
    private volatile Runnable autoExitCallback = null;

    public DailyStatsService(DhanProperties dhanProperties) {
        this.dhanProperties = dhanProperties;
        String openTimeStr = dhanProperties.getMarketOpenTime();
        if (openTimeStr != null && !openTimeStr.isBlank()) {
            try {
                this.marketOpenTime = LocalTime.parse(openTimeStr);
                log.info("Market open time set to: {}", openTimeStr);
            } catch (Exception ex) {
                log.warn("Invalid market open time format: {}. Using default 09:15", openTimeStr);
            }
        }
        checkAndResetIfNewDay();
    }

    /**
     * Wire in the exit-all callback. Called by TradeService on startup.
     */
    public void setAutoExitCallback(Runnable callback) {
        this.autoExitCallback = callback;
    }

    public void checkAndResetIfNewDay() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        if (!today.equals(lastResetDate)) {
            resetDailyStats();
            lastResetDate = today;
            lastResetAt = System.currentTimeMillis();
            log.info("Daily stats reset at market open: {}", today);
        }
    }

    /**
     * Update live P&L from position refresh.
     * This is the KEY method - called every time positions are fetched (every 5 seconds).
     * Checks both hard limits AND trailing profit stop with live unrealized P&L included.
     */
    public void updateLivePnl(double liveTotalPnl) {
        checkAndResetIfNewDay();

        // Update unrealized component based on live total vs realized
        // liveTotalPnl = realized + unrealized (live)
        double realized = dailyRealizedPnl.get();
        double liveUnrealized = liveTotalPnl - realized;
        dailyUnrealizedPnl.set(liveUnrealized);

        // Update peak P&L (high-water mark)
        double currentPeak = peakPnl.get();
        if (liveTotalPnl > currentPeak) {
            peakPnl.set(liveTotalPnl);
            log.debug("New peak P&L: ₹{}", String.format("%.2f", liveTotalPnl));
        }

        // Evaluate all limits with live P&L
        evaluateLimitsWithLivePnl(liveTotalPnl);
    }

    /**
     * Record a new trade and update daily counters.
     */
    public void recordTrade(double realizedPnl, double unrealizedPnl) {
        checkAndResetIfNewDay();
        dailyTradesCount.incrementAndGet();
        dailyRealizedPnl.updateAndGet(v -> v + realizedPnl);
        dailyUnrealizedPnl.set(unrealizedPnl);
        evaluateLimitsWithLivePnl(dailyRealizedPnl.get() + dailyUnrealizedPnl.get());
    }

    /**
     * Sync daily trade count from broker trade feed.
     *
     * In monitor-only mode, trades are executed outside this app, so recordTrade()
     * may never be called. This method keeps dailyTradesCount in sync using fetched
     * broker trades and ensures maxDailyTrades is enforced.
     */
    public void syncTradeCountFromFeed(int observedTradeCount) {
        checkAndResetIfNewDay();
        int sanitizedCount = Math.max(0, observedTradeCount);

        // Keep monotonic within a day to avoid accidental unlock on transient API gaps.
        dailyTradesCount.updateAndGet(current -> Math.max(current, sanitizedCount));

        // Re-evaluate limits using current live P&L context.
        evaluateLimitsWithLivePnl(dailyRealizedPnl.get() + dailyUnrealizedPnl.get());
    }

    /**
     * Evaluate all daily limits against current live P&L.
     * Also evaluates trailing profit stop.
     */
    private void evaluateLimitsWithLivePnl(double totalPnl) {
        if (tradingLocked.get()) {
            return; // Already locked, no need to re-evaluate
        }

        int maxDailyTrades = dhanProperties.getMaxDailyTrades();
        double maxDailyLoss = dhanProperties.getMaxDailyLoss();
        double maxDailyProfit = dhanProperties.getMaxDailyProfit();
        double trailingActivation = dhanProperties.getProfitTrailingActivationLevel();
        double trailingDrawdownAmount = dhanProperties.getProfitTrailingDrawdown();
        boolean trailingConfigured = trailingActivation > 0 && trailingDrawdownAmount > 0;

        String trigger = null;

        // 1. Check trade count limit
        if (maxDailyTrades > 0 && dailyTradesCount.get() >= maxDailyTrades) {
            dailyTradesLimitHit.set(true);
            trigger = "DAILY_TRADES_LIMIT";
            log.warn("Daily trades limit hit: {} trades", dailyTradesCount.get());
        }

        // 2. Check LIVE loss limit (includes unrealized loss on open positions)
        if (trigger == null && maxDailyLoss > 0 && totalPnl <= -maxDailyLoss) {
            dailyLossLimitHit.set(true);
            trigger = "DAILY_LOSS_LIMIT";
            log.warn("LIVE daily loss limit hit: ₹{} loss (limit: ₹{})",
                String.format("%.2f", Math.abs(totalPnl)),
                String.format("%.2f", maxDailyLoss));
        }

        // 3. Check LIVE profit limit (includes unrealized profit on open positions).
        // If trailing stop is configured, trailing takes control and hard profit cap is skipped.
        if (trigger == null && !trailingConfigured && maxDailyProfit > 0 && totalPnl >= maxDailyProfit) {
            dailyProfitLimitHit.set(true);
            trigger = "DAILY_PROFIT_LIMIT";
            log.info("LIVE daily profit limit hit: ₹{} profit (limit: ₹{})",
                String.format("%.2f", totalPnl),
                String.format("%.2f", maxDailyProfit));
        }

        // 4. Check TRAILING PROFIT STOP
        // Activates once profit hits activation level.
        // Triggers when profit drops by drawdown amount from peak.
        if (trigger == null && trailingConfigured) {
            double peak = peakPnl.get();
            if (peak >= trailingActivation) {
                trailingStopActive.set(true);
                double drawdownSoFar = peak - totalPnl;
                if (drawdownSoFar >= trailingDrawdownAmount) {
                    trigger = "TRAILING_PROFIT_STOP";
                    log.warn("TRAILING PROFIT STOP triggered: peak=₹{}, current=₹{}, drawdown=₹{} (limit=₹{})",
                        String.format("%.2f", peak),
                        String.format("%.2f", totalPnl),
                        String.format("%.2f", drawdownSoFar),
                        String.format("%.2f", trailingDrawdownAmount));
                }
            }
        }

        // Lock trading if any trigger hit
        if (trigger != null) {
            tradingLocked.set(true);
            lockReason.set(trigger);

            // AUTO-EXIT OPEN POSITIONS to prevent further P&L movement
            if (dhanProperties.isAutoExitOnLimit() && autoExitCallback != null) {
                log.warn("AUTO-EXIT triggered due to {}. Exiting all open positions...", trigger);
                try {
                    autoExitCallback.run();
                    log.warn("AUTO-EXIT completed for trigger: {}", trigger);
                } catch (Exception e) {
                    log.error("AUTO-EXIT failed: {}. Kill switch still active.", e.getMessage());
                }
            }
        }
    }

    public void lockTrading(String reason) {
        tradingLocked.set(true);
        lockReason.set(reason);
        log.warn("Trading locked: {}", reason);
    }

    public void unlockTrading() {
        tradingLocked.set(false);
        lockReason.set("NONE");
        log.info("Trading unlocked");
    }

    private void resetDailyStats() {
        dailyTradesCount.set(0);
        dailyRealizedPnl.set(0.0);
        dailyUnrealizedPnl.set(0.0);
        dailyLossLimitHit.set(false);
        dailyProfitLimitHit.set(false);
        dailyTradesLimitHit.set(false);
        tradingLocked.set(false);
        lockReason.set("NONE");
        peakPnl.set(0.0);
        trailingStopActive.set(false);
        lastResetAt = System.currentTimeMillis();
    }

    public DailyStatsDto getDailyStats() {
        checkAndResetIfNewDay();

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        long marketOpenEpoch = today.atTime(marketOpenTime)
                .atZone(ZoneId.of("Asia/Kolkata"))
                .toInstant()
                .toEpochMilli();

        double totalPnl = dailyRealizedPnl.get() + dailyUnrealizedPnl.get();
        double peak = peakPnl.get();
        double drawdownSoFar = Math.max(0, peak - totalPnl);

        return new DailyStatsDto(
                today.toString(),
                dailyTradesCount.get(),
                dailyRealizedPnl.get(),
                dailyUnrealizedPnl.get(),
                totalPnl,
                dailyLossLimitHit.get(),
                dailyProfitLimitHit.get(),
                dailyTradesLimitHit.get(),
                tradingLocked.get(),
                lockReason.get(),
                lastResetAt,
                marketOpenEpoch,
                peak,
                trailingStopActive.get(),
                dhanProperties.getProfitTrailingActivationLevel(),
                dhanProperties.getProfitTrailingDrawdown(),
                drawdownSoFar
        );
    }

    public boolean isTradingAllowed() {
        checkAndResetIfNewDay();
        return !tradingLocked.get();
    }

    public String getLockedReason() {
        return lockReason.get();
    }

    public double getPeakPnl() {
        return peakPnl.get();
    }

    public int getRemainingDailyTrades() {
        int max = dhanProperties.getMaxDailyTrades();
        if (max <= 0) return Integer.MAX_VALUE;
        return Math.max(0, max - dailyTradesCount.get());
    }

    public double getRemainingDailyLossBudget() {
        double max = dhanProperties.getMaxDailyLoss();
        if (max <= 0) return Double.MAX_VALUE;
        double totalPnl = dailyRealizedPnl.get() + dailyUnrealizedPnl.get();
        return Math.max(0, max + totalPnl);
    }

    public double getRemainingDailyProfitTarget() {
        double max = dhanProperties.getMaxDailyProfit();
        if (max <= 0) return Double.MAX_VALUE;
        double totalPnl = dailyRealizedPnl.get() + dailyUnrealizedPnl.get();
        return Math.max(0, max - totalPnl);
    }

    public void manualResetDailyStats() {
        resetDailyStats();
        lastResetDate = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        log.info("Daily stats manually reset");
    }
}
