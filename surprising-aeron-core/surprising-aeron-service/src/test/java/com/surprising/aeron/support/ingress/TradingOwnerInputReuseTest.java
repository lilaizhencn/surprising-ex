package com.surprising.aeron.support.ingress;

import com.surprising.aeron.bootstrap.TradingOwnerLoop;
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
 * 验证实际 Owner 输入交接不会在环满、字节预算满、中断、失败或停机时覆盖仍被借用的条目；另以真实 Owner 线程完成两次完整输入环绕回并核对响应顺序。
 */
class TradingOwnerInputReuseTest {
    /**
     * 不启动 Owner 工作线程，直接填满它的真实输入环；额外生产者必须等待且不能改队首。测试移除一条后允许生产者复用该槽，并核对新旧字段和预算。
     */
    @Test void fullQueueKeepsHeadStableUntilOwnerAdvancesTheActualRing() throws Exception {
        // 生产者确实开始等待输入空位的通知门闩
        var waiting = new CountDownLatch(1);
        // 与真实 Owner 配套的生产响应出口，测试结束清理
        var egress = new ClusterServiceEgress();
        // 本测试使用的实际 OwnerLoop；部分场景只操作其输入环而不启动线程
        var loop = new TradingOwnerLoop(ProductLine.SPOT, egress);
        // 只支持夹具所需回调方法的 Cluster 代理
        Cluster cluster = cluster(waiting);
        egress.start(cluster); set(loop, "cluster", cluster);
        // 从实际 OwnerLoop 借出的唯一输入环
        var input = input(loop);
        // 用于填满环的旧命令引用
        CoreMessage first = probe(1);
        for (/* 当前填槽或清槽的循环下标 */ int i = 0; i < OwnerIngressRing.CAPACITY; i++)
            loop.enqueue(first, ClusterServiceEgress.NO_SESSION_ID, i, i + 10, null);
        // 生产者被阻塞前保存的原队首条目，必须保持不变
        var owned = input.first();
        // 额外生产者等待入队的新命令
        CoreMessage successor = probe(2);
        // 额外生产者捕获的故障，用原子引用交回测试
        var failure = new AtomicReference<Throwable>();
        // 故意在容量边界等待或中断入队的生产者线程
        Thread producer = new Thread(() -> {
            try { loop.enqueue(successor, ClusterServiceEgress.NO_SESSION_ID, 99, 199, null); }
            catch (/* 生产者捕获的任意错误，交回主测试断言 */ Throwable fatal) { failure.set(fatal); }
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
            for (/* 当前填槽或清槽的循环下标 */ int i = 1; i < OwnerIngressRing.CAPACITY; i++) input.remove();
            // 环绕回后复用原队首位置的条目，必须含新命令字段
            var reused = input.first();
            assertThat(reused).isSameAs(owned);
            assertThat(reused.command).isSameAs(successor);
            assertThat(reused.timestamp).isEqualTo(99);
            assertThat(reused.position).isEqualTo(199);
            input.remove();
            assertThat(input.size()).isZero();
            assertThat(input.retainedBytes()).isZero();
            assertThat(owned.command).isNull();
            assertThat(owned.action).isNull();
        } finally {
            producer.interrupt(); producer.join(5_000);
            input.discard(); egress.clear();
        }
    }

    /**
     * 用六十四个一 MiB 载荷填满字节预算，让已中断生产者尝试再入队；要求明确终止且条目与字节数未变，清理后仍可用满全部条目容量。
     */
    @Test void interruptedByteBudgetWaitDoesNotClaimOrLoseAnInputSlot() throws Exception {
        // 与真实 Owner 配套的生产响应出口，测试结束清理
        var egress = new ClusterServiceEgress();
        // 本测试使用的实际 OwnerLoop；部分场景只操作其输入环而不启动线程
        var loop = new TradingOwnerLoop(ProductLine.SPOT, egress);
        // 只支持夹具所需回调方法的 Cluster 代理
        Cluster cluster = cluster(new CountDownLatch(1));
        egress.start(cluster); set(loop, "cluster", cluster);
        // 从实际 OwnerLoop 借出的唯一输入环
        var input = input(loop);
        // 有效载荷为一 MiB 的消息，用来只压满字节预算
        var megabyte = CoreMessage.owned(probe(1).header(), new byte[1024 * 1024]);
        for (/* 当前填槽或清槽的循环下标 */ int i = 0; i < 64; i++) loop.enqueue(megabyte, -1, i, i, null);
        assertThat(input.retainedBytes()).isEqualTo(OwnerIngressRing.MAX_BYTES);
        // 额外生产者捕获的故障，用原子引用交回测试
        var failure = new AtomicReference<Throwable>();
        // 故意在容量边界等待或中断入队的生产者线程
        Thread producer = new Thread(() -> {
            Thread.currentThread().interrupt();
            try { loop.enqueue(probe(1), -1, 1, 10, null); }
            catch (/* 生产者捕获的任意错误，交回主测试断言 */ Throwable fatal) { failure.set(fatal); }
        });
        producer.start(); producer.join(5_000);
        assertThat(producer.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(org.agrona.concurrent.AgentTerminationException.class);
        assertThat(input.size()).isEqualTo(64);
        assertThat(input.retainedBytes()).isEqualTo(OwnerIngressRing.MAX_BYTES);
        input.discard();
        for (/* 当前填槽或清槽的循环下标 */ int i = 0; i < OwnerIngressRing.CAPACITY; i++)
            assertThat(input.offer(probe(i + 2), -1, i, i, null, 0, null)).isTrue();
        assertThat(input.offer(probe(99_999), -1, 0, 0, null, 0, null)).isFalse();
        input.discard(); egress.clear();
    }

    /**
     * 给 Owner 注入已有失败，让真实 Cluster service 连续两次解码并入队失败；每次都要求解码槽租约可重新取得，输入环保持为空。
     */
    @Test void failedServiceEnqueueReturnsTheBorrowedDecoderLease() throws Exception {
        // 与真实 Owner 配套的生产响应出口，测试结束清理
        var egress = new ClusterServiceEgress();
        // 本测试使用的实际 OwnerLoop；部分场景只操作其输入环而不启动线程
        var loop = new TradingOwnerLoop(ProductLine.SPOT, egress);
        set(loop, "failure", new IllegalStateException("stopped Owner"));
        // 真实 Cluster 回调服务，用来覆盖解码到输入交接失败的 finally 释放
        var service = new com.surprising.aeron.controller.AeronTradingClusterService(loop, egress);
        // 原命令编码字节，或会话接收时复制的响应字节
        byte[] bytes = CoreMessageCodec.encode(probe(1));
        // 包裹已编码输入帧的 DirectBuffer
        var buffer = new org.agrona.concurrent.UnsafeBuffer(bytes);
        // 提供日志位置的测试 Aeron 消息头
        var header = new io.aeron.logbuffer.Header(0, 0)
                .buffer(new org.agrona.concurrent.UnsafeBuffer(new byte[64])).offset(0);
        for (/* 重复制造失败入队的轮次 */ int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> service.onSessionMessage(null, 1, buffer, 0, bytes.length, header))
                    .isInstanceOf(org.agrona.concurrent.AgentTerminationException.class);
            // 真实 service 内使用的解码器，通过反射检查借用槽
            var decoder = field(service, "messageDecoder");
            // 解码器实际缓存数组，检查失败交接后租约已归还
            var slots = (CoreMessage[]) field(decoder, "commandSlots");
            assertThat(slots[0]).isNotNull();
            assertThat(slots[0].acquireDecoderSlot()).as("failed handoff retains no reader").isTrue();
            slots[0].releaseDecoderSlot();
            assertThat(input(loop).size()).isZero();
        }
    }

    /**
     * 真实 Owner 已退出后，模拟生产者最后一次入队才变得可见；再次 terminate 必须由外层清掉残余条目并归还解码租约。
     */
    @Test void terminationAlsoReleasesInputPublishedAfterTheWorkerExit() throws Exception {
        // 只支持夹具所需回调方法的 Cluster 代理
        var cluster = cluster(new CountDownLatch(1));
        // 与真实 Owner 配套的生产响应出口，测试结束清理
        var egress = new ClusterServiceEgress();
        egress.start(cluster);
        // 本测试使用的实际 OwnerLoop；部分场景只操作其输入环而不启动线程
        var loop = new TradingOwnerLoop(ProductLine.SPOT, egress);
        try {
            loop.start(cluster, null);
            loop.terminate();
            // 模拟工作线程最后清理结束后，生产者最后一次入队才变得可见。
            // 在 Owner 已退出后才交入的带解码租约消息
            var command = CoreMessage.decoderSlot();
            assertThat(command.acquireDecoderSlot()).isTrue();
            command.decoderPayloadBuffer(8);
            assertThat(input(loop).offer(command, -1, 1, 2, null, 0, null)).isTrue();
            loop.terminate();
            assertThat(input(loop).size()).isZero();
            assertThat(command.acquireDecoderSlot()).isTrue();
            command.releaseDecoderSlot();
        } finally { loop.terminate(); egress.clear(); }
    }

    /**
     * 真实 Owner 处理一万六千三百八十四条余额命令，每二百五十六条排响应、每整环经过一次控制边界；逐条解码核对 UUID 与成功状态，最终输入与字节预算清零。
     */
    @Test void actualOwnerPreservesResponsesAcrossTwoCompleteInputPoolWraps() throws Exception {
        // 只支持夹具所需回调方法的 Cluster 代理
        var cluster = cluster(new CountDownLatch(1));
        // 与真实 Owner 配套的生产响应出口，测试结束清理
        var egress = new ClusterServiceEgress((/* 会话发送器工厂的 Cluster 参数，当前夹具不读取 */ ignored, /* 发送器工厂借用的原测试会话 */ target) -> /* 只借用夹具会话 offer 的发送器，不创建独立网络 Publication */ new ClusterServiceEgress.SessionEgress() {
            /**
             * 借用夹具会话发送编码字节，不读任期与时间参数。
             * @param term 发送契约附带的任期值，本夹具不读取
             * @param timestamp 发送契约附带的时间值，本夹具不读取
             * @param source 当前编码响应的原缓冲
             * @param offset 有效响应字节的起始偏移
             * @param length 有效响应字节数
             */
            public long offer(long term, long timestamp, DirectBuffer source, int offset, int length) {
                return target.offer(source, offset, length);
            }
            /**
             * 夹具没有创建实际 Publication，此处没有额外资源需释放。
             */
            public void close() { }
        }); egress.start(cluster);
        // 本测试使用的实际 OwnerLoop；部分场景只操作其输入环而不启动线程
        var loop = new TradingOwnerLoop(ProductLine.SPOT, egress);
        // 固定单槽保存已接收响应条数，发送代理据此核对 UUID 顺序
        long[] received = {0};
        // 把编码响应交回测试的 ClientSession 代理
        var session = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (/* 动态代理对象本身，当前分发不读取 */ proxy, /* 当前代理方法，按名称提供元数据或接收响应 */ method, /* 代理调用实参；会话发送分支读取缓冲、偏移和长度，元数据分支不读 */ args) -> switch (method.getName()) {
                    case "id" -> 1L;
                    case "isClosing" -> false;
                    case "offer" -> {
                        // 原命令编码字节，或会话接收时复制的响应字节
                        byte[] bytes = new byte[(int) args[2]];
                        ((DirectBuffer) args[0]).getBytes((int) args[1], bytes);
                        // 从本条编码响应解码出的独立消息
                        CoreMessage result = CoreMessageCodec.decode(bytes);
                        // 收到本条响应时递增的预期命令序号
                        long expected = ++received[0];
                        assertThat(result.header().commandId()).isEqualTo(new UUID(15, expected));
                        assertThat(CoreProtocol.decodeResponse(result.payloadUnsafe()).commandStatus())
                                .isEqualTo(ResponseStatus.APPLIED);
                        yield 1L;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        try {
            loop.start(cluster, null);
            for (/* 真实 Owner 本轮处理的唯一余额命令序号 */ long id = 1; id <= 16_384; id++) {
                loop.enqueue(probe(id), egress.transportSession(session).id(), id, id + 10, null);
                if ((id & 8191) == 0) loop.onRoleChange(Cluster.Role.LEADER, 0);
                if ((id & 255) == 0) {
                    // 本小批等待响应的十秒截止时间
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (received[0] < id) {
                        loop.drainResponses(); loop.checkFailure();
                        assertThat(System.nanoTime()).isLessThan(deadline);
                        Thread.onSpinWait();
                    }
                }
            }
            assertThat(received[0]).isEqualTo(16_384);
        } finally { loop.terminate(); egress.clear(); }
        assertThat(input(loop).size()).isZero();
        assertThat(input(loop).retainedBytes()).isZero();
    }

    /**
     * 用指定来源序号构造现货余额加一命令，保持 UUID 和输入顺序一致。
     * @param id 余额探针唯一命令和来源序号
     */
    private static CoreMessage probe(long id) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE, new UUID(15, id),
                ProductLine.SPOT, CommandSource.GATEWAY, 15, id, 7, 1, id), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 1)));
    }

    /**
     * 创建只支持本组测试所需元数据和等待策略的 Cluster 代理；进入等待时用门闩通知测试线程。
     * @param waiting 进入回调等待时通知测试的门闩
     */
    private static Cluster cluster(CountDownLatch waiting) {
        // 会通知门闩且只自旋的测试等待器
        var idle = /* 用于观察生产者进入等待的测试策略，不维护退避状态 */ new IdleStrategy() {
            /**
             * 有工作量时不退避，零工作量转入无参等待。
             * @param workCount 上一轮报告的工作量，零才调用无参等待
             */
            @Override public void idle(int workCount) { if (workCount == 0) idle(); }
            /**
             * 通知本测试已进入等待，然后发出自旋提示，不引入真实睡眠。
             */
            @Override public void idle() { waiting.countDown(); Thread.onSpinWait(); }
            /**
             * 夹具等待器没有可重置的退避状态。
             */
            @Override public void reset() { }
        };
        return (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (/* 动态代理对象本身，当前分发不读取 */ proxy, /* 当前代理方法，按名称提供元数据或接收响应 */ method, /* 代理调用实参；会话发送分支读取缓冲、偏移和长度，元数据分支不读 */ args) -> switch (method.getName()) {
                    case "role" -> Cluster.Role.LEADER;
                    case "timeUnit" -> TimeUnit.MILLISECONDS;
                    case "idleStrategy" -> idle;
                    case "time" -> 1L;
                    case "logPosition" -> 0L;
                    case "memberId" -> 0;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /**
     * 反射借看 OwnerLoop 实际持有的输入环，不创建替代队列。
     * @param loop 要读取真实输入环的生产 OwnerLoop
     */
    private static OwnerIngressRing input(TradingOwnerLoop loop) throws Exception {
        return (OwnerIngressRing) field(loop, "input");
    }
    /**
     * 读取目标类直接声明的测试字段，反射异常继续交给测试。
     * @param owner 要反射操作的目标对象
     * @param name 目标类直接声明字段的准确名称
     */
    private static Object field(Object owner, String name) throws Exception {
        // 目标对象类直接声明的字段，设置可访问后读写
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    /**
     * 写入目标类直接声明的字段，用于构造生产边界故障或接入测试 Cluster。
     * @param owner 要反射操作的目标对象
     * @param name 目标类直接声明字段的准确名称
     * @param value 要写入字段的测试值
     */
    private static void set(Object owner, String name, Object value) throws Exception {
        // 目标对象类直接声明的字段，设置可访问后读写
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
}
