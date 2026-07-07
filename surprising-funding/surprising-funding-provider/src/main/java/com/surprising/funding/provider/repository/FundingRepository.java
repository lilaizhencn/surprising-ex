package com.surprising.funding.provider.repository;

import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.funding.api.model.AdminCursorPage;
import com.surprising.funding.api.model.FundingPaymentResponse;
import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.api.model.FundingSettlementResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingBalanceState;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.funding.provider.model.FundingRateInput;
import com.surprising.funding.provider.service.FundingMath;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FundingRepository {

    private static final String RATE_MODULE = "funding-rate";

    private final JdbcTemplate jdbcTemplate;
    private final FundingProperties properties;

    public FundingRepository(JdbcTemplate jdbcTemplate, FundingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new FundingProperties() : properties;
    }

    protected FundingRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new FundingProperties());
    }

    public boolean acquireLease(String symbol, String ownerId, Duration leaseDuration) {
        Instant now = Instant.now();
        Instant leaseUntil = now.plus(leaseDuration);
        return !jdbcTemplate.query("""
                INSERT INTO price_symbol_leases (module, symbol, owner_id, lease_until, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (module, symbol) DO UPDATE SET
                    owner_id = EXCLUDED.owner_id,
                    lease_until = EXCLUDED.lease_until,
                    updated_at = EXCLUDED.updated_at
                WHERE price_symbol_leases.owner_id = EXCLUDED.owner_id
                   OR price_symbol_leases.lease_until <= EXCLUDED.updated_at
                RETURNING TRUE
                """, (rs, rowNum) -> rs.getBoolean(1), RATE_MODULE, symbol, ownerId,
                Timestamp.from(leaseUntil), Timestamp.from(now)).isEmpty();
    }

    public long nextSymbolSequence(String symbol) {
        Long value = jdbcTemplate.queryForObject("""
                INSERT INTO price_symbol_sequences (module, symbol, sequence, updated_at)
                VALUES (?, ?, 1, now())
                ON CONFLICT (module, symbol) DO UPDATE SET
                    sequence = price_symbol_sequences.sequence + 1,
                    updated_at = now()
                RETURNING sequence
                """, Long.class, RATE_MODULE, symbol);
        if (value == null) {
            throw new IllegalStateException("failed to allocate funding-rate sequence for " + symbol);
        }
        return value;
    }

    public long nextSequence(String sequenceName) {
        Long value = jdbcTemplate.queryForObject("""
                INSERT INTO funding_sequences (sequence_name, sequence_value, updated_at)
                VALUES (?, 1, now())
                ON CONFLICT (sequence_name) DO UPDATE SET
                    sequence_value = funding_sequences.sequence_value + 1,
                    updated_at = now()
                RETURNING sequence_value
                """, Long.class, sequenceName);
        if (value == null) {
            throw new IllegalStateException("failed to allocate funding sequence " + sequenceName);
        }
        return value;
    }

    public List<FundingRateInput> rateInputs(Duration maxMarkAge) {
        List<Object> args = new ArrayList<>();
        String productCondition = fundingInstrumentCondition(args, "i");
        args.add(maxMarkAge.toMillis());
        return jdbcTemplate.query("""
                SELECT i.symbol,
                       CAST(round(((pm.mark_price - pm.index_price) / pm.index_price) * 1000000) AS BIGINT)
                           AS premium_rate_ppm,
                       i.interest_rate_ppm,
                       i.funding_rate_floor_ppm,
                       i.funding_rate_cap_ppm,
                       i.funding_interval_hours,
                       pm.event_time
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                  JOIN LATERAL (
                      SELECT mark_price, index_price, event_time
                        FROM price_mark_ticks m
                       WHERE m.symbol = i.symbol
                       ORDER BY event_time DESC
                       LIMIT 1
                  ) pm ON TRUE
                 WHERE i.status = 'TRADING'
                   AND %s
                   AND i.funding_interval_hours > 0
                   AND pm.index_price > 0
                   AND pm.event_time >= now() - (? * INTERVAL '1 millisecond')
                """.formatted(productCondition), (rs, rowNum) -> new FundingRateInput(
                rs.getString("symbol"),
                0L,
                rs.getLong("premium_rate_ppm"),
                rs.getLong("interest_rate_ppm"),
                rs.getLong("funding_rate_floor_ppm"),
                rs.getLong("funding_rate_cap_ppm"),
                rs.getInt("funding_interval_hours"),
                rs.getTimestamp("event_time").toInstant()), args.toArray());
    }

    public FundingRateResponse saveRate(FundingRateInput input,
                                        long sequence,
                                        long fundingRatePpm,
                                        Instant fundingTime,
                                        Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO funding_rate_ticks (
                    symbol, sequence, funding_time, funding_interval_hours,
                    premium_rate_ppm, interest_rate_ppm, funding_rate_ppm,
                    status, event_time, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PREDICTED', ?, now())
                ON CONFLICT (symbol, sequence) DO NOTHING
                """, input.symbol(), sequence, Timestamp.from(fundingTime), input.fundingIntervalHours(),
                input.premiumRatePpm(), input.interestRatePpm(), fundingRatePpm, Timestamp.from(now));
        requireSingleRow(rows, "funding rate insert");
        return new FundingRateResponse(input.symbol(), sequence, fundingRatePpm, input.premiumRatePpm(),
                input.interestRatePpm(), fundingTime, input.fundingIntervalHours(), "PREDICTED", now);
    }

    public Optional<FundingRateResponse> latestRate(String symbol) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM funding_rate_ticks
                 WHERE symbol = ?
                 ORDER BY event_time DESC, sequence DESC
                 LIMIT 1
                """, (rs, rowNum) -> toRate(rs), symbol).stream().findFirst();
    }

    public List<FundingRateResponse> rateHistory(String symbol, int limit) {
        return rateHistoryPage(symbol, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<FundingRateResponse> rateHistoryPage(String symbol,
                                                                            int limit,
                                                                            String cursor,
                                                                            String sort) {
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec sortSpec = parseEventTimeSort(sort, "sequence");
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(symbol);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<FundingRateResponse> rows = jdbcTemplate.query("""
                SELECT *
                  FROM funding_rate_ticks
                 WHERE symbol = ?
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                (rs, rowNum) -> toRate(rs), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, FundingRateResponse::eventTime,
                FundingRateResponse::sequence);
    }

    public List<FundingRateResponse> dueRates(Instant now, int limit) {
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(now));
        String productCondition = fundingInstrumentCondition(args, "i");
        args.add(limit);
        return jdbcTemplate.query("""
                SELECT DISTINCT ON (r.symbol, r.funding_time) r.*
                  FROM funding_rate_ticks r
                  JOIN instrument_current_versions c
                    ON c.symbol = r.symbol
                  JOIN instruments i
                    ON i.symbol = c.symbol AND i.version = c.version
                 WHERE r.funding_time <= ?
                   AND %s
                   AND NOT EXISTS (
                       SELECT 1
                         FROM funding_settlements s
                        WHERE s.symbol = r.symbol
                          AND s.funding_time = r.funding_time
                   )
                 ORDER BY r.symbol, r.funding_time, r.sequence DESC
                 LIMIT ?
                """.formatted(productCondition), (rs, rowNum) -> toRate(rs), args.toArray());
    }

    public Optional<Long> createSettlement(FundingRateResponse rate, Instant now) {
        long settlementId = nextSequence("funding-settlement");
        return jdbcTemplate.query("""
                INSERT INTO funding_settlements (
                    settlement_id, symbol, funding_time, funding_rate_ppm,
                    total_long_payment_units, total_short_payment_units,
                    position_count, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 0, 0, 0, 'PROCESSING', ?, ?)
                ON CONFLICT (symbol, funding_time) DO NOTHING
                RETURNING settlement_id
                """, (rs, rowNum) -> rs.getLong("settlement_id"), settlementId, rate.symbol(),
                Timestamp.from(rate.fundingTime()), rate.fundingRatePpm(), Timestamp.from(now),
                Timestamp.from(now)).stream().findFirst();
    }

    public List<FundingPaymentCandidate> paymentCandidates(FundingRateResponse rate) {
        Optional<ProductLine> fundingProductLine = currentFundingProductLine();
        if (fundingProductLine.isEmpty()) {
            return List.of();
        }
        ProductLine productLine = fundingProductLine.get();
        return jdbcTemplate.query("""
                WITH rate_row AS (
                    SELECT funding_rate_ppm
                      FROM funding_rate_ticks
                     WHERE symbol = ? AND funding_time = ?
                     ORDER BY sequence DESC
                     LIMIT 1
                )
                SELECT p.user_id,
                       p.symbol,
                       p.margin_mode,
                       p.position_side,
                       i.contract_type,
                       i.settle_asset AS asset,
                       i.notional_multiplier_units,
                       i.price_tick_units,
                       ss.scale_units AS settle_scale_units,
                       p.signed_quantity_steps,
                       mark_row.mark_price_ticks,
                       rate_row.funding_rate_ppm
                  FROM account_positions p
                  JOIN instruments i
                    ON i.symbol = p.symbol
                   AND i.version = p.instrument_version
                   AND i.contract_type = ?
                  JOIN account_asset_scales ss ON ss.asset = i.settle_asset
                  JOIN LATERAL (
                      SELECT ((m.mark_price_units + i.price_tick_units / 2) / i.price_tick_units) AS mark_price_ticks
                        FROM price_mark_ticks m
                       WHERE m.symbol = p.symbol
                       ORDER BY m.event_time DESC
                       LIMIT 1
                  ) mark_row ON TRUE
                  CROSS JOIN rate_row
                 WHERE p.symbol = ?
                   AND p.product_line = ?
                   AND p.signed_quantity_steps <> 0
                   AND mark_row.mark_price_ticks > 0
                ORDER BY p.user_id ASC
                """, (rs, rowNum) -> {
            long signedQuantity = rs.getLong("signed_quantity_steps");
            long notionalUnits = PerpetualContractMath.notionalUnits(
                    ContractType.valueOf(rs.getString("contract_type")),
                    signedQuantity,
                    rs.getLong("mark_price_ticks"),
                    rs.getLong("notional_multiplier_units"),
                    rs.getLong("price_tick_units"),
                    rs.getLong("settle_scale_units"));
            long ratePpm = rs.getLong("funding_rate_ppm");
            return new FundingPaymentCandidate(
                    rs.getLong("user_id"),
                    rs.getString("symbol"),
                    MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                    PositionSide.fromNullableDbValue(rs.getString("position_side")),
                    rs.getString("asset"),
                    signedQuantity,
                    notionalUnits,
                    ratePpm,
                    FundingMath.paymentAmount(signedQuantity, notionalUnits, ratePpm));
        }, rate.symbol(), Timestamp.from(rate.fundingTime()), productLine.contractTypeCode(), rate.symbol(),
                productLine.name());
    }

    public boolean insertPayment(long settlementId, FundingPaymentCandidate payment, Instant now) {
        if (payment.amountUnits() == 0L) {
            return false;
        }
        long paymentId = nextSequence("funding-payment");
        int rows = jdbcTemplate.update("""
                INSERT INTO funding_payments (
                    payment_id, settlement_id, user_id, symbol, margin_mode, position_side, asset,
                    signed_quantity_steps, notional_units, funding_rate_ppm,
                    amount_units, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (settlement_id, user_id, symbol, margin_mode, position_side) DO NOTHING
                """, paymentId, settlementId, payment.userId(), payment.symbol(), payment.marginMode().name(),
                payment.positionSide().name(), payment.asset(),
                payment.signedQuantitySteps(), payment.notionalUnits(), payment.fundingRatePpm(),
                payment.amountUnits(), Timestamp.from(now));
        return rows == 1;
    }

    public void applyPaymentToAccount(long settlementId, FundingPaymentCandidate payment, Instant now) {
        ProductLine productLine = currentFundingProductLine()
                .orElseThrow(() -> new IllegalStateException("funding payment requires a funding product line"));
        if (!usesProductAccount(productLine)) {
            applyPaymentToLegacyAccount(settlementId, payment, now);
            return;
        }
        applyPaymentToProductAccount(productLine, settlementId, payment, now);
    }

    private void applyPaymentToLegacyAccount(long settlementId, FundingPaymentCandidate payment, Instant now) {
        String referenceId = settlementId + ":" + payment.userId() + ":" + payment.symbol() + ":"
                + payment.marginMode().name() + ":" + payment.positionSide().name();
        int ledgerRows = jdbcTemplate.update("""
                INSERT INTO account_ledger_entries (
                    entry_id, user_id, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, 0, 'FUNDING', ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                """, nextAccountSequence("ledger-entry"), payment.userId(), payment.asset(), payment.amountUnits(),
                referenceId, payment.amountUnits() >= 0 ? "FUNDING_RECEIVED" : "FUNDING_PAID", Timestamp.from(now));
        requireSingleRow(ledgerRows, "funding account ledger insert");
        long balanceAfterUnits = applyBalance(payment.userId(), payment.symbol(), payment.marginMode(),
                payment.positionSide(), payment.asset(), payment.amountUnits(), now);
        int ledgerUpdateRows = jdbcTemplate.update("""
                UPDATE account_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = 'FUNDING'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                """, balanceAfterUnits, referenceId, payment.userId(), payment.asset());
        requireSingleRow(ledgerUpdateRows, "funding account ledger balance update");
    }

    private void applyPaymentToProductAccount(ProductLine productLine,
                                              long settlementId,
                                              FundingPaymentCandidate payment,
                                              Instant now) {
        String accountType = productLine.accountTypeCode();
        String referenceId = settlementId + ":" + payment.userId() + ":" + payment.symbol() + ":"
                + payment.marginMode().name() + ":" + payment.positionSide().name();
        int ledgerRows = jdbcTemplate.update("""
                INSERT INTO account_product_ledger_entries (
                    entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, 0, 'FUNDING', ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                """, nextAccountSequence("product-ledger-entry"), payment.userId(), accountType, payment.asset(),
                payment.amountUnits(), referenceId,
                payment.amountUnits() >= 0 ? "FUNDING_RECEIVED" : "FUNDING_PAID", Timestamp.from(now));
        requireSingleRow(ledgerRows, "funding product account ledger insert");
        long balanceAfterUnits = applyProductBalance(productLine, payment.userId(), payment.symbol(),
                payment.marginMode(), payment.positionSide(), payment.asset(), payment.amountUnits(), now);
        int ledgerUpdateRows = jdbcTemplate.update("""
                UPDATE account_product_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = 'FUNDING'
                   AND reference_id = ?
                   AND user_id = ?
                   AND account_type = ?
                   AND asset = ?
                """, balanceAfterUnits, referenceId, payment.userId(), accountType, payment.asset());
        requireSingleRow(ledgerUpdateRows, "funding product account ledger balance update");
    }

    public void completeSettlement(long settlementId,
                                   long totalLongPaymentUnits,
                                   long totalShortPaymentUnits,
                                   int positionCount,
                                   Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE funding_settlements
                   SET total_long_payment_units = ?,
                       total_short_payment_units = ?,
                       position_count = ?,
                       status = 'COMPLETED',
                       updated_at = ?
                 WHERE settlement_id = ?
                """, totalLongPaymentUnits, totalShortPaymentUnits, positionCount,
                Timestamp.from(now), settlementId);
        requireSingleRow(rows, "funding settlement completion");
    }

    public Optional<FundingSettlementResponse> latestSettlement(String symbol) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM funding_settlements
                 WHERE symbol = ?
                 ORDER BY funding_time DESC
                 LIMIT 1
                """, (rs, rowNum) -> toSettlement(rs), symbol).stream().findFirst();
    }

    public List<FundingPaymentResponse> payments(long userId, String symbol, int limit) {
        return paymentsPage(userId, symbol, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<FundingPaymentResponse> paymentsPage(long userId,
                                                                            String symbol,
                                                                            int limit,
                                                                            String cursor,
                                                                            String sort) {
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase();
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec sortSpec = parseCreatedAtSort(sort, "payment_id");
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(normalizedSymbol);
        args.add(normalizedSymbol);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<FundingPaymentResponse> rows = jdbcTemplate.query("""
                SELECT *
                  FROM funding_payments
                 WHERE user_id = ?
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                (rs, rowNum) -> toPayment(rs), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, FundingPaymentResponse::createdAt,
                FundingPaymentResponse::paymentId);
    }

    private AdminCursorPage.SortSpec parseEventTimeSort(String sort, String idColumn) {
        AdminCursorPage.SortSpec desc = new AdminCursorPage.SortSpec("eventTime", "event_time", idColumn, true);
        AdminCursorPage.SortSpec asc = new AdminCursorPage.SortSpec("eventTime", "event_time", idColumn, false);
        return AdminCursorPage.parseSort(sort, desc, List.of(desc, asc));
    }

    private AdminCursorPage.SortSpec parseCreatedAtSort(String sort, String idColumn) {
        AdminCursorPage.SortSpec desc = new AdminCursorPage.SortSpec("createdAt", "created_at", idColumn, true);
        AdminCursorPage.SortSpec asc = new AdminCursorPage.SortSpec("createdAt", "created_at", idColumn, false);
        return AdminCursorPage.parseSort(sort, desc, List.of(desc, asc));
    }

    private long applyBalance(long userId,
                              String symbol,
                              MarginMode marginMode,
                              PositionSide positionSide,
                              String asset,
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
                ? lockPositionMargins(userId, symbol, normalizedMarginMode, PositionSide.defaultIfNull(positionSide),
                asset)
                : List.of();
        long maxLockedDebitUnits = lockedMargins.stream()
                .mapToLong(PositionMargin::marginUnits)
                .reduce(0L, Math::addExact);
        FundingBalanceState current = jdbcTemplate.queryForObject("""
                SELECT b.available_units, b.locked_units, d.deficit_units
                  FROM account_balances b
                  JOIN account_deficits d USING (user_id, asset)
                 WHERE b.user_id = ? AND b.asset = ?
                 FOR UPDATE OF b, d
                """, (rs, rowNum) -> new FundingBalanceState(
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("deficit_units")), userId, asset);
        long availableInput = amountUnits < 0 && normalizedMarginMode == MarginMode.ISOLATED
                ? 0L
                : current.availableUnits();
        FundingBalanceState next = FundingMath.applyPayment(availableInput, current.lockedUnits(),
                current.deficitUnits(), amountUnits, maxLockedDebitUnits);
        if (amountUnits < 0 && normalizedMarginMode == MarginMode.ISOLATED) {
            next = new FundingBalanceState(current.availableUnits(), next.lockedUnits(), next.deficitUnits());
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
        requireSingleRow(balanceRows, "funding account balance update");
        int deficitRows = jdbcTemplate.update("""
                UPDATE account_deficits
                   SET deficit_units = ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, next.deficitUnits(), Timestamp.from(now), userId, asset);
        requireSingleRow(deficitRows, "funding account deficit update");
        return Math.subtractExact(Math.addExact(next.availableUnits(), next.lockedUnits()), next.deficitUnits());
    }

    private long applyProductBalance(ProductLine productLine,
                                     long userId,
                                     String symbol,
                                     MarginMode marginMode,
                                     PositionSide positionSide,
                                     String asset,
                                     long amountUnits,
                                     Instant now) {
        String accountType = productLine.accountTypeCode();
        jdbcTemplate.update("""
                INSERT INTO account_product_balances (
                    account_type, user_id, asset, available_units, locked_units, updated_at
                ) VALUES (?, ?, ?, 0, 0, ?)
                ON CONFLICT (account_type, user_id, asset) DO NOTHING
                """, accountType, userId, asset, Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO account_product_deficits (account_type, user_id, asset, deficit_units, updated_at)
                VALUES (?, ?, ?, 0, ?)
                ON CONFLICT (account_type, user_id, asset) DO NOTHING
                """, accountType, userId, asset, Timestamp.from(now));
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        List<PositionMargin> lockedMargins = amountUnits < 0
                ? lockPositionMargins(userId, symbol, normalizedMarginMode, PositionSide.defaultIfNull(positionSide),
                asset)
                : List.of();
        long maxLockedDebitUnits = lockedMargins.stream()
                .mapToLong(PositionMargin::marginUnits)
                .reduce(0L, Math::addExact);
        FundingBalanceState current = jdbcTemplate.queryForObject("""
                SELECT b.available_units, b.locked_units, d.deficit_units
                  FROM account_product_balances b
                  JOIN account_product_deficits d USING (account_type, user_id, asset)
                 WHERE b.account_type = ? AND b.user_id = ? AND b.asset = ?
                 FOR UPDATE OF b, d
                """, (rs, rowNum) -> new FundingBalanceState(
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("deficit_units")), accountType, userId, asset);
        long availableInput = amountUnits < 0 && normalizedMarginMode == MarginMode.ISOLATED
                ? 0L
                : current.availableUnits();
        FundingBalanceState next = FundingMath.applyPayment(availableInput, current.lockedUnits(),
                current.deficitUnits(), amountUnits, maxLockedDebitUnits);
        if (amountUnits < 0 && normalizedMarginMode == MarginMode.ISOLATED) {
            next = new FundingBalanceState(current.availableUnits(), next.lockedUnits(), next.deficitUnits());
        }
        long lockedDebitUnits = Math.subtractExact(current.lockedUnits(), next.lockedUnits());
        reducePositionMargins(userId, asset, lockedDebitUnits, lockedMargins, now);
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET available_units = ?,
                       locked_units = ?,
                       updated_at = ?
                 WHERE account_type = ?
                   AND user_id = ?
                   AND asset = ?
                """, next.availableUnits(), next.lockedUnits(), Timestamp.from(now), accountType, userId, asset);
        requireSingleRow(balanceRows, "funding product account balance update");
        int deficitRows = jdbcTemplate.update("""
                UPDATE account_product_deficits
                   SET deficit_units = ?,
                       updated_at = ?
                 WHERE account_type = ?
                   AND user_id = ?
                   AND asset = ?
                """, next.deficitUnits(), Timestamp.from(now), accountType, userId, asset);
        requireSingleRow(deficitRows, "funding product account deficit update");
        return Math.subtractExact(Math.addExact(next.availableUnits(), next.lockedUnits()), next.deficitUnits());
    }

    private List<PositionMargin> lockPositionMargins(long userId,
                                                     String symbol,
                                                     MarginMode marginMode,
                                                     PositionSide positionSide,
                                                     String asset) {
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        ProductLine productLine = currentFundingProductLine()
                .orElseThrow(() -> new IllegalStateException("funding payment requires a funding product line"));
        if (normalizedMarginMode == MarginMode.ISOLATED) {
            return jdbcTemplate.query("""
                    SELECT product_line, symbol, asset, margin_mode, position_side, margin_units
                      FROM account_position_margins
                     WHERE user_id = ? AND symbol = ? AND product_line = ?
                       AND margin_mode = ? AND position_side = ? AND asset = ?
                       AND margin_units > 0
                     ORDER BY updated_at ASC, symbol ASC, margin_mode ASC, position_side ASC
                     FOR UPDATE
                    """, (rs, rowNum) -> new PositionMargin(
                    ProductLine.valueOf(rs.getString("product_line")),
                    rs.getString("symbol"),
                    rs.getString("asset"),
                    MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                    PositionSide.fromNullableDbValue(rs.getString("position_side")),
                    rs.getLong("margin_units")), userId, symbol, productLine.name(), normalizedMarginMode.name(),
                    normalizedPositionSide.name(), asset);
        }
        return jdbcTemplate.query("""
                SELECT product_line, symbol, asset, margin_mode, position_side, margin_units
                  FROM account_position_margins
                 WHERE user_id = ? AND product_line = ? AND asset = ? AND margin_mode = ? AND position_side = ?
                   AND margin_units > 0
                 ORDER BY updated_at ASC, symbol ASC, margin_mode ASC, position_side ASC
                 FOR UPDATE
                """, (rs, rowNum) -> new PositionMargin(
                ProductLine.valueOf(rs.getString("product_line")),
                rs.getString("symbol"),
                rs.getString("asset"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                rs.getLong("margin_units")), userId, productLine.name(), asset, normalizedMarginMode.name(),
                normalizedPositionSide.name());
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
                       AND position_side = ?
                       AND product_line = ?
                       AND margin_units >= ?
                    """, debit, Timestamp.from(now), userId, margin.symbol(), asset, margin.marginMode().name(),
                    margin.positionSide().name(), margin.productLine().name(), debit);
            if (rows != 1) {
                throw new IllegalStateException("failed to reduce consumed position margin");
            }
            jdbcTemplate.update("""
                    DELETE FROM account_position_margins
                     WHERE user_id = ? AND symbol = ? AND asset = ? AND margin_mode = ?
                       AND position_side = ? AND product_line = ? AND margin_units = 0
                    """, userId, margin.symbol(), asset, margin.marginMode().name(), margin.positionSide().name(),
                    margin.productLine().name());
            remaining = Math.subtractExact(remaining, debit);
        }
        if (remaining != 0) {
            throw new IllegalStateException("insufficient position margin for locked debit");
        }
    }

    private long nextAccountSequence(String sequenceName) {
        Long value = jdbcTemplate.queryForObject("""
                INSERT INTO account_sequences (sequence_name, sequence_value, updated_at)
                VALUES (?, 1, now())
                ON CONFLICT (sequence_name) DO UPDATE SET
                    sequence_value = account_sequences.sequence_value + 1,
                    updated_at = now()
                RETURNING sequence_value
                """, Long.class, sequenceName);
        if (value == null) {
            throw new IllegalStateException("failed to allocate account sequence " + sequenceName);
        }
        return value;
    }

    private FundingRateResponse toRate(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FundingRateResponse(
                rs.getString("symbol"),
                rs.getLong("sequence"),
                rs.getLong("funding_rate_ppm"),
                rs.getLong("premium_rate_ppm"),
                rs.getLong("interest_rate_ppm"),
                rs.getTimestamp("funding_time").toInstant(),
                rs.getInt("funding_interval_hours"),
                rs.getString("status"),
                rs.getTimestamp("event_time").toInstant());
    }

    private FundingSettlementResponse toSettlement(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FundingSettlementResponse(
                rs.getLong("settlement_id"),
                rs.getString("symbol"),
                rs.getTimestamp("funding_time").toInstant(),
                rs.getLong("funding_rate_ppm"),
                rs.getLong("total_long_payment_units"),
                rs.getLong("total_short_payment_units"),
                rs.getInt("position_count"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private FundingPaymentResponse toPayment(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FundingPaymentResponse(
                rs.getLong("payment_id"),
                rs.getLong("settlement_id"),
                rs.getLong("user_id"),
                rs.getString("symbol"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                rs.getString("asset"),
                rs.getLong("signed_quantity_steps"),
                rs.getLong("notional_units"),
                rs.getLong("funding_rate_ppm"),
                rs.getLong("amount_units"),
                rs.getTimestamp("created_at").toInstant());
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

    private String fundingInstrumentCondition(List<Object> args, String alias) {
        ProductLine productLine = properties.getKafka().getProductLine();
        if (properties.getKafka().isProductTopicsEnabled()) {
            if (!productLine.isFundingProduct()) {
                return "1 = 0";
            }
            args.add(productLine.contractTypeCode());
            return alias + ".contract_type = ?";
        }
        return alias + ".instrument_type = 'PERPETUAL'";
    }

    private Optional<ProductLine> currentFundingProductLine() {
        ProductLine productLine = properties.getKafka().isProductTopicsEnabled()
                ? properties.getKafka().getProductLine()
                : ProductLine.LINEAR_PERPETUAL;
        return productLine.isFundingProduct() ? Optional.of(productLine) : Optional.empty();
    }

    private boolean usesProductAccount(ProductLine productLine) {
        return productLine != ProductLine.LINEAR_PERPETUAL;
    }

    private record PositionMargin(ProductLine productLine,
                                  String symbol,
                                  String asset,
                                  MarginMode marginMode,
                                  PositionSide positionSide,
                                  long marginUnits) {
        private PositionMargin {
            marginMode = MarginMode.defaultIfNull(marginMode);
            positionSide = PositionSide.defaultIfNull(positionSide);
        }
    }
}
