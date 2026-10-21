package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;
import java.util.List;

public record AdminTradeQueryResponse(ProductLine productLine, int count, List<AdminTradeResponse> trades,
        String nextCursor, boolean hasMore, String sort, int limit) {}
