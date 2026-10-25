package com.surprising.aeron.support.egress;

import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.service.core.TradingCoreOwner;
import io.aeron.Publication;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.codecs.MessageHeaderEncoder;
import io.aeron.cluster.codecs.SessionMessageHeaderEncoder;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import java.util.Objects;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.springframework.stereotype.Component;

/**
 * 交易步骤 6：在 Cluster 回调线程立即编码和发送 Owner 已完成的响应；只有背压会话排队保存编码字节，返回前归还业务响应。
 */
@Component
public final class ClusterServiceEgress {
    /** 没有客户端回包目标时使用的会话 ID 哨兵值。 */
    public static final long NO_SESSION_ID = -1L;
    /** 清空临时包装器或尚未分配发送缓冲时共用的空字节数组。 */
    private static final byte[] EMPTY = new byte[0];
    /** 单条响应允许编码的最大载荷（十六 MiB 预算）。 */
    private static final int MAX_RESPONSE_PAYLOAD = 16 * 1024 * 1024;
    /** 编码后遇背压的会话 FIFO。 */
    private final DeferredSessionResponses deferred = new DeferredSessionResponses();
    /** 编码和首次发送时复用的发送缓冲视图。 */
    private final UnsafeBuffer sendBuffer = new UnsafeBuffer(new byte[0]);
    /** 复用编码数组，容量不足才扩容。 */
    private byte[] sendScratch = EMPTY;
    /** 按纯会话 ID 管理的传输句柄表。 */
    private final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<TransportSession>
            sessions = new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    /** 实际或测试会话发送器的创建边界。 */
    private final EgressFactory egressFactory;
    /** 当前持有的 Cluster 句柄。 */
    private Cluster cluster;
    /** 存在实际会话关闭请求的遍历提示。 */
    private boolean closeRequests;

    /** 出口当前角色代次，旧代响应在排出时丢弃。 */
    private long epoch;
    /** 实际 Aeron 会话头使用的领导任期 ID。 */
    private long leadershipTermId;

    /**
     * 隔离会话响应通道的创建边界；生产建立 Publication，测试可注入自己的发送器。
     */
    @FunctionalInterface
    public interface EgressFactory {
        /**
         * 为回调线程创建一个会话响应出口，返回对象由传输句柄管理和关闭。
         * @param cluster 仅在回调侧访问的当前 Cluster
         * @param session 要查找、登记或关闭的实际会话
         */
        SessionEgress open(Cluster cluster, ClientSession session);
    }

    /**
     * 一个会话响应通道的最小发送和关闭契约，只有 Cluster 回调线程使用。
     */
    public interface SessionEgress {
        /**
         * 尝试发送一次带任期和时间的响应，返回 Publication 风格结果；本契约不要求内部循环重试。
         * @param leadershipTermId 本次 Aeron 会话头的领导任期 ID
         * @param timestamp 本次 Aeron 会话头的 Cluster 时间
         * @param source 已经编码的待发送业务响应缓冲
         * @param offset 业务字节在原缓冲的起始偏移
         * @param length 当前有效业务字节数或输出槽编码长度
         */
        long offer(long leadershipTermId, long timestamp, DirectBuffer source, int offset, int length);
        /**
         * 释放本会话出口自己持有的传输资源。
         */
        void close();
    }

    /**
     * 使用实际 Aeron 会话 Publication 工厂创建出口。
     */
    public ClusterServiceEgress() {
        this(SessionResponsePublication::new);
    }

    /**
     * 保存会话出口工厂；会话连接时才创建 Publication。
     * @param egressFactory 必须存在的会话响应发送器工厂
     */
    public ClusterServiceEgress(EgressFactory egressFactory) {
        this.egressFactory = Objects.requireNonNull(egressFactory);
    }

    /**
     * 接入非空 Cluster，把响应角色代次和任期字段置零；本方法不清空已有会话或重试队列，资源清理由 clear 承担。
     * @param cluster 仅在回调侧访问的当前 Cluster
     */
    public void start(Cluster cluster) {
        this.cluster = Objects.requireNonNull(cluster);
        epoch = 0;
        leadershipTermId = 0;
    }

    /**
     * 只在回调线程按实际会话 ID 查找或登记传输句柄；首次登记且当前是 Leader 时连接响应通道。Owner 只接收其纯 ID。
     * @param session 要查找、登记或关闭的实际会话
     */
    public TransportSession transportSession(ClientSession session) {
        // 按实际会话 ID 查到或建立的传输句柄
        TransportSession transport = sessions.get(session.id());
        if (transport == null) {
            transport = new TransportSession(session);
            sessions.put(session.id(), transport);
            if (cluster.role() == Cluster.Role.LEADER) transport.connect();
        }
        return transport;
    }

    /**
     * 空会话直接返回；先移除该会话的背压字节，再从注册表移除传输句柄并关闭其 Publication，不在这里再次关闭实际 ClientSession。
     * @param session 要查找、登记或关闭的实际会话
     */
    public void closeSession(ClientSession session) {
        if (session == null) return;
        deferred.remove(session.id());
        // 从会话表移除的可空句柄，存在时断开其响应通道
        TransportSession transport = sessions.remove(session.id());
        if (transport != null) transport.disconnect();
    }

    /**
     * 有待关闭标记时遍历现有会话，在回调线程关闭已请求但尚未关闭的实际会话；清本轮遍历提示，具体关闭标记仍在句柄内。
     */
    public void flushSessionClosures() {
        if (!closeRequests) return;
        closeRequests = false;
        sessions.forEachValue(/* 回调线程正在处理的具体传输会话 */ session -> {
            if (session.closeRequested && !session.actual.isClosing()) session.actual.close();
        });
    }

    /**
     * 递增出口代次并清背压重试字节；Leader 连接现有会话通道，其他角色断开通道。后续旧 Owner 代次的响应直接丢弃。
     * @param role 日志边界确定的新角色
     */
    public long onRoleChange(Cluster.Role role) {
        // 递增后要交给 Owner 的新出口角色代次
        long next = ++epoch;
        deferred.clear();
        sessions.forEachValue(/* 回调线程正在处理的具体传输会话 */ session -> {
            if (role == Cluster.Role.LEADER) session.connect();
            else session.disconnect();
        });
        return next;
    }

    /**
     * 更新之后编码 Aeron 会话头使用的领导任期 ID，不在此切换业务角色或响应代次。
     * @param termId 之后发送使用的领导任期 ID
     */
    public void leadershipTerm(long termId) {
        leadershipTermId = termId;
    }

    /** 在当前回调内完成编码，背压队列只借用并复制有效字节，不持有业务对象。 */
    public void publishResponse(TradingCoreOwner processor, long sessionId, CoreMessageHeader header, CoreResponse response,
            long committedSequence, long ownerEpoch) {
        try {
            if (ownerEpoch != epoch || cluster == null || cluster.role() != Cluster.Role.LEADER) return;
            TransportSession session = sessions.get(sessionId);
            if (session == null || session.isClosing()) return;
            int length = CoreMessageCodec.encodedResponseLength(header, response);
            if (length > MAX_RESPONSE_PAYLOAD) {
                session.close();
                return;
            }
            if (sendScratch.length < length) {
                sendScratch = new byte[length];
                sendBuffer.wrap(sendScratch);
            }
            CoreMessageCodec.encodeResponse(header, response, committedSequence, sendScratch);
            deferred.offer(session, sendBuffer, length);
        } finally {
            if (processor != null) processor.releaseResponse(response);
        }
    }

    /** 每次回调最多重试 256 条慢会话响应。 */
    public int drainResponses() {
        return deferred.size() == 0 ? 0 : deferred.poll(System.nanoTime(), 256);
    }

    public void clear() {
        deferred.clear();
        sessions.forEachValue(TransportSession::disconnect);
        sessions.clear();
    }

    /**
     * 回调线程独占的实际会话句柄，保存响应通道与待关闭状态；不会交给 Owner 调用网络 API。
     */
    public final class TransportSession {
        /** 该传输句柄借用的实际 ClientSession。 */
        private final ClientSession actual;
        /**  构造时固定的实际会话 ID。 返回固定会话纯 ID，供 Owner 输入与输出关联。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private final long id;
        /** 该句柄是否已请求关闭，角色切换不自动清零。 */
        private boolean closeRequested;
        /** 该会话当前可空响应发送器，断开后清引用。 */
        private SessionEgress egress;

        /**
         * 保存实际会话及固定 ID，不立即打开 Publication。
         * @param actual 要封装的实际客户端会话
         */
        private TransportSession(ClientSession actual) {
            this.actual = actual;
            this.id = actual.id();
        }

        /**
         * 尚未连接时通过工厂打开会话响应通道，已有通道则复用。
         */
        private void connect() {
            if (egress == null) egress = egressFactory.open(cluster, actual);
        }

        /**
         * 有通道时关闭并清引用，已有关闭请求标记和实际会话由原生命周期处理。
         */
        private void disconnect() {
            if (egress != null) {
                egress.close();
                egress = null;
            }
        }

        /**
         * 传输句柄已请求关闭，或实际会话正在关闭，任一成立就不再发送新响应。
         */
        boolean isClosing() { return closeRequested || actual.isClosing(); }

        /**
         * 只标记该会话待关闭并设置回调侧处理提示，不在这里直接调用实际会话 close。
         */
        void close() {
            closeRequested = true;
            closeRequests = true;
        }

        /**
         * 通道存在时传入当前领导任期和 Cluster 时间尝试发送一次；未连接直接返回 NOT_CONNECTED，由原重试边界决定等待。
         * @param buffer 已经编码的待发送字节视图
         * @param offset 业务字节在原缓冲的起始偏移
         * @param length 当前有效业务字节数或输出槽编码长度
         */
        long offer(DirectBuffer buffer, int offset, int length) {
            return egress == null ? io.aeron.Publication.NOT_CONNECTED
                    : egress.offer(leadershipTermId, cluster.time(), buffer, offset, length);
        }
    }

    /**
     * 实际 Aeron 会话响应出口，持有独立 Publication 和复用会话头，由回调线程发送与关闭。
     */
    static final class SessionResponsePublication implements SessionEgress {
        /** 实际会话响应通道的独立 Aeron Publication。 */
        private final Publication publication;
        /** 复用的 Aeron 会话头字节缓冲，不是交易响应 Header 租约。 */
        private final UnsafeBuffer header = new UnsafeBuffer(new byte[AeronCluster.SESSION_HEADER_LENGTH]);
        /** 写入固定会话 ID 及本次任期、时间的会话头编码器。 */
        private final SessionMessageHeaderEncoder encoder = new SessionMessageHeaderEncoder();

        /**
         * 按实际会话响应通道及 stream 创建独立 Publication，并提前写好固定会话 ID 的 Aeron 响应头。
         * @param cluster 仅在回调侧访问的当前 Cluster
         * @param session 要查找、登记或关闭的实际会话
         */
        SessionResponsePublication(Cluster cluster, ClientSession session) {
            publication = cluster.aeron().addPublication(session.responseChannel(), session.responseStreamId());
            encoder.wrapAndApplyHeader(header, 0, new MessageHeaderEncoder()).clusterSessionId(session.id());
        }

        /**
         * 更新本次会话头任期和时间，用组合缓冲一次提交会话头与已编码业务响应；返回底层发送结果。
         * @param leadershipTermId 本次 Aeron 会话头的领导任期 ID
         * @param timestamp 本次 Aeron 会话头的 Cluster 时间
         * @param source 已经编码的待发送业务响应缓冲
         * @param offset 业务字节在原缓冲的起始偏移
         * @param length 当前有效业务字节数或输出槽编码长度
         */
        @Override
        public long offer(long leadershipTermId, long timestamp, DirectBuffer source, int offset, int length) {
            encoder.leadershipTermId(leadershipTermId).timestamp(timestamp);
            return publication.offer(header, 0, header.capacity(), source, offset, length, null);
        }

        /**
         * 关闭本出口持有的实际 Aeron Publication。
         */
        @Override
        public void close() {
            publication.close();
        }
    }
}
