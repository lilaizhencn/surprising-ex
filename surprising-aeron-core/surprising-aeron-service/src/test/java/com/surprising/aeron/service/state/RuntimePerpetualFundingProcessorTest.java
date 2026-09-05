package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.Iterator;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class RuntimePerpetualFundingProcessorTest {

    @Test
    void fundingCursorStartsFromTheIndexedTailInsteadOfRescanningEarlierUsers() {
        CursorAwareUserSet users = new CursorAwareUserSet(java.util.List.of(1L, 2L, 3L, 4L, 5L));

        RuntimePerpetualFundingProcessor.UserPage page =
                RuntimePerpetualFundingProcessor.selectUsers(users, 2L, 2);

        assertThat(users.tailCursor).isEqualTo(2L);
        assertThat(page.userIds()).containsExactly(3L, 4L);
        assertThat(page.nextCursorUserId()).isEqualTo(4L);
        assertThat(page.complete()).isFalse();
    }

    private static final class CursorAwareUserSet extends TreeSet<Long> {
        private long tailCursor = -1;

        private CursorAwareUserSet(Collection<Long> users) {
            super(users);
        }

        @Override
        public Iterator<Long> iterator() {
            throw new AssertionError("funding pagination rescanned users before its cursor");
        }

        @Override
        public NavigableSet<Long> tailSet(Long fromElement, boolean inclusive) {
            tailCursor = fromElement;
            return new TreeSet<>(super.tailSet(fromElement, inclusive));
        }
    }
}
