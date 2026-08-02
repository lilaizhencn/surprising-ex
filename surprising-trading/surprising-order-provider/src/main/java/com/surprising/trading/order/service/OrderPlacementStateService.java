package com.surprising.trading.order.service;

import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import com.surprising.account.api.client.PerpetualAccountStateRpcApi;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.order.model.ReduceOnlyPosition;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/** 订单下单所需的账户状态入口，只读取账户 JVM 快照并负责启动时按用户初始化。 */
@Service
public class OrderPlacementStateService {
    private final PerpetualAccountStateSnapshotCache accountStateSnapshotCache;
    private final PerpetualAccountStateRpcApi accountStateRpcApi;
    private final OrderMarginSnapshotCache marginSnapshotCache;
    private final ConcurrentMap<Long, Object> snapshotInitializationLocks = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public OrderPlacementStateService(@Nullable PerpetualAccountStateSnapshotCache accountStateSnapshotCache,
                                      @Nullable PerpetualAccountStateRpcApi accountStateRpcApi,
                                      @Nullable OrderMarginSnapshotCache marginSnapshotCache) {
        this.accountStateSnapshotCache = accountStateSnapshotCache;
        this.accountStateRpcApi = accountStateRpcApi;
        this.marginSnapshotCache = marginSnapshotCache;
    }

    /** 单元测试构造；生产由 Spring 注入完整的本地保证金快照。 */
    OrderPlacementStateService(@Nullable PerpetualAccountStateSnapshotCache accountStateSnapshotCache,
                               @Nullable PerpetualAccountStateRpcApi accountStateRpcApi) {
        this(accountStateSnapshotCache, accountStateRpcApi, null);
    }

    public PositionMode positionMode(ProductLine line, long userId) {
        if (line == ProductLine.SPOT) {
            return PositionMode.ONE_WAY;
        }
        requireCacheLine(line);
        if (accountStateSnapshotCache == null) {
            throw new IllegalStateException("订单本地状态尚未支持该产品线的仓位模式快照: " + line);
        }
        if (!accountStateSnapshotCache.ready()) {
            // Kafka 可能仍在追赶历史位点；只对当前用户做一次内部 RPC 初始化，
            // 初始化成功后本地缓存即可安全服务该用户，后续更新仍由 Kafka 驱动。
            initializeAccountState(line, userId);
        }
        return state(line, userId).positionMode();
    }

    /**
     * 订单 WAL 热路径使用的仓位模式读取；永续只允许从账户 JVM 快照读取，不能退回数据库。
     */
    public PositionMode localPositionMode(ProductLine line, long userId) {
        if (line == ProductLine.SPOT) {
            return PositionMode.ONE_WAY;
        }
        requireCacheLine(line);
        if (accountStateSnapshotCache == null) {
            throw new IllegalStateException("订单本地状态尚未支持该产品线的仓位模式快照: " + line);
        }
        return positionMode(line, userId);
    }

    /**
     * 从账户完整快照判断已有持仓是否使用了不同的保证金模式。
     */
    public boolean cachedPositionMarginModeConflict(ProductLine line,
                                                     long userId,
                                                     String symbol,
                                                     MarginMode marginMode) {
        if (line == ProductLine.SPOT) {
            return false;
        }
        requireCacheLine(line);
        if (accountStateSnapshotCache == null) {
            throw new IllegalStateException("订单本地状态尚未支持该产品线的保证金模式快照: " + line);
        }
        // positionMode 会在需要时通过账户内部 RPC 初始化单个用户，初始化完成后只读 JVM 快照。
        positionMode(line, userId);
        MarginMode normalized = MarginMode.defaultIfNull(marginMode);
        return state(line, userId).positions().stream()
                .filter(position -> position.symbol().equalsIgnoreCase(symbol))
                .filter(position -> position.signedQuantitySteps() != 0L)
                .anyMatch(position -> position.marginMode() != normalized);
    }

    /** 返回订单计算所依据的永续账户修订号；非永续或影子快照未启用时返回零。 */
    public long accountRevision(ProductLine line, long userId) {
        if (accountStateSnapshotCache == null || accountStateSnapshotCache.productLine() != line) {
            return 0L;
        }
        if (!accountStateSnapshotCache.ready()) {
            initializeAccountState(line, userId);
        }
        return state(line, userId).accountRevision();
    }

    private PerpetualAccountStateUpdatedEvent state(ProductLine line, long userId) {
        requireCacheLine(line);
        Optional<PerpetualAccountStateUpdatedEvent> state = accountStateSnapshotCache.state(userId);
        if (state.isPresent()) {
            return state.get();
        }
        initializeAccountState(line, userId);
        return accountStateSnapshotCache.state(userId)
                .orElseThrow(() -> new IllegalStateException("用户账户状态快照不存在: " + line + ":" + userId));
    }

    private void initializeAccountState(ProductLine line, long userId) {
        requireCacheLine(line);
        if (accountStateRpcApi == null) {
            if (!accountStateSnapshotCache.ready()) {
                throw new IllegalStateException("账户状态快照尚未就绪: " + line);
            }
            throw new IllegalStateException("用户账户状态快照不存在: " + line + ":" + userId);
        }
        Object lock = snapshotInitializationLocks.computeIfAbsent(userId, ignored -> new Object());
        try {
            synchronized (lock) {
                if (accountStateSnapshotCache.state(userId).isPresent()) {
                    return;
                }
                PerpetualAccountStateUpdatedEvent event = accountStateRpcApi.snapshot(line, userId);
                PerpetualAccountStateSnapshotCache.ApplyResult result = accountStateSnapshotCache.initialize(event);
                if (result == PerpetualAccountStateSnapshotCache.ApplyResult.PRODUCT_LINE_MISMATCH) {
                    throw new IllegalStateException("账户初始化快照产品线不一致");
                }
                if (result == PerpetualAccountStateSnapshotCache.ApplyResult.CONFLICT) {
                    throw new IllegalStateException("账户初始化快照同一修订号内容冲突");
                }
                if (marginSnapshotCache != null) {
                    marginSnapshotCache.applyAccountSnapshot(event);
                    marginSnapshotCache.markAccountSnapshotReady(line);
                }
            }
        } catch (RuntimeException ex) {
            throw new IllegalStateException("用户账户状态快照初始化失败: " + line + ":" + userId, ex);
        } finally {
            snapshotInitializationLocks.remove(userId, lock);
        }
    }

    /**
     * 永续订单事实流使用账户 JVM 快照读取持仓，不允许为了平仓重新打开持仓表事务。
     */
    public Optional<ReduceOnlyPosition> localPosition(ProductLine line,
                                                      long userId,
                                                      String symbol,
                                                      MarginMode mode,
                                                      PositionSide side) {
        if (line == ProductLine.SPOT) {
            return Optional.empty();
        }
        requireCacheLine(line);
        if (accountStateSnapshotCache == null) {
            throw new IllegalStateException("订单本地状态尚未支持该产品线的持仓快照: " + line);
        }
        PerpetualAccountStateUpdatedEvent state = state(line, userId);
        MarginMode normalizedMode = MarginMode.defaultIfNull(mode);
        PositionSide normalizedSide = PositionSide.defaultIfNull(side);
        return state.positions().stream()
                .filter(position -> position.symbol().equalsIgnoreCase(symbol))
                .filter(position -> position.marginMode() == normalizedMode)
                .filter(position -> position.positionSide() == normalizedSide)
                .filter(position -> position.signedQuantitySteps() != 0L)
                .map(position -> new ReduceOnlyPosition(position.signedQuantitySteps(),
                        position.instrumentVersion()))
                .findFirst();
    }

    private void requireCacheLine(ProductLine line) {
        if (line == null) {
            throw new IllegalArgumentException("产品线不能为空");
        }
        if (accountStateSnapshotCache != null && accountStateSnapshotCache.productLine() != line) {
            throw new IllegalStateException("订单账户快照产品线不一致: " + line);
        }
    }

}
