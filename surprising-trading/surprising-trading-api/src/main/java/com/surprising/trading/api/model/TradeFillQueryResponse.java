package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;
import java.util.List;

public record TradeFillQueryResponse(long userId, ProductLine productLine, int count,
        List<TradeFillResponse> trades, String nextCursor, boolean hasMore, String sort, int limit) {}
