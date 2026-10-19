package com.surprising.realtime.provider.option;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Black76ReferenceTest {
    // Golden vectors evaluated independently with an 80-digit Decimal normal integral.
    @ParameterizedTest
    @CsvSource({
        "true,100,100,1,7.965567455405796,.2,.539827837277029,.0198476273738506,.396952547477012,-.0108754122596442",
        "true,110,100,.5,17.467730366014846,.4,.683815001755782,.0114360474359101,.276752347949025,-.0303290244327699",
        "false,80,100,2,44.935788078871866,.7,-.393747187633692,.00485766867287706,.435247113089785,-.0208680122714280",
        "true,50000,50000,.00273972602739726,835.2027133571405,.8,.508352027133571,.000190502780521302,10.4385085217152,-417.540340868607",
        "true,1e-8,1e-8,1,7.965567455405796e-10,.2,.539827837277029,198476273.738506,3.96952547477012e-11,-1.08754122596442e-12"
    })
    void infersVolatilityAndScaledGreeks(boolean call, double forward, double strike, double time,
            double premium, double iv, double delta, double gamma, double vega, double theta) {
        var result = Black76Reference.infer(call, forward, strike, premium, time);
        assertThat(result).isNotNull();
        assertThat(result.impliedVolatility()).isCloseTo(iv, withinPercentage(1e-6));
        assertThat(result.forwardDelta()).isCloseTo(delta, withinPercentage(1e-6));
        assertThat(result.forwardGamma()).isCloseTo(gamma, withinPercentage(1e-6));
        assertThat(result.vegaPerVolPoint()).isCloseTo(vega, withinPercentage(1e-6));
        assertThat(result.thetaPerDay()).isCloseTo(theta, withinPercentage(1e-6));
    }

    @Test void putCallParityAndForwardSensitivitiesUseOneModel() {
        var call = Black76Reference.infer(true, 110, 100, 17.467730366014846, .5);
        var put = Black76Reference.infer(false, 110, 100, 7.467730366014846, .5);
        assertThat(call.forwardDelta() - put.forwardDelta()).isCloseTo(1, within(1e-10));
        assertThat(call.impliedVolatility()).isCloseTo(put.impliedVolatility(), within(1e-9));
        assertThat(call.forwardGamma()).isCloseTo(put.forwardGamma(), within(1e-10));
        assertThat(call.vegaPerVolPoint()).isCloseTo(put.vegaPerVolPoint(), within(1e-9));
    }

    @ParameterizedTest
    @CsvSource({"true,100,100,0,1", "true,110,100,10,1", "false,80,100,20,1",
        "true,100,100,100,1", "false,80,100,100,1", "true,100,100,-1,1",
        "true,100,100,8,0", "true,0,100,8,1", "true,100,0,8,1", "true,100,100,8,-1"})
    void refusesUnidentifiableOrOutOfBoundsInputs(boolean call, double forward, double strike, double price, double time) {
        assertThat(Black76Reference.infer(call, forward, strike, price, time)).isNull();
    }

    @Test void refusesNumericallyUnidentifiablePricesNearTheBounds() {
        assertThat(Black76Reference.infer(true, 100, 100, Math.nextDown(100.0), 1)).isNull();
        assertThat(Black76Reference.infer(true, 110, 100, Math.nextUp(10.0), 1)).isNull();
    }

    @Test void refusesNonFiniteInputs() {
        assertThat(Black76Reference.infer(true, Double.NaN, 100, 8, 1)).isNull();
        assertThat(Black76Reference.infer(true, 100, Double.POSITIVE_INFINITY, 8, 1)).isNull();
        assertThat(Black76Reference.infer(true, 100, 100, Double.NaN, 1)).isNull();
    }
}
