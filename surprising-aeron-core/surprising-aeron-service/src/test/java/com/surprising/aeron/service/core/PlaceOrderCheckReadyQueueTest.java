package com.surprising.aeron.service.core;

import com.surprising.aeron.support.concurrent.LaneSequenceQueue;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账户完成序号原始队列的 FIFO、满容量及槽复用测试；文件名沿用已有 PlaceAdmissionReadyQueueTest。
 */
class LaneSequenceQueueTest {
    /**
     * 容量二先发布 11 和 13，满时拒绝 17；取走 11 后复用槽发布 17，随后按顺序取出并检查空队列返回零。
     */
    @Test
    void publishesPrimitiveSequencesInLaneCompletionOrderAndReusesSlots() {
        // 容量二的实际序号队列，用于明确触发满环及一次槽复用
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
