package com.surprising.trading.order.model;

import java.util.OptionalLong;

public interface MarkPriceLookup {

    OptionalLong latestMarkPriceTicks(String symbol, long instrumentChangeId, long maxAgeMs);
}
