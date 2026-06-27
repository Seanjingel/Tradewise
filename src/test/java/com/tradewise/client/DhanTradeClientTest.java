package com.tradewise.client;

import com.tradewise.config.DhanProperties;
import com.tradewise.model.dto.PositionPnlResponse;
import com.tradewise.security.DhanCredentialStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DhanTradeClientTest {

    @Test
    void parsePositionsSupportsDocumentedRealizedProfitFields() throws Exception {
        DhanProperties properties = new DhanProperties();
        DhanTradeClient client = new DhanTradeClient(
                properties,
                new DhanCredentialStore(),
                new DhanRestClientFactory(new DhanHttpClientFactory(properties))
        );

        String json = """
                [
                  {
                    "dhanClientId": "1000000009",
                    "tradingSymbol": "TCS",
                    "securityId": "11536",
                    "positionType": "LONG",
                    "exchangeSegment": "NSE_EQ",
                    "productType": "CNC",
                    "buyAvg": 3345.8,
                    "buyQty": 40,
                    "costPrice": 3215.0,
                    "sellAvg": 0.0,
                    "sellQty": 0,
                    "netQty": 40,
                    "realizedProfit": 0.0,
                    "unrealizedProfit": 6122.0,
                    "rbiReferenceRate": 1.0,
                    "multiplier": 1,
                    "carryForwardBuyQty": 0,
                    "carryForwardSellQty": 0,
                    "carryForwardBuyValue": 0.0,
                    "carryForwardSellValue": 0.0,
                    "dayBuyQty": 40,
                    "daySellQty": 0,
                    "dayBuyValue": 133832.0,
                    "daySellValue": 0.0,
                    "drvExpiryDate": "0001-01-01",
                    "drvOptionType": null,
                    "drvStrikePrice": 0.0,
                    "crossCurrency": false
                  }
                ]
                """;

        Method parsePositions = DhanTradeClient.class.getDeclaredMethod("parsePositions", String.class);
        parsePositions.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<PositionPnlResponse> positions = (List<PositionPnlResponse>) parsePositions.invoke(client, json);

        assertEquals(1, positions.size());
        PositionPnlResponse position = positions.get(0);
        assertEquals("TCS", position.symbol());
        assertEquals(40, position.buyQuantity());
        assertEquals(0, position.sellQuantity());
        assertEquals(40, position.netQuantity());
        assertEquals(3345.8, position.averageBuyPrice(), 0.001);
        assertEquals(0.0, position.realizedPnl(), 0.001);
        assertEquals(6122.0, position.unrealizedPnl(), 0.001);
        assertEquals(6122.0, position.totalPnl(), 0.001);
    }
}

