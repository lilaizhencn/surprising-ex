package com.surprising.aeron.service.state.lifecycle;

import java.util.Collection;
import java.util.Iterator;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证有序集合的资金费用户分页从游标尾部开始。只检查选页、游标与末尾探测，不涉及账户扣款、资金费公式或异步线程。
 */
class RuntimePerpetualFundingProcessorTest {

    /**
     * 原集合迭代器一旦被调用就失败，要求分页先定位排他游标二，再取用户三和四，并因仍有用户五而保留下一页。
     */
    @Test
    void fundingCursorStartsFromTheIndexedTailInsteadOfRescanningEarlierUsers() {
        // 禁止从原集合头部迭代、可记录尾部游标的测试索引。
        CursorAwareUserSet users = new CursorAwareUserSet(java.util.List.of(1L, 2L, 3L, 4L, 5L));

        // 游标二之后最多取两个用户的实际分页结果。
        RuntimePerpetualFundingProcessor.UserPage page =
                RuntimePerpetualFundingProcessor.selectUsers(users, 2L, 2);

        assertThat(users.tailCursor).isEqualTo(2L);
        assertThat(page.userIds()).containsExactly(3L, 4L);
        assertThat(page.nextCursorUserId()).isEqualTo(4L);
        assertThat(page.complete()).isFalse();
    }

    /**
     * 用于验证访问路径的 TreeSet：直接迭代失败，尾部选择记录游标并返回可正常遍历的副本。
     */
    private static final class CursorAwareUserSet extends TreeSet<Long> {
        /** 最近一次 tailSet 收到的下界，初始负一表示还未调用。 */
        private long tailCursor = -1;

        /**
         * 装入指定用户作为有序索引测试数据。
         * @param users 初始化有序用户索引的编号集合
         */
        private CursorAwareUserSet(Collection<Long> users) {
            super(users);
        }

        /**
         * 禁止直接从原集合头部迭代，帮助测试识别没有走 tailSet 的分页实现。
         */
        @Override
        public Iterator<Long> iterator() {
            throw new AssertionError("funding pagination rescanned users before its cursor");
        }

        /**
         * 记录传入游标后，复制对应尾部集合供正常遍历；本测试记录下界，不另保存 inclusive 的观测值。
         * @param fromElement 尾部集合的用户编号下界
         * @param inclusive 是否包含下界，直接传给 TreeSet 的尾部视图实现
         */
        @Override
        public NavigableSet<Long> tailSet(Long fromElement, boolean inclusive) {
            tailCursor = fromElement;
            return new TreeSet<>(super.tailSet(fromElement, inclusive));
        }
    }
}
