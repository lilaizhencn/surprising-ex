package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CoreRoundingPolicyTest {

    @Test
    void booksEveryResidual() {
        CoreRoundingPolicy.RoundedUnits fee = CoreRoundingPolicy.feeCeiling(5, 2);
        CoreRoundingPolicy.RoundedUnits funding = CoreRoundingPolicy.fundingTruncate(-5, 2);
        CoreRoundingPolicy.RoundedUnits halfUp = CoreRoundingPolicy.signedHalfUp(-5, 2);

        assertThat(fee).isEqualTo(new CoreRoundingPolicy.RoundedUnits(3, 1));
        assertThat(funding).isEqualTo(new CoreRoundingPolicy.RoundedUnits(-2, -1));
        assertThat(halfUp).isEqualTo(new CoreRoundingPolicy.RoundedUnits(-3, 1));
        assertThat(CoreRoundingPolicy.roundingResidualPosting("USDT", fee))
                .isEqualTo(new FundsPosting("USDT", FundsPosting.OwnerKind.TREASURY, 0,
                        FundsPosting.Subledger.ROUNDING_RESIDUAL, 1));
    }
}
