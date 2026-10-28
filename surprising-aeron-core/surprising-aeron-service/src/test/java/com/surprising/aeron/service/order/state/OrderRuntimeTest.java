package com.surprising.aeron.service.order.state;

import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.service.core.CoreStateTestFixtures;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单值更新的单元测试，核对取消与提交元数据的复制和原位更新，原前值不变，创建时间、累计费用和成交值高低位在后续更新中保留。
 */
class OrderRuntimeTest {
    /**
     * 部分成交后的取消副本应与原位更新相同，原订单仍为 OPEN，费用和累计成交值保留。
     */
    @Test
    void combinesCancellationAndCommitAndPreservesTheOldOrder() {
        // 已补初次时间位置的原订单前值，后状态操作不能改写它。
        OrderRuntime before = CoreStateTestFixtures.order(11, 7, 5, 10).withCommitMetadata(100, 200);
        before.applyFillInPlace(3, 7, -1, CoreOrderStatus.OPEN, 1, -1, -1);
        before.executionValue(1, -1);
        var expected = before.snapshot();
        expected.applyStatusInPlace(CoreOrderStatus.CANCELED, 2, 110, 210);
        assertThat(before.withStatus(CoreOrderStatus.CANCELED, 2, 110, 210)).isEqualTo(expected);
        assertThat(before.status()).isEqualTo(CoreOrderStatus.OPEN);
        assertThat(before.revision()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidStatusMetadataWithoutChangingExecutionOrFees() {
        var order = CoreStateTestFixtures.order(11, 7, 5, 10).withCommitMetadata(100, 200);
        order.applyFillInPlace(3, 7, -1, CoreOrderStatus.OPEN, 2, 110, 210);
        var before = order.snapshot();
        assertThatThrownBy(() -> order.applyStatusInPlace(null, 3, 120, 220))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.applyStatusInPlace(CoreOrderStatus.CANCELED, 0, 120, 220))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.applyStatusInPlace(CoreOrderStatus.CANCELED, 3, 120, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(order).isEqualTo(before);
        var copied = order.withStatus(CoreOrderStatus.CANCELED, 3, -1, -1);
        order.applyStatusInPlace(CoreOrderStatus.CANCELED, 3, -1, -1);
        assertThat(order).isEqualTo(copied);
        assertThat(order.executedQuantitySteps()).isEqualTo(3);
        assertThat(order.cumulativeFeeUnits()).isEqualTo(-1);
        assertThat(order.updatedAtEpochMillis()).isEqualTo(110);
        assertThat(order.clusterPosition()).isEqualTo(210);
    }

    /**
     * 原位部分成交及取消不能改掉非零创建时间；快照和新提交值也要保留创建时间、费用及累计成交值原始高低位。
     */
    @Test
    void creationTimeSurvivesPartialFillCancellationAndLanePublication() {
        // 当前直接在所属测试范围原位成交及取消的订单。
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
        // 取消后再补新提交元数据的新订单值，创建时间及成交值应沿用。
        OrderRuntime copied = order.withCommitMetadata(130, 230);
        assertThat(copied.createdAtEpochMillis()).isEqualTo(100);
        assertThat(copied.updatedAtEpochMillis()).isEqualTo(130);
        assertThat(copied.executedValueHigh()).isEqualTo(1);
        assertThat(copied.executedValueLow()).isEqualTo(-1);
        // 从订单快照再补提交元数据的新值，核对累计成交值高低位未丢失。
        var stamped = order.snapshot().withCommitMetadata(140, 240);
        assertThat(stamped.executedValueHigh()).isEqualTo(1);
        assertThat(stamped.executedValueLow()).isEqualTo(-1);
    }
}
