package com.surprising.aeron.support.egress;

import io.aeron.Publication;
import io.aeron.cluster.service.ClientSession;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 使用真实传输句柄和重试队列检查已编码响应的复制、同会话 FIFO、跨会话公平轮询、过期与容量释放；仅将实际发送和会话状态替换为可控夹具。
 */
class DeferredSessionResponsesTest {
    /** 本测试类使用的真实会话出口，场景结束后清理。 */
    private final ClusterServiceEgress egress = localEgress();
    /**
     * 每个场景后关闭测试出口持有的本地状态与传输句柄。
     */
    @org.junit.jupiter.api.AfterEach void closeEgress() { egress.clear(); }

    /**
     * 建立实际 egress，发送器借用测试会话 offer，并接入只提供 Leader 角色和固定时间的 Cluster 代理。
     */
    private static ClusterServiceEgress localEgress() {
        // 已接入测试 Cluster 的实际响应出口
        var egress = new ClusterServiceEgress((/* 发送器工厂附带的 Cluster，本夹具不读取 */ ignored, /* 发送器借用的原测试会话 */ target) -> /* 借用夹具会话发送的本地出口，不建立网络 Publication */ new ClusterServiceEgress.SessionEgress() {
            /**
             * 把原编码字节直接交给夹具会话的一次发送尝试。
             * @param term 出口契约的领导任期，本夹具不读取
             * @param timestamp 出口契约的时间，本夹具不读取
             * @param source 本轮已编码响应缓冲
             * @param offset 有效字节的起始偏移
             * @param length 本轮有效字节数
             */
            public long offer(long term, long timestamp, DirectBuffer source, int offset, int length) {
                return target.offer(source, offset, length);
            }
            /**
             * 夹具没有创建独立 Publication，不做额外释放。
             */
            public void close() { }
        });
        // 只提供固定角色和时间的 Cluster 代理
        var cluster = (io.aeron.cluster.service.Cluster) Proxy.newProxyInstance(
                io.aeron.cluster.service.Cluster.class.getClassLoader(),
                new Class<?>[]{io.aeron.cluster.service.Cluster.class}, (/* 动态代理对象本身，方法分发不读取 */ p, /* 当前代理方法，按名称分发会话或 Cluster 操作 */ method, /* 代理调用实参；发送分支读取编码缓冲及范围，元数据分支不读 */ args) -> switch (method.getName()) {
                    case "role" -> io.aeron.cluster.service.Cluster.Role.LEADER;
                    case "time" -> 1L;
                    default -> throw new AssertionError(method.getName());
                });
        egress.start(cluster);
        return egress;
    }

    /**
     * 首条背压后改写原数组并继续交入第二条，要求没有同步重试且复制字节不被改写；放行后每轮只发本会话队首，按一、二顺序完成。
     */
    @Test void backpressureCopiesBytesPreservesFifoAndNeverRetriesInline() {
        // 本场景实际使用的延迟响应队列
        var queue = new DeferredSessionResponses();
        // 当前可控会话夹具
        var session = new Session(1);
        // 入队后故意改写的原数组，用来证明队列保存独立复制
        byte[] bytes = {1};
        queue.offer(session.handle, new UnsafeBuffer(bytes), 1, 0);
        bytes[0] = 2;
        queue.offer(session.handle, new UnsafeBuffer(bytes), 1, 0);
        assertThat(session.offers).isOne();
        session.result = 1;
        queue.poll(1, 64);
        assertThat(session.received).containsExactly(1);
        queue.poll(2, 64);
        assertThat(session.received).containsExactly(1, 2);
        assertThat(queue.size()).isZero();
        assertThat(session.closing).isFalse();
    }

    /**
     * 建立一百三十个慢会话，仅放行最后一个；每轮预算六十四次，第三轮应到达队尾并成功，前面的持续背压不能使队尾永远无机会。
     */
    @Test void boundedRoundRobinDoesNotStarveTailBehindSlowSessions() {
        // 本场景实际使用的延迟响应队列
        var queue = new DeferredSessionResponses();
        // 按顺序创建的一百三十个慢会话夹具
        var sessions = new ArrayList<Session>();
        for (/* 本轮创建会话的循环下标 */ int i = 0; i < 130; i++) {
            // 当前可控会话夹具
            var session = new Session(i); sessions.add(session);
            queue.offer(session.handle, new UnsafeBuffer(new byte[]{1}), 1, 0);
        }
        sessions.getLast().result = 1;
        assertThat(queue.poll(1, 64)).isZero();
        assertThat(queue.poll(2, 64)).isZero();
        assertThat(sessions.getLast().received).isEmpty();
        assertThat(queue.poll(3, 64)).isOne();
        assertThat(sessions.getLast().received).containsExactly(1);
        assertThat(queue.size()).isEqualTo(129);
        assertThat(sessions.stream().mapToInt(/* 累计统计发送次数时访问的单个会话 */ s -> s.offers).sum()).isEqualTo(130 + 3 * 64);
    }

    /**
     * 让先入队会话到达一秒期限，后入队会话仍有时间且可发送；排出关闭请求后，只关闭过期会话，全部响应本地容量释放。
     */
    @Test void expiryOnlyClosesSlowSessionAndReleasesItsCapacity() {
        // 本场景实际使用的延迟响应队列
        var queue = new DeferredSessionResponses();
        // 先入队并在本轮达到截止时间的慢会话
        var slow = new Session(1); /* 后入队仍在发送期限内的可用会话 */ var later = new Session(2);
        queue.offer(slow.handle, new UnsafeBuffer(new byte[]{1}), 1, 0);
        queue.offer(later.handle, new UnsafeBuffer(new byte[]{2}), 1, 500_000_000);
        later.result = 1;
        queue.poll(1_000_000_000, 64);
        egress.flushSessionClosures();
        assertThat(slow.closing).isTrue();
        assertThat(later.closing).isFalse();
        assertThat(later.received).containsExactly(2);
        assertThat(queue.size()).isZero();
    }

    /**
     * 分别移除一个会话、让另一个返回不可重试 CLOSED，再清空新积压；只核对本地响应释放与目标关闭，不触及已提交交易状态。
     */
    @Test void removeClearAndFatalOfferReleaseOnlyLocalResponseState() {
        // 本场景实际使用的延迟响应队列
        var queue = new DeferredSessionResponses();
        // 被显式移除队列但不要求关闭的会话
        var a = new Session(1); /* 返回不可重试错误并应关闭的会话 */ var b = new Session(2);
        queue.offer(a.handle, new UnsafeBuffer(new byte[]{1}), 1, 0);
        queue.offer(b.handle, new UnsafeBuffer(new byte[]{2}), 1, 0);
        queue.remove(1);
        b.result = Publication.CLOSED;
        queue.poll(1, 64);
        egress.flushSessionClosures();
        assertThat(a.closing).isFalse(); assertThat(b.closing).isTrue();
        assertThat(queue.size()).isZero();
        queue.offer(a.handle, new UnsafeBuffer(new byte[]{3}), 1, 2);
        queue.clear();
        assertThat(queue.poll(3, 64)).isZero();
        assertThat(queue.size()).isZero();
    }

    /**
     * 一个可控慢会话夹具，记录实际发送次数、成功字节和关闭状态，并借用真实传输句柄。
     */
    private final class Session {
        /** 下一次 offer 应返回的可控 Publication 结果，默认背压。 */
        long result = Publication.BACK_PRESSURED;
        /** 实际进入会话 offer 的累计次数。 */
        int offers;
        /** 夹具实际会话是否已被关闭。 */
        boolean closing;
        /** 只记录发送成功时收到的首字节，供 FIFO 断言使用。 */
        final List<Integer> received = new ArrayList<>();
        /** 注册到实际出口的传输句柄。 */
        final ClusterServiceEgress.TransportSession handle;
        /**
         * 创建具有固定 ID、可控发送返回值与关闭状态的会话代理，再注册为实际 egress 传输句柄。
         * @param id 本夹具固定会话 ID
         */
        Session(long id) {
            // 当前夹具的 ClientSession 动态代理
            var actual = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                    new Class<?>[]{ClientSession.class}, (/* 动态代理对象本身，方法分发不读取 */ p, /* 当前代理方法，按名称分发会话或 Cluster 操作 */ method, /* 代理调用实参；发送分支读取编码缓冲及范围，元数据分支不读 */ args) -> switch (method.getName()) {
                        case "id" -> id;
                        case "isClosing" -> closing;
                        case "close" -> { closing = true; yield null; }
                        case "offer" -> {
                            offers++;
                            if (result >= 0) received.add((int) ((DirectBuffer) args[0]).getByte((int) args[1]));
                            yield result;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            handle = egress.transportSession(actual);
        }
    }
}
