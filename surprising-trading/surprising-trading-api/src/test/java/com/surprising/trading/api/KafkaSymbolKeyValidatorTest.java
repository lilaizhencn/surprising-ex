package com.surprising.trading.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KafkaSymbolKeyValidatorTest {

    @Test
    void acceptsMatchingKafkaKeyAndPayloadSymbol() {
        assertThatCode(() -> KafkaSymbolKeyValidator.requireMatchingSymbol(
                "BTC-USDT", "BTC-USDT", "match trade"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingKafkaKey() {
        assertThatThrownBy(() -> KafkaSymbolKeyValidator.requireMatchingSymbol(
                null, "BTC-USDT", "match trade"))
                .isInstanceOf(KafkaSymbolKeyValidator.SymbolKeyMismatchException.class)
                .hasMessageContaining("Kafka key is required");
    }

    @Test
    void rejectsBlankKafkaKey() {
        assertThatThrownBy(() -> KafkaSymbolKeyValidator.requireMatchingSymbol(
                " ", "BTC-USDT", "match trade"))
                .isInstanceOf(KafkaSymbolKeyValidator.SymbolKeyMismatchException.class)
                .hasMessageContaining("Kafka key is required");
    }

    @Test
    void rejectsMismatchedKafkaKey() {
        assertThatThrownBy(() -> KafkaSymbolKeyValidator.requireMatchingSymbol(
                "ETH-USDT", "BTC-USDT", "match trade"))
                .isInstanceOf(KafkaSymbolKeyValidator.SymbolKeyMismatchException.class)
                .hasMessageContaining("Kafka key must match payload symbol");
    }
}
