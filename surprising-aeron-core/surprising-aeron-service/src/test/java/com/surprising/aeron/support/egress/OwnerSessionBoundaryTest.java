package com.surprising.aeron.support.egress;

import com.surprising.aeron.bootstrap.TradingOwnerLoop;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import io.aeron.Publication;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖六产品线真实 OwnerLoop 到回调线程的响应交接，使用实际传输句柄、输出环和背压队列，只把网络发送替换成夹具；每次会话及 Cluster 访问都检查在原回调线程。
 */
class OwnerSessionBoundaryTest {
    /**
     * 先背压两条已完成响应，再放行并核对 FIFO；切到 Follower 后丢弃旧代输出，重新当选后新命令正常响应且应用计数包含此前已提交命令，最后核对通道创建、断开和会话关闭均在回调侧。
     * @param product 当前参数化产品线
     */
    @ParameterizedTest @EnumSource(ProductLine.class) @Timeout(20)
    void ownerHandsResponsesBackToServiceThreadAcrossBackpressureAndRoleChanges(ProductLine product) {
        // 唯一允许调用 Cluster、会话与发送器的原测试线程
        Thread serviceThread = Thread.currentThread();
        // 可控 Cluster 角色，切换时同步交给真实 Owner 边界
        var role = new AtomicReference<>(Cluster.Role.LEADER);
        // 实际测试会话的原子关闭标记
        var closing = new AtomicBoolean();
        // 网络发送器的可控背压或成功返回值
        var result = new AtomicLong(Publication.BACK_PRESSURED);
        // 发送通道工厂实际被调用的次数
        var opened = new AtomicInteger();
        // 发送通道实际被关闭的次数
        var closed = new AtomicInteger();
        // 成功发送后复制解码的独立响应列表
        var received = new ArrayList<CoreMessage>();
        // 只允许回调线程访问的实际测试会话代理
        var session = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (/* 动态代理对象本身，方法分发不读取 */ proxy, /* 当前代理方法，按名称分发会话或 Cluster 操作 */ method, /* 代理调用实参；发送分支读取编码缓冲及范围，元数据分支不读 */ args) -> {
                    assertThat(Thread.currentThread()).as("ClientSession.%s", method.getName()).isSameAs(serviceThread);
                    return switch (method.getName()) {
                        case "id" -> 91L;
                        case "isClosing" -> closing.get();
                        case "close" -> { closing.set(true); yield null; }
                        default -> throw new AssertionError("unexpected session API " + method.getName());
                    };
                });
        // 只允许回调线程访问的 Cluster 元数据代理
        var cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(),
                new Class<?>[]{Cluster.class}, (/* 动态代理对象本身，方法分发不读取 */ proxy, /* 当前代理方法，按名称分发会话或 Cluster 操作 */ method, /* 代理调用实参；发送分支读取编码缓冲及范围，元数据分支不读 */ args) -> {
                    assertThat(Thread.currentThread()).as("Cluster.%s", method.getName()).isSameAs(serviceThread);
                    return switch (method.getName()) {
                        case "role" -> role.get();
                        case "memberId" -> 7;
                        case "time", "logPosition" -> 11L;
                        case "idleStrategy" -> NoOpIdleStrategy.INSTANCE;
                        default -> throw new AssertionError("unexpected Cluster API " + method.getName());
                    };
                });
        // 只替换网络发送，实际 Owner、会话句柄、输出环和背压重试队列都使用生产实现。
        // 本场景实际响应出口及重试队列
        var egress = new ClusterServiceEgress((/* 发送器工厂附带的 Cluster，本夹具不读取 */ ignored, /* 工厂收到的实际会话，必须就是本测试代理 */ actual) -> {
            assertThat(Thread.currentThread()).isSameAs(serviceThread);
            assertThat(actual).isSameAs(session);
            opened.incrementAndGet();
            return /* 可控背压的测试发送器，检查所有调用线程，并保留成功响应的独立副本 */ new ClusterServiceEgress.SessionEgress() {
                /**
                 * 要求当前仍是回调线程；可控返回值为成功时复制并解码响应，背压时不记录接收，最后原样返回结果。
                 * @param term 出口契约的领导任期，本夹具不读取
                 * @param timestamp 出口契约的时间，本夹具不读取
                 * @param source 本轮已编码响应缓冲
                 * @param offset 有效字节的起始偏移
                 * @param length 本轮有效字节数
                 */
                public long offer(long term, long timestamp, DirectBuffer source, int offset, int length) {
                    assertThat(Thread.currentThread()).isSameAs(serviceThread);
                    if (result.get() >= 0) {
                        // 当前成功发送响应的独立字节副本
                        byte[] encoded = new byte[length];
                        source.getBytes(offset, encoded);
                        received.add(CoreMessageCodec.decode(encoded));
                    }
                    return result.get();
                }
                /**
                 * 检查关闭发生在回调线程，并累计一次发送通道关闭事件。
                 */
                public void close() { assertThat(Thread.currentThread()).isSameAs(serviceThread); closed.incrementAndGet(); }
            };
        });
        egress.start(cluster);
        // 启动真实 Owner 线程的生产循环对象
        var loop = new TradingOwnerLoop(product, egress);
        try {
            loop.start(cluster, null);
            // 已注册到实际出口的传输句柄
            var transport = egress.transportSession(session);
            loop.enqueue(probe(product, 1), transport.id(), 70, 700, null);
            loop.enqueue(probe(product, 2), transport.id(), 80, 800, null);
            loop.onRoleChange(Cluster.Role.LEADER, 0);
            assertThat(received).isEmpty();
            result.set(1);
            loop.drainResponses();
            loop.drainResponses();
            assertThat(received).extracting(/* 提取已接收响应 UUID 时访问的单条独立消息 */ message -> message.header().commandId())
                    .containsExactly(new UUID(72, 1), new UUID(72, 2));

            result.set(Publication.BACK_PRESSURED);
            loop.enqueue(probe(product, 3), transport.id(), 90, 900, null);
            role.set(Cluster.Role.FOLLOWER);
            loop.onRoleChange(role.get(), egress.onRoleChange(role.get()));
            loop.drainResponses();
            assertThat(received).hasSize(2);
            assertThat(closed).hasValue(1);

            result.set(1);
            role.set(Cluster.Role.LEADER);
            loop.onRoleChange(role.get(), egress.onRoleChange(role.get()));
            loop.enqueue(probe(product, 4), transport.id(), 100, 1000, null);
            loop.onRoleChange(Cluster.Role.LEADER, 2);
            loop.drainResponses();
            assertThat(received).hasSize(3);
            assertThat(received.getLast().header().commandId()).isEqualTo(new UUID(72, 4));
            assertThat(CoreProtocol.decodeResponse(received.getLast().payloadUnsafe()).appliedCommandCount()).isEqualTo(4);
            assertThat(opened).hasValue(2);
            transport.close();
            egress.flushSessionClosures();
            assertThat(closing).isTrue();
            egress.closeSession(session);
            assertThat(closed).hasValue(2);
            loop.checkFailure();
        } finally { loop.terminate(); egress.clear(); }
    }

    /**
     * 按产品线和来源序号构造余额加一命令，用于检查旧代丢弃不撤销已提交业务。
     * @param product 当前参数化产品线
     * @param sequence 探针唯一来源和 UUID 序号
     */
    private static CoreMessage probe(ProductLine product, long sequence) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                new UUID(72, sequence), product, CommandSource.OPERATIONS, 72, sequence, 7, 1, sequence),
                com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 1)));
    }
}
