package com.surprising.account.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;

/** 账户模块提供给其他服务启动恢复使用的未平仓量完整快照。 */
public record OpenInterestSnapshotResponse(
        ProductLine productLine,
        long snapshotRevision,
        Instant snapshotAt,
        List<OpenInterestShardSnapshot> shards) {

    public OpenInterestSnapshotResponse {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        if (snapshotRevision < 0L || snapshotAt == null) {
            throw new IllegalArgumentException("snapshot revision and time are required");
        }
        shards = shards == null ? List.of() : List.copyOf(shards);
        shards.forEach(shard -> {
            if (shard.productLine() != productLine) {
                throw new IllegalArgumentException("snapshot shard product line mismatch");
            }
        });
    }
}
