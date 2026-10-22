package com.surprising.aeron.support.ingress;

import com.surprising.aeron.model.OwnerLogContext;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.TradingCoreOwner;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.Cluster;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 检查唯一输入环的字段发布、反复绕回和租约边界；条目移除、解码租约归还以及框架动作前缀完成是不同动作，测试分别验证。
 */
class OwnerIngressRingTest {
    /**
     * 由单独消费者线程与测试生产者交替业务输入和动作条目，绕环三次再多十七条；逐项核对请求、动作、指纹和原日志字段，并检查观察标记已清零，最后回收线程和输入。
     */
    @Test void concurrentCommandsAndBoundariesKeepTheirFieldsAcrossThreeWraps() throws Exception {
        // 本场景实际使用的唯一输入环
        var ring = new OwnerIngressRing();
        // 两种载荷长度的固定消息引用，按奇偶交替发送
        var commands = new CoreMessage[]{probe(1), CoreMessage.owned(probe(2).header(), new byte[64])};
        // 与两种固定消息一一对应的请求指纹
        var fingerprints = new CommandFingerprint[]{CommandFingerprint.of(commands[0]), CommandFingerprint.of(commands[1])};
        // 作为输入条目交接的框架动作，本测试按场景验证是否需要显式执行
        Runnable boundary = () -> { };
        // 超过三次完整环容量的总条目数
        int count = OwnerIngressRing.CAPACITY * 3 + 17;
        // 生产与消费循环共用的十秒截止时间
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        // 消费者任务的完成或异常结果，生产等待时也会检查它
        var consumed = new FutureTask<Void>(() -> {
            for (/* 本轮条目序号，同时决定业务字段的预期值 */ int i = 0; i < count; i++) {
                // 当前借看的输入条目，移除后必须视为可复用
                OwnerIngressRing.Entry entry;
                while ((entry = ring.first()) == null) {
                    assertThat(Thread.currentThread().isInterrupted()).isFalse();
                    assertThat(System.nanoTime()).isLessThan(deadline);
                    Thread.onSpinWait();
                }
                // 当前序号是否应交入空请求的框架动作
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
        // 与测试生产者并发读取输入环的专用线程
        var consumer = new Thread(consumed, "d4-ingress-consumer");
        consumer.start();
        try {
            for (/* 本轮条目序号，同时决定业务字段的预期值 */ int i = 0; i < count; i++) {
                // 当前序号是否应交入空请求的框架动作
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

    /**
     * 移除输入条目后槽字段应清空，但已借出的命令不能重新取得解码租约；显式归还命令租约后才可再借。
     */
    @Test void releasingTransportSlotDoesNotReleaseTheAdmittedCommandLease() {
        // 本场景实际使用的唯一输入环
        var ring = new OwnerIngressRing();
        // 具有显式解码租约的测试消息
        var command = CoreMessage.decoderSlot();
        assertThat(command.acquireDecoderSlot()).isTrue();
        command.decoderPayloadBuffer(8);
        assertThat(ring.offer(command, 0, 11, 22, null, 33, null)).isTrue();
        // 当前借看的输入条目，移除后必须视为可复用
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

    /**
     * 对仍归输入环的借用命令连续清理两次，要求解码槽可再借且字节预算归零；空环 remove 必须明确报错。
     */
    @Test void fatalDiscardReturnsUnadmittedDecoderLeasesExactlyOnce() {
        // 本场景实际使用的唯一输入环
        var ring = new OwnerIngressRing();
        // 具有显式解码租约的测试消息
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

    /**
     * 把两条余额命令分放在动作前后，真实 Owner 完成前缀时必须停在动作处；执行并移除动作后再完成后继，响应和应用数保持原顺序。
     */
    @Test void boundaryDrainsOnlyItsPrecedingCommandsAndLeavesTheSuffixInTheSameRing() {
        // 真实 Owner 已交出终态的 UUID 列表，用来核对动作前后顺序
        var responses = new ArrayList<UUID>();
        // 直接在测试线程推进的真实交易 Owner
        var owner = new TradingCoreOwner(ProductLine.SPOT, (/* 终态回调附带的会话 ID，本测试只检查结果与 UUID */ session, /* 终态回调的响应头，用其 UUID 记录完成顺序 */ header, /* 终态回调交出的结果，要求业务状态成功 */ result, /* 终态回调附带的提交序号，本场景不单独读取 */ sequence) -> {
            assertThat(result.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            responses.add(header.commandId());
        });
        owner.start(new OwnerLogContext(Cluster.Role.LEADER, 0, 0, NoOpIdleStrategy.INSTANCE));
        try {
            // 本场景实际使用的唯一输入环
            var ring = owner.ingress();
            // 位于动作边界之前的余额命令
            var first = probe(1); /* 位于动作边界之后的余额命令 */ var second = probe(2);
            ring.offer(first, 0, 10, 100, null, 0, CommandFingerprint.of(first));
            // 作为输入条目交接的框架动作，本测试按场景验证是否需要显式执行
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

    /**
     * 构造现货余额加一的唯一命令，用指定序号生成 UUID 和来源进度。
     * @param id 探针唯一命令序号
     */
    private static CoreMessage probe(long id) {
        return CoreMessage.owned(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE, new UUID(19, id),
                ProductLine.SPOT, CommandSource.GATEWAY, 19, id, 7, id, id), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 1)));
    }
}
