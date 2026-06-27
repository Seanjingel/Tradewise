# 🔗 UI Integration Guide: Trading Lock Enforcement

## How the UI Should Use the Backend Lock

When users try to execute trades through your trading platform or bot, follow this flow:

---

## Step 1: Check If Trading Is Allowed (Optional UI Check)

**Before showing trade buttons or forms:**

```java
// In TradeMonitorApp.java or wherever you handle trade actions

private void checkTradingStatus() {
    HttpRequest request = HttpRequest.newBuilder(
            URI.create(apiBaseUrl + "/api/trades/daily-stats/is-trading-allowed"))
            .GET().build();
    
    httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> Platform.runLater(() -> {
                if (response.statusCode() == 200) {
                    try {
                        var result = objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
                        boolean tradingAllowed = (Boolean) result.get("tradingAllowed");
                        
                        if (!tradingAllowed) {
                            addLog("🔒 Trading is LOCKED - Cannot execute new trades");
                            // Disable trade buttons
                            // tradePlaceButton.setDisable(true);
                            // tradePlaceButton.setText("🔒 Trading Locked");
                        } else {
                            addLog("🟢 Trading is ALLOWED");
                            // Enable trade buttons
                            // tradePlaceButton.setDisable(false);
                            // tradePlaceButton.setText("📝 Place Trade");
                        }
                    } catch (Exception ex) {
                        addLog("❌ Error checking trading status: " + ex.getMessage());
                    }
                }
            }))
            .exceptionally(ex -> {
                addLog("❌ Error checking trading status: " + ex.getMessage());
                return null;
            });
}
```

---

## Step 2: Attempt Trade Execution with Lock Check

**When user clicks "Place Trade" button:**

```java
private void handlePlaceTrade() {
    try {
        // Get trade details from UI
        String symbol = symbolField.getText();
        String side = sideCombo.getValue();  // "BUY" or "SELL"
        int quantity = Integer.parseInt(qtyField.getText());
        double price = Double.parseDouble(priceField.getText());
        
        // First: Check trading lock on backend
        HttpRequest executeRequest = HttpRequest.newBuilder(
                URI.create(apiBaseUrl + "/api/trades/execute"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        
        httpClient.sendAsync(executeRequest, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> Platform.runLater(() -> {
                    if (response.statusCode() == 200) {
                        // Trading is allowed - proceed with actual trade execution
                        executeActualTrade(symbol, side, quantity, price);
                    } else if (response.statusCode() == 403) {
                        // Trading is LOCKED
                        try {
                            var error = objectMapper.readValue(response.body(), 
                                    new TypeReference<Map<String, String>>() {});
                            String reason = (String) error.get("reason");
                            String message = (String) error.get("message");
                            
                            showAlert("🔒 Trading Locked", 
                                    "Cannot execute trade:\n" + message +
                                    "\n\nReason: " + reason);
                            addLog("❌ Trade blocked - " + reason);
                        } catch (Exception ex) {
                            showAlert("Trading Locked", "Cannot execute trades at this time");
                        }
                    } else {
                        showAlert("Error", "Unexpected response: " + response.statusCode());
                    }
                }))
                .exceptionally(ex -> {
                    showAlert("Error", "Failed to check trading lock: " + ex.getMessage());
                    return null;
                });
                
    } catch (NumberFormatException ex) {
        showAlert("Validation Error", "Invalid quantity or price");
    }
}

private void executeActualTrade(String symbol, String side, int quantity, double price) {
    // NOW execute the actual trade with Dhan API
    // This will only reach here if lock check passed
    
    addLog("✅ Trading lock check passed - Executing trade");
    addLog("📝 Placing " + side + " order: " + quantity + " " + symbol + " @ ₹" + price);
    
    // Call your actual Dhan trading API here
    // externalTradeClient.placeTrade(symbol, side, quantity, price);
}
```

---

## Step 3: Handle Lock-Related Responses

```java
private void handleTradingLockedResponse(String responseBody) {
    try {
        var error = objectMapper.readValue(responseBody, 
                new TypeReference<Map<String, Object>>() {});
        
        String errorMsg = (String) error.get("error");
        String reason = (String) error.get("reason");
        String message = (String) error.get("message");
        
        // Map reason to user-friendly message
        String reasonText = switch(reason) {
            case "DAILY_LOSS_LIMIT" -> "Daily loss limit (₹10,000) has been reached ❌";
            case "DAILY_PROFIT_LIMIT" -> "Daily profit target (₹25,000) has been reached ✅";
            case "DAILY_TRADES_LIMIT" -> "Daily trades limit (10 trades) has been reached ⛔";
            case "MANUAL" -> "Trading has been manually locked 🔒";
            default -> reason;
        };
        
        showAlert("🔒 Trading Locked - " + reasonText, 
                message + "\n\nYou can still EXIT existing positions.");
                
        addLog("🔒 TRADING LOCKED: " + reasonText);
        
    } catch (Exception ex) {
        addLog("❌ Error parsing lock response: " + ex.getMessage());
    }
}
```

---

## Step 4: Update UI Status Display

Add this to your refresh cycle (already runs every 5 seconds):

```java
private void updateTradingLockStatus() {
    HttpRequest request = HttpRequest.newBuilder(
            URI.create(apiBaseUrl + "/api/trades/daily-stats"))
            .GET().build();
    
    httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() >= 400) return;
                try {
                    DailyStatsDto stats = objectMapper.readValue(response.body(), DailyStatsDto.class);
                    Platform.runLater(() -> {
                        // Update UI based on lock status
                        if (stats.tradingLocked()) {
                            // Disable trade button
                            tradePlaceButton.setDisable(true);
                            tradePlaceButton.setStyle("-fx-text-fill: #d32f2f; -fx-background-color: #ffebee;");
                            tradePlaceButton.setText("🔒 " + stats.lockedReason());
                            
                            addLog("⚠️ Trading is LOCKED: " + stats.lockedReason());
                        } else {
                            // Enable trade button
                            tradePlaceButton.setDisable(false);
                            tradePlaceButton.setStyle("-fx-text-fill: #1b5e20; -fx-background-color: #e8f5e9;");
                            tradePlaceButton.setText("📝 Place Trade");
                        }
                    });
                } catch (Exception ignored) {}
            })
            .exceptionally(ex -> null);
}
```

---

## Complete Example: Trade Button Click Handler

```java
@FXML
private void onTradePlaceButtonClicked() {
    // Step 1: Validate inputs
    String symbol = symbolField.getText().trim();
    String side = sideCombo.getValue();
    
    if (symbol.isEmpty()) {
        showAlert("Validation Error", "Please enter a symbol");
        return;
    }
    
    if (side == null) {
        showAlert("Validation Error", "Please select BUY or SELL");
        return;
    }
    
    try {
        int quantity = Integer.parseInt(qtyField.getText());
        double price = Double.parseDouble(priceField.getText());
        
        if (quantity <= 0 || price <= 0) {
            throw new NumberFormatException("Quantity and price must be positive");
        }
        
        // Step 2: Check trading lock on backend FIRST
        checkTradingLockAndExecute(symbol, side, quantity, price);
        
    } catch (NumberFormatException ex) {
        showAlert("Validation Error", "Invalid quantity or price: " + ex.getMessage());
    }
}

private void checkTradingLockAndExecute(
        String symbol, String side, int quantity, double price) {
    
    // Execute check on backend
    HttpRequest checkRequest = HttpRequest.newBuilder(
            URI.create(apiBaseUrl + "/api/trades/execute"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    
    httpClient.sendAsync(checkRequest, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> Platform.runLater(() -> {
                if (response.statusCode() == 200) {
                    // ✅ Passed lock check - execute trade
                    showConfirmationAndExecute(symbol, side, quantity, price);
                } else if (response.statusCode() == 403) {
                    // ❌ Trading is locked
                    handleTradingLockedResponse(response.body());
                } else {
                    showAlert("Error", "HTTP " + response.statusCode());
                }
            }))
            .exceptionally(ex -> {
                showAlert("Error", "Failed to check trading lock: " + ex.getMessage());
                return null;
            });
}

private void showConfirmationAndExecute(
        String symbol, String side, int quantity, double price) {
    
    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
    confirm.setTitle("Confirm Trade");
    confirm.setHeaderText("Place " + side + " order?");
    confirm.setContentText(
            "Symbol: " + symbol + "\n" +
            "Side: " + side + "\n" +
            "Quantity: " + quantity + "\n" +
            "Price: ₹" + String.format("%,.2f", price) + "\n" +
            "Notional: ₹" + String.format("%,.2f", quantity * price));
    
    if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
        // User confirmed - now actually place the trade
        executeTrade(symbol, side, quantity, price);
    }
}

private void executeTrade(String symbol, String side, int quantity, double price) {
    addLog("📝 Executing " + side + " trade: " + quantity + " " + symbol + " @ ₹" + price);
    
    // Call your Dhan trading API
    // This will make the actual trade with your broker
    // externalTradeClient.placeTrade(symbol, side, quantity, price);
    
    // Update UI
    addLog("✅ Trade order submitted");
    refreshAll();  // Refresh to see updated positions/orders
}
```

---

## Frontend Response Handling

**When trading lock blocks a trade:**

```json
HTTP 403 FORBIDDEN

{
  "error": "Trading is LOCKED",
  "reason": "DAILY_LOSS_LIMIT",
  "tradingAllowed": false,
  "message": "Trading is LOCKED. Reason: DAILY_LOSS_LIMIT. Cannot execute trades until limits are reset."
}
```

**Display to user:**

```
┌─────────────────────────────────────────┐
│ 🔒 Trading Locked                       │
├─────────────────────────────────────────┤
│ Cannot execute trade                    │
│                                         │
│ Reason: DAILY_LOSS_LIMIT ❌             │
│                                         │
│ Daily loss limit (₹10,000) has been     │
│ reached. Trading is locked for the rest │
│ of the trading day.                     │
│                                         │
│ You can still EXIT existing positions.  │
│ Trading resets at 9:15 AM tomorrow.     │
│                                         │
│                  [ OK ]                 │
└─────────────────────────────────────────┘
```

---

## Button States

### Trading Allowed
```
[📝 Place Trade]  ← ENABLED, green background
```

### Trading Locked
```
[🔒 Loss Limit Hit]  ← DISABLED, red background
```

### Trading Locked (Profit Target)
```
[✅ Profit Target]  ← DISABLED, blue background
```

---

## Log Messages

```
Good logs:
✅ Trading lock check passed - Executing trade
✅ Trade order submitted
🟢 Trading is ALLOWED

Bad logs:
❌ Trade blocked - DAILY_LOSS_LIMIT
🔒 TRADING LOCKED: Daily loss limit has been reached
⚠️ Trading is LOCKED: DAILY_TRADES_LIMIT
```

---

## Best Practices

1. **Always check lock BEFORE attempting trade**
   ✅ Good: Check lock → If allowed → Execute trade
   ❌ Bad: Execute trade → Find out it's locked

2. **Show clear reason to user**
   ✅ Good: "Loss limit ₹10,000 reached - Trading locked"
   ❌ Bad: "HTTP 403 Forbidden"

3. **Allow exit positions even when locked**
   ✅ Show different button for "Exit All Positions"
   ✅ This button works even when trading is locked

4. **Refresh status frequently**
   ✅ Good: Every 5 seconds check if limits changed
   ❌ Bad: Only check once at startup

5. **Log all lock events**
   ✅ Add to activity log for audit trail
   ✅ Shows trader what triggered the lock

---

**Last Updated:** 2026-06-26  
**Status:** Ready for Integration


