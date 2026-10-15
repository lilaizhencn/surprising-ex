package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.Cluster;
import java.util.ArrayList;
import java.util.UUID;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OwnerIngressRingTest {
    @Test void releasingTransportSlotDoesNotReleaseTheAdmittedCommandLease() {
        var ring = new OwnerIngressRing();
        var command = CoreMessage.decoderSlot();
        assertThat(command.acquireDecoderSlot()).isTrue();
        command.decoderPayloadBuffer(8);
        assertThat(ring.offer(command, 0, 11, 22, null, 33, null)).isTrue();
        var entry = ring.first();
        assertThat(entry.command).isSameAs(command);
        assertThat(entry.sessionId).isZero();
        assertThat(entry.timestamp).isEqualTo(11);
        assertThat(entry.position).isEqualTo(22);
        assertThat(entry.enqueuedNanos).isEqualTo(33);
        ring.remove();
        assertThat(command.acquireDecoderSlot()).isFalse();
        assertThat(entry.command).isNull();
        assertThat(entry.fingerprint).isNull();
        command.releaseDecoderSlot();
        assertThat(command.acquireDecoderSlot()).isTrue();
        command.releaseDecoderSlot();
    }

    @Test void fatalDiscardReturnsUnadmittedDecoderLeasesExactlyOnce() {
        var ring = new OwnerIngressRing();
        var command = CoreMessage.decoderSlot();
        assertThat(command.acquireDecoderSlot()).isTrue();
        command.decoderPayloadBuffer(8);
        ring.offer(command, -1, 1, 2, null, 0, null);
        ring.discard(); ring.discard();
        assertThat(command.acquireDecoderSlot()).isTrue();
        command.releaseDecoderSlot();
        assertThat(ring.retainedBytes()).isZero();
        assertThatThrownBy(ring::remove).isInstanceOf(IllegalStateException.class);
    }

    @Test void boundaryDrainsOnlyItsPrecedingCommandsAndLeavesTheSuffixInTheSameRing() {
        var responses = new ArrayList<UUID>();
        var owner = new TradingCoreOwner(ProductLine.SPOT, (session, header, result, sequence) -> {
            assertThat(result.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            responses.add(header.commandId());
        });
        owner.start(new OwnerLogContext(Cluster.Role.LEADER, 0, 0, NoOpIdleStrategy.INSTANCE));
        try {
            var ring = owner.ingress();
            var first = probe(1); var second = probe(2);
            ring.offer(first, 0, 10, 100, null, 0, CommandFingerprint.of(first));
            Runnable boundary = () -> {
                owner.finishCommands();
                assertThat(responses).containsExactly(first.header().commandId());
                assertThat(owner.state().appliedCommandCount()).isEqualTo(1);
            };
            ring.offer(null, -1, 0, 0, boundary, 0, null);
            ring.offer(second, 0, 20, 200, null, 0, CommandFingerprint.of(second));
            owner.finishCommands();
            assertThat(ring.first().action).isSameAs(boundary);
            assertThat(ring.size()).isEqualTo(2);
            assertThat(owner.ownerWorkAvailable()).isFalse();
            ring.first().action.run();
            ring.remove();
            owner.finishCommands();
            assertThat(responses).containsExactly(first.header().commandId(), second.header().commandId());
            assertThat(owner.pendingCommandCount()).isZero();
            assertThat(owner.state().appliedCommandCount()).isEqualTo(2);
        } finally { owner.terminate(); }
    }

    private static CoreMessage probe(long id) {
        return CoreMessage.owned(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, new UUID(19, id),
                ProductLine.SPOT, CommandSource.GATEWAY, 19, id, 7, id, id), CoreProtocol.probePayload(1));
    }
}
