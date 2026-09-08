package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.AssetBalance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.ReservationKind;
import org.junit.jupiter.api.Test;

class CoreValueValidationTest {

    @Test
    void reservationAmountTransitionsPreserveImmutableValidatedIdentityAndValueSemantics() {
        var original = OrderReservation.create(9, " btc-usdt ", 3,
                ReservationKind.SPOT_ASSET, " usdt ", 100, 10);
        var consumed = original.consume(30);
        var released = consumed.release(20);
        var resized = released.replaceReservedUnits(80);
        var closed = resized.releaseAll();
        for (var value : new OrderReservation[]{consumed, released, resized, closed}) {
            assertThat(value.symbol()).isSameAs(original.symbol());
            assertThat(value.asset()).isSameAs(original.asset());
            var reconstructed = new OrderReservation(value.orderId(), value.symbol(), value.instrumentChangeId(),
                    value.kind(), value.asset(), value.reservedUnits(), value.releasedUnits(),
                    value.consumedUnits(), value.orderQuantitySteps());
            assertThat(value).isEqualTo(reconstructed);
            assertThat(value.hashCode()).isEqualTo(reconstructed.hashCode());
            assertThat(value.toString()).isEqualTo(reconstructed.toString());
        }
        assertThat(original.remainingUnits()).isEqualTo(100);
        assertThat(consumed.remainingUnits()).isEqualTo(70);
        assertThat(released.remainingUnits()).isEqualTo(50);
        assertThat(resized.remainingUnits()).isEqualTo(30);
        assertThat(closed.remainingUnits()).isZero();
        assertThat(closed.consumedUnits()).isEqualTo(30);
        assertThat(closed.releasedUnits()).isEqualTo(50);
        assertThatThrownBy(() -> consumed.consume(71)).isInstanceOf(CoreStateRejectedException.class);
        assertThatThrownBy(() -> released.replaceReservedUnits(49)).isInstanceOf(CoreStateRejectedException.class);
        assertThatThrownBy(() -> closed.release(1)).isInstanceOf(CoreStateRejectedException.class);
        assertThatThrownBy(() -> original.consume(0)).isInstanceOf(CoreStateRejectedException.class);
        assertThatThrownBy(() -> new OrderReservation(1, "BTC-USDT", 1, ReservationKind.SPOT_ASSET,
                "USDT", Long.MAX_VALUE, Long.MAX_VALUE, 1, 1)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void normalizesValidSymbolAndAssetWithoutChangingTheirContracts() {
        OrderReservation reservation = OrderReservation.create(
                1, " btc-usdt ", 1, ReservationKind.SPOT_ASSET, " usdt ", 100, 1);

        assertThat(reservation.symbol()).isEqualTo("BTC-USDT");
        assertThat(reservation.asset()).isEqualTo("USDT");
    }

    @Test
    void rejectsInvalidSymbolBoundaries() {
        assertThatThrownBy(() -> OrderReservation.create(
                1, "-BTC", 1, ReservationKind.SPOT_ASSET, "USDT", 100, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderReservation.create(
                1, "A", 1, ReservationKind.SPOT_ASSET, "USDT", 100, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderReservation.create(
                1, "BTC.USDT", 1, ReservationKind.SPOT_ASSET, "USDT", 100, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidAssetBoundaries() {
        assertThatThrownBy(() -> new AssetBalance("U-SDT", 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssetBalance("U", 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssetBalance("USDT$", 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
