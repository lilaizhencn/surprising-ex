package com.surprising.account.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

/**
 * 一个未平仓量分片的不可变快照。
 *
 * <p>账户模块只发布分片的绝对值，消费方可以按修订号幂等替换，避免重复消费增量造成重复累加。</p>
 */
public record OpenInterestShardSnapshot(
        ProductLine productLine,
        String symbol,
        int shardId,
        long longQuantitySteps,
        long shortQuantitySteps,
        long revision,
        Instant updatedAt) {

    public OpenInterestShardSnapshot {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        symbol = symbol.trim().toUpperCase();
        if (shardId < 0 || shardId >= 64) {
            throw new IllegalArgumentException("shardId must be in [0, 63]");
        }
        if (longQuantitySteps < 0L || shortQuantitySteps < 0L) {
            throw new IllegalArgumentException("open interest quantities must not be negative");
        }
        if (revision <= 0L || updatedAt == null) {
            throw new IllegalArgumentException("revision and updatedAt are required");
        }
    }
}
