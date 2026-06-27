package com.tradewise.service;

import com.tradewise.client.ExternalTradeClient;
import com.tradewise.config.DhanProperties;
import com.tradewise.model.dto.ExitAllPositionsResponse;
import com.tradewise.model.dto.KillSwitchStatusResponse;
import com.tradewise.model.dto.OrderResponse;
import com.tradewise.model.dto.PositionPnlResponse;
import com.tradewise.model.dto.RiskStatusResponse;
import com.tradewise.model.dto.TradeSummaryResponse;
import com.tradewise.model.entity.Trade;
import com.tradewise.security.DhanCredentialStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service for managing trades.
 * Auto kill-switch rules:
 *  - Total trades >= 10
 *  - Net loss  > ₹10,000  (negative P&L)
 *  - Net profit > ₹25,000  (positive P&L)
 */
@Service
public class TradeService {

    private static final Logger log = LoggerFactory.getLogger(TradeService.class);

    private static final String DHAN_KILL_SWITCH_URL = "https://api.dhan.co/v2/killswitch";

    /**
     * Minimum interval between live Dhan kill-switch GET calls.
     * Prevents hammering the broker API on every UI poll cycle.
     */
    private static final long DHAN_STATUS_CACHE_TTL_MS = 15_000;
    private volatile long killSwitchLastSyncedAt = 0;

    private final ExternalTradeClient externalTradeClient;
    private final DhanProperties dhanProperties;
    private final DhanCredentialStore dhanCredentialStore;
    private final DailyStatsService dailyStatsService;
    private final List<Trade> trades = new CopyOnWriteArrayList<>();
    private final AtomicBoolean killSwitchActive = new AtomicBoolean(false);
    private final AtomicReference<String> killSwitchReason = new AtomicReference<>("NONE");
    private final AtomicReference<String> killSwitchMessage = new AtomicReference<>("Kill switch is INACTIVE");
    private volatile long cooldownUntilEpochMs = 0;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TradeService(ExternalTradeClient externalTradeClient,
                        DhanProperties dhanProperties,
                        DhanCredentialStore dhanCredentialStore,
                        DailyStatsService dailyStatsService) {
        this.externalTradeClient = externalTradeClient;
        this.dhanProperties = dhanProperties;
        this.dhanCredentialStore = dhanCredentialStore;
        this.dailyStatsService = dailyStatsService;

        // Wire auto-exit callback: when DailyStatsService locks trading,
        // it will call this to exit all open positions at the broker.
        this.dailyStatsService.setAutoExitCallback(this::autoExitPositions);
    }

    /**
     * Auto-exit all positions - called by DailyStatsService when a limit is breached.
     * Activates broker kill-switch FIRST, then exits positions.
     */
    private void autoExitPositions() {
        if (!isDhanMode()) {
            log.warn("Auto-exit skipped: not in Dhan mode");
            return;
        }
        // Activate kill switch at broker level to block new orders during exit
        syncKillSwitchWithDhan("ACTIVATE", false);
        killSwitchActive.set(true);

        try {
            externalTradeClient.exitAllPositions();
            startCooldownAfterExit();
            log.warn("AUTO-EXIT: All positions exited successfully");
        } catch (Exception e) {
            log.error("AUTO-EXIT: Failed to exit positions: {}. Kill switch still active.", e.getMessage());
        }
    }

    public List<Trade> getTrades() {
        List<Trade> sourceTrades = isDhanMode() ? externalTradeClient.fetchTrades() : trades;
        List<Trade> sorted = sourceTrades.stream()
                .sorted((a, b) -> b.tradedAt().compareTo(a.tradedAt()))
                .toList();

        // Sync monitor-only trade count from broker feed for daily trade-limit enforcement.
        dailyStatsService.syncTradeCountFromFeed(sorted.size());

        // CRITICAL: If DailyStatsService says trading is locked, sync with Dhan
        syncDailyStatsLockWithDhan();
        
        return sorted;
    }
    
    /**
     * Sync daily stats lock status with Dhan kill-switch.
     * If trading is locked due to daily limits, activate Dhan kill-switch.
     */
    private void syncDailyStatsLockWithDhan() {
        if (dailyStatsService.isTradingAllowed()) {
            // Trading is allowed - no need to sync
            return;
        }
        
        // Trading is locked on our side - check if Dhan kill-switch is already active
        if (killSwitchActive.get()) {
            // Already active, no need to re-activate
            return;
        }
        
        // Sync with Dhan: activate kill-switch due to daily limit
        String reason = dailyStatsService.getLockedReason();
        log.warn("Daily trading limit hit ({}). Syncing with Dhan kill-switch...", reason);
        
        String syncMessage = syncKillSwitchWithDhan("ACTIVATE", false);
        killSwitchActive.set(true);
        killSwitchReason.set(reason);
        String message = "Kill switch synced with daily stats: " + reason + ". " + syncMessage;
        killSwitchMessage.set(message);
        
        log.warn("Kill switch SYNCED WITH DHAN due to daily limit: {} | Message: {}", reason, syncMessage);
    }


    public TradeSummaryResponse getSummary() {
        List<Trade> sourceTrades = getTrades();
        double totalNotional = sourceTrades.stream()
                .mapToDouble(t -> t.quantity() * t.price())
                .sum();
        return new TradeSummaryResponse(sourceTrades.size(), totalNotional);
    }


    public KillSwitchStatusResponse getKillSwitchStatus() {
        refreshKillSwitchStateFromDhan();
        return new KillSwitchStatusResponse(killSwitchActive.get(), killSwitchReason.get(), killSwitchMessage.get());
    }

    public KillSwitchStatusResponse activateKillSwitch() {
        String reason = "MANUAL";
        String syncMessage = syncKillSwitchWithDhan("ACTIVATE", true);

        killSwitchActive.set(true);
        killSwitchReason.set(reason);
        String message = "Kill switch activated." + (syncMessage.isBlank() ? "" : " " + syncMessage);
        killSwitchMessage.set(message);
        killSwitchLastSyncedAt = 0; // force Dhan re-read on next status poll
        log.warn("Kill switch manually ACTIVATED");

        return new KillSwitchStatusResponse(true, reason, message);
    }

    public KillSwitchStatusResponse deactivateKillSwitch() {
        String accessToken = resolveAccessToken();
        if (accessToken.isBlank()) {
            killSwitchActive.set(false);
            killSwitchReason.set("NONE");
            String message = "Kill switch deactivated locally (no Dhan access token). Trading is allowed.";
            killSwitchMessage.set(message);
            log.info("Kill switch locally DEACTIVATED (no Dhan token)");
            return new KillSwitchStatusResponse(false, "NONE", message);
        }

        String syncMessage = syncKillSwitchWithDhan("DEACTIVATE", true);

        // Re-read status from Dhan before finalizing local state.
        refreshKillSwitchStateFromDhan();

        if (killSwitchActive.get()) {
            if ("NONE".equalsIgnoreCase(killSwitchReason.get())) {
                killSwitchReason.set("DHAN");
            }
            String message = "Kill switch remains active. " + syncMessage;
            killSwitchMessage.set(message);
            log.warn("Kill switch DEACTIVATE requested but still ACTIVE. Response: {}", syncMessage);
            return new KillSwitchStatusResponse(true, killSwitchReason.get(), message);
        }

        killSwitchReason.set("NONE");
        String message = "Kill switch deactivated. Trading is allowed." + (syncMessage.isBlank() ? "" : " " + syncMessage);
        killSwitchMessage.set(message);
        killSwitchLastSyncedAt = 0; // force Dhan re-read on next status poll
        log.info("Kill switch manually DEACTIVATED");

        return new KillSwitchStatusResponse(false, "NONE", message);
    }

    public RiskStatusResponse getRiskStatus() {
        List<Trade> allTrades = isDhanMode() ? externalTradeClient.fetchTrades() : trades;

        double buyValue = allTrades.stream()
                .filter(t -> "BUY".equalsIgnoreCase(t.side()))
                .mapToDouble(t -> t.quantity() * t.price())
                .sum();
        double sellValue = allTrades.stream()
                .filter(t -> "SELL".equalsIgnoreCase(t.side()))
                .mapToDouble(t -> t.quantity() * t.price())
                .sum();
        double netPnl = sellValue - buyValue;
        int count = allTrades.size();
        int maxTrades = Math.max(1, dhanProperties.getMaxTrades());
        double maxLoss = Math.max(0.0, dhanProperties.getMaxLoss());
        double maxProfit = Math.max(0.0, dhanProperties.getMaxProfit());
        long remainingCooldownSeconds = getRemainingCooldownSeconds();

        return new RiskStatusResponse(
                count,
                buyValue,
                sellValue,
                netPnl,
                count >= maxTrades,
                maxLoss > 0 && netPnl <= -maxLoss,
                maxProfit > 0 && netPnl >= maxProfit,
                killSwitchActive.get(),
                killSwitchReason.get(),
                maxTrades,
                maxLoss,
                maxProfit,
                remainingCooldownSeconds > 0,
                remainingCooldownSeconds,
                formatCooldownEnd()
        );
    }

    public List<PositionPnlResponse> getPositions() {
        List<PositionPnlResponse> positions;

        if (isDhanMode()) {
            try {
                List<PositionPnlResponse> externalPositions = externalTradeClient.fetchPositions();
                if (externalPositions != null && !externalPositions.isEmpty()) {
                    positions = externalPositions;
                } else {
                    positions = derivePositionsFromTrades();
                }
            } catch (Exception ex) {
                log.warn("Could not fetch positions from external API. Falling back to trade-derived positions: {}", ex.getMessage());
                positions = derivePositionsFromTrades();
            }
        } else {
            positions = derivePositionsFromTrades();
        }

        // CRITICAL: Feed live total P&L to DailyStatsService every time positions are refreshed.
        // This is how we catch limit breaches on OPEN POSITIONS (unrealized P&L).
        // DailyStatsService will auto-exit + kill-switch if any limit is hit.
        double liveTotalPnl = positions.stream()
                .mapToDouble(p -> p.realizedPnl() + p.unrealizedPnl())
                .sum();
        dailyStatsService.updateLivePnl(liveTotalPnl);

        // Sync kill-switch state with Dhan if now locked
        syncDailyStatsLockWithDhan();

        return positions;
    }

    private List<PositionPnlResponse> derivePositionsFromTrades() {
        List<Trade> allTrades = isDhanMode() ? externalTradeClient.fetchTrades() : trades;
        List<Trade> sortedTrades = allTrades.stream()
                .sorted(Comparator.comparing(Trade::tradedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        Map<String, PositionAccumulator> bySymbol = new HashMap<>();

        for (Trade trade : sortedTrades) {
            String symbol = trade.symbol() == null || trade.symbol().isBlank() ? "UNKNOWN" : trade.symbol().trim().toUpperCase(Locale.ROOT);
            PositionAccumulator acc = bySymbol.computeIfAbsent(symbol, s -> new PositionAccumulator());
            if ("BUY".equalsIgnoreCase(trade.side())) {
                acc.buyQty += trade.quantity();
                acc.buyValue += trade.quantity() * trade.price();
            } else if ("SELL".equalsIgnoreCase(trade.side())) {
                acc.sellQty += trade.quantity();
                acc.sellValue += trade.quantity() * trade.price();
            }
            acc.lastTradedPrice = trade.price();
        }

        return bySymbol.entrySet().stream()
                .map(entry -> {
                    PositionAccumulator acc = entry.getValue();
                    double avgBuy = acc.buyQty > 0 ? acc.buyValue / acc.buyQty : 0.0;
                    double avgSell = acc.sellQty > 0 ? acc.sellValue / acc.sellQty : 0.0;
                    int netQty = acc.buyQty - acc.sellQty;

                    int closedQty = Math.min(acc.buyQty, acc.sellQty);
                    double realizedPnl = (avgSell - avgBuy) * closedQty;
                    double markPrice = acc.lastTradedPrice > 0 ? acc.lastTradedPrice : (avgSell > 0 ? avgSell : avgBuy);

                    double unrealizedPnl;
                    if (netQty > 0) {
                        unrealizedPnl = (markPrice - avgBuy) * netQty;
                    } else if (netQty < 0) {
                        unrealizedPnl = (avgSell - markPrice) * Math.abs(netQty);
                    } else {
                        unrealizedPnl = 0.0;
                    }

                    double totalPnl = realizedPnl + unrealizedPnl;

                    return new PositionPnlResponse(
                            entry.getKey(),
                            acc.buyQty,
                            acc.sellQty,
                            netQty,
                            avgBuy,
                            avgSell,
                            realizedPnl,
                            markPrice,
                            unrealizedPnl,
                            totalPnl
                    );
                })
                .sorted((a, b) -> a.symbol().compareToIgnoreCase(b.symbol()))
                .toList();
    } // end derivePositionsFromTrades

    public List<OrderResponse> getOrders() {
        if (!isDhanMode()) {
            return List.of();
        }
        return externalTradeClient.fetchOrders();
    }

    public ExitAllPositionsResponse exitAllPositions() {
        if (!isDhanMode()) {
            throw new IllegalStateException("Exit positions is available only when Dhan integration is enabled.");
        }
        ExitAllPositionsResponse response = externalTradeClient.exitAllPositions();
        startCooldownAfterExit();
        return response;
    }


    private String syncKillSwitchWithDhan(String status, boolean failHard) {
        String accessToken = resolveAccessToken();
        if (accessToken.isBlank()) {
            return "Dhan sync skipped (no access token).";
        }

        String clientId = resolveClientId();

        try {
            URI uri = URI.create(DHAN_KILL_SWITCH_URL + "?killSwitchStatus=" + status);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("access-token", accessToken);

            if (clientId != null && !clientId.isBlank()) {
                builder.header("client-id", clientId);
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                String dhanMessage = extractDhanKillSwitchMessage(response.body());
                String msg = "Dhan kill-switch API failed with HTTP " + response.statusCode()
                        + ": " + (dhanMessage == null || dhanMessage.isBlank() ? response.body() : dhanMessage);
                if (failHard) {
                    throw new IllegalStateException(msg);
                }
                log.error(msg);
                return "Dhan sync failed.";
            }

            String message = extractDhanKillSwitchMessage(response.body());
            return message == null || message.isBlank() ? "Dhan kill-switch sync successful." : message;
        } catch (IOException | InterruptedException ex) {
            String msg = "Dhan kill-switch API call failed: " + ex.getMessage();
            if (failHard) {
                throw new IllegalStateException(msg, ex);
            }
            log.error(msg, ex);
            return "Dhan sync failed.";
        }
    }

    /**
     * Refreshes local kill-switch state from Dhan, but only if the cache is stale.
     * This avoids hammering the Dhan API on every UI poll cycle.
     */
    private void refreshKillSwitchStateFromDhan() {
        long now = System.currentTimeMillis();
        if (now - killSwitchLastSyncedAt < DHAN_STATUS_CACHE_TTL_MS) {
            return; // cache still fresh — skip Dhan network call
        }

        String accessToken = resolveAccessToken();
        if (accessToken.isBlank()) {
            return;
        }

        String clientId = resolveClientId();

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(DHAN_KILL_SWITCH_URL))
                    .GET()
                    .header("Accept", "application/json")
                    .header("access-token", accessToken);

            if (clientId != null && !clientId.isBlank()) {
                builder.header("client-id", clientId);
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("Could not read Dhan kill-switch status. HTTP {}: {}", response.statusCode(), response.body());
                return;
            }

            String status = extractDhanKillSwitchEnum(response.body());
            if ("ACTIVATE".equalsIgnoreCase(status) || "ACTIVE".equalsIgnoreCase(status)) {
                killSwitchActive.set(true);
                if ("NONE".equalsIgnoreCase(killSwitchReason.get())) {
                    killSwitchReason.set("DHAN");
                }
                killSwitchMessage.set("Kill switch is ACTIVE (synced from Dhan).");
            } else if ("DEACTIVATE".equalsIgnoreCase(status) || "INACTIVE".equalsIgnoreCase(status)) {
                killSwitchActive.set(false);
                killSwitchReason.set("NONE");
                killSwitchMessage.set("Kill switch is INACTIVE (synced from Dhan).");
            }
            killSwitchLastSyncedAt = System.currentTimeMillis();
        } catch (IOException | InterruptedException ex) {
            log.debug("Skipping Dhan kill-switch status refresh: {}", ex.getMessage());
        }
    }

    private String extractDhanKillSwitchMessage(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode messageNode = node.get("message");
            if (messageNode != null && !messageNode.isNull() && !messageNode.asText().isBlank()) {
                return messageNode.asText();
            }
            JsonNode remarksNode = node.get("remarks");
            if (remarksNode != null && !remarksNode.isNull() && !remarksNode.asText().isBlank()) {
                return remarksNode.asText();
            }
            JsonNode errorNode = node.get("error");
            if (errorNode != null && !errorNode.isNull() && !errorNode.asText().isBlank()) {
                return errorNode.asText();
            }
            JsonNode statusNode = node.get("killSwitchStatus");
            if (statusNode != null && !statusNode.isNull() && !statusNode.asText().isBlank()) {
                return statusNode.asText();
            }
            return json;
        } catch (Exception ignored) {
            return json;
        }
    }

    private String extractDhanKillSwitchEnum(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode statusNode = node.get("killSwitchStatus");
            if (statusNode != null && !statusNode.isNull()) {
                return statusNode.asText();
            }
            return "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean isDhanMode() {
        return dhanCredentialStore.hasAccessToken() || dhanProperties.isEnabled();
    }

    private void startCooldownAfterExit() {
        int cooldownMinutes = Math.max(0, dhanProperties.getCooldownMinutesAfterExit());
        if (cooldownMinutes <= 0) {
            cooldownUntilEpochMs = 0;
            return;
        }
        cooldownUntilEpochMs = System.currentTimeMillis() + (cooldownMinutes * 60_000L);
    }

    private long getRemainingCooldownSeconds() {
        long remainingMs = cooldownUntilEpochMs - System.currentTimeMillis();
        return Math.max(0L, remainingMs / 1000L);
    }

    private String formatCooldownEnd() {
        if (cooldownUntilEpochMs <= System.currentTimeMillis()) {
            return "";
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(cooldownUntilEpochMs), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private String resolveAccessToken() {
        if (dhanCredentialStore.hasAccessToken()) {
            return dhanCredentialStore.getAccessToken();
        }
        String token = dhanProperties.getAccessToken();
        return token == null ? "" : token.trim();
    }

    private String resolveClientId() {
        String fromStore = dhanCredentialStore.getClientId();
        if (fromStore != null && !fromStore.isBlank()) {
            return fromStore;
        }
        return dhanProperties.getClientId();
    }


    private static class PositionAccumulator {
        int buyQty;
        int sellQty;
        double buyValue;
        double sellValue;
        double lastTradedPrice;
    }
}
