package com.tradewise.client;

import com.tradewise.model.dto.ExitAllPositionsResponse;
import com.tradewise.model.dto.OrderResponse;
import com.tradewise.model.dto.PositionPnlResponse;
import com.tradewise.model.entity.Trade;

import java.util.List;

/**
 * Interface for fetching trades from external sources.
 */
public interface ExternalTradeClient {
    List<Trade> fetchTrades();

    List<PositionPnlResponse> fetchPositions();

    List<OrderResponse> fetchOrders();

    ExitAllPositionsResponse exitAllPositions();
}
