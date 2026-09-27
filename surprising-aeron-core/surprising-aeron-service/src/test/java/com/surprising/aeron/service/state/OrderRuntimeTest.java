package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreOrderStatus;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class OrderRuntimeTest {
    @Test
    void combinesFillAndCommitWithoutChangingRevisionFeesOrPriorSnapshot() {
        OrderRuntime before = CoreStateTestFixtures.order(11, 7, 5, 10).withCommitMetadata(100, 200);
        OrderRuntime expected = before.withFill(3, 7, 4, CoreOrderStatus.OPEN, 2)
                .withCommitMetadata(110, 210);
        OrderRuntime actual = before.withFill(3, 7, 4, CoreOrderStatus.OPEN, 2, 110, 210);
        assertThat(actual).isEqualTo(expected);
        assertThat(before.executedQuantitySteps()).isZero();
        assertThat(before.cumulativeFeeUnits()).isZero();
        assertThat(before.updatedAtEpochMillis()).isEqualTo(100);
        assertThat(actual.withFill(10, 0, -1, CoreOrderStatus.FILLED, 3, 120, 220))
                .isEqualTo(actual.withFill(10, 0, -1, CoreOrderStatus.FILLED, 3).withCommitMetadata(120, 220));
        assertThat(before.withFill(3, 7, 4, CoreOrderStatus.OPEN, 2, -1, -1))
                .isEqualTo(before.withFill(3, 7, 4, CoreOrderStatus.OPEN, 2));
    }

    @Test
    void combinesCancellationAndCommitAndPreservesTheOldOrder() {
        OrderRuntime before = CoreStateTestFixtures.order(11, 7, 5, 10).withCommitMetadata(100, 200);
        assertThat(before.withStatus(CoreOrderStatus.CANCELED, 2, 110, 210))
                .isEqualTo(before.withStatus(CoreOrderStatus.CANCELED, 2).withCommitMetadata(110, 210));
        assertThat(before.status()).isEqualTo(CoreOrderStatus.OPEN);
        assertThat(before.revision()).isEqualTo(1);
    }

    @Test
    void creationTimeSurvivesPartialFillCancellationAndLanePublication() {
        OrderRuntime order = CoreStateTestFixtures.order(11, 7, 5, 10).withCommitMetadata(100, 200);
        order.applyFillInPlace(3, 7, 4, CoreOrderStatus.OPEN, 2, 110, 210);
        order.executionValue(1, -1);
        assertThat(order.createdAtEpochMillis()).isEqualTo(100);
        assertThat(order.updatedAtEpochMillis()).isEqualTo(110);
        order.applyStatusInPlace(CoreOrderStatus.CANCELED, 3, 120, 220);
        assertThat(order.createdAtEpochMillis()).isEqualTo(100);
        assertThat(order.updatedAtEpochMillis()).isEqualTo(120);
        assertThat(order.cumulativeFeeUnits()).isEqualTo(4);
        assertThat(order.snapshot().createdAtEpochMillis()).isEqualTo(100);
        OrderRuntime copied = order.withCommitMetadata(130, 230);
        assertThat(copied.createdAtEpochMillis()).isEqualTo(100);
        assertThat(copied.updatedAtEpochMillis()).isEqualTo(130);
        assertThat(copied.executedValueHigh()).isEqualTo(1);
        assertThat(copied.executedValueLow()).isEqualTo(-1);
        var stamped = order.snapshot().withCommitMetadata(140, 240);
        assertThat(stamped.executedValueHigh()).isEqualTo(1);
        assertThat(stamped.executedValueLow()).isEqualTo(-1);
    }
}
