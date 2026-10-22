package com.surprising.aeron.service.core;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.order.state.OrderReservation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证订单预留的不可变数量转换、共享身份和值比较，以及标的 ID 和资产名的基础输入边界；不启动账户线程或撮合器。
 */
class CoreValueValidationTest {

    /**
     * 依次消耗、释放、调整总预留及释放剩余，确认共享规范身份、剩余金额和值语义；越界数量和总额溢出必须失败，原对象保持原值。
     */
    @Test
    void reservationAmountTransitionsPreserveImmutableValidatedIdentityAndValueSemantics() {
        // 初始预留一百的不可变对象，后续派生不能改写它
        var original = OrderReservation.create(9, "1",
                ReservationKind.SPOT_ASSET, " usdt ", 100, 10);
        // 已消耗三十、剩余七十的预留版本
        var consumed = original.consume(30);
        // 再释放二十、剩余五十的预留版本
        var released = consumed.release(20);
        // 把总预留改成八十、剩余三十的版本，已消耗和已释放数量保留
        var resized = released.replaceReservedUnits(80);
        // 把剩余三十也释放后的版本，剩余零、累计释放五十
        var closed = resized.releaseAll();
        for (var /* 当前待检查共享身份和值比较的派生预留版本 */ value : new OrderReservation[]{consumed, released, resized, closed}) {
            assertThat(value.instrumentId()).isSameAs(original.instrumentId());
            assertThat(value.asset()).isSameAs(original.asset());
            // 使用该版本全部值重建的对象，用于比较 equals、hashCode 和诊断文本
            var reconstructed = new OrderReservation(value.orderId(), value.instrumentId(), value.kind(), value.asset(), value.reservedUnits(), value.releasedUnits(),
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
        assertThatThrownBy(() -> new OrderReservation(1, "1", ReservationKind.SPOT_ASSET,
                "USDT", Long.MAX_VALUE, Long.MAX_VALUE, 1, 1)).isInstanceOf(ArithmeticException.class);
    }

    /**
     * 确认规范数字标的文本不变，带空白的小写资产被规范为大写。
     */
    @Test
    void preservesCanonicalInstrumentIdAndNormalizesAsset() {
        // 使用小写带空白资产名创建的预留，供规范化断言
        OrderReservation reservation = OrderReservation.create(
                1, "1", ReservationKind.SPOT_ASSET, " usdt ", 100, 1);

        assertThat(reservation.instrumentId()).isEqualTo("1");
        assertThat(reservation.asset()).isEqualTo("USDT");
    }

    /**
     * 拒绝负号币名、字母和币对名称作为数字标的 ID。
     */
    @Test
    void rejectsNamesAndInvalidInstrumentIds() {
        assertThatThrownBy(() -> OrderReservation.create(
                1, "-BTC", ReservationKind.SPOT_ASSET, "USDT", 100, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderReservation.create(
                1, "A", ReservationKind.SPOT_ASSET, "USDT", 100, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderReservation.create(
                1, "BTC.USDT", ReservationKind.SPOT_ASSET, "USDT", 100, 1))
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
