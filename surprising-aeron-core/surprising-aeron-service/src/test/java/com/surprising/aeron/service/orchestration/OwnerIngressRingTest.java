package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.Cluster;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OwnerIngressRingTest {
    @Test void concurrentCommandsAndBoundariesKeepTheirFieldsAcrossThreeWraps() throws Exception {
        var ring = new OwnerIngressRing();
        var commands = new CoreMessage[]{probe(1), CoreMessage.owned(probe(2).header(), new byte[64])};
        var fingerprints = new CommandFingerprint[]{CommandFingerprint.of(commands[0]), CommandFingerprint.of(commands[1])};
        Runnable boundary = () -> { };
        int count = OwnerIngressRing.CAPACITY * 3 + 17;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var consumed = new FutureTask<Void>(() -> {
            for (int i = 0; i < count; i++) {
                OwnerIngressRing.Entry entry;
                while ((entry = ring.first()) == null) {
                    assertThat(Thread.currentThread().isInterrupted()).isFalse();
                    assertThat(System.nanoTime()).isLessThan(deadline);
                    Thread.onSpinWait();
                }
                boolean isBoundary = i % 7 == 0;
                assertThat(entry.command).isSameAs(isBoundary ? null : commands[i & 1]);
                assertThat(entry.action).isSameAs(isBoundary ? boundary : null);
                assertThat(entry.fingerprint).isSameAs(isBoundary ? null : fingerprints[i & 1]);
                assertThat(entry.sessionId).isEqualTo(i);
                assertThat(entry.timestamp).isEqualTo(i + 10L);
                assertThat(entry.position).isEqualTo(i + 20L);
                assertThat(entry.enqueuedNanos).isEqualTo(i + 30L);
                assertThat(entry.ownerObserved).isFalse();
                entry.ownerObserved = true;
                ring.remove();
            }
            return null;
        });
        var consumer = new Thread(consumed, "d4-ingress-consumer");
        consumer.start();
        try {
            for (int i = 0; i < count; i++) {
                boolean isBoundary = i % 7 == 0;
                while (!ring.offer(isBoundary ? null : commands[i & 1], i, i + 10L, i + 20L,
                        isBoundary ? boundary : null, i + 30L, isBoundary ? null : fingerprints[i & 1])) {
                    if (consumed.isDone()) consumed.get();
                    assertThat(System.nanoTime()).isLessThan(deadline);
                    Thread.onSpinWait();
                }
            }
            consumed.get(10, TimeUnit.SECONDS);
            assertThat(ring.size()).isZero();
            assertThat(ring.retainedBytes()).isZero();
        } finally {
            consumer.interrupt();
            consumer.join(5_000);
            assertThat(consumer.isAlive()).isFalse();
            ring.discard();
        }
    }

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
        return CoreMessage.owned(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE, new UUID(19, id),
                ProductLine.SPOT, CommandSource.GATEWAY, 19, id, 7, id, id), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 1)));
    }
}
