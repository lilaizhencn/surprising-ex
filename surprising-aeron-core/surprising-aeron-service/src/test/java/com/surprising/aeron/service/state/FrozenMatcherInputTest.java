package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.order.OrderRuntime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * 检查 Matcher 只能读取独立冻结的订单输入，不能借用账户权威订单、其他用户输入或已经释放的命令槽；也不允许把已完全成交的输入当作新下单。
 */
class FrozenMatcherInputTest {
    /**
     * 原权威订单直接作 Matcher 输入应拒绝；复制到命令槽后可使用，之后原订单部分成交不改变冻结输入的剩余量与已成交量。
     */
    @Test void matcherRejectsTheLiveAuthorityAndAcceptsItsIndependentAdmissionValue() {
        // 本用例账户侧的可变权威订单，可原地成交变更
        var authority = authority();
        assertThatThrownBy(() -> authority.requireFrozenMatcherInput(7)).isInstanceOf(IllegalArgumentException.class);
        // 从权威订单复制出的独立命令槽输入，清空后租约失效
        var input = authority.copyIntoCommandSlot(null);
        input.requireFrozenMatcherInput(7);
        authority.applyFillInPlace(4, 6, 0, CoreOrderStatus.OPEN, 2, 10, 20);
        assertThat(input.remainingQuantitySteps()).isEqualTo(10);
        assertThat(input.executedQuantitySteps()).isZero();
        input.requireFrozenMatcherInput(7);
    }

    /**
     * 从已成交四、剩六的权威订单冻结输入，核对数量；另一用户使用或槽位清空后再次使用都被拒绝。
     */
    @Test void frozenPartialOrderRetainsRemainingQuantityAndRejectsAnotherUserOrReleasedLease() {
        // 本用例账户侧的可变权威订单，可原地成交变更
        var authority = authority();
        authority.applyFillInPlace(4, 6, 0, CoreOrderStatus.OPEN, 2, 10, 20);
        // 从权威订单复制出的独立命令槽输入，清空后租约失效
        var input = authority.copyIntoCommandSlot(null);
        input.requireFrozenMatcherInput(7);
        assertThat(input.remainingQuantitySteps()).isEqualTo(6);
        assertThatThrownBy(() -> input.requireFrozenMatcherInput(8)).isInstanceOf(IllegalArgumentException.class);
        input.clearCommandSlot();
        assertThatThrownBy(() -> input.requireFrozenMatcherInput(7)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 把权威订单全部成交后再冻结，检查它不能通过新下单输入检查。
     */
    @Test void filledInputCannotBecomeANewNativePlacement() {
        // 本用例账户侧的可变权威订单，可原地成交变更
        var authority = authority();
        authority.applyFillInPlace(10, 0, 0, CoreOrderStatus.FILLED, 2, 10, 20);
        // 从权威订单复制出的独立命令槽输入，清空后租约失效
        var input = authority.copyIntoCommandSlot(null);
        assertThatThrownBy(() -> input.requireFrozenMatcherInput(7)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 构造用户七、订单十一、数量十的可变权威测试订单，使用共享测试合约。
     */
    private static OrderRuntime authority() {
        return new OrderRuntime(11, 7, 0, CoreStateTestFixtures.runtimeInstrument(), CoreOrderSide.BUY, 100,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                0, 0, 10, 0, 10, false);
    }
}
