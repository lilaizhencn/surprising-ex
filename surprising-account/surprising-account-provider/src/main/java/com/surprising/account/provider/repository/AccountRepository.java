package com.surprising.account.provider.repository;

import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.PositionMarginAdjustmentResponse;
import com.surprising.account.api.model.PositionMarginResponse;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.provider.model.BalanceDebitResult;
import com.surprising.account.provider.model.BalanceSettlementState;
import com.surprising.account.provider.model.ContractSpec;
import com.surprising.account.provider.model.LiquidationFeeContext;
import com.surprising.account.provider.model.LiquidationFeeSettlement;
import com.surprising.account.provider.model.OrderFeeSnapshot;
import com.surprising.account.provider.model.PositionState;
import com.surprising.account.provider.service.MarginTransferMath;
import com.surprising.account.provider.service.PnlSettlementMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.trading.api.model.MarginMode;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AccountRepository {

    private static final long PPM = 1_000_000L;

    private final JdbcTemplate jdbcTemplate;
    private final AccountSequenceRepository sequenceRepository;

    public AccountRepository(JdbcTemplate jdbcTemplate, AccountSequenceRepository sequenceRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.sequenceRepository = sequenceRepository;
    }

    public Optional<BalanceResponse> balance(long userId, String asset) {
        return jdbcTemplate.query("""
                SELECT b.user_id, b.asset, b.available_units, b.locked_units,
                       b.available_units + b.locked_units - COALESCE(d.deficit_units, 0) AS equity_units,
                       b.updated_at
                  FROM account_balances b
                  LEFT JOIN account_deficits d USING (user_id, asset)
                 WHERE b.user_id = ? AND b.asset = ?
                """, (rs, rowNum) -> new BalanceResponse(
                rs.getLong("user_id"),
                rs.getString("asset"),
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("equity_units"),
                rs.getTimestamp("updated_at").toInstant()), userId, asset).stream().findFirst();
    }

    public List<BalanceResponse> balances(long userId) {
        return jdbcTemplate.query("""
                SELECT b.user_id, b.asset, b.available_units, b.locked_units,
                       b.available_units + b.locked_units - COALESCE(d.deficit_units, 0) AS equity_units,
                       b.updated_at
                  FROM account_balances b
                  LEFT JOIN account_deficits d USING (user_id, asset)
                 WHERE b.user_id = ?
                 ORDER BY b.asset ASC
                """, (rs, rowNum) -> new BalanceResponse(
                rs.getLong("user_id"),
                rs.getString("asset"),
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("equity_units"),
                rs.getTimestamp("updated_at").toInstant()), userId);
    }

    public BalanceResponse adjustBalance(long userId, String asset, long amountUnits, String referenceId, String reason) {
        Instant now = Instant.now();
        int ledgerRows = jdbcTemplate.update("""
                INSERT INTO account_ledger_entries (
                    entry_id, user_id, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, 0, 'BALANCE_ADJUSTMENT', ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                """, sequenceRepository.nextSequence("ledger-entry"), userId, asset, amountUnits,
                referenceId, reason, Timestamp.from(now));
        if (ledgerRows == 0) {
            requireDuplicateBalanceAdjustmentMatches(userId, asset, amountUnits, referenceId, reason);
            return balance(userId, asset)
                    .orElseThrow(() -> new IllegalStateException("duplicate balance adjustment but balance missing"));
        }
        jdbcTemplate.update("""
                INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
                VALUES (?, ?, 0, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
        Long currentAvailable = jdbcTemplate.queryForObject("""
                SELECT available_units
                  FROM account_balances
                 WHERE user_id = ? AND asset = ?
                 FOR UPDATE
                """, Long.class, userId, asset);
        long nextAvailable = Math.addExact(currentAvailable == null ? 0L : currentAvailable, amountUnits);
        if (nextAvailable < 0) {
            throw new IllegalArgumentException("insufficient available balance");
        }
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, nextAvailable, Timestamp.from(now), userId, asset);
        requireSingleRow(balanceRows, "balance adjustment update");
        BalanceResponse updated = balance(userId, asset)
                .orElseThrow(() -> new IllegalStateException("balance not found after adjustment"));
        int ledgerRowsAfter = jdbcTemplate.update("""
                UPDATE account_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = 'BALANCE_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                """, updated.availableUnits(), referenceId, userId, asset);
        requireSingleRow(ledgerRowsAfter, "balance adjustment ledger update");
        return updated;
    }

    private void requireDuplicateBalanceAdjustmentMatches(long userId,
                                                          String asset,
                                                          long amountUnits,
                                                          String referenceId,
                                                          String reason) {
        AdjustmentReference existing = jdbcTemplate.query("""
                SELECT amount_units, reason
                  FROM account_ledger_entries
                 WHERE reference_type = 'BALANCE_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new AdjustmentReference(
                rs.getLong("amount_units"),
                rs.getString("reason")), referenceId, userId, asset).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("duplicate balance adjustment but ledger missing"));
        if (existing.amountUnits() != amountUnits || !Objects.equals(existing.reason(), reason)) {
            throw new IllegalStateException("conflicting duplicate balance adjustment reference " + referenceId);
        }
    }

    public Optional<PositionResponse> position(long userId, String symbol, MarginMode marginMode) {
        return jdbcTemplate.query("""
                SELECT user_id, symbol, margin_mode, instrument_version, signed_quantity_steps,
                       entry_price_ticks, realized_pnl_units, updated_at
                  FROM account_positions
                 WHERE user_id = ? AND symbol = ? AND margin_mode = ?
                """, (rs, rowNum) -> toPositionResponse(rs), userId, symbol,
                MarginMode.defaultIfNull(marginMode).name()).stream().findFirst();
    }

    public List<PositionResponse> positions(long userId) {
        return jdbcTemplate.query("""
                SELECT user_id, symbol, margin_mode, instrument_version, signed_quantity_steps,
                       entry_price_ticks, realized_pnl_units, updated_at
                  FROM account_positions
                 WHERE user_id = ?
                   AND signed_quantity_steps <> 0
                 ORDER BY symbol ASC, margin_mode ASC
                """, (rs, rowNum) -> toPositionResponse(rs), userId);
    }

    public Optional<PositionMarginResponse> positionMargin(long userId, String symbol, MarginMode marginMode) {
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        return jdbcTemplate.query("""
                SELECT p.user_id,
                       p.symbol,
                       i.settle_asset AS asset,
                       p.margin_mode,
                       COALESCE(m.margin_units, 0) AS margin_units,
                       COALESCE(m.updated_at, p.updated_at) AS updated_at
                  FROM account_positions p
                  JOIN instruments i
                    ON i.symbol = p.symbol
                   AND i.version = p.instrument_version
                  LEFT JOIN account_position_margins m
                    ON m.user_id = p.user_id
                   AND m.symbol = p.symbol
                   AND m.asset = i.settle_asset
                   AND m.margin_mode = p.margin_mode
                 WHERE p.user_id = ?
                   AND p.symbol = ?
                   AND p.margin_mode = ?
                """, (rs, rowNum) -> new PositionMarginResponse(
                rs.getLong("user_id"),
                rs.getString("symbol"),
                rs.getString("asset"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                rs.getLong("margin_units"),
                rs.getTimestamp("updated_at").toInstant()), userId, symbol, normalizedMarginMode.name())
                .stream()
                .findFirst();
    }

    public PositionMarginAdjustmentResponse adjustIsolatedPositionMargin(long userId,
                                                                         String symbol,
                                                                         long amountUnits,
                                                                         String referenceId,
                                                                         String reason,
                                                                         Duration maxRiskSnapshotAge,
                                                                         long removalBufferPpm) {
        Optional<PositionMarginAdjustmentReference> existing =
                positionMarginAdjustmentReference(userId, symbol, referenceId);
        if (existing.isPresent()) {
            requirePositionMarginAdjustmentMatches(existing.get(), amountUnits, reason, symbol);
            return positionMarginAdjustmentResponse(userId, symbol, existing.get().asset(), amountUnits,
                    referenceId);
        }

        PositionCollateralTarget target = lockOpenIsolatedPosition(userId, symbol);
        int ledgerRows = jdbcTemplate.update("""
                INSERT INTO account_ledger_entries (
                    entry_id, user_id, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, symbol, created_at
                ) VALUES (?, ?, ?, ?, 0, 'POSITION_MARGIN_ADJUSTMENT', ?, ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                """, sequenceRepository.nextSequence("ledger-entry"), userId, target.asset(), amountUnits,
                referenceId, reason, symbol, Timestamp.from(Instant.now()));
        if (ledgerRows == 0) {
            PositionMarginAdjustmentReference duplicate =
                    positionMarginAdjustmentReferenceByAsset(userId, target.asset(), referenceId)
                            .orElseThrow(() -> new IllegalStateException("duplicate position margin adjustment but ledger missing"));
            requirePositionMarginAdjustmentMatches(duplicate, amountUnits, reason, symbol);
            return positionMarginAdjustmentResponse(userId, symbol, target.asset(), amountUnits, referenceId);
        }

        Instant now = Instant.now();
        long currentMarginUnits = lockPositionMarginUnits(userId, symbol, target.asset(), MarginMode.ISOLATED);
        if (amountUnits > 0) {
            addIsolatedPositionMargin(userId, symbol, target.asset(), amountUnits, now);
        } else {
            long removeUnits = Math.absExact(amountUnits);
            validateIsolatedMarginRemoval(target, currentMarginUnits, removeUnits, maxRiskSnapshotAge,
                    removalBufferPpm);
            removeIsolatedPositionMargin(userId, symbol, target.asset(), removeUnits, now);
        }

        PositionMarginAdjustmentResponse response =
                positionMarginAdjustmentResponse(userId, symbol, target.asset(), amountUnits, referenceId);
        int ledgerRowsAfter = jdbcTemplate.update("""
                UPDATE account_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = 'POSITION_MARGIN_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                """, response.equityUnits(), referenceId, userId, target.asset());
        requireSingleRow(ledgerRowsAfter, "position margin adjustment ledger update");
        return response;
    }

    public PositionState lockPosition(long userId, String symbol, MarginMode marginMode) {
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO account_positions (
                    user_id, symbol, margin_mode, instrument_version, signed_quantity_steps,
                    entry_price_ticks, realized_pnl_units, updated_at
                ) VALUES (?, ?, ?, NULL, 0, 0, 0, ?)
                ON CONFLICT (user_id, symbol, margin_mode) DO NOTHING
                """, userId, symbol, normalizedMarginMode.name(), Timestamp.from(now));
        return jdbcTemplate.queryForObject("""
                SELECT instrument_version, signed_quantity_steps, entry_price_ticks, realized_pnl_units
                  FROM account_positions
                 WHERE user_id = ? AND symbol = ? AND margin_mode = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new PositionState(
                rs.getLong("signed_quantity_steps"),
                longOrZero(rs, "instrument_version"),
                rs.getLong("entry_price_ticks"),
                rs.getLong("realized_pnl_units")), userId, symbol, normalizedMarginMode.name());
    }

    private PositionCollateralTarget lockOpenIsolatedPosition(long userId, String symbol) {
        return jdbcTemplate.query("""
                SELECT p.instrument_version,
                       p.signed_quantity_steps,
                       i.settle_asset AS asset
                  FROM account_positions p
                  JOIN instruments i
                    ON i.symbol = p.symbol
                   AND i.version = p.instrument_version
                 WHERE p.user_id = ?
                   AND p.symbol = ?
                   AND p.margin_mode = 'ISOLATED'
                   AND p.signed_quantity_steps <> 0
                 FOR UPDATE OF p
                """, (rs, rowNum) -> new PositionCollateralTarget(
                userId,
                symbol,
                rs.getString("asset"),
                rs.getLong("instrument_version"),
                rs.getLong("signed_quantity_steps")), userId, symbol).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("open isolated position not found"));
    }

    private long lockPositionMarginUnits(long userId, String symbol, String asset, MarginMode marginMode) {
        return jdbcTemplate.query("""
                SELECT margin_units
                  FROM account_position_margins
                 WHERE user_id = ?
                   AND symbol = ?
                   AND asset = ?
                   AND margin_mode = ?
                 FOR UPDATE
                """, (rs, rowNum) -> rs.getLong("margin_units"), userId, symbol, asset,
                MarginMode.defaultIfNull(marginMode).name()).stream().findFirst().orElse(0L);
    }

    private void addIsolatedPositionMargin(long userId,
                                           String symbol,
                                           String asset,
                                           long amountUnits,
                                           Instant now) {
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = available_units - ?,
                       locked_units = locked_units + ?,
                       updated_at = ?
                 WHERE user_id = ?
                   AND asset = ?
                   AND available_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (balanceRows != 1) {
            throw new IllegalArgumentException("insufficient available balance");
        }
        int marginRows = jdbcTemplate.update("""
                INSERT INTO account_position_margins (user_id, symbol, asset, margin_mode, margin_units, updated_at)
                VALUES (?, ?, ?, 'ISOLATED', ?, ?)
                ON CONFLICT (user_id, symbol, asset, margin_mode) DO UPDATE
                   SET margin_units = account_position_margins.margin_units + EXCLUDED.margin_units,
                       updated_at = EXCLUDED.updated_at
                """, userId, symbol, asset, amountUnits, Timestamp.from(now));
        requireSingleRow(marginRows, "isolated position margin add");
    }

    private void removeIsolatedPositionMargin(long userId,
                                              String symbol,
                                              String asset,
                                              long amountUnits,
                                              Instant now) {
        int marginRows = jdbcTemplate.update("""
                UPDATE account_position_margins
                   SET margin_units = margin_units - ?,
                       updated_at = ?
                 WHERE user_id = ?
                   AND symbol = ?
                   AND asset = ?
                   AND margin_mode = 'ISOLATED'
                   AND margin_units >= ?
                """, amountUnits, Timestamp.from(now), userId, symbol, asset, amountUnits);
        requireSingleRow(marginRows, "isolated position margin remove");
        jdbcTemplate.update("""
                DELETE FROM account_position_margins
                 WHERE user_id = ? AND symbol = ? AND asset = ? AND margin_mode = 'ISOLATED' AND margin_units = 0
                """, userId, symbol, asset);
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = available_units + ?,
                       locked_units = locked_units - ?,
                       updated_at = ?
                 WHERE user_id = ?
                   AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (balanceRows != 1) {
            throw new IllegalStateException("insufficient locked balance for isolated margin removal");
        }
    }

    private void validateIsolatedMarginRemoval(PositionCollateralTarget target,
                                               long currentMarginUnits,
                                               long removeUnits,
                                               Duration maxRiskSnapshotAge,
                                               long removalBufferPpm) {
        if (currentMarginUnits < removeUnits) {
            throw new IllegalArgumentException("insufficient isolated position margin");
        }
        RiskRemovalSnapshot snapshot = latestRiskRemovalSnapshot(target.userId(), target.symbol(),
                maxRiskSnapshotAge);
        if (snapshot.instrumentVersion() != target.instrumentVersion()
                || snapshot.signedQuantitySteps() != target.signedQuantitySteps()) {
            throw new IllegalStateException("risk snapshot is stale for isolated margin removal");
        }
        if ("LIQUIDATION".equals(snapshot.status())) {
            throw new IllegalStateException("position is already in liquidation risk");
        }
        long afterMarginUnits = Math.subtractExact(currentMarginUnits, removeUnits);
        long equityAfterUnits = Math.addExact(afterMarginUnits, snapshot.unrealizedPnlUnits());
        long requiredEquityUnits = requiredEquityWithBuffer(snapshot.maintenanceMarginUnits(), removalBufferPpm);
        if (equityAfterUnits < requiredEquityUnits) {
            throw new IllegalArgumentException("isolated margin removal would breach maintenance margin buffer");
        }
    }

    private RiskRemovalSnapshot latestRiskRemovalSnapshot(long userId, String symbol, Duration maxRiskSnapshotAge) {
        return jdbcTemplate.query("""
                SELECT instrument_version,
                       signed_quantity_steps,
                       unrealized_pnl_units,
                       maintenance_margin_units,
                       status,
                       event_time
                  FROM risk_position_snapshots
                 WHERE user_id = ?
                   AND symbol = ?
                   AND margin_mode = 'ISOLATED'
                   AND event_time >= now() - (? * INTERVAL '1 millisecond')
                 ORDER BY event_time DESC
                 LIMIT 1
                """, (rs, rowNum) -> new RiskRemovalSnapshot(
                rs.getLong("instrument_version"),
                rs.getLong("signed_quantity_steps"),
                rs.getLong("unrealized_pnl_units"),
                rs.getLong("maintenance_margin_units"),
                rs.getString("status"),
                rs.getTimestamp("event_time").toInstant()), userId, symbol, maxRiskSnapshotAge.toMillis())
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("fresh risk snapshot not found for isolated margin removal"));
    }

    private long requiredEquityWithBuffer(long maintenanceMarginUnits, long removalBufferPpm) {
        if (removalBufferPpm < 0) {
            throw new IllegalArgumentException("removalBufferPpm must be non-negative");
        }
        BigInteger required = BigInteger.valueOf(maintenanceMarginUnits)
                .multiply(BigInteger.valueOf(Math.addExact(PPM, removalBufferPpm)))
                .add(BigInteger.valueOf(PPM - 1L))
                .divide(BigInteger.valueOf(PPM));
        return required.longValueExact();
    }

    private PositionMarginAdjustmentResponse positionMarginAdjustmentResponse(long userId,
                                                                              String symbol,
                                                                              String asset,
                                                                              long amountUnits,
                                                                              String referenceId) {
        long marginUnits = lockPositionMarginUnits(userId, symbol, asset, MarginMode.ISOLATED);
        BalanceResponse currentBalance = balance(userId, asset)
                .orElse(new BalanceResponse(userId, asset, 0L, 0L, 0L, Instant.EPOCH));
        return new PositionMarginAdjustmentResponse(userId, symbol, asset, MarginMode.ISOLATED, amountUnits,
                marginUnits, currentBalance.availableUnits(), currentBalance.lockedUnits(),
                currentBalance.equityUnits(), referenceId, currentBalance.updatedAt());
    }

    private Optional<PositionMarginAdjustmentReference> positionMarginAdjustmentReference(long userId,
                                                                                         String symbol,
                                                                                         String referenceId) {
        return jdbcTemplate.query("""
                SELECT asset, amount_units, reason, symbol
                  FROM account_ledger_entries
                 WHERE reference_type = 'POSITION_MARGIN_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND symbol = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new PositionMarginAdjustmentReference(
                rs.getString("asset"),
                rs.getLong("amount_units"),
                rs.getString("reason"),
                rs.getString("symbol")), referenceId, userId, symbol).stream().findFirst();
    }

    private Optional<PositionMarginAdjustmentReference> positionMarginAdjustmentReferenceByAsset(long userId,
                                                                                                String asset,
                                                                                                String referenceId) {
        return jdbcTemplate.query("""
                SELECT asset, amount_units, reason, symbol
                  FROM account_ledger_entries
                 WHERE reference_type = 'POSITION_MARGIN_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new PositionMarginAdjustmentReference(
                rs.getString("asset"),
                rs.getLong("amount_units"),
                rs.getString("reason"),
                rs.getString("symbol")), referenceId, userId, asset).stream().findFirst();
    }

    private void requirePositionMarginAdjustmentMatches(PositionMarginAdjustmentReference existing,
                                                        long amountUnits,
                                                        String reason,
                                                        String symbol) {
        if (existing.amountUnits() != amountUnits
                || !Objects.equals(existing.reason(), reason)
                || !Objects.equals(existing.symbol(), symbol)) {
            throw new IllegalStateException("conflicting duplicate position margin adjustment reference");
        }
    }

    public PositionResponse updatePosition(long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           PositionState state,
                                           Instant now) {
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        long previousSignedQuantitySteps = lockCurrentPositionQuantity(userId, symbol, normalizedMarginMode);
        return updatePosition(userId, symbol, normalizedMarginMode, state, previousSignedQuantitySteps, now);
    }

    public PositionResponse updatePosition(long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           PositionState state,
                                           long previousSignedQuantitySteps,
                                           Instant now) {
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        int rows = jdbcTemplate.update("""
                UPDATE account_positions
                   SET signed_quantity_steps = ?,
                       instrument_version = ?,
                       entry_price_ticks = ?,
                       realized_pnl_units = ?,
                       updated_at = ?
                 WHERE user_id = ? AND symbol = ? AND margin_mode = ?
                """, state.signedQuantitySteps(), nullableVersion(state.instrumentVersion()),
                state.entryPriceTicks(), state.realizedPnlUnits(),
                Timestamp.from(now), userId, symbol, normalizedMarginMode.name());
        requireSingleRow(rows, "account position update");
        updateSymbolOpenInterest(symbol, previousSignedQuantitySteps, state.signedQuantitySteps(), now);
        return new PositionResponse(userId, symbol, state.instrumentVersion(), normalizedMarginMode,
                state.signedQuantitySteps(), state.entryPriceTicks(), state.realizedPnlUnits(), now);
    }

    private long lockCurrentPositionQuantity(long userId, String symbol, MarginMode marginMode) {
        return jdbcTemplate.query("""
                SELECT signed_quantity_steps
                  FROM account_positions
                 WHERE user_id = ? AND symbol = ? AND margin_mode = ?
                 FOR UPDATE
                """, (rs, rowNum) -> rs.getLong("signed_quantity_steps"), userId, symbol,
                MarginMode.defaultIfNull(marginMode).name()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("position not found before update"));
    }

    private void updateSymbolOpenInterest(String symbol,
                                          long previousSignedQuantitySteps,
                                          long nextSignedQuantitySteps,
                                          Instant now) {
        long longDelta = Math.subtractExact(longQuantitySteps(nextSignedQuantitySteps),
                longQuantitySteps(previousSignedQuantitySteps));
        long shortDelta = Math.subtractExact(shortQuantitySteps(nextSignedQuantitySteps),
                shortQuantitySteps(previousSignedQuantitySteps));
        if (longDelta == 0L && shortDelta == 0L) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO trading_symbol_open_interest (
                    symbol, long_quantity_steps, short_quantity_steps, open_quantity_steps, updated_at
                ) VALUES (?, 0, 0, 0, ?)
                ON CONFLICT (symbol) DO NOTHING
                """, symbol, Timestamp.from(now));
        int rows = jdbcTemplate.update("""
                UPDATE trading_symbol_open_interest
                   SET long_quantity_steps = long_quantity_steps + ?,
                       short_quantity_steps = short_quantity_steps + ?,
                       open_quantity_steps = GREATEST(long_quantity_steps + ?, short_quantity_steps + ?),
                       updated_at = ?
                 WHERE symbol = ?
                   AND long_quantity_steps + ? >= 0
                   AND short_quantity_steps + ? >= 0
                """, longDelta, shortDelta, longDelta, shortDelta, Timestamp.from(now), symbol,
                longDelta, shortDelta);
        requireSingleRow(rows, "symbol open interest update");
    }

    private long longQuantitySteps(long signedQuantitySteps) {
        return signedQuantitySteps > 0 ? signedQuantitySteps : 0L;
    }

    private long shortQuantitySteps(long signedQuantitySteps) {
        return signedQuantitySteps < 0 ? Math.negateExact(signedQuantitySteps) : 0L;
    }

    public ContractSpec contractSpec(String symbol, long instrumentVersion) {
        return jdbcTemplate.query("""
                SELECT i.version,
                       i.contract_type,
                       i.settle_asset,
                       i.notional_multiplier_units,
                       i.price_tick_units,
                       i.initial_margin_rate_ppm,
                       i.maker_fee_rate_ppm,
                       i.taker_fee_rate_ppm,
                       ss.scale_units AS settle_scale_units
                  FROM instruments i
                  JOIN account_asset_scales ss
                    ON ss.asset = i.settle_asset
                 WHERE i.symbol = ?
                   AND i.version = ?
                """, (rs, rowNum) -> new ContractSpec(
                rs.getLong("version"),
                ContractType.valueOf(rs.getString("contract_type")),
                rs.getString("settle_asset"),
                rs.getLong("notional_multiplier_units"),
                rs.getLong("price_tick_units"),
                rs.getLong("settle_scale_units"),
                rs.getLong("initial_margin_rate_ppm"),
                rs.getLong("maker_fee_rate_ppm"),
                rs.getLong("taker_fee_rate_ppm")), symbol, instrumentVersion).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("instrument contract spec not found for "
                        + symbol + " version " + instrumentVersion));
    }

    public OrderFeeSnapshot orderFeeSnapshot(long orderId, long userId, String symbol) {
        return jdbcTemplate.query("""
                SELECT maker_fee_rate_ppm,
                       taker_fee_rate_ppm
                  FROM trading_orders
                 WHERE order_id = ?
                   AND user_id = ?
                   AND symbol = ?
                """, (rs, rowNum) -> new OrderFeeSnapshot(
                rs.getLong("maker_fee_rate_ppm"),
                rs.getLong("taker_fee_rate_ppm")), orderId, userId, symbol).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("order fee snapshot not found for order " + orderId));
    }

    public Optional<LiquidationFeeContext> liquidationFeeContext(long orderId, long userId, String symbol) {
        return jdbcTemplate.query("""
                SELECT liquidation_order_id,
                       candidate_id,
                       liquidation_fee_rate_ppm
                  FROM liquidation_orders
                 WHERE order_id = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND status IN ('SUBMITTED', 'PARTIALLY_FILLED', 'FILLED')
                """, (rs, rowNum) -> new LiquidationFeeContext(
                rs.getLong("liquidation_order_id"),
                rs.getLong("candidate_id"),
                rs.getLong("liquidation_fee_rate_ppm")), orderId, userId, symbol).stream().findFirst();
    }

    public boolean markTradeProcessing(long tradeId, String symbol) {
        int rows = jdbcTemplate.update("""
                INSERT INTO account_processed_trades (trade_id, symbol, processed_at)
                VALUES (?, ?, now())
                ON CONFLICT (symbol, trade_id) DO NOTHING
                """, tradeId, symbol);
        return rows == 1;
    }

    public void settleRealizedPnl(long userId,
                                  String asset,
                                  long orderId,
                                  long tradeId,
                                  String symbol,
                                  MarginMode marginMode,
                                  long realizedPnlDeltaUnits,
                                  Instant now) {
        if (realizedPnlDeltaUnits == 0) {
            return;
        }
        String referenceId = tradeId + ":" + orderId;
        int ledgerRows = jdbcTemplate.update("""
                INSERT INTO account_ledger_entries (
                    entry_id, user_id, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, 0, 'TRADE_PNL', ?, 'REALIZED_PNL', ?)
                ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                """, sequenceRepository.nextSequence("ledger-entry"), userId, asset, realizedPnlDeltaUnits,
                referenceId, Timestamp.from(now));
        requireSingleRow(ledgerRows, "trade pnl ledger insert");
        long balanceAfterUnits = applyAmountToBalance(userId, asset, symbol, marginMode, realizedPnlDeltaUnits, now);
        int ledgerRowsAfter = jdbcTemplate.update("""
                UPDATE account_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = 'TRADE_PNL'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                """, balanceAfterUnits, referenceId, userId, asset);
        requireSingleRow(ledgerRowsAfter, "trade pnl ledger update");
    }

    public void settleRealizedPnl(long userId,
                                  String asset,
                                  long orderId,
                                  long tradeId,
                                  long realizedPnlDeltaUnits,
                                  Instant now) {
        settleRealizedPnl(userId, asset, orderId, tradeId, "", MarginMode.CROSS, realizedPnlDeltaUnits, now);
    }

    public void settleTradeFee(long userId,
                               String asset,
                               long orderId,
                               long tradeId,
                               long feeDeltaUnits,
                               String reason,
                               long feeRatePpm,
                               String symbol,
                               MarginMode marginMode,
                               Instant now) {
        if (feeDeltaUnits == 0) {
            return;
        }
        String referenceId = tradeId + ":" + orderId;
        int ledgerRows = jdbcTemplate.update("""
                INSERT INTO account_ledger_entries (
                    entry_id, user_id, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, trade_id, order_id, symbol, fee_rate_ppm, created_at
                ) VALUES (?, ?, ?, ?, 0, 'TRADE_FEE', ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                """, sequenceRepository.nextSequence("ledger-entry"), userId, asset, feeDeltaUnits, referenceId,
                reason, tradeId, orderId, symbol, feeRatePpm, Timestamp.from(now));
        requireSingleRow(ledgerRows, "trade fee ledger insert");
        long balanceAfterUnits = applyAmountToBalance(userId, asset, symbol, marginMode, feeDeltaUnits, now);
        int ledgerRowsAfter = jdbcTemplate.update("""
                UPDATE account_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = 'TRADE_FEE'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                """, balanceAfterUnits, referenceId, userId, asset);
        requireSingleRow(ledgerRowsAfter, "trade fee ledger update");
    }

    public void settleTradeFee(long userId,
                               String asset,
                               long orderId,
                               long tradeId,
                               long feeDeltaUnits,
                               String reason,
                               long feeRatePpm,
                               String symbol,
                               Instant now) {
        settleTradeFee(userId, asset, orderId, tradeId, feeDeltaUnits, reason, feeRatePpm, symbol, MarginMode.CROSS,
                now);
    }

    @Transactional
    public Optional<LiquidationFeeSettlement> settleLiquidationFee(long userId,
                                                                   String asset,
                                                                   long orderId,
                                                                   long tradeId,
                                                                   String symbol,
                                                                   MarginMode marginMode,
                                                                   long requestedFeeUnits,
                                                                   LiquidationFeeContext context,
                                                                   Instant now) {
        if (requestedFeeUnits <= 0 || context == null || context.feeRatePpm() <= 0) {
            return Optional.empty();
        }
        String referenceId = tradeId + ":" + orderId;
        if (liquidationFeeReferenceExists(userId, asset, referenceId)) {
            return Optional.empty();
        }
        BalanceDebitResult debit = applyCappedDebitToBalance(userId, asset, symbol, marginMode,
                requestedFeeUnits, now);
        if (debit.debitedUnits() <= 0) {
            return Optional.empty();
        }
        int ledgerRows = jdbcTemplate.update("""
                INSERT INTO account_ledger_entries (
                    entry_id, user_id, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, trade_id, order_id, symbol, fee_rate_ppm, created_at
                ) VALUES (?, ?, ?, ?, ?, 'LIQUIDATION_FEE', ?, 'COLLECT_LIQUIDATION_FEE', ?, ?, ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                """, sequenceRepository.nextSequence("ledger-entry"), userId, asset,
                Math.negateExact(debit.debitedUnits()), debit.balanceAfterUnits(), referenceId, tradeId,
                orderId, symbol, context.feeRatePpm(), Timestamp.from(now));
        requireSingleRow(ledgerRows, "liquidation fee ledger insert");
        return Optional.of(new LiquidationFeeSettlement(context.liquidationOrderId(), context.candidateId(),
                debit.debitedUnits(), context.feeRatePpm()));
    }

    public void releaseOrderMargin(long orderId,
                                   long userId,
                                   String symbol,
                                   long closeSteps,
                                   boolean sweepRemainder,
                                   Instant now) {
        OrderMarginReservation reservation = lockOrderMarginReservation(orderId, userId, symbol);
        if (reservation == null) {
            requireReservationUnlessReduceOnly(orderId, userId, symbol);
            return;
        }
        long amountUnits = MarginTransferMath.orderMarginReleaseAmount(reservation.reservedUnits(),
                reservation.releasedUnits(), reservation.positionMarginUnits(), reservation.orderQuantitySteps(),
                closeSteps, sweepRemainder);
        releaseReservedMargin(orderId, reservation.userId(), reservation.asset(), amountUnits,
                "POSITION_REDUCED", now);
    }

    public void consumeOrderMargin(long orderId,
                                   long userId,
                                   String symbol,
                                   MarginMode marginMode,
                                   long openSteps,
                                   long actualMarginUnits,
                                   boolean sweepRemainder,
                                   Instant now) {
        if (openSteps <= 0) {
            return;
        }
        OrderMarginReservation reservation = lockOrderMarginReservation(orderId, userId, symbol);
        if (reservation == null) {
            throw new IllegalStateException("missing order margin reservation for opening fill " + orderId);
        }
        if (reservation.reduceOnly()) {
            throw new IllegalStateException("reduce-only order cannot consume opening margin " + orderId);
        }
        long allocatedUnits = MarginTransferMath.orderMarginConsumeAmount(reservation.reservedUnits(),
                reservation.releasedUnits(), reservation.positionMarginUnits(), reservation.orderQuantitySteps(),
                openSteps, sweepRemainder);
        long excessUnits = MarginTransferMath.excessOrderMarginUnits(allocatedUnits, actualMarginUnits);
        if (actualMarginUnits <= 0 && excessUnits <= 0) {
            return;
        }
        if (actualMarginUnits > 0) {
            MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
            int reservationRows = jdbcTemplate.update("""
                    UPDATE account_margin_reservations
                       SET position_margin_units = position_margin_units + ?,
                           status = CASE
                               WHEN released_units + position_margin_units + ? >= reserved_units THEN 'CONSUMED'
                               ELSE 'PARTIALLY_CONSUMED'
                           END,
                           reason = 'POSITION_OPENED',
                           updated_at = ?
                     WHERE order_id = ?
                       AND released_units + position_margin_units + ? <= reserved_units
                    """, actualMarginUnits, actualMarginUnits, Timestamp.from(now), orderId, actualMarginUnits);
            requireSingleRow(reservationRows, "order margin consumption");
            int positionMarginRows = jdbcTemplate.update("""
                    INSERT INTO account_position_margins (user_id, symbol, asset, margin_mode, margin_units, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (user_id, symbol, asset, margin_mode) DO UPDATE
                       SET margin_units = account_position_margins.margin_units + EXCLUDED.margin_units,
                           updated_at = EXCLUDED.updated_at
                    """, userId, symbol, reservation.asset(), normalizedMarginMode.name(), actualMarginUnits,
                    Timestamp.from(now));
            requireSingleRow(positionMarginRows, "position margin upsert");
        }
        releaseReservedMargin(orderId, reservation.userId(), reservation.asset(), excessUnits,
                "ORDER_PRICE_IMPROVEMENT", now);
    }

    public void releasePositionMargin(long userId,
                                      String symbol,
                                      MarginMode marginMode,
                                      long closeSteps,
                                      long positionAbsSteps,
                                      Instant now) {
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        List<PositionMargin> margins = jdbcTemplate.query("""
                SELECT asset, margin_mode, margin_units
                  FROM account_position_margins
                 WHERE user_id = ? AND symbol = ? AND margin_mode = ?
                   AND margin_units > 0
                 FOR UPDATE
                """, (rs, rowNum) -> new PositionMargin(symbol, rs.getString("asset"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")), rs.getLong("margin_units")),
                userId, symbol, normalizedMarginMode.name());
        for (PositionMargin margin : margins) {
            long amountUnits = MarginTransferMath.positionMarginReleaseAmount(margin.marginUnits(),
                    closeSteps, positionAbsSteps);
            if (amountUnits <= 0) {
                continue;
            }
            releaseBalanceLock(userId, margin.asset(), amountUnits, now);
            int marginRows = jdbcTemplate.update("""
                    UPDATE account_position_margins
                       SET margin_units = margin_units - ?,
                           updated_at = ?
                     WHERE user_id = ? AND symbol = ? AND asset = ?
                       AND margin_mode = ?
                       AND margin_units >= ?
                    """, amountUnits, Timestamp.from(now), userId, symbol, margin.asset(),
                    margin.marginMode().name(), amountUnits);
            requireSingleRow(marginRows, "position margin release");
            jdbcTemplate.update("""
                    DELETE FROM account_position_margins
                     WHERE user_id = ? AND symbol = ? AND asset = ? AND margin_mode = ? AND margin_units = 0
                    """, userId, symbol, margin.asset(), margin.marginMode().name());
        }
    }

    private OrderMarginReservation lockOrderMarginReservation(long orderId, long userId, String symbol) {
        return jdbcTemplate.query("""
                SELECT r.user_id, r.asset, r.reserved_units, r.released_units,
                       r.position_margin_units, r.margin_mode, o.quantity_steps, o.reduce_only
                  FROM account_margin_reservations r
                  JOIN trading_orders o
                    ON o.order_id = r.order_id
                 WHERE r.order_id = ?
                   AND r.user_id = ?
                   AND r.symbol = ?
                 FOR UPDATE OF r
                """, (rs, rowNum) -> new OrderMarginReservation(
                rs.getLong("user_id"),
                rs.getString("asset"),
                rs.getLong("reserved_units"),
                rs.getLong("released_units"),
                rs.getLong("position_margin_units"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                rs.getLong("quantity_steps"),
                rs.getBoolean("reduce_only")), orderId, userId, symbol).stream().findFirst().orElse(null);
    }

    private void requireReservationUnlessReduceOnly(long orderId, long userId, String symbol) {
        Boolean reduceOnly = jdbcTemplate.query("""
                SELECT reduce_only
                  FROM trading_orders
                 WHERE order_id = ?
                   AND user_id = ?
                   AND symbol = ?
                 FOR UPDATE
                """, (rs, rowNum) -> rs.getBoolean("reduce_only"), orderId, userId, symbol)
                .stream()
                .findFirst()
                .orElse(null);
        if (reduceOnly == null) {
            throw new IllegalStateException("order not found for account margin release " + orderId);
        }
        if (!reduceOnly) {
            throw new IllegalStateException("missing order margin reservation for closing fill " + orderId);
        }
    }

    private void releaseReservedMargin(long orderId,
                                       long userId,
                                       String asset,
                                       long amountUnits,
                                       String reason,
                                       Instant now) {
        if (amountUnits <= 0) {
            return;
        }
        releaseBalanceLock(userId, asset, amountUnits, now);
        int reservationRows = jdbcTemplate.update("""
                UPDATE account_margin_reservations
                   SET released_units = released_units + ?,
                       status = CASE
                           WHEN released_units + ? >= reserved_units AND position_margin_units = 0 THEN 'RELEASED'
                           WHEN released_units + ? + position_margin_units >= reserved_units THEN 'CONSUMED'
                           WHEN position_margin_units > 0 THEN 'PARTIALLY_CONSUMED'
                           ELSE 'PARTIALLY_RELEASED'
                       END,
                       reason = ?,
                       updated_at = ?
                 WHERE order_id = ?
                """, amountUnits, amountUnits, amountUnits, reason, Timestamp.from(now), orderId);
        requireSingleRow(reservationRows, "reserved margin release");
    }

    private void releaseBalanceLock(long userId, String asset, long amountUnits, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET locked_units = locked_units - ?,
                       available_units = available_units + ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("insufficient locked balance for margin release");
        }
    }

    private long applyAmountToBalance(long userId,
                                      String asset,
                                      String symbol,
                                      MarginMode marginMode,
                                      long amountUnits,
                                      Instant now) {
        jdbcTemplate.update("""
                INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
                VALUES (?, ?, 0, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO account_deficits (user_id, asset, deficit_units, updated_at)
                VALUES (?, ?, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        List<PositionMargin> lockedMargins = amountUnits < 0
                ? lockPositionMargins(userId, asset, symbol, normalizedMarginMode)
                : List.of();
        long maxLockedDebitUnits = lockedMargins.stream()
                .mapToLong(PositionMargin::marginUnits)
                .reduce(0L, Math::addExact);
        BalanceSettlementState current = jdbcTemplate.queryForObject("""
                SELECT b.available_units, b.locked_units, d.deficit_units
                  FROM account_balances b
                  JOIN account_deficits d USING (user_id, asset)
                 WHERE b.user_id = ? AND b.asset = ?
                 FOR UPDATE OF b, d
        """, (rs, rowNum) -> new BalanceSettlementState(
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("deficit_units")), userId, asset);
        long availableInput = amountUnits < 0 && normalizedMarginMode == MarginMode.ISOLATED
                ? 0L
                : current.availableUnits();
        BalanceSettlementState next = PnlSettlementMath.apply(availableInput, current.lockedUnits(),
                current.deficitUnits(), amountUnits, maxLockedDebitUnits);
        if (amountUnits < 0 && normalizedMarginMode == MarginMode.ISOLATED) {
            next = new BalanceSettlementState(current.availableUnits(), next.lockedUnits(), next.deficitUnits());
        }
        long lockedDebitUnits = Math.subtractExact(current.lockedUnits(), next.lockedUnits());
        reducePositionMargins(userId, asset, lockedDebitUnits, lockedMargins, now);
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = ?,
                       locked_units = ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, next.availableUnits(), next.lockedUnits(), Timestamp.from(now), userId, asset);
        requireSingleRow(balanceRows, "pnl balance update");
        updateDeficitIfChanged(userId, asset, current.deficitUnits(), next.deficitUnits(), now,
                "pnl deficit update");
        return PnlSettlementMath.netEquityUnits(next.availableUnits(), next.lockedUnits(), next.deficitUnits());
    }

    private BalanceDebitResult applyCappedDebitToBalance(long userId,
                                                         String asset,
                                                         String symbol,
                                                         MarginMode marginMode,
                                                         long requestedDebitUnits,
                                                         Instant now) {
        jdbcTemplate.update("""
                INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
                VALUES (?, ?, 0, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO account_deficits (user_id, asset, deficit_units, updated_at)
                VALUES (?, ?, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        List<PositionMargin> lockedMargins = lockPositionMargins(userId, asset, symbol, normalizedMarginMode);
        long maxLockedDebitUnits = lockedMargins.stream()
                .mapToLong(PositionMargin::marginUnits)
                .reduce(0L, Math::addExact);
        BalanceSettlementState current = jdbcTemplate.queryForObject("""
                SELECT b.available_units, b.locked_units, d.deficit_units
                  FROM account_balances b
                  JOIN account_deficits d USING (user_id, asset)
                 WHERE b.user_id = ? AND b.asset = ?
                 FOR UPDATE OF b, d
        """, (rs, rowNum) -> new BalanceSettlementState(
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("deficit_units")), userId, asset);
        long availableInput = normalizedMarginMode == MarginMode.ISOLATED ? 0L : current.availableUnits();
        long collectibleUnits = Math.min(requestedDebitUnits, Math.addExact(availableInput, maxLockedDebitUnits));
        if (collectibleUnits <= 0) {
            return new BalanceDebitResult(0L, PnlSettlementMath.netEquityUnits(current.availableUnits(),
                    current.lockedUnits(), current.deficitUnits()));
        }
        BalanceSettlementState next = PnlSettlementMath.apply(availableInput, current.lockedUnits(),
                current.deficitUnits(), Math.negateExact(collectibleUnits), maxLockedDebitUnits);
        if (normalizedMarginMode == MarginMode.ISOLATED) {
            next = new BalanceSettlementState(current.availableUnits(), next.lockedUnits(), next.deficitUnits());
        }
        if (next.deficitUnits() != current.deficitUnits()) {
            throw new IllegalStateException("liquidation fee must not create account deficit");
        }
        long lockedDebitUnits = Math.subtractExact(current.lockedUnits(), next.lockedUnits());
        reducePositionMargins(userId, asset, lockedDebitUnits, lockedMargins, now);
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = ?,
                       locked_units = ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, next.availableUnits(), next.lockedUnits(), Timestamp.from(now), userId, asset);
        requireSingleRow(balanceRows, "liquidation fee balance update");
        updateDeficitIfChanged(userId, asset, current.deficitUnits(), next.deficitUnits(), now,
                "liquidation fee deficit update");
        return new BalanceDebitResult(collectibleUnits,
                PnlSettlementMath.netEquityUnits(next.availableUnits(), next.lockedUnits(), next.deficitUnits()));
    }

    private void updateDeficitIfChanged(long userId,
                                        String asset,
                                        long currentDeficitUnits,
                                        long nextDeficitUnits,
                                        Instant now,
                                        String operation) {
        if (currentDeficitUnits == nextDeficitUnits) {
            return;
        }
        int deficitRows = jdbcTemplate.update("""
                UPDATE account_deficits
                   SET deficit_units = ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, nextDeficitUnits, Timestamp.from(now), userId, asset);
        requireSingleRow(deficitRows, operation);
    }

    private boolean liquidationFeeReferenceExists(long userId, String asset, String referenceId) {
        return jdbcTemplate.query("""
                SELECT 1
                  FROM account_ledger_entries
                 WHERE reference_type = 'LIQUIDATION_FEE'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                 FOR UPDATE
                """, (rs, rowNum) -> 1, referenceId, userId, asset).stream().findFirst().isPresent();
    }

    private List<PositionMargin> lockPositionMargins(long userId, String asset, String symbol, MarginMode marginMode) {
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        if (normalizedMarginMode == MarginMode.ISOLATED) {
            return jdbcTemplate.query("""
                    SELECT symbol, asset, margin_mode, margin_units
                      FROM account_position_margins
                     WHERE user_id = ? AND asset = ? AND symbol = ? AND margin_mode = ?
                       AND margin_units > 0
                     ORDER BY updated_at ASC, symbol ASC, margin_mode ASC
                     FOR UPDATE
                    """, (rs, rowNum) -> new PositionMargin(
                    rs.getString("symbol"),
                    rs.getString("asset"),
                    MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                    rs.getLong("margin_units")), userId, asset, symbol, normalizedMarginMode.name());
        }
        return jdbcTemplate.query("""
                SELECT symbol, asset, margin_mode, margin_units
                  FROM account_position_margins
                 WHERE user_id = ? AND asset = ? AND margin_mode = ?
                   AND margin_units > 0
                 ORDER BY updated_at ASC, symbol ASC, margin_mode ASC
                 FOR UPDATE
                """, (rs, rowNum) -> new PositionMargin(
                rs.getString("symbol"),
                rs.getString("asset"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                rs.getLong("margin_units")), userId, asset, normalizedMarginMode.name());
    }

    private void reducePositionMargins(long userId,
                                       String asset,
                                       long amountUnits,
                                       List<PositionMargin> lockedMargins,
                                       Instant now) {
        long remaining = amountUnits;
        for (PositionMargin margin : lockedMargins) {
            if (remaining <= 0) {
                break;
            }
            long debit = Math.min(margin.marginUnits(), remaining);
            int rows = jdbcTemplate.update("""
                    UPDATE account_position_margins
                       SET margin_units = margin_units - ?,
                           updated_at = ?
                     WHERE user_id = ? AND symbol = ? AND asset = ?
                       AND margin_mode = ?
                       AND margin_units >= ?
                    """, debit, Timestamp.from(now), userId, margin.symbol(), asset,
                    margin.marginMode().name(), debit);
            if (rows != 1) {
                throw new IllegalStateException("failed to reduce consumed position margin");
            }
            jdbcTemplate.update("""
                    DELETE FROM account_position_margins
                     WHERE user_id = ? AND symbol = ? AND asset = ? AND margin_mode = ? AND margin_units = 0
                    """, userId, margin.symbol(), asset, margin.marginMode().name());
            remaining = Math.subtractExact(remaining, debit);
        }
        if (remaining != 0) {
            throw new IllegalStateException("insufficient position margin for locked debit");
        }
    }

    private PositionResponse toPositionResponse(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PositionResponse(
                rs.getLong("user_id"),
                rs.getString("symbol"),
                longOrZero(rs, "instrument_version"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                rs.getLong("signed_quantity_steps"),
                rs.getLong("entry_price_ticks"),
                rs.getLong("realized_pnl_units"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private record OrderMarginReservation(
            long userId,
            String asset,
            long reservedUnits,
            long releasedUnits,
            long positionMarginUnits,
            MarginMode marginMode,
            long orderQuantitySteps,
            boolean reduceOnly) {
    }

    private record PositionMargin(String symbol, String asset, MarginMode marginMode, long marginUnits) {
    }

    private record AdjustmentReference(long amountUnits, String reason) {
    }

    private record PositionCollateralTarget(
            long userId,
            String symbol,
            String asset,
            long instrumentVersion,
            long signedQuantitySteps) {
    }

    private record RiskRemovalSnapshot(
            long instrumentVersion,
            long signedQuantitySteps,
            long unrealizedPnlUnits,
            long maintenanceMarginUnits,
            String status,
            Instant eventTime) {
    }

    private record PositionMarginAdjustmentReference(
            String asset,
            long amountUnits,
            String reason,
            String symbol) {
    }

    private Long nullableVersion(long version) {
        return version <= 0 ? null : version;
    }

    private long longOrZero(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? 0L : value;
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

}
