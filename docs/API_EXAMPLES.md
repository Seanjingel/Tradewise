# TradeWise API Examples

## Base URL
```
http://localhost:9092
```

---

## Daily Stats Endpoints

### Get Daily Statistics
```bash
GET /api/trades/daily-stats
```

**Response:**
```json
{
  "date": "2026-06-26",
  "tradesCount": 5,
  "realizedPnl": 2500.00,
  "unrealizedPnl": 1200.00,
  "totalPnl": 3700.00,
  "dailyLossLimitHit": false,
  "dailyProfitLimitHit": false,
  "dailyTradesLimitHit": false,
  "tradingLocked": false,
  "lockedReason": "NONE",
  "lastResetAt": 1719332400000,
  "marketOpenAt": 1719332400000
}
```

**When Trading Locked (Loss Limit):**
```json
{
  "date": "2026-06-26",
  "tradesCount": 8,
  "realizedPnl": -5200.00,
  "unrealizedPnl": -300.00,
  "totalPnl": -5500.00,
  "dailyLossLimitHit": true,
  "dailyProfitLimitHit": false,
  "dailyTradesLimitHit": false,
  "tradingLocked": true,
  "lockedReason": "DAILY_LOSS_LIMIT",
  "lastResetAt": 1719332400000,
  "marketOpenAt": 1719332400000
}
```

---

### Check If Trading is Allowed
```bash
GET /api/trades/daily-stats/is-trading-allowed
```

**Response (Trading Allowed):**
```json
{
  "tradingAllowed": true,
  "remainingTrades": 5,
  "remainingLossBudget": 1300.0,
  "remainingProfitTarget": 11300.0
}
```

**Response (Trading Locked):**
```json
{
  "tradingAllowed": false,
  "remainingTrades": 0,
  "remainingLossBudget": 0.0,
  "remainingProfitTarget": 11300.0
}
```

---

### Manually Reset Daily Stats
```bash
POST /api/trades/daily-stats/reset
```

**Response:**
```json
{
  "date": "2026-06-26",
  "tradesCount": 0,
  "realizedPnl": 0.0,
  "unrealizedPnl": 0.0,
  "totalPnl": 0.0,
  "dailyLossLimitHit": false,
  "dailyProfitLimitHit": false,
  "dailyTradesLimitHit": false,
  "tradingLocked": false,
  "lockedReason": "NONE",
  "lastResetAt": 1719360120000,
  "marketOpenAt": 1719332400000
}
```

---

## Java Integration Examples

### Inject Services
```java
@RestController
public class MyTradingController {
    
    private final DailyStatsService dailyStatsService;
    private final TradeMonitorApp tradeMonitorApp;
    
    public MyTradingController(
        DailyStatsService dailyStatsService,
        TradeMonitorApp tradeMonitorApp) {
        this.dailyStatsService = dailyStatsService;
        this.tradeMonitorApp = tradeMonitorApp;
    }
    
    // ... rest of class
}
```

---

### Check Daily Limits Before Trading
```java
@PostMapping("/place-trade")
public ResponseEntity<?> placeTrade(@RequestBody TradeRequest request) {
    // Check if trading is allowed
    if (!dailyStatsService.isTradingAllowed()) {
        DailyStatsDto stats = dailyStatsService.getDailyStats();
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(Map.of(
                "error", "Trading locked: " + stats.lockedReason(),
                "dailyStats", stats
            ));
    }
    
    // Check remaining budget
    double remainingBudget = dailyStatsService.getRemainingDailyLossBudget();
    if (remainingBudget < 500) {
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(Map.of(
                "warning", "Low remaining loss budget: ₹" + remainingBudget
            ));
    }
    
    // ... proceed with trade execution
    return ResponseEntity.ok("Trade placed");
}
```

---

### Record Trade With Daily Stats
```java
@PostMapping("/record-trade")
public ResponseEntity<?> recordTrade(@RequestBody TradeRequest request) {
    // Place the trade
    Trade trade = externalTradeClient.executeTrade(
        request.getSymbol(),
        request.getQuantity(),
        request.getPrice(),
        request.getSide(),
        request.getReasonAndJournal()
    );
    
    // Calculate P&L
    double realizedPnl = calculateRealizedPnl(trade);
    double unrealizedPnl = calculateUnrealizedPnl(trade);
    
    // Record in daily stats
    dailyStatsService.recordTrade(realizedPnl, unrealizedPnl);
    
    // Get updated daily stats
    DailyStatsDto stats = dailyStatsService.getDailyStats();
    
    return ResponseEntity.ok(Map.of(
        "trade", trade,
        "dailyStats", stats
    ));
}
```

---

### Get Daily Report
```java
@GetMapping("/daily-report")
public ResponseEntity<?> getDailyReport() {
    DailyStatsDto stats = dailyStatsService.getDailyStats();
    
    return ResponseEntity.ok(Map.of(
        "date", stats.date(),
        "summary", Map.of(
            "totalTrades", stats.tradesCount(),
            "realizedPnL", String.format("₹%,.2f", stats.realizedPnl()),
            "unrealizedPnL", String.format("₹%,.2f", stats.unrealizedPnl()),
            "totalPnL", String.format("₹%,.2f", stats.totalPnl())
        ),
        "limits", Map.of(
            "remainingTrades", dailyStatsService.getRemainingDailyTrades(),
            "remainingLossBudget", String.format("₹%,.2f", dailyStatsService.getRemainingDailyLossBudget()),
            "remainingProfitTarget", String.format("₹%,.2f", dailyStatsService.getRemainingDailyProfitTarget())
        ),
        "lockStatus", Map.of(
            "tradingAllowed", dailyStatsService.isTradingAllowed(),
            "lockedReason", stats.lockedReason()
        )
    ));
}
```

---

### JavaFX UI Integration
```java
// In your trading UI event handler
private void handleTradeButtonClick() {
    // Show trade reason dialog
    String reasonAndJournal = tradeMonitorApp.showTradeReasonDialog();
    if (reasonAndJournal == null) {
        return; // User cancelled
    }
    
    // Check if trading is allowed
    if (!dailyStatsService.isTradingAllowed()) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Trading Locked");
        DailyStatsDto stats = dailyStatsService.getDailyStats();
        alert.setContentText("Cannot trade - " + stats.lockedReason());
        alert.showAndWait();
        return;
    }
    
    // Execute trade with reason
    executeTrade(reasonAndJournal);
}
```

---

## Configuration Examples

### Conservative Settings
```properties
# Limited daily trading
tradewise.dhan.max-daily-trades=5
tradewise.dhan.max-daily-loss=2000.0
tradewise.dhan.max-daily-profit=5000.0

# Stop early to preserve capital
tradewise.dhan.max-trades=3
tradewise.dhan.max-loss=1000.0
tradewise.dhan.max-profit=2500.0
```

### Moderate Settings
```properties
# Standard daily trading
tradewise.dhan.max-daily-trades=15
tradewise.dhan.max-daily-loss=5000.0
tradewise.dhan.max-daily-profit=10000.0

# Standard kill switch
tradewise.dhan.max-trades=10
tradewise.dhan.max-loss=10000.0
tradewise.dhan.max-profit=25000.0
```

### Aggressive Settings
```properties
# High daily limits
tradewise.dhan.max-daily-trades=50
tradewise.dhan.max-daily-loss=20000.0
tradewise.dhan.max-daily-profit=50000.0

# High kill switch
tradewise.dhan.max-trades=30
tradewise.dhan.max-loss=50000.0
tradewise.dhan.max-profit=100000.0
```

---

## Error Responses


### Trading Locked
```json
{
  "error": "Trading locked: DAILY_LOSS_LIMIT",
  "remainingBudget": 0.0
}
```

### Invalid Request
```json
{
  "error": "Reason for trade is required"
}
```



