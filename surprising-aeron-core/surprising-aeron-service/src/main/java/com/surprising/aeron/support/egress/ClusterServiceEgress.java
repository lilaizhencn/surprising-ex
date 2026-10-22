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
 * 交易步骤 6：把 Owner 已完成响应交给 Cluster 回调线程编码和发送。Owner 单写有界输出环，回调线程持有会话、Publication 和背压重试队列；原 Header 与响应在编码前保留租约，出口失败不能改变已提交业务。
 */
@Component
public final class ClusterServiceEgress {
    /** 没有客户端回包目标时使用的会话 ID 哨兵值。 */
    public static final long NO_SESSION_ID = -1L;
    /** 清空临时包装器或尚未分配发送缓冲时共用的空字节数组。 */
    private static final byte[] EMPTY = new byte[0];
    /** Owner 到回调线程最多保留十六 MiB 编码长度预算。 */
    private static final long OUTPUT_BYTES = 16L * 1024 * 1024;
    /** 输出环固定八千一百九十二个槽。 */
    private static final int OUTPUT_SLOTS = 8192;
    /** 输出环二次幂下标掩码。 */
    private static final int OUTPUT_MASK = OUTPUT_SLOTS - 1;
    /** 累计清完六十四 KiB 编码内容时公布消费进度。 */
    private static final long BATCH_OUTPUT_CONSUMED_BYTES_THRESHOLD = 64 * 1024;
    /** 累计清完六十四个输出槽时公布消费进度。 */
    private static final int BATCH_OUTPUT_CONSUMED_SLOTS_THRESHOLD = 64;

    /** 预分配的 Owner 到回调侧响应交接槽。 */
    private final Output[] outputs = new Output[OUTPUT_SLOTS];
    /** 编码后遇背压的会话 FIFO，只由回调线程访问。 */
    private final DeferredSessionResponses deferred = new DeferredSessionResponses();
    /** 回调线程编码和首次发送时复用的发送缓冲视图。 */
    private final UnsafeBuffer sendBuffer = new UnsafeBuffer(new byte[0]);
    /** 回调侧复用编码数组，容量不足才扩容。 */
    private byte[] sendScratch = EMPTY;
    /** 回调线程按纯会话 ID 管理的传输句柄表。 */
    private final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<TransportSession>
            sessions = new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    /** 实际或测试会话发送器的创建边界。 */
    private final EgressFactory egressFactory;
    /** 回调侧持有的 Cluster 句柄，Owner 不直接调用。 */
    private Cluster cluster;
    /** 回调侧存在实际会话关闭请求的遍历提示。 */
    private boolean closeRequests;

    /**
     * 生产进度前的显式填充基类，不存放业务状态。
     */
    private static class PaddedOutputProducedPadding {
        /**
         * p00：生产进度之前的一项显式填充字段，不存业务数据，代码不读写它。
         * p01：生产进度之前的一项显式填充字段，不存业务数据，代码不读写它。
         * p02：生产进度之前的一项显式填充字段，不存业务数据，代码不读写它。
         * p03：生产进度之前的一项显式填充字段，不存业务数据，代码不读写它。
         * p04：生产进度之前的一项显式填充字段，不存业务数据，代码不读写它。
         * p05：生产进度之前的一项显式填充字段，不存业务数据，代码不读写它。
         * p06：生产进度之前的一项显式填充字段，不存业务数据，代码不读写它。
         * p07：生产进度之前的一项显式填充字段，不存业务数据，代码不读写它。
         * p08：生产进度之前的一项显式填充字段，不存业务数据，代码不读写它。
         * p09：生产进度之前的一项显式填充字段，不存业务数据，代码不读写它。
         * p0a：生产进度之前的一项显式填充字段，不存业务数据，代码不读写它。
         * p0b：生产进度之前的一项显式填充字段，不存业务数据，代码不读写它。
         * p0c：生产进度之前的一项显式填充字段，不存业务数据，代码不读写它。
         * p0d：生产进度之前的一项显式填充字段，不存业务数据，代码不读写它。
         * p0e：生产进度之前的一项显式填充字段，不存业务数据，代码不读写它。
         */
        @SuppressWarnings("unused")
        private long p00, p01, p02, p03, p04, p05, p06, p07, p08, p09, p0a, p0b, p0c, p0d, p0e;
    }

    /**
     * Owner 单写的输出生产游标和消费进度缓存；发布条数让回调侧看见已填槽。
     */
    private static class PaddedOutputProducedValue extends PaddedOutputProducedPadding {
        /** Owner 填完槽后发布的累计条数。 */
        protected volatile long outputProducedCount;
        /** 仅 Owner 修改的累计输出编码字节。 */
        protected long outputProducedBytes;
        /** Owner 缓存的已公布消费条数，容量不足时刷新。 */
        protected long cachedOutputConsumedCount;
        /** Owner 缓存的已公布消费字节，容量不足时刷新。 */
        protected long cachedOutputConsumedBytes;
    }

    /**
     * 生产与消费进度之间的显式填充基类，不保证 JVM 必定按某个缓存行布局。
     */
    private static class PaddedOutputConsumedPadding extends PaddedOutputProducedValue {
        /**
         * p10：生产与消费进度之间的一项显式填充字段，不存业务数据，代码不读写它。
         * p11：生产与消费进度之间的一项显式填充字段，不存业务数据，代码不读写它。
         * p12：生产与消费进度之间的一项显式填充字段，不存业务数据，代码不读写它。
         * p13：生产与消费进度之间的一项显式填充字段，不存业务数据，代码不读写它。
         * p14：生产与消费进度之间的一项显式填充字段，不存业务数据，代码不读写它。
         * p15：生产与消费进度之间的一项显式填充字段，不存业务数据，代码不读写它。
         * p16：生产与消费进度之间的一项显式填充字段，不存业务数据，代码不读写它。
         * p17：生产与消费进度之间的一项显式填充字段，不存业务数据，代码不读写它。
         * p18：生产与消费进度之间的一项显式填充字段，不存业务数据，代码不读写它。
         * p19：生产与消费进度之间的一项显式填充字段，不存业务数据，代码不读写它。
         * p1a：生产与消费进度之间的一项显式填充字段，不存业务数据，代码不读写它。
         * p1b：生产与消费进度之间的一项显式填充字段，不存业务数据，代码不读写它。
         * p1c：生产与消费进度之间的一项显式填充字段，不存业务数据，代码不读写它。
         * p1d：生产与消费进度之间的一项显式填充字段，不存业务数据，代码不读写它。
         * p1e：生产与消费进度之间的一项显式填充字段，不存业务数据，代码不读写它。
         */
        @SuppressWarnings("unused")
        private long p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p1a, p1b, p1c, p1d, p1e;
    }

    /**
     * 回调线程单写的输出消费进度及本地累计值，批量向 Owner 公布可复用容量。
     */
    private static class PaddedOutputConsumedValue extends PaddedOutputConsumedPadding {
        /** 回调线程批量公布的累计消费条数。 */
        protected volatile long outputConsumedCount;
        /** 回调线程批量公布的累计消费编码字节。 */
        protected volatile long outputConsumedBytes;
        /** 回调线程缓存的生产前缀，消费完再刷新。 */
        protected long cachedOutputProducedCount;
        /** 回调线程本地已取走的累计条数。 */
        protected long localConsumedCount;
        /** 已清槽但尚未公布给 Owner 的编码字节增量。 */
        protected long uncommittedConsumedBytes;
        /** 已清槽但尚未公布给 Owner 的条数增量。 */
        protected int uncommittedConsumedSlots;
    }

    /**
     * 合并输出环生产、消费进度的实际存储对象，末尾保留显式填充。
     */
    private static final class PaddedEgressOffsets extends PaddedOutputConsumedValue {
        /**
         * p20：消费进度之后的一项显式填充字段，不存业务数据，代码不读写它。
         * p21：消费进度之后的一项显式填充字段，不存业务数据，代码不读写它。
         * p22：消费进度之后的一项显式填充字段，不存业务数据，代码不读写它。
         * p23：消费进度之后的一项显式填充字段，不存业务数据，代码不读写它。
         * p24：消费进度之后的一项显式填充字段，不存业务数据，代码不读写它。
         * p25：消费进度之后的一项显式填充字段，不存业务数据，代码不读写它。
         * p26：消费进度之后的一项显式填充字段，不存业务数据，代码不读写它。
         * p27：消费进度之后的一项显式填充字段，不存业务数据，代码不读写它。
         * p28：消费进度之后的一项显式填充字段，不存业务数据，代码不读写它。
         * p29：消费进度之后的一项显式填充字段，不存业务数据，代码不读写它。
         * p2a：消费进度之后的一项显式填充字段，不存业务数据，代码不读写它。
         * p2b：消费进度之后的一项显式填充字段，不存业务数据，代码不读写它。
         * p2c：消费进度之后的一项显式填充字段，不存业务数据，代码不读写它。
         * p2d：消费进度之后的一项显式填充字段，不存业务数据，代码不读写它。
         * p2e：消费进度之后的一项显式填充字段，不存业务数据，代码不读写它。
         */
        @SuppressWarnings("unused")
        private long p20, p21, p22, p23, p24, p25, p26, p27, p28, p29, p2a, p2b, p2c, p2d, p2e;
    }

    /** 输出生产与消费两侧的进度存储，按各自线程修改。 */
    private final PaddedEgressOffsets egressOffsets = new PaddedEgressOffsets();
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
     * 要求会话出口工厂非空，并一次建立全部响应槽；暂不创建网络 Publication。
     * @param egressFactory 必须存在的会话响应发送器工厂
     */
    public ClusterServiceEgress(EgressFactory egressFactory) {
        this.egressFactory = Objects.requireNonNull(egressFactory);
        for (/* 当前创建的预分配输出槽下标 */ int i = 0; i < OUTPUT_SLOTS; i++) outputs[i] = new Output();
    }

    /**
     * 接入非空 Cluster，把响应角色代次和任期字段置零；本方法不清空已有环、会话或重试队列，资源清理由 clear 承担。
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
     * 递增出口代次并清背压重试字节；Leader 连接现有会话通道，其他角色断开通道。旧输出环仍留给 drain 按代次丢弃并释放租约。
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

    /** Owner 异常路径发布、回调侧消费的去重会话关闭请求集合。 */
    private final java.util.Set<Long> overflowSessions = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 跨线程可见的溢出关闭请求提示，集合内容仍是待处理来源。 */
    private volatile boolean hasOverflowSessions;

    /**
     * 在异常出口路径登记去重的会话关闭请求，并发布可见提示；无会话响应不登记，实际会话关闭仍由回调线程处理。
     * @param sessionId 目标会话 ID，无会话为 NO_SESSION_ID
     */
    private void handleSessionOverflow(long sessionId) {
        if (sessionId != NO_SESSION_ID && overflowSessions.add(sessionId)) {
            hasOverflowSessions = true;
        }
    }

    /**
     * Owner 先核算编码长度及输出容量，必要时刷新消费进度；仍无空间就请求关闭目标会话并归还响应。可入队时先保留 Header 租约、填槽，再发布生产游标，不在 Owner 发送网络。
     * @param processor 对应 Core Owner，排出或丢弃时通过它归还响应
     * @param sessionId 目标会话 ID，无会话为 NO_SESSION_ID
     * @param header 需要保持 egress 租约到编码结束的响应头
     * @param response 需要发送或归还的业务终态响应
     * @param committedSequence 响应对应的已提交 Core 序号
     * @param ownerEpoch 产生本终态时的 Owner 角色代次
     */
    public void publishResponse(TradingCoreOwner processor, long sessionId, CoreMessageHeader header, CoreResponse response,
                         long committedSequence, long ownerEpoch) {
        // 当前响应预计完整编码字节数
        int length = CoreMessageCodec.encodedResponseLength(header, response);
        // Owner 入队前累计发布条数
        long currentCount = egressOffsets.outputProducedCount;
        // 本条入队成功后要发布的累计条数
        long nextCount = currentCount + 1L;
        // 本条入队成功后的累计编码字节数
        long nextBytes = egressOffsets.outputProducedBytes + length;
        if (nextCount - egressOffsets.cachedOutputConsumedCount > OUTPUT_SLOTS
                || nextBytes - egressOffsets.cachedOutputConsumedBytes > OUTPUT_BYTES) {
            egressOffsets.cachedOutputConsumedCount = egressOffsets.outputConsumedCount;
            egressOffsets.cachedOutputConsumedBytes = egressOffsets.outputConsumedBytes;
            if (nextCount - egressOffsets.cachedOutputConsumedCount > OUTPUT_SLOTS
                    || nextBytes - egressOffsets.cachedOutputConsumedBytes > OUTPUT_BYTES) {
                handleSessionOverflow(sessionId);
                processor.releaseResponse(response);
                return;
            }
        }
        // Owner 当前可写的输出环槽
        Output slot = outputs[(int) currentCount & OUTPUT_MASK];
        header.retainForEgress();
        slot.prepare(processor, sessionId, header, response, committedSequence, length, ownerEpoch);
        egressOffsets.outputProducedBytes = nextBytes;
        egressOffsets.outputProducedCount = nextCount;
    }

    /**
     * 回调线程先处理溢出关闭请求，再最多取当前可见前缀中的 256 条输出；只编码当前代次的 Leader 有效会话响应。发送或丢弃都归还响应及 Header，批量公布容量后再公平重试慢会话。
     * @param processor 对应 Core Owner，排出或丢弃时通过它归还响应
     */
    public int drainResponses(TradingCoreOwner processor) {
        // 回调线程本轮消费游标
        long consumed = egressOffsets.localConsumedCount;
        // 本轮观察到的已发布响应前缀
        long produced = egressOffsets.cachedOutputProducedCount;
        if (consumed == produced) {
            produced = egressOffsets.outputProducedCount;
            egressOffsets.cachedOutputProducedCount = produced;
            if (consumed == produced && deferred.size() == 0 && !hasOverflowSessions) {
                return 0;
            }
        }
        // 本轮取走的输出条数，丢弃也计入工作量
        int work = 0;
        if (hasOverflowSessions) {
            hasOverflowSessions = false;
            // 并发关闭请求集合的弱一致迭代器，只移除已处理 ID
            var iterator = overflowSessions.iterator();
            while (iterator.hasNext()) {
                // 本轮取出的一个溢出会话 ID
                long sid = iterator.next();
                // 回调线程正在处理的具体传输会话
                TransportSession session = sessions.get(sid);
                if (session != null) {
                    session.close();
                }
                deferred.remove(sid);
                // 只移除已经处理的会话 ID，保留 Owner 并发加入的其他关闭请求。
                // 同一 ID 的重复请求无需再做一次处理，对应会话已经被标记关闭。
                iterator.remove();
            }
        }
        for (; work < 256 && consumed != produced; work++) {
            // 当前借出的输出槽，清空并公布消费进度后可被 Owner 复用
            Output next = outputs[(int) consumed & OUTPUT_MASK];
            consumed++;
            egressOffsets.localConsumedCount = consumed;
            egressOffsets.uncommittedConsumedBytes += next.length;
            egressOffsets.uncommittedConsumedSlots++;
            try {
                if (next.epoch != epoch || cluster.role() != Cluster.Role.LEADER) continue;
                // 回调线程正在处理的具体传输会话
                TransportSession session = sessions.get(next.sessionId);
                if (session == null || session.isClosing()) continue;
                if (sendScratch.length < next.length) {
                    sendScratch = new byte[next.length];
                    sendBuffer.wrap(sendScratch);
                }
                CoreMessageCodec.encodeResponse(next.header, next.response, next.committedSequence, sendScratch);
                deferred.offer(session, sendBuffer, next.length);
            } finally {
                try { processor.releaseResponse(next.response); }
                finally {
                    next.header.releaseFromEgress();
                    next.clear();
                }
            }
        }
        if (egressOffsets.uncommittedConsumedBytes >= BATCH_OUTPUT_CONSUMED_BYTES_THRESHOLD
                || egressOffsets.uncommittedConsumedSlots >= BATCH_OUTPUT_CONSUMED_SLOTS_THRESHOLD
                || consumed == produced) {
            commitConsumedBytes();
        }
        return work + (deferred.size() == 0 ? 0 : deferred.poll(System.nanoTime(), 256));
    }

    /**
     * 一次公布本地已清槽的条数和编码字节数，再清累计增量，让 Owner 可以安全复用容量。
     */
    private void commitConsumedBytes() {
        if (egressOffsets.uncommittedConsumedSlots > 0 || egressOffsets.uncommittedConsumedBytes > 0) {
            egressOffsets.outputConsumedBytes += egressOffsets.uncommittedConsumedBytes;
            egressOffsets.outputConsumedCount = egressOffsets.localConsumedCount;
            egressOffsets.uncommittedConsumedBytes = 0;
            egressOffsets.uncommittedConsumedSlots = 0;
        }
    }

    /**
     * 在生命周期清理边界逐槽归还响应与 Header，公布消费进度，清掉背压及溢出记录，断开全部会话通道；不重置所有累计游标或 Cluster 引用。
     */
    public void clear() {
        // 回调线程本轮消费游标
        long consumed = egressOffsets.localConsumedCount;
        // 本轮观察到的已发布响应前缀
        long produced = egressOffsets.outputProducedCount;
        egressOffsets.cachedOutputProducedCount = produced;
        while (consumed != produced) {
            // 当前借出的输出槽，清空并公布消费进度后可被 Owner 复用
            Output next = outputs[(int) consumed & OUTPUT_MASK];
            consumed++;
            egressOffsets.localConsumedCount = consumed;
            egressOffsets.uncommittedConsumedBytes += next.length;
            egressOffsets.uncommittedConsumedSlots++;
            try { next.processor.releaseResponse(next.response); }
            finally {
                next.header.releaseFromEgress();
                next.clear();
            }
        }
        commitConsumedBytes();
        deferred.clear();
        hasOverflowSessions = false;
        overflowSessions.clear();
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
     * 有界响应环中的复用槽，Owner 填好后到回调侧释放前不再改动；保留响应及 Header 的跨线程租约。
     */
    private static final class Output {
        /** 产生该响应的 Core Owner，清理时用它归还响应。 */
        private TradingCoreOwner processor;
        /** 本槽终态的目标会话 ID。 */
        private long sessionId = NO_SESSION_ID;
        /** 编码前保留 egress 租约的响应头。 */
        private CoreMessageHeader header;
        /** 本槽持有到编码或丢弃的响应缓冲租约。 */
        private CoreResponse response;
        /**
         * committedSequence：本响应的已提交 Core 序号。
         * epoch：本槽填入时的 Owner 角色代次，用于淘汰旧角色输出。
         */
        private long committedSequence, epoch;
        /** 本槽完整业务响应的预计编码字节数。 */
        private int length;

        /**
         * 在 Owner 发布生产游标前填好本代响应的全部引用、长度、提交序号和角色代次。
         * @param processor 对应 Core Owner，排出或丢弃时通过它归还响应
         * @param sessionId 目标会话 ID，无会话为 NO_SESSION_ID
         * @param header 需要保持 egress 租约到编码结束的响应头
         * @param response 需要发送或归还的业务终态响应
         * @param committedSequence 响应对应的已提交 Core 序号
         * @param length 当前有效业务字节数或输出槽编码长度
         * @param epoch 填槽时的响应角色代次
         */
        void prepare(TradingCoreOwner processor, long sessionId, CoreMessageHeader header,
                CoreResponse response, long committedSequence, int length, long epoch) {
            this.processor = processor;
            this.sessionId = sessionId;
            this.header = header;
            this.response = response;
            this.committedSequence = committedSequence;
            this.length = length;
            this.epoch = epoch;
        }

        /**
         * 编码或丢弃结束后移除强引用并恢复无会话值，长度与代次等数值留待下一次 prepare 覆盖。
         */
        void clear() {
            processor = null;
            sessionId = NO_SESSION_ID;
            header = null;
            response = null;
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
