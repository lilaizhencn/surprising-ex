package com.surprising.aeron.service.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import org.junit.jupiter.api.Test;

class DirectMatcherCompletionRingTest {

    @Test
    void completesTheReservedCorrelationAfterCopyingTheMutableCommand() {
        DirectMatcherCompletionRing ring = new DirectMatcherCompletionRing(2);
        DirectMatcherCompletionRing.Pending pending = ring.reserve();
        OrderCommand command = command(41);

        ring.complete(pending.correlationId(), 17, command);
        command.orderId = 99;

        CoreMatchingResult result = pending.future().join();
        assertThat(result.accepted()).isTrue();
        assertThat(result.nativeCommand().nativeSequence()).isEqualTo(17);
        assertThat(result.nativeMatcherResult().orderId()).isEqualTo(41);
    }

    @Test
    void failsClosedWhenCapacityWouldOverwriteAnInflightCommand() {
        DirectMatcherCompletionRing ring = new DirectMatcherCompletionRing(1);
        ring.reserve();

        assertThatThrownBy(ring::reserve)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exhausted");
    }

    private static OrderCommand command(long orderId) {
        OrderCommand command = new OrderCommand();
        command.command = OrderCommandType.PLACE_ORDER;
        command.orderId = orderId;
        command.symbol = 7;
        command.price = 100;
        command.reserveBidPrice = 100;
        command.size = 2;
        command.action = OrderAction.BID;
        command.orderType = OrderType.GTC;
        command.uid = 5;
        command.resultCode = CommandResultCode.SUCCESS;
        return command;
    }
}
