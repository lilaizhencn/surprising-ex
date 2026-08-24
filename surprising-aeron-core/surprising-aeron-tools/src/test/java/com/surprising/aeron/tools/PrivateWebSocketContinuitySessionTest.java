package com.surprising.aeron.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PrivateWebSocketContinuitySessionTest {

    @Test
    void neverSubmitsOrdersBeforeAuthenticationAndEveryPrivateSubscriptionAck() {
        var session = new PrivateWebSocketContinuitySession("client-1", 101, Set.of("orders", "positions"));
        var submissions = new AtomicInteger();

        session.connected();
        assertThatThrownBy(() -> session.submitOrders(120, submissions::incrementAndGet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not subscribed");
        session.authenticated(101, 100);
        session.subscribed("orders", 110);
        assertThatThrownBy(() -> session.submitOrders(120, submissions::incrementAndGet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not subscribed");
        session.subscribed("positions", 115);
        session.submitOrders(120, submissions::incrementAndGet);

        assertThat(submissions).hasValue(1);
        assertThat(session.firstOrderSubmittedAtEpochMillis()).isEqualTo(120);
    }

    @Test
    void badAuthenticationFailsClosedAndIsCounted() {
        var session = new PrivateWebSocketContinuitySession("client-1", 101, Set.of("orders"));
        session.connected();

        assertThatThrownBy(() -> session.authenticated(202, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user mismatch");
        assertThat(session.authenticationFailures()).isEqualTo(1);
        assertThat(session.ready()).isFalse();
    }

    @Test
    void reconnectRequiresFreshAuthenticationAndSubscriptionBeforeMoreOrders() {
        var session = new PrivateWebSocketContinuitySession("client-1", 101, Set.of("orders"));
        session.connected();
        session.authenticated(101, 100);
        session.subscribed("orders", 110);
        session.submitOrders(120, () -> { });
        session.disconnected();
        session.connected();

        assertThatThrownBy(() -> session.submitOrders(130, () -> { }))
                .isInstanceOf(IllegalStateException.class);
        session.authenticated(101, 140);
        session.subscribed("orders", 150);
        session.submitOrders(160, () -> { });

        assertThat(session.reconnects()).isEqualTo(1);
        assertThat(session.ready()).isTrue();
    }
}
