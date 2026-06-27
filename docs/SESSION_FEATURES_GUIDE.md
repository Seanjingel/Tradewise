# TradeWise - Daily Stats and Trading Lock Guide

## Overview

This document describes the current daily risk controls and trade journaling behavior in TradeWise:

1. **Trade Reason/Journal Box** - Capture reason and notes for each trade
2. **"Today Closed" Lock** - Automatic lock when daily limits are hit
3. **Persistent Daily Stats** - Daily statistics with automatic reset at market open

---

## Feature 1: Trade Reason / Journal Box

### Purpose
Captures the trading rationale for audit trail and post-trade analysis.

### Implementation
- Dialog appears before recording a trade (to be integrated into trading flow)
- Requires a mandatory reason and optional detailed journal notes
- Reason + journal are stored with the trade record

### How to Use
```java
// In your trading code:
String reasonAndJournal = tradeMonitorApp.showTradeReasonDialog();
if (reasonAndJournal != null) {
    // Proceed with trade
    // Store reasonAndJournal with the trade
}
```

### Dialog Fields
1. **Reason for trade** (mandatory)
   - Examples: "Support breakout", "Technical signal", "Mean reversion"
   
2. **Trade Journal / Notes** (optional)
   - Detailed explanation of setup, risk/reward, timeframe, etc.

---

## Feature 2: "Today Closed" Lock

### Purpose
Automatically locks trading when daily limits are breached.

### Triggers
Trading gets locked when ANY of these conditions are met:
- ✋ **Daily Trades Limit Hit** - Reached max number of trades for the day
- ❌ **Daily Loss Limit Hit** - Lost more than configured daily max loss
- ✅ **Daily Profit Limit Hit** - Gained more than configured daily max profit

### Visual Indicator
- Red indicator: 🔒 TODAY CLOSED - [REASON]
- Green indicator: 🟢 Trading Allowed

### Configuration
Set in `application.properties`:
```properties
# Daily limits
tradewise.dhan.max-daily-trades=10       # 0 = unlimited
tradewise.dhan.max-daily-loss=5000.0     # 0 = unlimited, in ₹
tradewise.dhan.max-daily-profit=15000.0  # 0 = unlimited, in ₹
tradewise.dhan.market-open-time=09:15    # IST format HH:mm
```

### API Endpoints
```
GET /api/trades/daily-stats                     # Get current daily stats
POST /api/trades/daily-stats/reset              # Manually reset stats
GET /api/trades/daily-stats/is-trading-allowed  # Check if trading allowed
```

### Example Response
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

---

## Feature 3: Persistent Daily Stats and Reset at Market Open

### Purpose
Tracks daily trading statistics with automatic reset at market open time.

### Statistics Tracked
- Total trades executed today
- Realized P&L
- Unrealized P&L
- Total P&L (Realized + Unrealized)
- Daily limit hit status
- Last reset timestamp

### Automatic Reset
- Resets at **9:15 AM IST** (configurable)
- Fires every day when market opens
- Persists throughout the trading day
- All counters reset to zero at market open

### Configuration
```properties
# Market open time (IST, format HH:mm)
tradewise.dhan.market-open-time=09:15

# Daily limits
tradewise.dhan.max-daily-trades=0           # Max trades before lock
tradewise.dhan.max-daily-loss=0.0           # Max loss ₹ before lock
tradewise.dhan.max-daily-profit=0.0         # Max profit ₹ before lock
```

### Service: DailyStatsService

```java
// Methods available
dailyStatsService.recordTrade(realizedPnl, unrealizedPnl);
dailyStatsService.getDailyStats();
dailyStatsService.isTradingAllowed();
dailyStatsService.getRemainingDailyTrades();
dailyStatsService.getRemainingDailyLossBudget();
dailyStatsService.getRemainingDailyProfitTarget();
dailyStatsService.manualResetDailyStats();
dailyStatsService.lockTrading(reason);
dailyStatsService.unlockTrading();
```

---

## Configuration Example

### application.properties
```properties
# ============ Trading Limits ============
# Session-level kill switch limits (trigger immediate kill switch)
tradewise.dhan.max-trades=10
tradewise.dhan.max-loss=10000.0
tradewise.dhan.max-profit=25000.0

# Daily limits (trigger "today closed" lock)
tradewise.dhan.max-daily-trades=15
tradewise.dhan.max-daily-loss=5000.0
tradewise.dhan.max-daily-profit=15000.0

# Market timing
tradewise.dhan.market-open-time=09:15

# Cooldown after exit all positions (minutes)
tradewise.dhan.cooldown-minutes-after-exit=15
```

---

## UI Components Added

### 1. Daily Stats Label
- Displays current daily statistics and lock status
- Shows in the Risk Monitor section
- Updates every 5 seconds

### 2. Trading Lock Indicator
- Visual indicator of whether trading is allowed
- Red (🔒) when locked with reason
- Green (🟢) when trading is allowed

### 3. Trade Reason Dialog
- Modal dialog for trade entry
- Mandatory reason field
- Optional detailed notes
- Validates before allowing submission

---

## Service Classes

### DailyStatsService
- Manages daily trading statistics
- Tracks limits and locks
- Auto-resets at market open
- Records trades and updates counters

**Methods:**
```java
void recordTrade(double realizedPnl, double unrealizedPnl)
DailyStatsDto getDailyStats()
boolean isTradingAllowed()
int getRemainingDailyTrades()
double getRemainingDailyLossBudget()
double getRemainingDailyProfitTarget()
void lockTrading(String reason)
void unlockTrading()
void manualResetDailyStats()
void checkAndResetIfNewDay()
```

---

## Integration Points

### For developers integrating trade recording:

1. **Before creating a trade**, check if trading is allowed:
```java
if (!dailyStatsService.isTradingAllowed()) {
    showAlert("Trading Locked", "Cannot place trades - daily limit exceeded");
    return;
}
```

2. **After trade execution**, record the trade:
```java
dailyStatsService.recordTrade(realizedPnl, unrealizedPnl);
```

3. **Before showing trade entry form**, show reason dialog:
```java
String reasonAndJournal = tradeMonitorApp.showTradeReasonDialog();
if (reasonAndJournal == null) return; // User cancelled
// Store reasonAndJournal with trade
```

---

## Testing

### Manual Testing Checklist

1. **Daily Stats**
   - [ ] Check daily stats display in UI
   - [ ] Verify trading lock indicator shows correctly
   - [ ] Set low daily limits and execute trades
   - [ ] Verify lock when limit is exceeded
   - [ ] Verify unlock after manual reset

2. **"Today Closed" Lock**
   - [ ] Set max trades = 2
   - [ ] Create 2 trades - lock should activate
   - [ ] Verify UI shows lock indicator
   - [ ] Try to create another trade - should be blocked
   - [ ] Verify manual reset works

---

## Key Files

```
src/main/java/com/tradewise/api/controller/TradeController.java
src/main/java/com/tradewise/service/DailyStatsService.java
src/main/java/com/tradewise/config/DhanProperties.java
src/main/java/com/tradewise/ui/TradeMonitorApp.java
```

---

## Notes

- All features are thread-safe using atomic variables
- Daily reset is automatic based on market open time (IST timezone)
- Daily stats lock is based on configured limits
- All API endpoints return JSON for easy integration
- UI dialogs are non-blocking with async HTTP calls



