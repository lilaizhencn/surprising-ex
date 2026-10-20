package com.surprising.gateway.c2c;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class C2cMoneyTest {
    @Test void quoteNeverUsesFloatingPointOrRoundsUpCrypto() {
        var price = new BigDecimal("6.65");
        long units = C2cMoney.unitsForFiat("100.00", price, 100_000_000, 2);
        assertThat(units).isEqualTo(1_503_759_398L);
        assertThat(C2cMoney.fiatAmount(units, price, 100_000_000, 2)).isEqualByComparingTo("100.00");
        assertThat(C2cMoney.quantityUnits("90071992.54740993", 100_000_000)).isEqualTo(9_007_199_254_740_993L);
        assertThat(C2cMoney.buyerFee(Long.MAX_VALUE, 1000)).isEqualTo(9_223_372_036_854_776L);
        assertThat(C2cMoney.buyerFee(100, 0)).isZero();
    }
    @Test void invalidPrecisionOverflowAndUnknownScaleAreRejected() {
        for (String bad : new String[]{"1e2", "-1", "0", "NaN", " 1", "01", "1."})
            assertThatThrownBy(() -> C2cMoney.decimal(bad)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> C2cMoney.quantityUnits("0.000000001", 100_000_000)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> C2cMoney.quantityUnits("92233720368.54775808", 100_000_000)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> C2cMoney.quantityUnits("1", 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> C2cMoney.unitsForFiat("1.001", BigDecimal.ONE, 100_000_000, 2)).isInstanceOf(ArithmeticException.class);
    }
    @Test void paidOrdersNeverAutomaticallyRefundAndPendingFundsCannotBeReleased() {
        for (var status : C2cOrderStatus.values()) {
            if (status != C2cOrderStatus.PAYMENT_PENDING)
                assertThatThrownBy(status::requireCancellation).isInstanceOf(IllegalStateException.class);
            if (status != C2cOrderStatus.PAID)
                assertThatThrownBy(status::requireRelease).isInstanceOf(IllegalStateException.class);
        }
    }
}
