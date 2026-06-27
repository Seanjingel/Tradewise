package com.tradewise.trade;

import com.tradewise.config.DhanProperties;
import com.tradewise.service.DailyStatsService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyStatsServiceTest {

    @Test
    void trailingProfitAllowsRunBeyond25kAndLocksOnDropTo20k() {
        DhanProperties p = new DhanProperties();
        p.setMaxDailyProfit(0.0); // hard profit cap disabled in trailing mode
        p.setProfitTrailingActivationLevel(25_000.0);
        p.setProfitTrailingDrawdown(5_000.0);
        p.setAutoExitOnLimit(false); // no broker call in unit test

        DailyStatsService service = new DailyStatsService(p);

        // First move to 25k should NOT lock.
        service.updateLivePnl(25_000.0);
        assertTrue(service.isTradingAllowed(), "Should keep trading at 25k in trailing mode");

        // Move further up; peak should track.
        service.updateLivePnl(30_000.0);
        assertTrue(service.isTradingAllowed(), "Should keep trading while making higher peak");

        // Drop to 20k = 10k drawdown from 30k peak, so trailing stop must lock.
        service.updateLivePnl(20_000.0);
        assertFalse(service.isTradingAllowed(), "Should lock when profit falls to 20k after 30k peak");
        assertEquals("TRAILING_PROFIT_STOP", service.getLockedReason());
    }

    @Test
    void hardDailyProfitStillWorksWhenTrailingNotConfigured() {
        DhanProperties p = new DhanProperties();
        p.setMaxDailyProfit(25_000.0);
        p.setProfitTrailingActivationLevel(0.0);
        p.setProfitTrailingDrawdown(0.0);
        p.setAutoExitOnLimit(false);

        DailyStatsService service = new DailyStatsService(p);

        service.updateLivePnl(25_000.0);
        assertFalse(service.isTradingAllowed(), "Without trailing config, hard 25k profit cap should lock");
        assertEquals("DAILY_PROFIT_LIMIT", service.getLockedReason());
    }

    @Test
    void syncTradeCountFromFeedTriggersDailyTradeLimit() {
        DhanProperties p = new DhanProperties();
        p.setMaxDailyTrades(2);

        DailyStatsService service = new DailyStatsService(p);

        service.syncTradeCountFromFeed(1);
        assertTrue(service.isTradingAllowed());

        service.syncTradeCountFromFeed(2);
        assertFalse(service.isTradingAllowed());
        assertEquals("DAILY_TRADES_LIMIT", service.getLockedReason());
    }

    @Test
    void syncTradeCountFromFeedIsMonotonicWithinDay() {
        DhanProperties p = new DhanProperties();
        p.setMaxDailyTrades(10);

        DailyStatsService service = new DailyStatsService(p);

        service.syncTradeCountFromFeed(4);
        service.syncTradeCountFromFeed(2); // transient lower broker response should not reduce counter

        assertEquals(4, service.getDailyStats().tradesCount());
    }
}

