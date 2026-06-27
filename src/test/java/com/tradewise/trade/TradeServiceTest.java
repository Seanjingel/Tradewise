package com.tradewise.trade;

import com.tradewise.client.ExternalTradeClient;
import com.tradewise.config.DhanProperties;
import com.tradewise.model.dto.ExitAllPositionsResponse;
import com.tradewise.model.dto.OrderResponse;
import com.tradewise.model.dto.PositionPnlResponse;
import com.tradewise.model.dto.RiskStatusResponse;
import com.tradewise.model.entity.Trade;
import com.tradewise.security.DhanCredentialStore;
import com.tradewise.service.TradeService;
import com.tradewise.service.DailyStatsService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TradeServiceTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 1, 10, 0, 0);

    private final DhanCredentialStore emptyCredentialStore = new DhanCredentialStore();

    // Mock DailyStatsService that allows trading for testing
    private DailyStatsService mockDailyStatsService() {
        DailyStatsService mock = new DailyStatsService(new DhanProperties());
        return mock;
    }

    @Test
    void killSwitchIsInactiveByDefault() {
        TradeService service = new TradeService(emptyClient(), dhanOffProperties(), emptyCredentialStore, mockDailyStatsService());
        assertFalse(service.getKillSwitchStatus().active());
        assertEquals("NONE", service.getKillSwitchStatus().reason());
    }

    @Test
    void manualActivateAndDeactivateKillSwitch() {
        TradeService service = new TradeService(emptyClient(), dhanOffProperties(), emptyCredentialStore, mockDailyStatsService());

        service.activateKillSwitch();
        assertTrue(service.getKillSwitchStatus().active(), "Should be active after manual activate");
        assertEquals("MANUAL", service.getKillSwitchStatus().reason());

        service.deactivateKillSwitch();
        assertFalse(service.getKillSwitchStatus().active(), "Should be inactive after deactivate");
    }

    @Test
    void riskStatusReflectsTradesFromExternalClient() {
        // BUY 10 @ 100 = outflow 1000 ; SELL 10 @ 600 = inflow 6000 ; netPnl = +5000
        TradeService service = new TradeService(sampleClient(), dhanOnProperties(), emptyCredentialStore, mockDailyStatsService());

        RiskStatusResponse risk = service.getRiskStatus();
        assertEquals(2, risk.totalTrades());
        assertEquals(5000.0, risk.netPnl(), 0.001);
        assertFalse(risk.loss10kTriggered());
        assertFalse(risk.profit25kTriggered());
        assertFalse(risk.tradeLimit10Triggered());
        assertEquals(10, risk.configuredMaxTrades());
        assertEquals(10_000.0, risk.configuredMaxLoss(), 0.001);
        assertEquals(25_000.0, risk.configuredMaxProfit(), 0.001);
        assertFalse(risk.cooldownActive());
    }

    @Test
    void riskStatusShowsNoTradesWhenClientReturnsEmpty() {
        TradeService service = new TradeService(emptyClient(), dhanOnProperties(), emptyCredentialStore, mockDailyStatsService());

        RiskStatusResponse risk = service.getRiskStatus();
        assertEquals(0, risk.totalTrades());
        assertEquals(0.0, risk.netPnl(), 0.001);
    }

    @Test
    void positionsDerivedFromTradesWhenExternalPositionsEmpty() {
        // positions endpoint returns empty -> falls back to computing from trades
        TradeService service = new TradeService(sampleClient(), dhanOnProperties(), emptyCredentialStore, mockDailyStatsService());

        List<PositionPnlResponse> positions = service.getPositions();
        assertEquals(1, positions.size());

        PositionPnlResponse pos = positions.get(0);
        assertEquals("AAPL", pos.symbol());
        assertEquals(10, pos.buyQuantity());
        assertEquals(10, pos.sellQuantity());
        assertEquals(0, pos.netQuantity()); // fully closed
        assertEquals(5000.0, pos.realizedPnl(), 0.001);
    }

    @Test
    void summaryReturnsCorrectTradeCount() {
        TradeService service = new TradeService(sampleClient(), dhanOnProperties(), emptyCredentialStore, mockDailyStatsService());
        assertEquals(2, service.getSummary().totalTrades());
    }

    @Test
    void ordersAreReturnedWhenDhanModeEnabled() {
        TradeService service = new TradeService(sampleClientWithOrders(), dhanOnProperties(), emptyCredentialStore, mockDailyStatsService());
        List<OrderResponse> orders = service.getOrders();

        assertEquals(1, orders.size());
        assertEquals("AAPL", orders.get(0).tradingSymbol());
        assertEquals("TRANSIT", orders.get(0).orderStatus());
    }

    @Test
    void ordersAreEmptyWhenDhanModeDisabled() {
        TradeService service = new TradeService(sampleClientWithOrders(), dhanOffProperties(), emptyCredentialStore, mockDailyStatsService());
        assertTrue(service.getOrders().isEmpty());
    }

    @Test
    void exitAllPositionsIsRejectedWhenDhanModeDisabled() {
        TradeService service = new TradeService(sampleClientWithOrders(), dhanOffProperties(), emptyCredentialStore, mockDailyStatsService());
        assertThrows(IllegalStateException.class, service::exitAllPositions);
    }

    @Test
    void exitAllPositionsDelegatesToClientWhenDhanModeEnabled() {
        TradeService service = new TradeService(sampleClientWithOrders(), dhanOnProperties(), emptyCredentialStore, mockDailyStatsService());
        ExitAllPositionsResponse response = service.exitAllPositions();
        assertEquals("SUCCESS", response.status());
    }

    @Test
    void riskStatusUsesConfiguredTradeLimit() {
        DhanProperties properties = dhanOnProperties();
        properties.setMaxTrades(2);
        TradeService service = new TradeService(sampleClient(), properties, emptyCredentialStore, mockDailyStatsService());

        RiskStatusResponse risk = service.getRiskStatus();
        assertTrue(risk.tradeLimit10Triggered());
        assertEquals(2, risk.configuredMaxTrades());
    }

    @Test
    void riskStatusDoesNotTriggerProfitLimitWhenProfitLimitDisabledWithZero() {
        DhanProperties properties = dhanOnProperties();
        properties.setMaxProfit(0.0);

        TradeService service = new TradeService(sampleClient(), properties, emptyCredentialStore, mockDailyStatsService());
        RiskStatusResponse risk = service.getRiskStatus();

        assertFalse(risk.profit25kTriggered());
        assertEquals(0.0, risk.configuredMaxProfit(), 0.001);
    }

    @Test
    void exitAllPositionsStartsCooldownWhenConfigured() {
        DhanProperties properties = dhanOnProperties();
        properties.setCooldownMinutesAfterExit(1);
        TradeService service = new TradeService(sampleClientWithOrders(), properties, emptyCredentialStore, mockDailyStatsService());

        service.exitAllPositions();
        RiskStatusResponse risk = service.getRiskStatus();

        assertTrue(risk.cooldownActive());
        assertTrue(risk.cooldownRemainingSeconds() > 0);
        assertFalse(risk.cooldownEndsAt().isBlank());
    }

    // ---- Fixtures ------------------------------------------------------------

    private ExternalTradeClient emptyClient() {
        return new StubExternalTradeClient(List.of(), List.of(), List.of());
    }

    private ExternalTradeClient sampleClient() {
        return new StubExternalTradeClient(sampleTrades(), List.of(), List.of());
    }

    private ExternalTradeClient sampleClientWithOrders() {
        return new StubExternalTradeClient(sampleTrades(), List.of(), List.of(sampleOrder()));
    }

    private List<Trade> sampleTrades() {
        return List.of(
                new Trade(1L, "AAPL", 10, 100.0, "BUY", BASE_TIME.minusMinutes(10)),
                new Trade(2L, "AAPL", 10, 600.0, "SELL", BASE_TIME.minusMinutes(5))
        );
    }

    private OrderResponse sampleOrder() {
        return new OrderResponse(
                "OID-1",
                "EX-1",
                "TRANSIT",
                "BUY",
                "NSE_EQ",
                "CNC",
                "LIMIT",
                "DAY",
                "AAPL",
                "12345",
                10,
                0,
                10,
                100.0,
                0.0,
                0.0,
                "2026-01-01T09:55:00",
                "2026-01-01T09:55:00",
                ""
        );
    }

    private DhanProperties dhanOffProperties() {
        DhanProperties p = new DhanProperties();
        p.setEnabled(false);
        return p;
    }

    private DhanProperties dhanOnProperties() {
        DhanProperties p = new DhanProperties();
        p.setEnabled(true);
        return p;
    }

    private static class StubExternalTradeClient implements ExternalTradeClient {
        private final List<Trade> trades;
        private final List<PositionPnlResponse> positions;
        private final List<OrderResponse> orders;

        private StubExternalTradeClient(List<Trade> trades,
                                        List<PositionPnlResponse> positions,
                                        List<OrderResponse> orders) {
            this.trades = trades;
            this.positions = positions;
            this.orders = orders;
        }

        @Override
        public List<Trade> fetchTrades() {
            return trades;
        }

        @Override
        public List<PositionPnlResponse> fetchPositions() {
            return positions;
        }

        @Override
        public List<OrderResponse> fetchOrders() {
            return orders;
        }

        @Override
        public ExitAllPositionsResponse exitAllPositions() {
            return new ExitAllPositionsResponse("SUCCESS", "All orders and positions exited successfully");
        }
    }
}
