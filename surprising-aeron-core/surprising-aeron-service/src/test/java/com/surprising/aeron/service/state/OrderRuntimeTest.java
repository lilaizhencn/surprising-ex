package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class OrderRuntimeTest {
    @Test
    void combinesFillAndCommitWithoutChangingRevisionFeesOrPriorSnapshot() {
        OrderRuntime before = new OrderRuntime(11, 7, 5, 10).withCommitMetadata(100, 200);
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
        OrderRuntime before = new OrderRuntime(11, 7, 5, 10).withCommitMetadata(100, 200);
        assertThat(before.withStatus(CoreOrderStatus.CANCELED, 2, 110, 210))
                .isEqualTo(before.withStatus(CoreOrderStatus.CANCELED, 2).withCommitMetadata(110, 210));
        assertThat(before.status()).isEqualTo(CoreOrderStatus.OPEN);
        assertThat(before.revision()).isEqualTo(1);
    }
}
