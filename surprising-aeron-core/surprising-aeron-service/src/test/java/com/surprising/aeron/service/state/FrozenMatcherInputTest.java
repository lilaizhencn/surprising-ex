package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.protocol.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FrozenMatcherInputTest {
    @Test void matcherRejectsTheLiveAuthorityAndAcceptsItsIndependentAdmissionValue() {
        var authority = authority();
        assertThatThrownBy(() -> authority.requireFrozenMatcherInput(7)).isInstanceOf(IllegalArgumentException.class);
        var input = authority.copyIntoCommandSlot(null);
        input.requireFrozenMatcherInput(7);
        authority.applyFillInPlace(4, 6, 0, CoreOrderStatus.OPEN, 2, 10, 20);
        assertThat(input.remainingQuantitySteps()).isEqualTo(10);
        assertThat(input.executedQuantitySteps()).isZero();
        input.requireFrozenMatcherInput(7);
    }

    @Test void frozenPartialOrderRetainsRemainingQuantityAndRejectsAnotherUserOrReleasedLease() {
        var authority = authority();
        authority.applyFillInPlace(4, 6, 0, CoreOrderStatus.OPEN, 2, 10, 20);
        var input = authority.copyIntoCommandSlot(null);
        input.requireFrozenMatcherInput(7);
        assertThat(input.remainingQuantitySteps()).isEqualTo(6);
        assertThatThrownBy(() -> input.requireFrozenMatcherInput(8)).isInstanceOf(IllegalArgumentException.class);
        input.clearCommandSlot();
        assertThatThrownBy(() -> input.requireFrozenMatcherInput(7)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void filledInputCannotBecomeANewNativePlacement() {
        var authority = authority();
        authority.applyFillInPlace(10, 0, 0, CoreOrderStatus.FILLED, 2, 10, 20);
        var input = authority.copyIntoCommandSlot(null);
        assertThatThrownBy(() -> input.requireFrozenMatcherInput(7)).isInstanceOf(IllegalArgumentException.class);
    }

    private static OrderRuntime authority() {
        return new OrderRuntime(11, 7, 0, CoreStateTestFixtures.runtimeInstrument(), CoreOrderSide.BUY, 100,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                0, 0, 10, 0, 10, false);
    }
}
