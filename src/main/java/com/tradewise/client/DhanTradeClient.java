package com.tradewise.client;

import com.tradewise.config.DhanProperties;
import com.tradewise.model.dto.ExitAllPositionsResponse;
import com.tradewise.model.dto.OrderResponse;
import com.tradewise.model.dto.PositionPnlResponse;
import com.tradewise.model.entity.Trade;
import com.tradewise.security.DhanCredentialStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client for fetching trades from Dhan API.
 */
@Service
public class DhanTradeClient implements ExternalTradeClient {

    private static final Logger log = LoggerFactory.getLogger(DhanTradeClient.class);

    private final DhanProperties dhanProperties;
    private final DhanCredentialStore dhanCredentialStore;
    private final DhanRestClientFactory restClientFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Response cache ---
    private final AtomicReference<List<Trade>> cachedTrades = new AtomicReference<>(null);
    private volatile long tradesCachedAt = 0;

    private final AtomicReference<List<PositionPnlResponse>> cachedPositions = new AtomicReference<>(null);
    private volatile long positionsCachedAt = 0;

    private final AtomicReference<List<OrderResponse>> cachedOrders = new AtomicReference<>(null);
    private volatile long ordersCachedAt = 0;

    public DhanTradeClient(
            DhanProperties dhanProperties,
            DhanCredentialStore dhanCredentialStore,
            DhanRestClientFactory restClientFactory
    ) {
        this.dhanProperties = dhanProperties;
        this.dhanCredentialStore = dhanCredentialStore;
        this.restClientFactory = restClientFactory;
    }

    @Override
    public List<Trade> fetchTrades() {
        validateConfiguration();

        // Serve from cache if still within TTL to avoid rate-limiting (HTTP 429).
        long cacheTtlMs = dhanProperties.getCacheTtlSeconds() * 1000L;
        List<Trade> cached = cachedTrades.get();
        if (cached != null && (System.currentTimeMillis() - tradesCachedAt) < cacheTtlMs) {
            log.debug("Returning cached trades (age {}ms, TTL {}ms)", System.currentTimeMillis() - tradesCachedAt, cacheTtlMs);
            return cached;
        }

        RestClient restClient = restClientFactory.create();

        String accessToken = resolveAccessToken();
        String clientId = resolveClientId();

        try {
            log.info("Fetching trades from Dhan: {}", dhanProperties.getTradesUrl());
            String responseBody = restClient.get()
                    .uri(dhanProperties.getTradesUrl())
                    .headers(headers -> {
                        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
                        headers.set("access-token", accessToken);
                        // Keep client-id optional because Dhan trades API can be called with access-token only.
                        if (clientId != null && !clientId.isBlank()) {
                            headers.set("client-id", clientId);
                        }
                    })
                    .retrieve()
                    .body(String.class);

            log.info("Dhan API responded with HTTP 200");
            log.debug("Dhan response body: {}", responseBody);

            if (responseBody == null || responseBody.isBlank()) {
                return List.of();
            }

            List<Trade> result = parseTrades(responseBody);
            cachedTrades.set(result);
            tradesCachedAt = System.currentTimeMillis();
            return result;
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String responseBody = ex.getResponseBodyAsString();
            if (status == 429) {
                List<Trade> stale = cachedTrades.get();
                if (stale != null) {
                    log.warn("Dhan API rate-limited (HTTP 429). Returning stale cached trades.");
                    return stale;
                }
                throw new IllegalStateException(
                        "Dhan API returned HTTP 429 (rate limit). Try throttling API calls.", ex);
            }
            if (status == 401 || status == 403) {
                throw new IllegalStateException(
                        "Dhan API returned HTTP " + status + " — access token is invalid or expired. Please login again.", ex);
            }
            throw new IllegalStateException(
                    "Dhan API returned HTTP " + status + ". Response: " + responseBody, ex);
        } catch (RestClientException ex) {
            String cause = buildCauseChain(ex);
            log.error("Error while calling Dhan API: {}", cause, ex);
            throw new IllegalStateException(
                    "Could not reach Dhan API — " + cause
                            + ". If Postman works from same machine, check Java truststore/TLS settings.", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse Dhan trade response", ex);
        }
    }

    @Override
    public List<PositionPnlResponse> fetchPositions() {
        validateConfiguration();

        // Serve from cache if still within TTL.
        long cacheTtlMs = dhanProperties.getCacheTtlSeconds() * 1000L;
        List<PositionPnlResponse> cachedPos = cachedPositions.get();
        if (cachedPos != null && (System.currentTimeMillis() - positionsCachedAt) < cacheTtlMs) {
            log.debug("Returning cached positions (age {}ms, TTL {}ms)", System.currentTimeMillis() - positionsCachedAt, cacheTtlMs);
            return cachedPos;
        }

        RestClient restClient = restClientFactory.create();
        String accessToken = resolveAccessToken();
        String clientId = resolveClientId();

        try {
            log.info("Fetching positions from Dhan: {}", dhanProperties.getPositionsUrl());
            String responseBody = restClient.get()
                    .uri(dhanProperties.getPositionsUrl())
                    .headers(headers -> {
                        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
                        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                        headers.set("access-token", accessToken);
                        if (clientId != null && !clientId.isBlank()) {
                            headers.set("client-id", clientId);
                        }
                    })
                    .retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                return List.of();
            }

            List<PositionPnlResponse> result = parsePositions(responseBody);
            cachedPositions.set(result);
            positionsCachedAt = System.currentTimeMillis();
            return result;
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String responseBody = ex.getResponseBodyAsString();
            if (status == 429) {
                List<PositionPnlResponse> stale = cachedPositions.get();
                if (stale != null) {
                    log.warn("Dhan positions API rate-limited (HTTP 429). Returning stale cached positions.");
                    return stale;
                }
                throw new IllegalStateException(
                        "Dhan positions API returned HTTP 429 (rate limit). Try throttling API calls.", ex);
            }
            if (status == 401 || status == 403) {
                throw new IllegalStateException(
                        "Dhan positions API returned HTTP " + status + " — access token is invalid or expired. Please login again.", ex);
            }
            throw new IllegalStateException(
                    "Dhan positions API returned HTTP " + status + ". Response: " + responseBody, ex);
        } catch (RestClientException ex) {
            String cause = buildCauseChain(ex);
            log.error("Error while calling Dhan positions API: {}", cause, ex);
            throw new IllegalStateException(
                    "Could not reach Dhan positions API — " + cause
                            + ". If Postman works from same machine, check Java truststore/TLS settings.", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse Dhan positions response", ex);
        }
    }

    @Override
    public List<OrderResponse> fetchOrders() {
        validateConfiguration();

        long cacheTtlMs = dhanProperties.getOrdersCacheTtlSeconds() * 1000L;
        List<OrderResponse> cached = cachedOrders.get();
        if (cached != null && (System.currentTimeMillis() - ordersCachedAt) < cacheTtlMs) {
            log.debug("Returning cached orders (age {}ms, TTL {}ms)", System.currentTimeMillis() - ordersCachedAt, cacheTtlMs);
            return cached;
        }

        RestClient restClient = restClientFactory.create();
        String accessToken = resolveAccessToken();
        String clientId = resolveClientId();

        try {
            log.info("Fetching orders from Dhan: {}", dhanProperties.getOrdersUrl());
            String responseBody = restClient.get()
                    .uri(dhanProperties.getOrdersUrl())
                    .headers(headers -> {
                        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
                        headers.set("access-token", accessToken);
                        if (clientId != null && !clientId.isBlank()) {
                            headers.set("client-id", clientId);
                        }
                    })
                    .retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                return List.of();
            }

            List<OrderResponse> result = parseOrders(responseBody);
            cachedOrders.set(result);
            ordersCachedAt = System.currentTimeMillis();
            return result;
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String responseBody = ex.getResponseBodyAsString();
            if (status == 429) {
                List<OrderResponse> stale = cachedOrders.get();
                if (stale != null) {
                    log.warn("Dhan orders API rate-limited (HTTP 429). Returning stale cached orders.");
                    return stale;
                }
                throw new IllegalStateException("Dhan orders API returned HTTP 429 (rate limit). Try throttling API calls.", ex);
            }
            if (status == 401 || status == 403) {
                throw new IllegalStateException(
                        "Dhan orders API returned HTTP " + status + " — access token is invalid or expired. Please login again.", ex);
            }
            throw new IllegalStateException("Dhan orders API returned HTTP " + status + ". Response: " + responseBody, ex);
        } catch (RestClientException ex) {
            String cause = buildCauseChain(ex);
            log.error("Error while calling Dhan orders API: {}", cause, ex);
            throw new IllegalStateException("Could not reach Dhan orders API — " + cause, ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse Dhan orders response", ex);
        }
    }

    @Override
    public ExitAllPositionsResponse exitAllPositions() {
        validateConfiguration();

        RestClient restClient = restClientFactory.create();
        String accessToken = resolveAccessToken();
        String clientId = resolveClientId();

        try {
            log.warn("Requesting Dhan to exit all positions/orders via: {}", dhanProperties.getPositionsUrl());
            String responseBody = restClient.delete()
                    .uri(dhanProperties.getPositionsUrl())
                    .headers(headers -> {
                        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
                        headers.set("access-token", accessToken);
                        if (clientId != null && !clientId.isBlank()) {
                            headers.set("client-id", clientId);
                        }
                    })
                    .retrieve()
                    .body(String.class);

            ExitAllPositionsResponse parsed = parseExitAllResponse(responseBody);
            invalidateCaches();
            return parsed;
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String responseBody = ex.getResponseBodyAsString();
            if (status == 401 || status == 403) {
                throw new IllegalStateException(
                        "Dhan exit API returned HTTP " + status + " - access token is invalid or expired. Please login again.", ex);
            }
            throw new IllegalStateException("Dhan exit API returned HTTP " + status + ". Response: " + responseBody, ex);
        } catch (RestClientException ex) {
            String cause = buildCauseChain(ex);
            log.error("Error while calling Dhan exit API: {}", cause, ex);
            throw new IllegalStateException("Could not reach Dhan exit API - " + cause, ex);
        }
    }

    private ExitAllPositionsResponse parseExitAllResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return new ExitAllPositionsResponse("UNKNOWN", "Exit request submitted.");
        }
        try {
            JsonNode n = objectMapper.readTree(responseBody);
            String status = n.path("status").asText("UNKNOWN");
            String message = n.path("message").asText("Exit request submitted.");
            return new ExitAllPositionsResponse(status, message);
        } catch (Exception ex) {
            return new ExitAllPositionsResponse("UNKNOWN", responseBody);
        }
    }

    private void invalidateCaches() {
        cachedTrades.set(null);
        cachedPositions.set(null);
        cachedOrders.set(null);
        tradesCachedAt = 0;
        positionsCachedAt = 0;
        ordersCachedAt = 0;
    }

    private List<OrderResponse> parseOrders(String responseBody) throws IOException {
        JsonNode rootNode = objectMapper.readTree(responseBody);
        JsonNode arr = rootNode.isArray() ? rootNode : rootNode.path("data");
        if (!arr.isArray()) return List.of();

        List<OrderResponse> orders = new ArrayList<>();
        for (JsonNode n : arr) {
            orders.add(new OrderResponse(
                    readText(n, "orderId"),
                    readText(n, "exchangeOrderId"),
                    readText(n, "orderStatus"),
                    readText(n, "transactionType"),
                    readText(n, "exchangeSegment"),
                    readText(n, "productType"),
                    readText(n, "orderType"),
                    readText(n, "validity"),
                    readText(n, "tradingSymbol"),
                    readText(n, "securityId"),
                    (int) readLong(n, "quantity"),
                    (int) readLong(n, "filledQty"),
                    (int) readLong(n, "remainingQuantity"),
                    readDouble(n, "price"),
                    readDouble(n, "triggerPrice"),
                    readDouble(n, "averageTradedPrice"),
                    readText(n, "createTime"),
                    readText(n, "updateTime"),
                    readText(n, "omsErrorDescription")
            ));
        }
        return orders;
    }

    private String buildCauseChain(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable current = ex;
        while (current != null) {
            if (!sb.isEmpty()) {
                sb.append(" → ");
            }
            sb.append(current.getClass().getSimpleName());
            if (current.getMessage() != null) {
                sb.append(": ").append(current.getMessage());
            }
            current = current.getCause();
        }
        return sb.toString();
    }

    private void validateConfiguration() {
        if (resolveAccessToken().isBlank()) {
            throw new IllegalStateException("Dhan access token is missing. Login from UI or set tradewise.dhan.access-token.");
        }
    }

    private String resolveAccessToken() {
        if (dhanCredentialStore.hasAccessToken()) {
            return dhanCredentialStore.getAccessToken();
        }
        return dhanProperties.getAccessToken() == null ? "" : dhanProperties.getAccessToken().trim();
    }

    private String resolveClientId() {
        if (dhanCredentialStore.getClientId() != null && !dhanCredentialStore.getClientId().isBlank()) {
            return dhanCredentialStore.getClientId();
        }
        return dhanProperties.getClientId();
    }

    private List<Trade> parseTrades(String responseBody) throws IOException {
        JsonNode rootNode = objectMapper.readTree(responseBody);
        JsonNode tradesNode = extractTradesNode(rootNode);
        if (!tradesNode.isArray()) {
            return List.of();
        }

        AtomicLong fallbackId = new AtomicLong(1);
        List<Trade> trades = new ArrayList<>();

        for (JsonNode node : tradesNode) {
            long id = readLong(node, "exchangeTradeId", "orderId", "id");
            if (id <= 0) {
                id = fallbackId.getAndIncrement();
            }

            String symbol = readText(node, "tradingSymbol", "securityId", "symbol", "displayName");
            int quantity = (int) readLong(node, "tradedQuantity", "quantity", "filledQty", "fillQty");
            double price = readDouble(node, "tradedPrice", "price", "avgTradedPrice", "averagePrice");
            String side = normalizeSide(readText(node, "transactionType", "side", "orderSide"));
            LocalDateTime tradedAt = parseDateTime(readText(node, "exchangeTime", "tradeTime", "createTime", "orderDateTime"));

            trades.add(new Trade(id, symbol, quantity, price, side, tradedAt));
        }

        return trades.stream()
                .sorted(Comparator.comparing(Trade::tradedAt).reversed())
                .toList();
    }

    private List<PositionPnlResponse> parsePositions(String responseBody) throws IOException {
        JsonNode rootNode = objectMapper.readTree(responseBody);
        JsonNode positionsNode = extractPositionsNode(rootNode);
        if (!positionsNode.isArray()) {
            return List.of();
        }

        List<PositionPnlResponse> positions = new ArrayList<>();

        for (JsonNode node : positionsNode) {
            String symbol = readText(node,
                    "tradingSymbol", "displayName", "securityId", "symbol", "customSymbol");

            int buyQty = (int) readLong(node, "buyQty", "buyQuantity", "totalBuyQty");
            int sellQty = (int) readLong(node, "sellQty", "sellQuantity", "totalSellQty");

            int netQty = (int) readLong(node, "netQty", "netQuantity", "quantity");
            if (netQty == 0 && (buyQty > 0 || sellQty > 0)) {
                netQty = buyQty - sellQty;
            }

            double avgBuy = readDouble(node, "buyAvg", "avgBuyPrice", "averageBuyPrice", "costPrice");
            double avgSell = readDouble(node, "sellAvg", "avgSellPrice", "averageSellPrice");

            double markPrice = readDouble(node, "ltp", "lastPrice", "markPrice", "close", "closePrice");
            if (markPrice <= 0) {
                markPrice = avgSell > 0 ? avgSell : avgBuy;
            }

            double realizedPnl = readDouble(node,
                    "realizedProfit", "realizedPnl", "realisedPnl", "bookedPnl");
            double unrealizedPnl = readDouble(node,
                    "unrealizedProfit", "unrealizedPnl", "unrealisedPnl", "openPnl");

            // Compute fallbacks when Dhan doesn't provide explicit realized/unrealized values.
            if (realizedPnl == 0.0) {
                int closedQty = Math.min(buyQty, sellQty);
                realizedPnl = (avgSell - avgBuy) * closedQty;
            }
            if (unrealizedPnl == 0.0) {
                if (netQty > 0) {
                    unrealizedPnl = (markPrice - avgBuy) * netQty;
                } else if (netQty < 0) {
                    unrealizedPnl = (avgSell - markPrice) * Math.abs(netQty);
                }
            }

            double totalPnl = readDouble(node, "pnl", "totalPnl", "netPnl");
            if (totalPnl == 0.0) {
                totalPnl = realizedPnl + unrealizedPnl;
            }

            positions.add(new PositionPnlResponse(
                    symbol,
                    buyQty,
                    sellQty,
                    netQty,
                    avgBuy,
                    avgSell,
                    realizedPnl,
                    markPrice,
                    unrealizedPnl,
                    totalPnl
            ));
        }

        return positions.stream()
                .sorted(Comparator.comparing(PositionPnlResponse::symbol, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private JsonNode extractTradesNode(JsonNode rootNode) {
        if (rootNode.isArray()) {
            return rootNode;
        }
        JsonNode dataNode = rootNode.path("data");
        if (dataNode.isArray()) {
            return dataNode;
        }
        JsonNode tradesNode = rootNode.path("trades");
        if (tradesNode.isArray()) {
            return tradesNode;
        }
        return objectMapper.createArrayNode();
    }

    private JsonNode extractPositionsNode(JsonNode rootNode) {
        if (rootNode.isArray()) {
            return rootNode;
        }
        JsonNode dataNode = rootNode.path("data");
        if (dataNode.isArray()) {
            return dataNode;
        }
        JsonNode positionsNode = rootNode.path("positions");
        if (positionsNode.isArray()) {
            return positionsNode;
        }
        return objectMapper.createArrayNode();
    }

    private String readText(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                String text = value.asText().trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "UNKNOWN";
    }

    private long readLong(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                if (value.isNumber()) {
                    return value.asLong();
                }
                try {
                    return Long.parseLong(value.asText().trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0L;
    }

    private double readDouble(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                if (value.isNumber()) {
                    return value.asDouble();
                }
                try {
                    return Double.parseDouble(value.asText().trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0.0;
    }

    private String normalizeSide(String rawSide) {
        String normalized = rawSide.toUpperCase(Locale.ROOT);
        if ("B".equals(normalized)) {
            return "BUY";
        }
        if ("S".equals(normalized)) {
            return "SELL";
        }
        return normalized;
    }

    private LocalDateTime parseDateTime(String rawDateTime) {
        try {
            return LocalDateTime.parse(rawDateTime, DateTimeFormatter.ISO_DATE_TIME);
        } catch (DateTimeParseException ignored) {
        }

        try {
            return OffsetDateTime.parse(rawDateTime, DateTimeFormatter.ISO_DATE_TIME).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }

        try {
            return LocalDateTime.parse(rawDateTime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (DateTimeParseException ignored) {
        }

        return LocalDateTime.now();
    }
}

