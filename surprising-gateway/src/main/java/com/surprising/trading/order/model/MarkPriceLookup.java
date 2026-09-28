package com.surprising.trading.order.model;

import java.util.OptionalLong;

public interface MarkPriceLookup {

    OptionalLong latestMarkPriceTicks(String instrumentId, long instrumentChangeId, long maxAgeMs);
}
