package com.surprising.aeron.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProductionChainContinuityAuditorTest {

    private static final String TOPIC = "surprising.linear-perp.core.events.v1";
    private static final long USER_ID = 101L;

    @Test
    void rejectsCrossLayerMutationAndMissingRangeBoundaries() {
        var report = new ProductionChainContinuityAuditor().audit(
                new ProductionChainContinuityAuditor.Scope(10, 12, 12),
                List.of(
                        event(ProductionChainContinuityAuditor.Layer.CORE, "event-11", 11, "core", null, null),
                        event(ProductionChainContinuityAuditor.Layer.KAFKA, "event-11", 11, "mutated", null, 40L),
                        event(ProductionChainContinuityAuditor.Layer.POSTGRES, "event-11", 11, "core", null, null)),
                List.of());

        assertThat(report.coreMissing()).isEqualTo(2);
        assertThat(report.mutations()).isEqualTo(1);
        assertThat(report.passed()).isFalse();
    }

    @Test
    void separatesLegalRedeliveryFromPermanentWebSocketLoss() {
        var checkpoint = new ProductionChainContinuityAuditor.ClientCheckpoint(
                "client-1", TOPIC, USER_ID, 2, 100, 110, 120, 1, 0, 0);
        var report = new ProductionChainContinuityAuditor().audit(
                new ProductionChainContinuityAuditor.Scope(1, 2, 2),
                List.of(
                        event(ProductionChainContinuityAuditor.Layer.CORE, "event-1", 1, "one", null, null),
                        event(ProductionChainContinuityAuditor.Layer.CORE, "event-2", 2, "two", null, null),
                        event(ProductionChainContinuityAuditor.Layer.KAFKA, "event-1", 1, "one", null, 40L),
                        event(ProductionChainContinuityAuditor.Layer.KAFKA, "event-1", 1, "one", null, 40L),
                        event(ProductionChainContinuityAuditor.Layer.KAFKA, "event-2", 2, "two", null, 41L),
                        event(ProductionChainContinuityAuditor.Layer.POSTGRES, "event-1", 1, "one", null, null),
                        event(ProductionChainContinuityAuditor.Layer.POSTGRES, "event-2", 2, "two", null, null),
                        event(ProductionChainContinuityAuditor.Layer.WEBSOCKET, "event-1", 1, "one", "client-1", null),
                        event(ProductionChainContinuityAuditor.Layer.WEBSOCKET, "event-1", 1, "one", "client-1", null)),
                List.of(checkpoint));

        assertThat(report.kafkaRedeliveries()).isEqualTo(1);
        assertThat(report.webSocketRedeliveries()).isEqualTo(1);
        assertThat(report.permanentWebSocketLosses()).isEqualTo(1);
        assertThat(report.passed()).isFalse();
    }

    @Test
    void rejectsOrdersBeforePrivateSubscriptionAndStaleCatchUp() {
        var checkpoint = new ProductionChainContinuityAuditor.ClientCheckpoint(
                "client-1", TOPIC, USER_ID, 2, 100, 130, 120, 1, 0, 0);
        var report = new ProductionChainContinuityAuditor().audit(
                new ProductionChainContinuityAuditor.Scope(1, 2, 2),
                completeChain(false),
                List.of(checkpoint));

        assertThat(report.subscriptionOrderViolations()).isEqualTo(1);
        assertThat(report.staleCatchUps()).isEqualTo(1);
        assertThat(report.passed()).isFalse();
    }

    @Test
    void passesCompleteChainWithSubscriptionBeforeOrdersAndReconnectReplay() {
        var checkpoint = new ProductionChainContinuityAuditor.ClientCheckpoint(
                "client-1", TOPIC, USER_ID, 2, 100, 110, 120, 1, 0, 0);
        var report = new ProductionChainContinuityAuditor().audit(
                new ProductionChainContinuityAuditor.Scope(1, 2, 2),
                completeChain(true),
                List.of(checkpoint));

        assertThat(report.passed()).isTrue();
        assertThat(report.reconnects()).isEqualTo(1);
        assertThat(report.kafkaRedeliveries()).isEqualTo(1);
        assertThat(report.webSocketRedeliveries()).isEqualTo(1);
    }

    @Test
    void doesNotClaimFullChainSuccessWithoutASubscribedClientCheckpoint() {
        var report = new ProductionChainContinuityAuditor().audit(
                new ProductionChainContinuityAuditor.Scope(1, 2, 2), completeChain(true), List.of());

        assertThat(report.missingClientCheckpoints()).isEqualTo(1);
        assertThat(report.passed()).isFalse();
    }

    @Test
    void rejectsAnUncountableSequenceRangeInsteadOfHanging() {
        assertThatThrownBy(() -> new ProductionChainContinuityAuditor.Scope(0, Long.MAX_VALUE, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void rejectsAWebSocketFactThatHasNoCanonicalCoreEvent() {
        var observations = new java.util.ArrayList<>(completeChain(true));
        observations.add(event(ProductionChainContinuityAuditor.Layer.WEBSOCKET,
                "invented-event", 2, "invented", "client-1", null));
        var checkpoint = new ProductionChainContinuityAuditor.ClientCheckpoint(
                "client-1", TOPIC, USER_ID, 2, 100, 110, 120, 0, 0, 0);

        var report = new ProductionChainContinuityAuditor().audit(
                new ProductionChainContinuityAuditor.Scope(1, 2, 2), observations, List.of(checkpoint));

        assertThat(report.unexpectedFacts()).isEqualTo(1);
        assertThat(report.passed()).isFalse();
    }

    private static List<ProductionChainContinuityAuditor.Observation> completeChain(boolean includeSecondWebSocket) {
        var events = new java.util.ArrayList<ProductionChainContinuityAuditor.Observation>();
        events.add(event(ProductionChainContinuityAuditor.Layer.CORE, "event-1", 1, "one", null, null));
        events.add(event(ProductionChainContinuityAuditor.Layer.CORE, "event-2", 2, "two", null, null));
        events.add(event(ProductionChainContinuityAuditor.Layer.KAFKA, "event-1", 1, "one", null, 40L));
        events.add(event(ProductionChainContinuityAuditor.Layer.KAFKA, "event-1", 1, "one", null, 40L));
        events.add(event(ProductionChainContinuityAuditor.Layer.KAFKA, "event-2", 2, "two", null, 41L));
        events.add(event(ProductionChainContinuityAuditor.Layer.POSTGRES, "event-1", 1, "one", null, null));
        events.add(event(ProductionChainContinuityAuditor.Layer.POSTGRES, "event-2", 2, "two", null, null));
        events.add(event(ProductionChainContinuityAuditor.Layer.WEBSOCKET, "event-1", 1, "one", "client-1", null));
        events.add(event(ProductionChainContinuityAuditor.Layer.WEBSOCKET, "event-1", 1, "one", "client-1", null));
        if (includeSecondWebSocket) {
            events.add(event(ProductionChainContinuityAuditor.Layer.WEBSOCKET, "event-2", 2, "two", "client-1", null));
        }
        return List.copyOf(events);
    }

    private static ProductionChainContinuityAuditor.Observation event(
            ProductionChainContinuityAuditor.Layer layer,
            String eventId,
            long sequence,
            String payloadHash,
            String clientId,
            Long kafkaOffset) {
        return new ProductionChainContinuityAuditor.Observation(
                layer, eventId, sequence, TOPIC, USER_ID, payloadHash, clientId, 0, kafkaOffset);
    }
}
