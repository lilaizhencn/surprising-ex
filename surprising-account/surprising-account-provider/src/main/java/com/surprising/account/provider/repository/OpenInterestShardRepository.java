package com.surprising.account.provider.repository;

import com.surprising.product.api.ProductLine;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OpenInterestShardRepository {

    private static final int SHARD_COUNT = 64;

    private final JdbcTemplate jdbcTemplate;

    public OpenInterestShardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int seed(ProductLine productLine, String symbol, int shardId, Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO trading_symbol_open_interest_shards (
                    product_line, symbol, shard_id, long_quantity_steps, short_quantity_steps, updated_at
                ) VALUES (?, ?, ?, 0, 0, ?)
                ON CONFLICT (product_line, symbol, shard_id) DO NOTHING
                """, productLine.name(), symbol, shardId, Timestamp.from(now));
    }

    public int adjust(ProductLine productLine,
                      String symbol,
                      int shardId,
                      long longDelta,
                      long shortDelta,
                      Instant now) {
        return jdbcTemplate.update("""
                UPDATE trading_symbol_open_interest_shards
                   SET long_quantity_steps = long_quantity_steps + ?,
                       short_quantity_steps = short_quantity_steps + ?,
                       updated_at = ?
                 WHERE product_line = ?
                   AND symbol = ?
                   AND shard_id = ?
                   AND long_quantity_steps + ? >= 0
                   AND short_quantity_steps + ? >= 0
                """, longDelta, shortDelta, Timestamp.from(now), productLine.name(), symbol, shardId,
                longDelta, shortDelta);
    }

    public static int shardId(long userId) {
        return Math.floorMod(userId, SHARD_COUNT);
    }

    public void seedAndLock(List<OpenInterestShard> shards, Instant now) {
        if (shards == null || shards.isEmpty()) {
            return;
        }
        String values = String.join(", ", Collections.nCopies(shards.size(),
                "(?::text, ?::text, ?::integer, ?::timestamptz)"));
        List<Object> seedArgs = new ArrayList<>(shards.size() * 4);
        Timestamp lockedAt = Timestamp.from(now == null ? Instant.now() : now);
        for (OpenInterestShard shard : shards) {
            seedArgs.add(shard.productLine().name());
            seedArgs.add(shard.symbol());
            seedArgs.add(shard.shardId());
            seedArgs.add(lockedAt);
        }
        int inserted = jdbcTemplate.update("""
                WITH input(product_line, symbol, shard_id, locked_at) AS (
                    VALUES %s
                )
                INSERT INTO trading_symbol_open_interest_shards (
                    product_line, symbol, shard_id, long_quantity_steps, short_quantity_steps, updated_at
                )
                SELECT product_line, symbol, shard_id, 0, 0, locked_at
                  FROM input
                 ORDER BY product_line, symbol, shard_id
                ON CONFLICT (product_line, symbol, shard_id) DO NOTHING
                """.formatted(values), seedArgs.toArray());
        if (inserted < 0 || inserted > shards.size()) {
            throw new IllegalStateException("unexpected open interest batch seed rows: " + inserted);
        }

        String lockValues = String.join(", ", Collections.nCopies(shards.size(),
                "(?::text, ?::text, ?::integer)"));
        List<Object> lockArgs = new ArrayList<>(shards.size() * 3);
        for (OpenInterestShard shard : shards) {
            lockArgs.add(shard.productLine().name());
            lockArgs.add(shard.symbol());
            lockArgs.add(shard.shardId());
        }
        List<Integer> locked = jdbcTemplate.query("""
                WITH input(product_line, symbol, shard_id) AS (
                    VALUES %s
                )
                SELECT 1
                  FROM trading_symbol_open_interest_shards shard
                  JOIN input USING (product_line, symbol, shard_id)
                 ORDER BY shard.product_line, shard.symbol, shard.shard_id
                   FOR UPDATE OF shard
                """.formatted(lockValues), (rs, rowNum) -> 1, lockArgs.toArray());
        if (locked.size() != shards.size()) {
            throw new IllegalStateException("failed to lock all open interest shards");
        }
    }

    public record OpenInterestShard(ProductLine productLine, String symbol, int shardId) {
    }
}
