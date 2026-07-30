package com.surprising.account.provider.service;

import com.surprising.account.provider.model.PositionState;
import com.surprising.account.provider.repository.OpenInterestShardRepository;
import com.surprising.account.provider.repository.PositionRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PositionOpenInterestService {

    private final JdbcTemplate jdbcTemplate;
    private final PositionRepository positionRepository;
    private final OpenInterestShardRepository openInterestShardRepository;

    public PositionOpenInterestService(JdbcTemplate jdbcTemplate,
                                       PositionRepository positionRepository,
                                       OpenInterestShardRepository openInterestShardRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.positionRepository = positionRepository;
        this.openInterestShardRepository = openInterestShardRepository;
    }

    public void update(ProductLine productLine,
                       long userId,
                       String symbol,
                       MarginMode marginMode,
                       PositionSide positionSide,
                       PositionState state,
                       long previousSignedQuantitySteps,
                       Instant now) {
        long longDelta = Math.subtractExact(longQuantitySteps(state.signedQuantitySteps()),
                longQuantitySteps(previousSignedQuantitySteps));
        long shortDelta = Math.subtractExact(shortQuantitySteps(state.signedQuantitySteps()),
                shortQuantitySteps(previousSignedQuantitySteps));
        if (longDelta == 0L && shortDelta == 0L) {
            int rows = positionRepository.update(
                    productLine, userId, symbol, marginMode, positionSide, state, now);
            requireSingleRow(rows, "account position update");
            return;
        }
        int shardId = OpenInterestShardRepository.shardId(userId);
        int rows = openInterestShardRepository.seed(productLine, symbol, shardId, now);
        if (rows < 0 || rows > 1) {
            throw new IllegalStateException("unexpected open interest shard seed rows: " + rows);
        }
        Timestamp updatedAt = Timestamp.from(now);
        rows = jdbcTemplate.update("""
                WITH updated_position AS (
                    UPDATE account_positions
                       SET signed_quantity_steps = ?,
                           instrument_version = ?,
                           entry_price_ticks = ?,
                           entry_value_ticks = ?,
                           realized_pnl_units = ?,
                           updated_at = ?
                     WHERE product_line = ?
                       AND user_id = ?
                       AND symbol = ?
                       AND margin_mode = ?
                       AND position_side = ?
                 RETURNING 1
                )
                UPDATE trading_symbol_open_interest_shards AS shard
                   SET long_quantity_steps = shard.long_quantity_steps + ?,
                       short_quantity_steps = shard.short_quantity_steps + ?,
                       updated_at = ?
                  FROM updated_position
                 WHERE shard.product_line = ?
                   AND shard.symbol = ?
                   AND shard.shard_id = ?
                   AND shard.long_quantity_steps + ? >= 0
                   AND shard.short_quantity_steps + ? >= 0
                """, state.signedQuantitySteps(), nullableVersion(state.instrumentVersion()),
                state.entryPriceTicks(), state.entryValueTicks(), state.realizedPnlUnits(), updatedAt,
                productLine.name(), userId, symbol, marginMode.name(), positionSide.name(),
                longDelta, shortDelta, updatedAt, productLine.name(), symbol, shardId, longDelta, shortDelta);
        requireSingleRow(rows, "account position and open interest shard update");
    }

    private static long longQuantitySteps(long signedQuantitySteps) {
        return signedQuantitySteps > 0 ? signedQuantitySteps : 0L;
    }

    private static long shortQuantitySteps(long signedQuantitySteps) {
        return signedQuantitySteps < 0 ? Math.negateExact(signedQuantitySteps) : 0L;
    }

    private static Long nullableVersion(long version) {
        return version == 0L ? null : version;
    }

    private static void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + " affected " + rows + " rows");
        }
    }
}
