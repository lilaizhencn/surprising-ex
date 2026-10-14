package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.ReservationKind;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReservationPublicationLeaseTest {
    @Test void publicationFollowsLaneUntilFreezeAndReusesItsCellOnlyAfterConsumerClear() {
        var source = reservation(11, 100);
        var buffer = new RuntimeReservationChangeBuffer();
        buffer.putLive(11, source);
        source.consumeInPlace(20);
        buffer.freezeValues();
        var frozen = buffer.get(11);
        source.consumeInPlace(10);
        assertThat(frozen.reservedUnits()).isEqualTo(80);
        assertThat(source.reservedUnits()).isEqualTo(70);
        assertThat(frozen).isNotSameAs(source);
        buffer.clear();
        assertThat(buffer.get(11)).isNull();
        buffer.putLive(12, reservation(12, 60));
        buffer.freezeValues();
        assertThat(buffer.get(12)).isSameAs(frozen);
        assertThat(buffer.get(12).orderId()).isEqualTo(12);
        assertThat(buffer.get(12).reservedUnits()).isEqualTo(60);
    }

    @Test void copyRejectsAnAuthoritativeReservationAsItsReuseTarget() {
        var source = reservation(11, 100);
        var authoritative = reservation(12, 60);
        assertThatThrownBy(() -> source.copyIntoPublicationSlot(authoritative))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(authoritative.orderId()).isEqualTo(12);
        assertThat(authoritative.reservedUnits()).isEqualTo(60);
    }

    private static ReservationRuntime reservation(long id, long amount) {
        return new ReservationRuntime(id, 7, 0, ReservationKind.DERIVATIVE_MARGIN, 0, amount, 0, 0, 2);
    }
}
