package com.surprising.aeron.support.ingress;

import com.surprising.aeron.config.CoreConfiguration;
import com.surprising.aeron.config.CoreRuntimeConfiguration;
import com.surprising.aeron.config.RealtimeConfiguration;
import com.surprising.aeron.config.TradingCpuAffinity;
import com.surprising.aeron.controller.AeronTradingClusterService;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.support.egress.ClusterServiceEgress;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 验证实际服务输入交接不会在环满、字节预算满、中断、失败或停机时覆盖仍被借用的条目；另以真实内联服务完成两次完整输入环绕回并核对响应顺序。
 */
class TradingOwnerInputReuseTest {
    /**
     * 不启动工作线程，直接填满它的真实输入环；额外生产者必须等待且不能改队首。测试移除一条后允许生产者复用该槽，并核对新旧字段和预算。
     */
    @Test void fullQueueKeepsHeadStableUntilOwnerAdvancesTheActualRing() throws Exception {
        var waiting = new CountDownLatch(1);
        var egress = new ClusterServiceEgress();
        var loop = new AeronTradingClusterService(ProductLine.SPOT, egress, TradingCpuAffinity.disabled(),
                CoreConfiguration.standaloneLaneTopology(), CoreRuntimeConfiguration.standalone(),
                RealtimeConfiguration.standalone());
        Cluster cluster = cluster(waiting);
        egress.start(cluster); set(loop, "cluster", cluster);
        var input = input(loop);
        CoreMessage first = probe(1);
        for (int i = 0; i < OwnerIngressRing.CAPACITY; i++)
            loop.enqueue(first, ClusterServiceEgress.NO_SESSION_ID, i, i + 10, null);
        var owned = input.first();
        CoreMessage successor = probe(2);
        var failure = new AtomicReference<Throwable>();
        Thread producer = new Thread(() -> {
            try { loop.enqueue(successor, ClusterServiceEgress.NO_SESSION_ID, 99, 199, null); }
            catch (Throwable fatal) { failure.set(fatal); }
        });
        producer.start();
        try {
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(input.first()).isSameAs(owned);
            assertThat(owned.command).isSameAs(first);
            assertThat(owned.position).isEqualTo(10);
            assertThat(input.size()).isEqualTo(OwnerIngressRing.CAPACITY);
            input.remove();
            producer.join(5_000);
            assertThat(producer.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            for (int i = 1; i < OwnerIngressRing.CAPACITY; i++) input.remove();
            var reused = input.first();
            assertThat(reused).isSameAs(owned);
            assertThat(reused.command).isSameAs(successor);
            assertThat(reused.timestamp).isEqualTo(99);
            assertThat(reused.position).isEqualTo(199);
            input.remove();
            assertThat(input.size()).isZero();
            assertThat(input.retainedBytes()).isZero();
            assertThat(owned.command).isNull();
        } finally {
            producer.interrupt(); producer.join(5_000);
            input.discard(); egress.clear();
        }
    }

    /**
     * 填满输入环全部容量后尝试再入队，要求明确抛出饱和异常且条目数保持不变，清理后仍可用满全部条目容量。
     */
    @Test void saturatedRingRejectsWithoutLosingInputSlot() throws Exception {
        var egress = new ClusterServiceEgress();
        var loop = new AeronTradingClusterService(ProductLine.SPOT, egress, TradingCpuAffinity.disabled(),
                CoreConfiguration.standaloneLaneTopology(), CoreRuntimeConfiguration.standalone(),
                RealtimeConfiguration.standalone());
        Cluster cluster = cluster(new CountDownLatch(1));
        egress.start(cluster); set(loop, "cluster", cluster);
        var input = input(loop);
        for (int i = 0; i < OwnerIngressRing.CAPACITY; i++) loop.enqueue(probe(i), -1, i, i, null);
        assertThat(input.size()).isEqualTo(OwnerIngressRing.CAPACITY);
        assertThatThrownBy(() -> loop.enqueue(probe(9999), -1, 9999, 9999, null))
                .isInstanceOf(org.agrona.concurrent.AgentTerminationException.class);
        assertThat(input.size()).isEqualTo(OwnerIngressRing.CAPACITY);
        input.discard(); egress.clear();
        assertThat(input.size()).isZero();
        for (int i = 0; i < OwnerIngressRing.CAPACITY; i++) loop.enqueue(probe(i), -1, i, i, null);
        assertThat(input.size()).isEqualTo(OwnerIngressRing.CAPACITY);
        input.discard(); egress.clear();
    }

    /**
     * 覆盖解码到输入交接失败的 finally 释放租约。
     */
    @Test void failedServiceEnqueueReturnsTheBorrowedDecoderLease() throws Exception {
        var egress = new ClusterServiceEgress();
        var service = new AeronTradingClusterService(ProductLine.SPOT, egress, TradingCpuAffinity.disabled(),
                CoreConfiguration.standaloneLaneTopology(), CoreRuntimeConfiguration.standalone(),
                RealtimeConfiguration.standalone());
        set(service, "failure", new IllegalStateException("stopped Owner"));
        byte[] bytes = CoreMessageCodec.encode(probe(1));
        var buffer = new org.agrona.concurrent.UnsafeBuffer(bytes);
        var header = new io.aeron.logbuffer.Header(0, 0)
                .buffer(new org.agrona.concurrent.UnsafeBuffer(new byte[64])).offset(0);
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> service.onSessionMessage(null, 1, buffer, 0, bytes.length, header))
                    .isInstanceOf(org.agrona.concurrent.AgentTerminationException.class);
            var decoder = field(service, "messageDecoder");
            var slots = (CoreMessage[]) field(decoder, "commandSlots");
            assertThat(slots[0]).isNotNull();
            assertThat(slots[0].acquireDecoderSlot()).as("failed handoff retains no reader").isTrue();
            slots[0].releaseDecoderSlot();
            assertThat(input(service).size()).isZero();
        }
    }

    /**
     * 真实服务已退出后，模拟生产者最后一次入队才变得可见；再次 terminate 必须由外层清掉残余条目并归还解码租约。
     */
    @Test void terminationAlsoReleasesInputPublishedAfterTheWorkerExit() throws Exception {
        var cluster = cluster(new CountDownLatch(1));
        var egress = new ClusterServiceEgress();
        egress.start(cluster);
        var loop = new AeronTradingClusterService(ProductLine.SPOT, egress, TradingCpuAffinity.disabled(),
                CoreConfiguration.standaloneLaneTopology(), CoreRuntimeConfiguration.standalone(),
                RealtimeConfiguration.standalone());
        try {
            loop.onStart(cluster, null);
            loop.onTerminate(cluster);
            var command = CoreMessage.decoderSlot();
            assertThat(command.acquireDecoderSlot()).isTrue();
            command.decoderPayloadBuffer(8);
            assertThat(input(loop).offer(command, -1, 1, 2, null)).isTrue();
            loop.onTerminate(cluster);
            assertThat(input(loop).size()).isZero();
            assertThat(command.acquireDecoderSlot()).isTrue();
            command.releaseDecoderSlot();
        } finally { loop.onTerminate(cluster); egress.clear(); }
    }

    /**
     * 真实服务处理一万六千三百八十四条余额命令，每二百五十六条排响应、每整环经过一次控制边界；逐条解码核对 UUID 与成功状态，最终输入与字节预算清零。
     */
    @Test void actualOwnerPreservesResponsesAcrossTwoCompleteInputPoolWraps() throws Exception {
        var cluster = cluster(new CountDownLatch(1));
        var egress = new ClusterServiceEgress((ignored, target) -> new ClusterServiceEgress.SessionEgress() {
            public long offer(long term, long timestamp, DirectBuffer source, int offset, int length) {
                return target.offer(source, offset, length);
            }
            public void close() { }
        }); egress.start(cluster);
        var loop = new AeronTradingClusterService(ProductLine.SPOT, egress, TradingCpuAffinity.disabled(),
                CoreConfiguration.standaloneLaneTopology(), CoreRuntimeConfiguration.standalone(),
                RealtimeConfiguration.standalone());
        long[] received = {0};
        var session = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> 1L;
                    case "isClosing" -> false;
                    case "offer" -> {
                        byte[] bytes = new byte[(int) args[2]];
                        ((DirectBuffer) args[0]).getBytes((int) args[1], bytes);
                        CoreMessage result = CoreMessageCodec.decode(bytes);
                        long expected = ++received[0];
                        assertThat(result.header().commandId()).isEqualTo(new UUID(15, expected));
                        assertThat(CoreProtocol.decodeResponse(result.payloadUnsafe()).commandStatus())
                                .isEqualTo(ResponseStatus.APPLIED);
                        yield (long) bytes.length;
                    }
                    default -> throw new AssertionError(method.getName());
                });
        long sessionId = egress.transportSession(session).id();
        loop.onStart(cluster, null);
        try {
            for (long id = 1; id <= 16_384; id++) {
                loop.enqueue(probe(id), sessionId, id, id + 10, null);
                if ((id & 8191) == 0) loop.onRoleChange(Cluster.Role.LEADER, 0);
                if ((id & 255) == 0) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (received[0] < id) {
                        loop.drainResponses(); loop.checkFailure();
                        assertThat(System.nanoTime()).isLessThan(deadline);
                        Thread.onSpinWait();
                    }
                }
            }
            assertThat(received[0]).isEqualTo(16_384);
        } finally { loop.onTerminate(cluster); egress.clear(); }
        assertThat(input(loop).size()).isZero();
        assertThat(input(loop).retainedBytes()).isZero();
    }

    private static CoreMessage probe(long id) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE, new UUID(15, id),
                ProductLine.SPOT, CommandSource.GATEWAY, 15, id, 7, 1, id), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 1)));
    }

    private static Cluster cluster(CountDownLatch waiting) {
        var idle = new IdleStrategy() {
            @Override public void idle(int workCount) { if (workCount == 0) idle(); }
            @Override public void idle() { waiting.countDown(); Thread.onSpinWait(); }
            @Override public void reset() { }
        };
        return (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "role" -> Cluster.Role.LEADER;
                    case "timeUnit" -> TimeUnit.MILLISECONDS;
                    case "idleStrategy" -> idle;
                    case "time" -> 1L;
                    case "logPosition" -> 0L;
                    case "memberId" -> 0;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static OwnerIngressRing input(AeronTradingClusterService loop) throws Exception {
        return (OwnerIngressRing) field(loop, "input");
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
}
