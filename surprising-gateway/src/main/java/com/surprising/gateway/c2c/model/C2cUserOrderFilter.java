package com.surprising.gateway.c2c.model;

import java.util.UUID;

public record C2cUserOrderFilter(Scope scope, UUID marketId, Side side, UUID orderId, int limit, int offset) {
    public enum Scope { ONGOING, APPEALED, ENDED, ALL }
    public enum Side { BUY, SELL, ALL }
}
