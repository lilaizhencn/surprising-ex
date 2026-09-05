package com.surprising.aeron.protocol;

public record CoreBalanceView(String asset, long availableUnits, long lockedUnits) {
}
