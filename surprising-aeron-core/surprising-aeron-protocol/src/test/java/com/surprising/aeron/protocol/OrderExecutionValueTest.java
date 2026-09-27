package com.surprising.aeron.protocol;
import static org.assertj.core.api.Assertions.*;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
class OrderExecutionValueTest {
    @Test void accumulatesExactProductsBeyondSignedLongRange() {
        long h=0, l=0; BigInteger expected=BigInteger.ZERO;
        long[][] fills={{Long.MAX_VALUE, 1}, {Long.MAX_VALUE, 2}, {123456789, 777}};
        for (long[] fill:fills) {
            h=OrderExecutionValue.addHigh(h,l,fill[0],fill[1]);
            l=OrderExecutionValue.addLow(l,fill[0],fill[1]);
            expected=expected.add(BigInteger.valueOf(fill[0]).multiply(BigInteger.valueOf(fill[1])));
        }
        assertThat(OrderExecutionValue.value(h,l)).isEqualTo(expected);
    }
    @Test void weightedAverageDoesNotSubstituteTheOrderLimitPrice() {
        long h=OrderExecutionValue.addHigh(0,0,100,2), l=OrderExecutionValue.addLow(0,100,2);
        h=OrderExecutionValue.addHigh(h,l,110,1); l=OrderExecutionValue.addLow(l,110,1);
        assertThat(OrderExecutionValue.average(h,l,3)).isEqualTo("103.333333333333333333");
        assertThat(OrderExecutionValue.average(0,0,0)).isNull();
        assertThat(OrderExecutionValue.value(-1,0)).isNull();
    }
}
