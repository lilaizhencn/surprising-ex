package com.surprising.aeron.service.order.commit;

import com.surprising.aeron.service.core.TradingCoreRuntime;
import java.util.Objects;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 交易步骤五的 Owner 发布边界：把当前命令已积累的变化提交到派生索引及可选实时捕获，并维护独立发布序号。它只拥有发布标记和修订水位，账户、订单与账本仍在原权威状态；中止发布标记不等于回滚业务。
 */
public final class CommitPublication {
    /** 只由 Owner 推进的所属交易运行时。 */
    private final TradingCoreRuntime owner;
    /** 上一次成功应用派生索引时的已提交运行时修订。 */
    private long runtimePatchRevision;
    /**  是否正在积累本命令的发布请求。 返回当前是否正在积累一个命令批次的发布请求。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean deferred;
    /**  积累期间是否存在需要发布或清理的变化。 返回该批次是否请求过发布，不代表账户和账本已完成。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean dirty;
    /**  是否只有准入临时变化，正式请求后不再降级。 返回本批次是否仅有准入临时投影，不能作为已提交终态发布。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean provisionalOnly;
    /** 当前是否正在 publish 中，防止重入。 */
    private boolean active;
    /**  独立的已发布序号，正常发布成功后推进。 返回最近一次正常发布的独立序号，不等同于 Core 命令序号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long publishedSequence;
    /**  是否已经进入可发布生命周期。 返回是否曾成功激活，关闭状态仍须单独由健康检查判定。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean activated;
    /** 是否已经关闭，关闭后健康检查失败。 */
    private boolean closed;

    /**
     * 保存非空所属运行时，不启动线程，也不复制账户状态。
     * @param owner 所属交易运行时
     */
    CommitPublication(TradingCoreRuntime owner) {
        this.owner = Objects.requireNonNull(owner);
    }

    /**
     * 要求没有积累或正在执行的发布，且发布序号非负，再恢复索引修订水位及发布序号；不在此验证运行时修订非负或重开已关闭对象。
     * @param runtimePatchRevision 要恢复的派生索引已应用修订水位
     * @param publishedSequence 要恢复的非负发布序号
     */
    public void initialize(long runtimePatchRevision, long publishedSequence) {
        if (deferred || dirty || provisionalOnly || active) {
            throw new IllegalStateException("cannot initialize an active commit publication");
        }
        if (publishedSequence < 0) throw new IllegalArgumentException("initial commit sequence is negative");
        this.runtimePatchRevision = runtimePatchRevision;
        this.publishedSequence = publishedSequence;
    }

    /**
     * 拒绝激活已关闭发布器，否则标记可使用；重复激活不会重置水位。
     */
    public void activate() {
        if (closed) throw new IllegalStateException("cannot activate closed commit publication");
        activated = true;
    }

    /**
     * 未激活或已关闭时抛错，防止在无效生命周期内发布。
     */
    public void assertHealthy() {
        if (closed) throw new IllegalStateException("commit publication is closed");
        if (!activated) throw new IllegalStateException("commit publication is not activated");
    }

    /**
     * 只标记关闭，不自行补发当前变化或停止其他线程。
     */
    public void close() { closed = true; }

    /**
     * 积累模式只记脏并升级为正式发布；未积累时立即进入一次发布。
     */
    public void request() {
        if (deferred) {
            dirty = true;
            provisionalOnly = false;
            return;
        }
        publish();
    }

    /**
     * 拒绝重复开始积累，然后开启命令发布批次并清空脏及临时标记；不冻结业务状态。
     */
    public void begin() {
        if (deferred) throw new IllegalStateException("snapshot projection batch is already active");
        deferred = true;
        dirty = false;
        provisionalOnly = false;
    }

    /**
     * 先取出并清理本批标记；只有临时变化就清除变化键，正式变化则发布，没有变化则结束。这里不要求调用前一定处于积累模式。
     */
    public void complete() {
        // 清理标记前记住的本批是否有变化
        boolean changed = dirty;
        // 清理标记前记住的本批是否仅临时变化
        boolean provisional = provisionalOnly;
        clearBatchState();
        if (changed && provisional) owner.runtimeState().clearChangedKeys();
        else if (changed) publish();
    }


    /**
     * 拒绝在已有积累或发布执行中覆盖标记，再恢复调用方保存的脏及临时状态。
     * @param dirty 被挂起命令保存的待发布标记
     * @param provisionalOnly 被挂起命令保存的仅临时投影标记
     */
    public void restore(boolean dirty, boolean provisionalOnly) {
        if (deferred || active) throw new IllegalStateException("another commit publication is active");
        deferred = true;
        this.dirty = dirty;
        this.provisionalOnly = provisionalOnly;
    }

    /**
     * 拒绝重入并检查生命周期，计算下一发布序号；在活动事实作用域内捕获可选实时变化、应用派生索引，再推进序号和修订并清已提交变化。内部运行异常记录恢复故障后继续抛出，不回滚已发生的部分变化。
     */
    void publish() {
        if (active) throw new IllegalStateException("owner commit publisher is already active");
        active = true;
        try {
            assertHealthy();
            // 本次计划发布的下一独立序号，精确加一溢出会抛错
            long sequence = Math.incrementExact(publishedSequence);
            try {
                if (!owner.factContextActive()) {
                    throw new IllegalStateException("runtime commit requires an active command scope");
                }
                if (owner.realtimeCapture() != null) owner.runtimeState().captureRealtimeChanges(owner.realtimeCapture());
                if (owner.runtimeState().committedRevision() < runtimePatchRevision)
                    throw new IllegalStateException("runtime changed-index commit is out of order");
                owner.runtimeState().applyChangedIndexes();
                publishedSequence = sequence;
                runtimePatchRevision = owner.runtimeState().committedRevision();
                owner.runtimeState().clearCommittedChanges(owner.identities());
            } catch (/* 内部发布过程中遇到的运行异常，需记录并由外层恢复处理 */ RuntimeException failure) {
                owner.commitPublicationFailure(new IllegalStateException(
                        "owner commit failed after deterministic mutation; restart from snapshot and log is required",
                        failure));
                throw failure;
            }
        } finally {
            active = false;
        }
    }

    /**
     * 清空积累、脏及临时三项标记，不改变发布水位、生命周期或账户状态。
     */
    public void clearBatchState() {
        deferred = false;
        dirty = false;
        provisionalOnly = false;
    }
}
