package com.surprising.trading.api;

public final class KafkaSymbolKeyValidator {

    private KafkaSymbolKeyValidator() {
    }

    public static void requireMatchingSymbol(String kafkaKey, String payloadSymbol, String payloadType) {
        if (kafkaKey == null || kafkaKey.isBlank()) {
            throw new SymbolKeyMismatchException(payloadType + " Kafka key is required for symbol partitioning");
        }
        if (!kafkaKey.equals(payloadSymbol)) {
            throw new SymbolKeyMismatchException(payloadType + " Kafka key must match payload symbol: key="
                    + kafkaKey + " symbol=" + payloadSymbol);
        }
    }

    public static final class SymbolKeyMismatchException extends IllegalArgumentException {

        public SymbolKeyMismatchException(String message) {
            super(message);
        }
    }
}
