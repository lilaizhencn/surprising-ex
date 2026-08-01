package com.surprising.account.api.cache;

import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.product.api.ProductLine;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按永续用户维护完整账户状态快照。
 *
 * <p>快照只接受同一用户更大的账户修订号；发现修订间隙时暂停该用户，不能把缺失事件
 * 当作零余额或零持仓。启动恢复完成后由协调器显式标记 ready。</p>
 */
public final class PerpetualAccountStateSnapshotCache {

    private final ProductLine productLine;
    private final ConcurrentMap<Long, PerpetualAccountStateUpdatedEvent> states = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Object> userLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, AtomicBoolean> userReady = new ConcurrentHashMap<>();
    private final AtomicBoolean ready = new AtomicBoolean();

    public PerpetualAccountStateSnapshotCache() {
        this(ProductLine.LINEAR_PERPETUAL);
    }

    public PerpetualAccountStateSnapshotCache(ProductLine productLine) {
        if (productLine != ProductLine.LINEAR_PERPETUAL) {
            throw new IllegalArgumentException("account state cache only supports LINEAR_PERPETUAL");
        }
        this.productLine = productLine;
    }

    public ApplyResult apply(PerpetualAccountStateUpdatedEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("account state event is required");
        }
        if (event.productLine() != productLine) {
            return ApplyResult.PRODUCT_LINE_MISMATCH;
        }
        Object lock = userLocks.computeIfAbsent(event.userId(), ignored -> new Object());
        synchronized (lock) {
            PerpetualAccountStateUpdatedEvent previous = states.get(event.userId());
            if (previous != null && event.accountRevision() <= previous.accountRevision()) {
                return ApplyResult.STALE;
            }
            if (previous != null && event.accountRevision() > previous.accountRevision() + 1L) {
                userReady.computeIfAbsent(event.userId(), ignored -> new AtomicBoolean()).set(false);
                ready.set(false);
                return ApplyResult.REVISION_GAP;
            }
            states.put(event.userId(), event);
            // Kafka 启动追赶完成前不能把历史中间态当成可下单快照；RPC 初始化的用户
            // 由 initialize 标记为就绪，Kafka 后续只负责按修订号增量更新。
            if (ready.get()) {
                userReady.computeIfAbsent(event.userId(), ignored -> new AtomicBoolean()).set(true);
            }
            return ApplyResult.APPLIED;
        }
    }

    /** 使用账户模块内部 RPC 写入一个当前完整快照，不要求全局 Kafka 已追赶完成。 */
    public ApplyResult initialize(PerpetualAccountStateUpdatedEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("account state event is required");
        }
        if (event.productLine() != productLine) {
            return ApplyResult.PRODUCT_LINE_MISMATCH;
        }
        Object lock = userLocks.computeIfAbsent(event.userId(), ignored -> new Object());
        synchronized (lock) {
            PerpetualAccountStateUpdatedEvent previous = states.get(event.userId());
            if (previous != null && event.accountRevision() < previous.accountRevision()) {
                return ApplyResult.STALE;
            }
            states.put(event.userId(), event);
            userReady.computeIfAbsent(event.userId(), ignored -> new AtomicBoolean()).set(true);
            return previous != null && event.accountRevision() == previous.accountRevision()
                    ? ApplyResult.STALE : ApplyResult.APPLIED;
        }
    }

    public Optional<PerpetualAccountStateUpdatedEvent> state(long userId) {
        if (userId <= 0L || !isUserReady(userId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(states.get(userId));
    }

    /** 返回当前已经接收的完整用户快照，调用方不能修改缓存内部状态。 */
    public List<PerpetualAccountStateUpdatedEvent> states() {
        if (!ready()) {
            return List.of();
        }
        return states.values().stream()
                .sorted(java.util.Comparator.comparingLong(PerpetualAccountStateUpdatedEvent::userId))
                .toList();
    }

    public boolean isUserReady(long userId) {
        AtomicBoolean value = userReady.get(userId);
        return value != null && value.get();
    }

    public long revision(long userId) {
        return state(userId).map(PerpetualAccountStateUpdatedEvent::accountRevision).orElse(0L);
    }

    public boolean ready() {
        return ready.get();
    }

    public void markReady() {
        ready.set(true);
        // Kafka 追赶期间收到的历史事件不会提前创建 userReady 标记；全局追赶完成后，
        // 将已有完整状态统一转换为可读快照，避免空 map 导致用户一直不可用。
        states.keySet().forEach(userId ->
                userReady.computeIfAbsent(userId, ignored -> new AtomicBoolean()).set(true));
        userReady.forEach((userId, value) -> value.set(states.containsKey(userId)));
    }

    public void markNotReady() {
        ready.set(false);
    }

    public void clear() {
        states.clear();
        userLocks.clear();
        userReady.clear();
        ready.set(false);
    }

    public ProductLine productLine() {
        return productLine;
    }

    public enum ApplyResult {
        APPLIED,
        STALE,
        REVISION_GAP,
        PRODUCT_LINE_MISMATCH
    }
}
