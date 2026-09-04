package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RuntimeFundsAccumulatorTest {

    @Test
    void validatesConservationWithoutMaterializingFundsDelta() {
        RuntimeFundsAccumulator accumulator = new RuntimeFundsAccumulator();
        accumulator.add(7, FundsPosting.OwnerKind.USER, 11,
                FundsPosting.Subledger.AVAILABLE, -100);
        accumulator.add(7, FundsPosting.OwnerKind.TREASURY, 0,
                FundsPosting.Subledger.FEE, 100);
        accumulator.add(9, FundsPosting.OwnerKind.USER, 12,
                FundsPosting.Subledger.LOCKED, -40);
        accumulator.add(9, FundsPosting.OwnerKind.USER, 13,
                FundsPosting.Subledger.AVAILABLE, 40);

        assertThatCode(() -> accumulator.requireConserved(false)).doesNotThrowAnyException();

        accumulator.add(9, FundsPosting.OwnerKind.TREASURY, 0,
                FundsPosting.Subledger.INSURANCE, 1);
        assertThatThrownBy(() -> accumulator.requireConserved(false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("asset 9");
        assertThatCode(() -> accumulator.requireConserved(true)).doesNotThrowAnyException();
    }
}
