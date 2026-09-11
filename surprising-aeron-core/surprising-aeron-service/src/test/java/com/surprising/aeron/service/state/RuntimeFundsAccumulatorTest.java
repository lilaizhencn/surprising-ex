package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RuntimeFundsAccumulatorTest {

    @Test
    void handsOffBackingStorageAndKeepsSubsequentCommandsIsolated() throws Exception {
        var source = new RuntimeFundsAccumulator(1);
        var target = new RuntimeFundsAccumulator(1);
        for (int user = 1; user <= 64; user++) {
            source.add(user % 3, FundsPosting.OwnerKind.USER, user, FundsPosting.Subledger.AVAILABLE, -user);
            source.add(user % 3, FundsPosting.OwnerKind.USER, user, FundsPosting.Subledger.LOCKED, user);
        }
        var expected = source.toDelta().postings();
        var storage = RuntimeFundsAccumulator.class.getDeclaredField("units");
        storage.setAccessible(true);
        Object originalStorage = storage.get(source);
        source.transferToEmpty(target);
        assertThat(storage.get(target)).isSameAs(originalStorage);
        assertThat(source.toDelta()).isSameAs(RuntimeFundsDelta.empty());
        source.add(9, FundsPosting.OwnerKind.USER, 900, FundsPosting.Subledger.AVAILABLE, 17);
        assertThat(target.toDelta().postings()).containsExactlyElementsOf(expected);
        assertThatCode(() -> target.requireConserved(false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> target.transferToEmpty(source)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> target.transferToEmpty(target)).isInstanceOf(IllegalArgumentException.class);
        source.clear();
        target.transferToEmpty(source);
        target.clear();
        assertThat(source.toDelta().postings()).containsExactlyElementsOf(expected);
        assertThat(storage.get(source)).isSameAs(originalStorage);
    }

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
