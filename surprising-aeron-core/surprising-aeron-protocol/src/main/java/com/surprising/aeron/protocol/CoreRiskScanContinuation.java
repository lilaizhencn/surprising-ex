package com.surprising.aeron.protocol;

import java.nio.charset.StandardCharsets;

public record CoreRiskScanContinuation(String instrumentId, long priceSequence, long lastUserId) {
    private static final int MAX_SYMBOL_BYTES = 64;

    public CoreRiskScanContinuation {
        if (instrumentId == null || instrumentId.isBlank()
                || instrumentId.getBytes(StandardCharsets.UTF_8).length > MAX_SYMBOL_BYTES
                || priceSequence < 0 || lastUserId < 0) {
            throw new IllegalArgumentException("invalid risk scan continuation");
        }
    }

    public boolean exact() {
        return priceSequence > 0 && !"-".equals(instrumentId);
    }

}
