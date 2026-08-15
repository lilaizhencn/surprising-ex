package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.ReservationKind;
import org.junit.jupiter.api.Test;

class CoreValueValidationTest {

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
