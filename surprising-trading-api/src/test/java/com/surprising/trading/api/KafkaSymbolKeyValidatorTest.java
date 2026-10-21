package com.surprising.trading.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KafkaSymbolKeyValidatorTest {

    @Test
    void acceptsMatchingKafkaKeyAndPayloadSymbol() {
        assertThatCode(() -> KafkaSymbolKeyValidator.requireMatchingSymbol(
                "1", "1", "match trade"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingKafkaKey() {
        assertThatThrownBy(() -> KafkaSymbolKeyValidator.requireMatchingSymbol(
                null, "1", "match trade"))
                .isInstanceOf(KafkaSymbolKeyValidator.SymbolKeyMismatchException.class)
                .hasMessageContaining("Kafka key is required");
    }

    @Test
    void rejectsBlankKafkaKey() {
        assertThatThrownBy(() -> KafkaSymbolKeyValidator.requireMatchingSymbol(
                " ", "1", "match trade"))
                .isInstanceOf(KafkaSymbolKeyValidator.SymbolKeyMismatchException.class)
                .hasMessageContaining("Kafka key is required");
    }

    @Test
    void rejectsMismatchedKafkaKey() {
        assertThatThrownBy(() -> KafkaSymbolKeyValidator.requireMatchingSymbol(
                "2", "1", "match trade"))
                .isInstanceOf(KafkaSymbolKeyValidator.SymbolKeyMismatchException.class)
                .hasMessageContaining("Kafka key must match payload instrumentId");
    }
}
