package com.surprising.aeron.service.core;

import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.order.state.OrderReservation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证订单预留的金额守恒、规范身份和值比较，以及标的 ID 和资产名的基础输入边界；不启动账户线程或撮合器。
 */
class CoreValueValidationTest {

    /** 构造不同释放/消耗进度，检查剩余金额、规范文本和值语义，不要求文本引用相同。 */
    @Test
    void reservationRecordsValidateAmountsAndValueSemantics() {
        long[][] amounts = {{100, 0, 0, 100}, {100, 0, 30, 70}, {100, 20, 30, 50},
                {80, 20, 30, 30}, {80, 50, 30, 0}};
        for (long[] amount : amounts) {
            var value = new OrderReservation(9, new String("1"), ReservationKind.SPOT_ASSET,
                    " usdt ", amount[0], amount[1], amount[2], 10);
            var reconstructed = new OrderReservation(9, "1", ReservationKind.SPOT_ASSET,
                    "USDT", amount[0], amount[1], amount[2], 10);
            assertThat(value.instrumentId()).isEqualTo("1");
            assertThat(value.asset()).isEqualTo("USDT");
            assertThat(value.remainingUnits()).isEqualTo(amount[3]);
            assertThat(value).isEqualTo(reconstructed);
            assertThat(value.hashCode()).isEqualTo(reconstructed.hashCode());
            assertThat(value.toString()).isEqualTo(reconstructed.toString());
        }
        for (long[] invalid : new long[][]{{0, 0, 0}, {100, -1, 0}, {100, 0, -1},
                {100, 0, 101}, {49, 20, 30}, {80, 51, 30}}) {
            assertThatThrownBy(() -> new OrderReservation(9, "1", ReservationKind.SPOT_ASSET,
                    "USDT", invalid[0], invalid[1], invalid[2], 10))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new OrderReservation(1, "1", ReservationKind.SPOT_ASSET,
                "USDT", Long.MAX_VALUE, Long.MAX_VALUE, 1, 1)).isInstanceOf(ArithmeticException.class);
    }

    /**
     * 确认规范数字标的文本不变，带空白的小写资产被规范为大写。
     */
    @Test
    void preservesCanonicalInstrumentIdAndNormalizesAsset() {
        // 使用小写带空白资产名创建的预留，供规范化断言
        OrderReservation reservation = new OrderReservation(1, "1", ReservationKind.SPOT_ASSET, " usdt ", 100, 0, 0, 1);

        assertThat(reservation.instrumentId()).isEqualTo("1");
        assertThat(reservation.asset()).isEqualTo("USDT");
    }

    /**
     * 拒绝负号币名、字母和币对名称作为数字标的 ID。
     */
    @Test
    void rejectsNamesAndInvalidInstrumentIds() {
        assertThatThrownBy(() -> new OrderReservation(1, "-BTC", ReservationKind.SPOT_ASSET, "USDT", 100, 0, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderReservation(1, "A", ReservationKind.SPOT_ASSET, "USDT", 100, 0, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderReservation(1, "BTC.USDT", ReservationKind.SPOT_ASSET, "USDT", 100, 0, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 拒绝含不允许字符或长度不符合资产规则的输入。
     */
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
