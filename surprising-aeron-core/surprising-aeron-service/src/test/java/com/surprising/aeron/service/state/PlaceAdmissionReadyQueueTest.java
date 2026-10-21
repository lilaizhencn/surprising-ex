package com.surprising.aeron.service.state;

import com.surprising.aeron.service.lane.LaneSequenceQueue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LaneSequenceQueueTest {
    @Test
    void publishesPrimitiveSequencesInLaneCompletionOrderAndReusesSlots() {
        LaneSequenceQueue queue = new LaneSequenceQueue(2);
        queue.publish(11);
        queue.publish(13);
        assertThatThrownBy(() -> queue.publish(17)).isInstanceOf(IllegalStateException.class);
        assertThat(queue.poll()).isEqualTo(11);
        queue.publish(17);
        assertThat(queue.poll()).isEqualTo(13);
        assertThat(queue.poll()).isEqualTo(17);
        assertThat(queue.poll()).isZero();
    }
}
