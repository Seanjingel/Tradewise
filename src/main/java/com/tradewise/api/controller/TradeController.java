package com.tradewise.api.controller;

import com.tradewise.model.dto.ExitAllPositionsResponse;
import com.tradewise.model.dto.KillSwitchStatusResponse;
import com.tradewise.model.dto.OrderResponse;
import com.tradewise.model.dto.PositionPnlResponse;
import com.tradewise.model.dto.RiskStatusResponse;
import com.tradewise.model.dto.TradeSummaryResponse;
import com.tradewise.model.dto.DailyStatsDto;
import com.tradewise.model.entity.Trade;
import com.tradewise.service.TradeService;
import com.tradewise.service.DailyStatsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST API controller for trade management.
 */
@RestController
@RequestMapping("/api/trades")
public class TradeController {

    private final TradeService tradeService;
    private final DailyStatsService dailyStatsService;

    public TradeController(TradeService tradeService, DailyStatsService dailyStatsService) {
        this.tradeService = tradeService;
        this.dailyStatsService = dailyStatsService;
    }

    @GetMapping
    public List<Trade> getTrades() {
        return tradeService.getTrades();
    }

    @GetMapping("/summary")
    public TradeSummaryResponse getSummary() {
        return tradeService.getSummary();
    }


    @GetMapping("/kill-switch")
    public KillSwitchStatusResponse getKillSwitchStatus() {
        return tradeService.getKillSwitchStatus();
    }

    @PostMapping("/kill-switch/activate")
    public KillSwitchStatusResponse activateKillSwitch() {
        return tradeService.activateKillSwitch();
    }

    @PostMapping("/kill-switch/deactivate")
    public KillSwitchStatusResponse deactivateKillSwitch() {
        return tradeService.deactivateKillSwitch();
    }

    @GetMapping("/risk-status")
    public RiskStatusResponse getRiskStatus() {
        return tradeService.getRiskStatus();
    }

    @GetMapping("/positions")
    public List<PositionPnlResponse> getPositions() {
        return tradeService.getPositions();
    }

    @GetMapping("/orders")
    public List<OrderResponse> getOrders() {
        return tradeService.getOrders();
    }

    @DeleteMapping("/positions/exit-all")
    public ResponseEntity<?> exitAllPositions() {
        try {
            // Allow exit-all even when trading is locked (exit is always allowed)
            // This lets users recover from bad situations
            ExitAllPositionsResponse response = tradeService.exitAllPositions();
            return ResponseEntity.ok(response);
        } catch (IllegalStateException ex) {
            return ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body(Map.of(
                            "error", "Exit positions failed",
                            "message", ex.getMessage()
                    ));
        }
    }

    @GetMapping("/daily-stats")
    public DailyStatsDto getDailyStats() {
        return dailyStatsService.getDailyStats();
    }

    @PostMapping("/daily-stats/reset")
    public DailyStatsDto resetDailyStats() {
        dailyStatsService.manualResetDailyStats();
        return dailyStatsService.getDailyStats();
    }

    @GetMapping("/daily-stats/is-trading-allowed")
    public Map<String, Object> isTradingAllowed() {
        return Map.of(
                "tradingAllowed", dailyStatsService.isTradingAllowed(),
                "remainingTrades", dailyStatsService.getRemainingDailyTrades(),
                "remainingLossBudget", dailyStatsService.getRemainingDailyLossBudget(),
                "remainingProfitTarget", dailyStatsService.getRemainingDailyProfitTarget()
        );
    }


    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> handleValidationError(RuntimeException exception) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", exception.getMessage()));
    }
}
