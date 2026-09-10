package com.surprising.aeron.service.execution;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OrderBatchSlotReuseTest {
    @Test void reuseClearsPriorInputsAndLaneResultsAcrossDifferentBatchSizes() {
        var batch = new OrderBatchPending(20);
        var command = new Object();
        batch.addItem(1, 0, 0, command);
        var first = batch.items.getFirst();
        first.laneResultPrepared = true;
        first.executionCount = 3;
        first.resultOrderSymbol = "BTC-USDT";
        first.matchingSubmission = () -> null;
        for (int i = 2; i <= 20; i++) batch.addItem(i, 0, 0, command);
        var last = batch.items.getLast();
        batch.clear();
        assertThat(first.command).isNull();
        assertThat(last.command).isNull();
        assertThat(first.matchingSubmission).isNull();
        assertThat(first.resultOrderSymbol).isNull();
        batch.addItem(22, 21, 22, "amend");
        assertThat(batch.items.getFirst()).isSameAs(first);
        assertThat(first.laneResultPrepared).isFalse();
        assertThat(first.executionCount).isZero();
        assertThat(first.originalOrderId()).isEqualTo(21);
        assertThat(batch.size()).isOne();
        batch.clear();
        assertThat(first.command).isNull();
    }
}
