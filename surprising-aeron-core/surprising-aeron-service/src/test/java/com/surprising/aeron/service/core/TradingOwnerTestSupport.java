package com.surprising.aeron.service.core;

import com.surprising.aeron.model.OwnerLogContext;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.service.order.ClusterCommandWindow;
import com.surprising.aeron.support.egress.ClusterServiceEgress;
import com.surprising.aeron.support.ingress.CoreMessageFlyweightDecoder;
import com.surprising.product.api.ProductLine;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import java.util.function.BooleanSupplier;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * 让测试直接调用真实 TradingCoreOwner 的 ClusteredService 适配器。Owner 逻辑在测试调用线程推进；Matcher、账户和账本资源仍按实际执行模式工作。
 */
public final class TradingOwnerTestSupport implements ClusteredService {
    /** 本夹具使用的真实交易 Owner，由测试调用线程推进。 */
    private final TradingCoreOwner owner;
    /** 解码已复制帧的复用解码器，按真实租约规则使用。 */
    private final CoreMessageFlyweightDecoder messageDecoder = new CoreMessageFlyweightDecoder();
    /** 本次启动接入的测试 Cluster。 */
    private Cluster cluster;
    /** 测试复制给 Owner 的角色、业务时间和日志位置。 */
    private OwnerLogContext logContext;
    /** 复用真实出口队列的测试响应边界，会话发送由夹具提供。 */
    private final ClusterServiceEgress egress = new ClusterServiceEgress((/* 会话出口工厂的第一个参数，本夹具不读取 */ ignored, /* 响应目标的原测试会话，匿名出口借用它发送 */ session) ->
            /* 借用夹具会话发送编码响应的出口，不额外创建或持有 Publication */ new ClusterServiceEgress.SessionEgress() {
                /**
                 * 把出口编码后的字节借给夹具会话发送；当前适配器不使用任期和时间参数。
                 * @param term 出口提供的任期值，当前测试发送器不读取
                 * @param timestamp 已复制业务时间；会话生命周期回调中当前不读取
                 * @param source 本轮已编码响应的借用缓冲
                 * @param offset 输入缓冲的起始字节偏移
                 * @param length 本轮帧或片段的有效字节数
                 */
                public long offer(long term, long timestamp, DirectBuffer source, int offset, int length) {
                    return session.offer(source, offset, length);
                }
                /**
                 * 测试会话出口没有创建需单独释放的 Publication，此处不执行额外关闭。
                 */
                public void close() { }
            });
    /** 当前测试角色代次，启动时清零、角色变化时更新。 */
    private long epoch;

    /**
     * 创建指定产品线的真实 Core Owner，并把终态接到本适配器的响应出口。
     * @param productLine 夹具所属的唯一产品线
     */
    public TradingOwnerTestSupport(ProductLine productLine) {
        owner = new TradingCoreOwner(productLine, this::respond);
    }

    /**
     * 用当前测试角色代次把终态交给 egress，继续保留既有响应租约规则。
     * @param sessionId 响应目标会话 ID
     * @param header 异步编码完成前保持有效的响应头租约
     * @param response 已完成或需归还的响应缓冲
     * @param sequence 响应的已提交 Core 序号
     */
    private void respond(long sessionId, com.surprising.aeron.protocol.CoreMessageHeader header,
                         CoreResponse response, long sequence) {
        egress.publishResponse(owner, sessionId, header, response, sequence, epoch);
    }

    /**
     * 保存 Cluster，启动出口并复制日志上下文，然后启动实际 Owner；提供快照 Image 时用真实分片恢复入口读取，本方法不显式激活全部工作线程。
     * @param cluster 提供初始日志元数据与等待策略的测试 Cluster
     * @param snapshotImage 可空快照 Image，非空时读取至结束
     */
    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        this.cluster = cluster;
        egress.start(cluster);
        epoch = 0;
        logContext = new OwnerLogContext(cluster.role(), cluster.time(), cluster.logPosition());
        owner.start(logContext);
        if (snapshotImage != null) {
            var recovery = new com.surprising.aeron.service.snapshot.SectionedCoreSnapshotRecovery();
            while (!snapshotImage.isEndOfStream()) {
                int fragments = snapshotImage.poll(
                        (buffer, offset, length, header) -> recovery.accept(buffer, offset, length), 10);
                if (fragments == 0) Thread.onSpinWait();
            }
            owner.restoreSnapshot(recovery);
        }
    }

    /**
     * 解码已复制帧并交给 Owner；非法参数帧直接忽略，其他执行错误继续向测试传播。
     * @param session 原测试会话，可为空表示无回包目标
     * @param timestamp 已复制业务时间；会话生命周期回调中当前不读取
     * @param buffer 待解码的原始已复制帧缓冲
     * @param offset 输入缓冲的起始字节偏移
     * @param length 本轮帧或片段的有效字节数
     * @param header 原解码帧的 Aeron 头或终态响应头，具体由调用边界决定
     */
    @Override
    public void onSessionMessage(
            ClientSession session,
            long timestamp,
            DirectBuffer buffer,
            int offset,
            int length,
            Header header) {
        // 解码成功的原请求及输入租约
        CoreMessage request;
        try {
            request = messageDecoder.decode(buffer, offset, length);
        } catch (/* 解码时发现非法参数的异常，当前回调直接忽略该帧 */ IllegalArgumentException invalid) {
            return;
        }
        acceptCommittedCommand(session, request, timestamp, header.position());
    }

    /**
     * 交接已复制命令后推进一次 Owner；不等待该命令到终态，也不在正常路径排出 egress。
     * @param session 原测试会话，可为空表示无回包目标
     * @param request 已经解码并借给 Owner 的请求
     * @param timestamp 已复制业务时间；会话生命周期回调中当前不读取
     * @param position 本命令的复制日志位置
     */
    public void acceptCommittedCommand(ClientSession session, CoreMessage request, long timestamp, long position) {
        enqueueCommittedCommand(session, request, timestamp, position, null);
        owner.pollCommands();
    }

    /**
     * 额外交接已有请求指纹，再推进一次 Owner；异步完成条件仍由后续测试轮询处理。
     * @param session 原测试会话，可为空表示无回包目标
     * @param request 已经解码并借给 Owner 的请求
     * @param timestamp 已复制业务时间；会话生命周期回调中当前不读取
     * @param position 本命令的复制日志位置
     * @param fingerprint 调用方已有的请求指纹
     */
    public void acceptCommittedCommand(ClientSession session, CoreMessage request, long timestamp, long position,
                                CommandFingerprint fingerprint) {
        enqueueCommittedCommand(session, request, timestamp, position, fingerprint);
        owner.pollCommands();
    }

    /**
     * 更新复制时间及位置，取得会话 ID 后写 Owner 原输入环；环满时推进 Owner 和响应出口，有三十秒等待上限。
     * @param session 原测试会话，可为空表示无回包目标
     * @param request 已经解码并借给 Owner 的请求
     * @param timestamp 已复制业务时间；会话生命周期回调中当前不读取
     * @param position 本命令的复制日志位置
     * @param fingerprint 调用方已有的请求指纹
     */
    public void enqueueCommittedCommand(ClientSession session, CoreMessage request, long timestamp, long position,
                                 CommandFingerprint fingerprint) {
        logContext.timestamp(timestamp);
        logContext.position(position);
        // 保存进输入环的纯会话 ID，不把会话对象交给 Owner
        long sessionId = session == null ? ClusterServiceEgress.NO_SESSION_ID : egress.transportSession(session).id();
        // 输入环满时允许继续推进的三十秒截止时间
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (!owner.ingress().offer(request, sessionId, timestamp, position, fingerprint)) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Owner ingress did not advance");
            owner.pollCommands();
            egress.drainResponses();
        }
    }

    /**
     * 取得分块快照，按 Publication 最大载荷逐片发送；只对三类可重试负返回值继续等待，其他负值报错。本发送循环没有另设本地重试期限。
     * @param publication 发送本次快照片段的 Publication
     */
    @Override
    public void onTakeSnapshot(ExclusivePublication publication) {
        // 真实 Owner 生成的分块快照
        var snapshot = owner.captureSnapshotSections(Math.max(1, cluster.logPosition()));
        for (byte[] /* 当前准备发送的快照字节分块 */ chunk : snapshot.chunks()) {
            // 包裹当前快照分块的只读发送视图
            UnsafeBuffer buffer = new UnsafeBuffer(chunk);
            for (/* 当前分块已成功发送的字节数 */ int offset = 0; offset < chunk.length;) {
                // 本片不超过 Publication 最大载荷的字节数
                int length = Math.min(publication.maxPayloadLength(), chunk.length - offset);
                // 本片 Publication.offer 的返回值，负值按可重试规则处理
                long result;
                while ((result = publication.offer(buffer, offset, length)) < 0) {
                    if (!retryableOffer(result)) {
                        throw new IllegalStateException("snapshot publication failed: " + result);
                    }
                    cluster.idleStrategy().idle();
                }
                offset += length;
            }
        }
    }

    /**
     * 按真实 Owner 默认快照期限取得合并快照。
     * @param snapshotId 本次快照身份
     */
    public byte[] captureSnapshot(long snapshotId) {
        return owner.captureSnapshot(snapshotId);
    }

    /**
     * 按指定单调纳秒截止时间取得合并快照。
     * @param snapshotId 本次快照身份
     * @param deadlineNanos 显式快照等待截止时间，使用单调纳秒时钟
     */
    public byte[] captureSnapshot(long snapshotId, long deadlineNanos) {
        return owner.captureSnapshot(snapshotId, deadlineNanos);
    }

    /**
     * 更新 egress 代次、上下文角色和 Owner 实时角色；此测试回调不包含生产 OwnerLoop 的前缀等待动作。
     * @param newRole 日志边界确定的新角色
     */
    @Override
    public void onRoleChange(Cluster.Role newRole) {
        epoch = egress.onRoleChange(newRole);
        logContext.role(newRole);
        owner.roleChange(newRole);
    }

    /**
     * 把本轮纳秒时间交给 Owner 背景读取，不顺带排出响应出口。
     * @param nowNs 本轮单调纳秒时间
     */
    @Override
    public int doBackgroundWork(long nowNs) {
        return owner.pollCommands();
    }

    /**
     * 关闭 Owner 并始终清理 egress；只有原运行时已记录 fatalFailure 时才吞掉关闭时的运行时异常。正常返回后清空 Cluster 和日志上下文引用。
     * @param ignored 终止回调附带的 Cluster，当前关闭使用已保存资源
     */
    @Override
    public void onTerminate(Cluster ignored) {
        TradingCoreRuntime runtime = null;
        try { runtime = owner.state(); } catch (RuntimeException ignoredFailure) {}
        try { owner.terminate(); }
        catch (/* 关闭真实 Owner 时产生的运行时异常，只有原故障已记录时才抑制 */ RuntimeException failure) {
            if (runtime == null || runtime.fatalFailure == null) throw failure;
        }
        finally { egress.clear(); }
        cluster = null;
        logContext = null;
    }

    /**
     * 推进已有命令并排出响应；本适配器不读取本次会话和时间参数。
     * @param session 原测试会话，可为空表示无回包目标
     * @param timestamp 已复制业务时间；会话生命周期回调中当前不读取
     */
    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        owner.pollCommands();
        egress.drainResponses();
    }

    /**
     * 会话非空时先关闭相应 egress，再通知 Owner 推进；关闭原因和时间不参与这里的业务执行。
     * @param session 原测试会话，可为空表示无回包目标
     * @param timestamp 已复制业务时间；会话生命周期回调中当前不读取
     * @param reason 会话关闭原因，当前适配器不读取
     */
    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason reason) {
        if (session != null) { egress.closeSession(session); owner.pollCommands(); }
    }

    /**
     * 测试保留计时回调形状，不在此生成交易动作。
     * @param correlationId 计时事件关联号，当前回调不生成交易
     * @param timestamp 已复制业务时间；会话生命周期回调中当前不读取
     */
    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        // 计时回调不在这里生成交易动作，业务变化仍由复制命令驱动。
    }

    /**
     * 读取真实 Owner 的待处理条目数，包含输入环中的动作边界。
     */
    public int pendingCommandCount() { return owner.pendingCommandCount(); }
    /**
     * 读取真实 Owner 当前推进诊断，帮助异步测试超时定位。
     */
    public String stalledCommandDiagnostics() { return owner.stalledCommandDiagnostics(); }
    /**
     * 读取真实 Owner 的 Matcher 或 Lane 完成提示。
     */
    public boolean ownerCompletionAvailable() { return owner.ownerCompletionAvailable(); }
    /**
     * 推进一次 Owner，再排出测试响应；返回二者工作量之和，不是本轮终态条数。
     */
    public int pollCommands() {
        // 本轮 Owner 推进报告的进展，随后加上出口排出量
        int work = owner.pollCommands();
        return work + egress.drainResponses();
    }
    /**
     * 先完成当前业务前缀，再排出已有终态响应。
     */
    public void finishCommands() { owner.finishCommands(); egress.drainResponses(); }
    /**
     * 读取真实流水窗口当前占用。
     */
    public int commandWindowSize() { return owner.commandWindowSize(); }
    /**
     * 读取真实窗口的历史占用高水位。
     */
    public int commandWindowHighWaterMark() { return owner.commandWindowHighWaterMark(); }
    /**
     * 借出真实 Owner 的窗口，测试不得当成独立状态修改。
     */
    public ClusterCommandWindow commandWindow() { return owner.commandWindow(); }
    /**
     * 借出真实实时边界的有界快照请求队列。
     */
    public ManyToOneConcurrentArrayQueue<com.surprising.aeron.protocol.RealtimeFrame> snapshotRequests() {
        return owner.snapshotRequests();
    }
    /**
     * 取得真实 Owner 当前已启动的运行时引用，仍须遵守账户所有权。
     */
    public TradingCoreRuntime state() { return owner.state(); }
    /**
     * 通过真实 Owner 归还响应缓冲；运行时已释放时由 Owner 避免再访问池。
     * @param response 已完成或需归还的响应缓冲
     */
    public void releaseResponse(CoreResponse response) { owner.releaseResponse(response); }

    /**
     * 把夹具 outbox 接入实际 Owner，当前不提供额外变更捕获器。
     * @param outbox 要接入真实 Owner 的实时输出队列
     */
    public void attachRealtime(com.surprising.aeron.client.RealtimeOutbox outbox) {
        owner.attachRealtime(outbox, null);
    }

    /**
     * 把完整编码字节交给生产恢复入口。
     * @param snapshot 完整快照编码字节
     */
    public void restoreSnapshot(byte[] snapshot) {
        owner.restoreSnapshot(snapshot);
    }

    /**
     * 将测试分片来源适配为实际 Owner 的读取契约，再读至结束并恢复。
     * @param source 提供原始快照片段的测试来源
     * @param endOfStream 判断输入已经结束的函数
     */
    public void loadSnapshot(SnapshotFragmentSource source, BooleanSupplier endOfStream) {
        var recovery = new com.surprising.aeron.service.snapshot.SectionedCoreSnapshotRecovery();
        while (!endOfStream.getAsBoolean()) {
            int fragments = source.poll((buffer, offset, length, header) -> recovery.accept(buffer, offset, length), 10);
            if (fragments == 0) Thread.onSpinWait();
        }
        owner.restoreSnapshot(recovery);
    }

    /**
     * 测试快照来源契约，经方法引用直接接到真实 Owner 分片恢复入口。
     */
    public @FunctionalInterface
    interface SnapshotFragmentSource {
        /**
         * 按片段预算读取输入，并把每片交给指定处理器，返回实际片段数。
         * @param fragmentHandler 处理每个快照片段的回调
         * @param fragmentLimit 本轮最多读取的片段数
         */
        public int poll(FragmentHandler fragmentHandler, int fragmentLimit);
    }

    /**
     * 仅允许背压、管理动作和尚未连接三类 Publication 返回值继续重试。
     * @param result Publication.offer 返回值
     */
    private static boolean retryableOffer(long result) {
        return result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION
                || result == Publication.NOT_CONNECTED;
    }
}
