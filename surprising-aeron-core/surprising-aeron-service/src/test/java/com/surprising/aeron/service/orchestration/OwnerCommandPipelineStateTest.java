package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OwnerCommandPipelineStateTest {
    @Test
    void dependencyWaitSurvivesUnrelatedRetirementAndEndsAtActualBlocker() {
        var state = new OwnerCommandPipelineState();
        var request = request(9);
        add(state, 1);
        add(state, 2);
        add(state, 3);
        state.rememberBlockedIngress(request, 2);
        retire(state);
        assertThat(state.isIngressBlocked(request)).isTrue();
        assertThat(state.isIngressBlocked(request(10))).isFalse();
        retire(state);
        assertThat(state.isIngressBlocked(request)).isFalse();
        assertThat(state.commandWindow().size()).isOne();
        retire(state);
        // 复用所有槽位后，旧依赖不能重新生效。
        for (int i = 0; i < state.commandWindow().capacity() + 1; i++) {
            add(state, i + 10);
            retire(state);
        }
        assertThat(state.isIngressBlocked(request)).isFalse();
    }

    @Test
    void controlFenceWaitsForWholeWindowAndResetDropsReferences() {
        var state = new OwnerCommandPipelineState();
        var request = request(9);
        add(state, 1);
        add(state, 2);
        state.rememberBlockedIngress(request, state.commandWindow().size());
        retire(state);
        assertThat(state.isIngressBlocked(request)).isTrue();
        state.beginHeadCommit();
        state.reset();
        assertThat(state.isIngressBlocked(request)).isFalse();
        assertThat(state.hasCommittingHead()).isFalse();
        assertThat(state.commandWindow().size()).isZero();
    }

    @Test
    void headBoundaryUsesWindowEntryEvenWithoutMatchingSequence() {
        var state = new OwnerCommandPipelineState();
        add(state, 1);
        var head = state.commandWindow().get(0);
        assertThat(head.sequence).isZero();
        state.beginHeadCommit();
        assertThat(state.hasCommittingHead()).isTrue();
        assertThat(state.committingHead()).isSameAs(head);
        assertThatThrownBy(state::beginHeadCommit).isInstanceOf(IllegalStateException.class);
        state.finishHeadCommit();
        assertThat(state.hasCommittingHead()).isFalse();
        assertThatThrownBy(state::finishHeadCommit).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void waitingProbeIsInvalidatedByProgressNotificationsAndHeadRetirement() {
        var state = new OwnerCommandPipelineState();
        add(state, 1);
        add(state, 2);
        state.beginHeadCommit();
        state.rememberMatchingWait(2, 7, 7, false);
        assertThat(state.canSkipMatchingPoll(2, 7, false)).isTrue();
        assertThat(state.canSkipMatchingPoll(3, 7, false)).isFalse();
        assertThat(state.canSkipMatchingPoll(2, 8, false)).isFalse();
        assertThat(state.canSkipMatchingPoll(2, 7, true)).isFalse();
        state.finishHeadCommit();
        state.beginHeadCommit();
        assertThat(state.canSkipMatchingPoll(2, 7, false)).isFalse();
        state.rememberMatchingWait(2, 7, 7, true);
        assertThat(state.canSkipMatchingPoll(2, 7, false)).isFalse();
    }

    private static void retire(OwnerCommandPipelineState state) {
        state.beginHeadCommit();
        state.finishHeadCommit();
    }

    private static void add(OwnerCommandPipelineState state, long id) {
        var window = state.commandWindow();
        window.resetCandidate(id);
        window.route(0, 1L);
        window.add(null, request(id), id, id);
    }

    private static CoreMessage request(long id) {
        return CoreMessage.owned(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER,
                new UUID(1, id), ProductLine.SPOT, CommandSource.GATEWAY, 1, id, id, id, id), new byte[0]);
    }
}
