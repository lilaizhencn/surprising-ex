package com.surprising.account.api.model;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.List;

/**
 * Aeron Core 提交后的完整产品线用户状态快照。
 *
 * <p>这是 Core Export 从 Aeron Cluster 提交状态发布的 canonical 状态事件。Core 的恢复依据是
 * Snapshot 加 Snapshot 之后的 Cluster Log Replay；其他模块通过该事件建立自己的 JVM/Redis
 * 快照，数据库只异步投影该完整状态。任何列表为空都表示该类状态确实为空，不能把事件缺失解释成
 * 零余额或零持仓。</p>
 */
public record PerpetualAccountStateUpdatedEvent(
        int schemaVersion,
        long eventId,
        long accountRevision,
        ProductLine productLine,
        long userId,
        String accountType,
        List<Balance> balances,
        List<Deficit> deficits,
        List<Position> positions,
        List<PositionMargin> positionMargins,
        List<OrderLock> orderLocks,
        PositionMode positionMode,
        Instant eventTime,
        String traceId) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public PerpetualAccountStateUpdatedEvent {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported account state schemaVersion: "
                    + schemaVersion);
        }
        if (eventId <= 0L || accountRevision <= 0L) {
            throw new IllegalArgumentException("eventId and accountRevision must be positive");
        }
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        if (userId <= 0L) {
            throw new IllegalArgumentException("userId must be positive");
        }
        accountType = requireText(accountType, "accountType");
        AccountType accountTypeValue;
        try {
            accountTypeValue = AccountType.valueOf(accountType);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unsupported accountType: " + accountType, ex);
        }
        if (accountTypeValue.productLine().orElse(null) != productLine) {
            throw new IllegalArgumentException("accountType 与 productLine 不匹配: "
                    + accountType + " / " + productLine);
        }
        balances = copyRequired(balances, "balances");
        deficits = copyRequired(deficits, "deficits");
        positions = copyRequired(positions, "positions");
        positionMargins = copyRequired(positionMargins, "positionMargins");
        orderLocks = copyRequired(orderLocks, "orderLocks");
        positionMode = PositionMode.defaultIfNull(positionMode);
        if (eventTime == null) {
            throw new IllegalArgumentException("eventTime is required");
        }
        traceId = traceId == null || traceId.isBlank() ? null : traceId.trim();
    }

    public String partitionKey() {
        return productLine.name() + ":" + userId;
    }

    public record Balance(String asset, long availableUnits, long lockedUnits) {
        public Balance {
            asset = normalizeAsset(asset);
            if (availableUnits < 0L || lockedUnits < 0L) {
                throw new IllegalArgumentException("account balance units must not be negative");
            }
        }
    }

    public record Deficit(String asset, long deficitUnits, long reservedUnits) {
        public Deficit {
            asset = normalizeAsset(asset);
            if (deficitUnits < 0L || reservedUnits < 0L || reservedUnits > deficitUnits) {
                throw new IllegalArgumentException("invalid account deficit units");
            }
        }
    }

    public record Position(String symbol,
                           long instrumentVersion,
                           MarginMode marginMode,
                           PositionSide positionSide,
                           long signedQuantitySteps,
                           long entryPriceTicks,
                           long entryValueTicks,
                           long realizedPnlUnits,
                           Instant updatedAt) {
        public Position {
            symbol = normalizeSymbol(symbol);
            marginMode = MarginMode.defaultIfNull(marginMode);
            positionSide = PositionSide.defaultIfNull(positionSide);
            if (signedQuantitySteps == 0L) {
                if (entryPriceTicks != 0L || entryValueTicks != 0L
                        || (instrumentVersion < 0L)) {
                    throw new IllegalArgumentException("flat position fields are invalid");
                }
            } else if (instrumentVersion <= 0L || entryPriceTicks <= 0L || entryValueTicks <= 0L) {
                throw new IllegalArgumentException("open position fields are incomplete");
            }
            if (updatedAt == null) {
                throw new IllegalArgumentException("position updatedAt is required");
            }
        }
    }

    public record PositionMargin(String symbol,
                                 String asset,
                                 MarginMode marginMode,
                                 PositionSide positionSide,
                                 long marginUnits) {
        public PositionMargin {
            symbol = normalizeSymbol(symbol);
            asset = normalizeAsset(asset);
            marginMode = MarginMode.defaultIfNull(marginMode);
            positionSide = PositionSide.defaultIfNull(positionSide);
            if (marginUnits < 0L) {
                throw new IllegalArgumentException("position margin units must not be negative");
            }
        }
    }

    public record OrderLock(String asset, long lockedUnits) {
        public OrderLock {
            asset = normalizeAsset(asset);
            if (lockedUnits < 0L) {
                throw new IllegalArgumentException("order lock units must not be negative");
            }
        }
    }

    private static <T> List<T> copyRequired(List<T> values, String field) {
        if (values == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return List.copyOf(values);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim().toUpperCase();
    }

    private static String normalizeAsset(String value) {
        String normalized = requireText(value, "asset");
        if (!normalized.matches("[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("invalid asset: " + value);
        }
        return normalized;
    }

    private static String normalizeSymbol(String value) {
        String normalized = requireText(value, "symbol");
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + value);
        }
        return normalized;
    }
}
