package com.surprising.account.api.model;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

/**
 * Aeron Core 提交后的完整持仓状态事件。
 *
 * <p>Core Export 发布该事件，驱动风控、触发单清理、WebSocket 推送和 Redis 持仓读模型。
 * {@code revision} 由 Core 状态提交顺序分配，消费者按修订号幂等应用；PostgreSQL 只作为异步
 * 投影。生产者必须使用 {@link #partitionKey()} 作为 Kafka key，保证同一用户的持仓更新有序。</p>
 */
public record PositionUpdatedEvent(
        int schemaVersion,
        long eventId,
        long tradeId,
        ProductLine productLine,
        long revision,
        long userId,
        String symbol,
        long instrumentVersion,
        MarginMode marginMode,
        PositionSide positionSide,
        long signedQuantitySteps,
        long entryPriceTicks,
        long entryValueTicks,
        long realizedPnlUnits,
        String marginAsset,
        long marginUnits,
        Instant positionUpdatedAt,
        Instant marginUpdatedAt,
        Instant eventTime,
        String traceId) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public PositionUpdatedEvent {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported position event schemaVersion: " + schemaVersion);
        }
        if (eventId <= 0L || revision <= 0L || instrumentVersion <= 0L) {
            throw new IllegalArgumentException("eventId, revision, and instrumentVersion must be positive");
        }
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        if (userId <= 0L) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
        marginAsset = marginAsset == null ? "" : marginAsset;
        if (marginUnits < 0L) {
            throw new IllegalArgumentException("marginUnits must not be negative");
        }
        if (positionUpdatedAt == null || marginUpdatedAt == null || eventTime == null) {
            throw new IllegalArgumentException("position, margin, and event timestamps are required");
        }
    }

    public String partitionKey() {
        return AccountUserCommand.partitionKey(productLine, userId);
    }

    public PositionCacheEvent cacheEvent() {
        return new PositionCacheEvent(
                revision,
                productLine,
                userId,
                symbol,
                instrumentVersion,
                marginMode,
                positionSide,
                signedQuantitySteps,
                entryPriceTicks,
                entryValueTicks,
                realizedPnlUnits,
                marginAsset,
                marginUnits,
                positionUpdatedAt,
                marginUpdatedAt,
                revision);
    }

}
