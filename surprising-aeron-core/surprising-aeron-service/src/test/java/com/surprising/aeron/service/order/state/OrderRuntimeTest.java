package com.surprising.aeron.service.order.state;

import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.service.core.CoreStateTestFixtures;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单值更新的单元测试，核对合并成交与提交元数据操作等价于分步操作，原前值不变，创建时间、累计费用和成交值高低位在后续更新中保留。
 */
class OrderRuntimeTest {
    /**
     * 比较一次生成成交及提交后状态与先成交再补元数据的结果，覆盖返佣和 -1 不更新时间分支，并核对原订单成交量、费用和时间未变。
     */
    @Test
    void combinesFillAndCommitWithoutChangingRevisionFeesOrPriorSnapshot() {
        // 已补初次时间位置的原订单前值，后状态操作不能改写它。
        OrderRuntime before = CoreStateTestFixtures.order(11, 7, 5, 10).withCommitMetadata(100, 200);
        // 先应用成交再补提交元数据的参照后状态。
        OrderRuntime expected = before.withFill(3, 7, 4, CoreOrderStatus.OPEN, 2)
                .withCommitMetadata(110, 210);
        // 一次合并成交、费用和提交元数据生成的结果，应与参照相同。
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

    /**
     * 取消与提交元数据合并后应等同分步操作，原订单仍为 OPEN 且版本不变。
     */
    @Test
    void combinesCancellationAndCommitAndPreservesTheOldOrder() {
        // 已补初次时间位置的原订单前值，后状态操作不能改写它。
        OrderRuntime before = CoreStateTestFixtures.order(11, 7, 5, 10).withCommitMetadata(100, 200);
        assertThat(before.withStatus(CoreOrderStatus.CANCELED, 2, 110, 210))
                .isEqualTo(before.withStatus(CoreOrderStatus.CANCELED, 2).withCommitMetadata(110, 210));
        assertThat(before.status()).isEqualTo(CoreOrderStatus.OPEN);
        assertThat(before.revision()).isEqualTo(1);
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
