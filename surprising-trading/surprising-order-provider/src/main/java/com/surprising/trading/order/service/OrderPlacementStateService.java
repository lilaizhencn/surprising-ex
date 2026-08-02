package com.surprising.trading.order.service;

import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.order.model.ReduceOnlyPosition;
import java.util.Optional;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/** 订单下单所需的账户状态入口，只读取已经就绪的账户 JVM 快照。 */
@Service
public class OrderPlacementStateService {
    private final PerpetualAccountStateSnapshotCache accountStateSnapshotCache;
    private final OrderMarginSnapshotCache marginSnapshotCache;

    @org.springframework.beans.factory.annotation.Autowired
    public OrderPlacementStateService(@Nullable PerpetualAccountStateSnapshotCache accountStateSnapshotCache,
                                      @Nullable OrderMarginSnapshotCache marginSnapshotCache) {
        this.accountStateSnapshotCache = accountStateSnapshotCache;
        this.marginSnapshotCache = marginSnapshotCache;
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
            throw new IllegalStateException("账户状态快照尚未就绪: " + line);
        }
        return state(line, userId).positionMode();
    }

    /**
     * 订单 WAL 热路径使用的仓位模式读取；合约只允许从账户 JVM 快照读取，不能退回数据库。
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
        positionMode(line, userId);
        MarginMode normalized = MarginMode.defaultIfNull(marginMode);
        return state(line, userId).positions().stream()
                .filter(position -> position.symbol().equalsIgnoreCase(symbol))
                .filter(position -> position.signedQuantitySteps() != 0L)
                .anyMatch(position -> position.marginMode() != normalized);
    }

    /** 返回订单计算所依据的合约账户修订号；现货或影子快照未启用时返回零。 */
    public long accountRevision(ProductLine line, long userId) {
        if (accountStateSnapshotCache == null || accountStateSnapshotCache.productLine() != line) {
            return 0L;
        }
        return state(line, userId).accountRevision();
    }

    private PerpetualAccountStateUpdatedEvent state(ProductLine line, long userId) {
        requireCacheLine(line);
        Optional<PerpetualAccountStateUpdatedEvent> state = accountStateSnapshotCache.state(userId);
        if (state.isPresent()) {
            return state.get();
        }
        throw new IllegalStateException("用户账户状态快照不存在: " + line + ":" + userId);
    }

    /**
     * 合约订单事实流使用账户 JVM 快照读取持仓，不允许为了平仓重新打开持仓表事务。
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
