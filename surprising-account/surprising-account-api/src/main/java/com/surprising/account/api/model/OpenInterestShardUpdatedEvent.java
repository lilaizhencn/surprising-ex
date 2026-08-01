package com.surprising.account.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

/** 账户模块提交分片变化后发布的未平仓量增量事件。 */
public record OpenInterestShardUpdatedEvent(
        int schemaVersion,
        long eventId,
        ProductLine productLine,
        String symbol,
        int shardId,
        long longQuantitySteps,
        long shortQuantitySteps,
        long revision,
        Instant eventTime) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public OpenInterestShardUpdatedEvent {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported open interest event schemaVersion: " + schemaVersion);
        }
        if (eventId <= 0L || productLine == null || symbol == null || symbol.isBlank()
                || shardId < 0 || shardId >= 64 || longQuantitySteps < 0L || shortQuantitySteps < 0L
                || revision <= 0L || eventTime == null) {
            throw new IllegalArgumentException("invalid open interest event");
        }
        symbol = symbol.trim().toUpperCase();
    }

    public String partitionKey() {
        return productLine.name() + ":" + symbol + ":" + shardId;
    }
}
