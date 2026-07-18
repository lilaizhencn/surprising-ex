package com.surprising.funding.provider.repository;

import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.funding.api.model.AdminCursorPage;
import com.surprising.funding.api.model.FundingPaymentResponse;
import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.api.model.FundingSettlementResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.funding.provider.model.FundingRateInput;
import com.surprising.funding.provider.service.FundingMath;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FundingRepository {

    private static final String RATE_MODULE = "funding-rate";

    private final JdbcTemplate jdbcTemplate;
    private final FundingProperties properties;
    private final LatestMarkPriceCache markPriceCache;

    public FundingRepository(JdbcTemplate jdbcTemplate, FundingProperties properties) {
        this(jdbcTemplate, properties, null);
    }

    @Autowired
    public FundingRepository(JdbcTemplate jdbcTemplate,
                             FundingProperties properties,
                             LatestMarkPriceCache markPriceCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new FundingProperties() : properties;
        this.markPriceCache = markPriceCache;
    }

    protected FundingRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new FundingProperties(), null);
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

    public List<FundingRateInput> rateInputs(Duration maxMarkAge) {
        MarkPriceValues markPrices = freshMarkPrices(maxMarkAge);
        if (markPrices.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>(markPrices.args());
        String productCondition = fundingInstrumentCondition(args, "i");
        return jdbcTemplate.query("""
                WITH %s
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
                  JOIN mark_prices pm
                    ON pm.symbol = i.symbol
                   AND pm.instrument_version = i.version
                 WHERE i.status = 'TRADING'
                   AND %s
                   AND i.funding_interval_hours > 0
                   AND pm.index_price > 0
                """.formatted(markPrices.cte(), productCondition), (rs, rowNum) -> new FundingRateInput(
                rs.getString("symbol"),
                0L,
                rs.getLong("premium_rate_ppm"),
                rs.getLong("interest_rate_ppm"),
                rs.getLong("funding_rate_floor_ppm"),
                rs.getLong("funding_rate_cap_ppm"),
                rs.getInt("funding_interval_hours"),
                rs.getTimestamp("event_time").toInstant()), args.toArray());
    }

    public boolean saveFinalRate(FundingRateResponse rate) {
        int rows = jdbcTemplate.update("""
                INSERT INTO funding_rate_ticks (
                    symbol, sequence, funding_time, funding_interval_hours,
                    premium_rate_ppm, interest_rate_ppm, funding_rate_ppm,
                    status, event_time, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'FINAL', ?, now())
                ON CONFLICT (symbol, sequence) DO NOTHING
                """, rate.symbol(), rate.sequence(), Timestamp.from(rate.fundingTime()), rate.fundingIntervalHours(),
                rate.premiumRatePpm(), rate.interestRatePpm(), rate.fundingRatePpm(), Timestamp.from(rate.eventTime()));
        return rows == 1;
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
                   AND r.status = 'FINAL'
                   AND %s
                   AND NOT EXISTS (
                       SELECT 1
                         FROM funding_settlements s
                        WHERE s.symbol = r.symbol
                          AND s.funding_time = r.funding_time
                          AND s.status <> 'PROCESSING'
                   )
                 ORDER BY r.symbol, r.funding_time, r.sequence DESC
                 LIMIT ?
                """.formatted(productCondition), (rs, rowNum) -> toRate(rs), args.toArray());
    }

    public Optional<FundingSettlementWork> createOrResumeSettlement(FundingRateResponse rate, Instant now) {
        Optional<FundingSettlementWork> existing = processingSettlement(rate.symbol(), rate.fundingTime());
        if (existing.isPresent()) {
            return existing;
        }
        MarkPriceEvent markPrice = requireMarkPrice(rate.symbol());
        Long settlementId = jdbcTemplate.queryForObject(
                "SELECT nextval('funding_settlement_id_seq')", Long.class);
        if (settlementId == null) {
            throw new IllegalStateException("failed to allocate funding settlement id");
        }
        jdbcTemplate.update("""
                INSERT INTO funding_settlements (
                    settlement_id, symbol, funding_time, funding_rate_ppm,
                    instrument_version, mark_price_ticks,
                    total_long_payment_units, total_short_payment_units,
                    position_count, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, 'PROCESSING', ?, ?)
                ON CONFLICT (symbol, funding_time) DO NOTHING
                """, settlementId, rate.symbol(), Timestamp.from(rate.fundingTime()), rate.fundingRatePpm(),
                markPrice.instrumentVersion(), markPrice.markPriceTicks(), Timestamp.from(now), Timestamp.from(now));
        return processingSettlement(rate.symbol(), rate.fundingTime());
    }

    public Optional<FundingSettlementWork> lockProcessingSettlement(long settlementId) {
        return jdbcTemplate.query("""
                SELECT settlement_id, symbol, funding_time, funding_rate_ppm,
                       instrument_version, mark_price_ticks,
                       scan_user_id, scan_margin_mode, scan_position_side
                  FROM funding_settlements
                 WHERE settlement_id = ?
                   AND status = 'PROCESSING'
                 FOR UPDATE
                """, (rs, rowNum) -> toSettlementWork(rs), settlementId).stream().findFirst();
    }

    public FundingPaymentPage paymentCandidatesPage(FundingSettlementWork settlement, int limit) {
        Optional<ProductLine> fundingProductLine = currentFundingProductLine();
        if (fundingProductLine.isEmpty()) {
            return FundingPaymentPage.empty(settlement.cursor());
        }
        ProductLine productLine = fundingProductLine.get();
        int safeLimit = Math.max(1, limit);
        List<FundingPaymentCandidate> rows = jdbcTemplate.query("""
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
                       ?::BIGINT AS mark_price_ticks,
                       ?::BIGINT AS funding_rate_ppm
                  FROM account_positions p
                  JOIN instruments i
                    ON i.symbol = p.symbol
                   AND i.version = p.instrument_version
                   AND i.contract_type = ?
                  JOIN account_asset_scales ss ON ss.asset = i.settle_asset
                 WHERE p.symbol = ?
                   AND p.product_line = ?
                   AND p.instrument_version = ?
                   AND p.signed_quantity_steps <> 0
                   AND (p.user_id, p.margin_mode, p.position_side) > (?, ?, ?)
                 ORDER BY p.user_id ASC, p.margin_mode ASC, p.position_side ASC
                 LIMIT ?
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
        }, settlement.markPriceTicks(), settlement.fundingRatePpm(), productLine.contractTypeCode(),
                settlement.symbol(), productLine.name(), settlement.instrumentVersion(),
                settlement.cursor().userId(), settlement.cursor().marginMode(), settlement.cursor().positionSide(),
                safeLimit + 1);
        boolean hasMore = rows.size() > safeLimit;
        List<FundingPaymentCandidate> items = hasMore ? List.copyOf(rows.subList(0, safeLimit)) : List.copyOf(rows);
        FundingPaymentCursor nextCursor = items.isEmpty()
                ? settlement.cursor()
                : FundingPaymentCursor.from(items.getLast());
        return new FundingPaymentPage(items, nextCursor, hasMore);
    }

    public List<FundingPaymentWrite> insertPayments(long settlementId,
                                                    List<FundingPaymentCandidate> payments,
                                                    Instant now) {
        if (payments == null || payments.isEmpty()) {
            return List.of();
        }
        if (payments.stream().anyMatch(payment -> payment.amountUnits() == 0L)) {
            throw new IllegalArgumentException("zero funding payments must be filtered before insert");
        }
        List<Long> paymentIds = jdbcTemplate.query("""
                SELECT nextval('funding_payment_id_seq') AS payment_id
                  FROM generate_series(1, ?)
                """, (rs, rowNum) -> rs.getLong("payment_id"), payments.size());
        if (paymentIds.size() != payments.size()) {
            throw new IllegalStateException("failed to allocate funding payment ids");
        }
        List<FundingPaymentWrite> writes = new ArrayList<>(payments.size());
        String productLine = properties.getKafka().getProductLine().name();
        for (int i = 0; i < payments.size(); i++) {
            long paymentId = paymentIds.get(i);
            writes.add(new FundingPaymentWrite(paymentId,
                    "FUNDING:" + productLine + ":" + settlementId + ":" + paymentId, payments.get(i)));
        }
        int[] rows = jdbcTemplate.batchUpdate("""
                INSERT INTO funding_payments (
                    payment_id, settlement_id, user_id, symbol, margin_mode, position_side, asset,
                    signed_quantity_steps, notional_units, funding_rate_ppm,
                    amount_units, command_id, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws java.sql.SQLException {
                FundingPaymentWrite write = writes.get(index);
                FundingPaymentCandidate payment = write.payment();
                statement.setLong(1, write.paymentId());
                statement.setLong(2, settlementId);
                statement.setLong(3, payment.userId());
                statement.setString(4, payment.symbol());
                statement.setString(5, payment.marginMode().name());
                statement.setString(6, payment.positionSide().name());
                statement.setString(7, payment.asset());
                statement.setLong(8, payment.signedQuantitySteps());
                statement.setLong(9, payment.notionalUnits());
                statement.setLong(10, payment.fundingRatePpm());
                statement.setLong(11, payment.amountUnits());
                statement.setString(12, write.commandId());
                statement.setTimestamp(13, Timestamp.from(now));
                statement.setTimestamp(14, Timestamp.from(now));
            }

            @Override
            public int getBatchSize() {
                return writes.size();
            }
        });
        requireCompleteBatch(rows, writes.size(), "funding payments");
        return List.copyOf(writes);
    }

    public void advanceSettlementPage(long settlementId,
                                      FundingPaymentPage page,
                                      List<FundingPaymentWrite> writes,
                                      Instant now) {
        long totalLongPaymentUnits = 0L;
        long totalShortPaymentUnits = 0L;
        for (FundingPaymentWrite write : writes) {
            FundingPaymentCandidate payment = write.payment();
            if (payment.signedQuantitySteps() > 0L) {
                totalLongPaymentUnits = Math.addExact(totalLongPaymentUnits, payment.amountUnits());
            } else {
                totalShortPaymentUnits = Math.addExact(totalShortPaymentUnits, payment.amountUnits());
            }
        }
        int paymentCount = writes.size();
        boolean completed = !page.hasMore();
        int rows = jdbcTemplate.update("""
                UPDATE funding_settlements
                   SET total_long_payment_units = total_long_payment_units + ?,
                       total_short_payment_units = total_short_payment_units + ?,
                       position_count = position_count + ?,
                       expected_payment_count = expected_payment_count + ?,
                       scan_user_id = ?,
                       scan_margin_mode = ?,
                       scan_position_side = ?,
                       scan_completed = ?,
                       status = CASE
                           WHEN NOT ? THEN 'PROCESSING'
                           WHEN rejected_payment_count > 0 THEN 'FAILED'
                           WHEN applied_payment_count = expected_payment_count + ? THEN 'COMPLETED'
                           ELSE 'WAITING_ACCOUNTS'
                       END,
                       updated_at = ?
                 WHERE settlement_id = ? AND status = 'PROCESSING'
                """, totalLongPaymentUnits, totalShortPaymentUnits, paymentCount, paymentCount,
                page.nextCursor().userId(), page.nextCursor().marginMode(), page.nextCursor().positionSide(),
                completed, completed, paymentCount, Timestamp.from(now), settlementId);
        requireSingleRow(rows, "funding settlement page");
    }

    public boolean completePayment(String commandId,
                                   long expectedUserId,
                                   String terminalStatus,
                                   String errorCode,
                                   String errorMessage,
                                   Instant completedAt) {
        return completePayments(List.of(new PaymentResult(commandId, expectedUserId, terminalStatus,
                errorCode, errorMessage, completedAt))) == 1;
    }

    public int completePayments(List<PaymentResult> results) {
        if (results == null || results.isEmpty()) {
            return 0;
        }
        Map<String, PaymentResult> unique = new LinkedHashMap<>();
        for (PaymentResult result : results) {
            validatePaymentResult(result);
            PaymentResult previous = unique.putIfAbsent(result.commandId(), result);
            if (previous != null && !previous.equals(result)) {
                throw new IllegalStateException("conflicting funding payment results for " + result.commandId());
            }
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(unique.size(), "?"));
        List<PaymentState> states = jdbcTemplate.query("""
                SELECT payment_id, settlement_id, command_id, user_id, status
                  FROM funding_payments
                 WHERE command_id IN (%s)
                """.formatted(placeholders), (rs, rowNum) -> new PaymentState(
                rs.getLong("payment_id"), rs.getLong("settlement_id"), rs.getString("command_id"),
                rs.getLong("user_id"), rs.getString("status")), unique.keySet().toArray());
        Map<String, PaymentState> stateByCommand = new LinkedHashMap<>();
        for (PaymentState state : states) {
            stateByCommand.put(state.commandId(), state);
        }
        List<PaymentResult> pending = new ArrayList<>();
        for (PaymentResult result : unique.values()) {
            PaymentState state = stateByCommand.get(result.commandId());
            if (state == null) {
                throw new IllegalStateException("funding payment not found for " + result.commandId());
            }
            if (state.userId() != result.userId()) {
                throw new IllegalStateException("funding payment user mismatch for " + result.commandId());
            }
            if ("PENDING".equals(state.status())) {
                pending.add(result);
            } else if (!state.status().equals(result.status())) {
                throw new IllegalStateException("conflicting funding payment result for " + result.commandId());
            }
        }
        if (pending.isEmpty()) {
            return 0;
        }

        StringBuilder values = new StringBuilder();
        List<Object> args = new ArrayList<>(pending.size() * 6);
        for (PaymentResult result : pending) {
            if (!values.isEmpty()) {
                values.append(", ");
            }
            values.append("(?::TEXT, ?::BIGINT, ?::TEXT, ?::TEXT, ?::TEXT, ?::TIMESTAMPTZ)");
            args.add(result.commandId());
            args.add(result.userId());
            args.add(result.status());
            args.add(result.errorCode());
            args.add(truncate(result.errorMessage()));
            args.add(Timestamp.from(result.completedAt()));
        }
        Integer updated = jdbcTemplate.queryForObject("""
                WITH input(command_id, user_id, status, error_code, error_message, completed_at) AS (
                    VALUES %s
                ),
                updated AS (
                    UPDATE funding_payments p
                       SET status = i.status,
                           applied_at = CASE WHEN i.status = 'APPLIED' THEN i.completed_at ELSE NULL END,
                           rejected_at = CASE WHEN i.status = 'REJECTED' THEN i.completed_at ELSE NULL END,
                           error_code = i.error_code,
                           error_message = i.error_message,
                           updated_at = i.completed_at
                      FROM input i
                     WHERE p.command_id = i.command_id
                       AND p.user_id = i.user_id
                       AND p.status = 'PENDING'
                    RETURNING p.settlement_id, p.status
                ),
                counts AS (
                    SELECT settlement_id,
                           count(*) FILTER (WHERE status = 'APPLIED')::INTEGER AS applied_count,
                           count(*) FILTER (WHERE status = 'REJECTED')::INTEGER AS rejected_count
                      FROM updated
                     GROUP BY settlement_id
                ),
                progress AS (
                    UPDATE funding_settlements s
                       SET applied_payment_count = s.applied_payment_count + c.applied_count,
                           rejected_payment_count = s.rejected_payment_count + c.rejected_count,
                           status = CASE
                               WHEN s.status = 'PROCESSING' THEN 'PROCESSING'
                               WHEN s.rejected_payment_count + c.rejected_count > 0 THEN 'FAILED'
                               WHEN s.applied_payment_count + c.applied_count = s.expected_payment_count
                                   THEN 'COMPLETED'
                               ELSE 'WAITING_ACCOUNTS'
                           END,
                           updated_at = GREATEST(s.updated_at, (
                               SELECT max(i.completed_at)
                                 FROM input i
                           ))
                      FROM counts c
                     WHERE s.settlement_id = c.settlement_id
                    RETURNING s.settlement_id
                )
                SELECT count(*)::INTEGER
                  FROM updated
                 WHERE (SELECT count(*) FROM progress) >= 0
                """.formatted(values), Integer.class, args.toArray());
        return updated == null ? 0 : updated;
    }

    public List<PaymentResult> terminalAccountCommandsForPendingPayments(int limit) {
        return jdbcTemplate.query("""
                SELECT c.command_id, c.user_id, c.status, c.error_code, c.error_message, c.completed_at
                  FROM funding_payments p
                  JOIN account_commands c ON c.command_id = p.command_id
                 WHERE p.status = 'PENDING'
                   AND c.product_line = ?
                   AND c.command_type = 'FUNDING_SETTLE'
                   AND c.status IN ('APPLIED', 'REJECTED')
                 ORDER BY c.completed_at ASC, c.command_id ASC
                 LIMIT ?
                """, (rs, rowNum) -> new PaymentResult(
                rs.getString("command_id"), rs.getLong("user_id"), rs.getString("status"),
                rs.getString("error_code"), rs.getString("error_message"),
                rs.getTimestamp("completed_at").toInstant()),
                properties.getKafka().getProductLine().name(), Math.max(1, limit));
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

    private Optional<FundingSettlementWork> processingSettlement(String symbol, Instant fundingTime) {
        return jdbcTemplate.query("""
                SELECT settlement_id, symbol, funding_time, funding_rate_ppm,
                       instrument_version, mark_price_ticks,
                       scan_user_id, scan_margin_mode, scan_position_side
                  FROM funding_settlements
                 WHERE symbol = ?
                   AND funding_time = ?
                   AND status = 'PROCESSING'
                """, (rs, rowNum) -> toSettlementWork(rs),
                symbol, Timestamp.from(fundingTime)).stream().findFirst();
    }

    private FundingSettlementWork toSettlementWork(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FundingSettlementWork(
                rs.getLong("settlement_id"),
                rs.getString("symbol"),
                rs.getTimestamp("funding_time").toInstant(),
                rs.getLong("funding_rate_ppm"),
                rs.getLong("instrument_version"),
                rs.getLong("mark_price_ticks"),
                new FundingPaymentCursor(
                        rs.getLong("scan_user_id"),
                        rs.getString("scan_margin_mode"),
                        rs.getString("scan_position_side")));
    }

    private MarkPriceEvent requireMarkPrice(String symbol) {
        if (markPriceCache == null) {
            throw new IllegalStateException("mark price cache is not configured");
        }
        return markPriceCache.fresh(symbol, properties.getCalculation().getMaxMarkAge())
                .orElseThrow(() -> new IllegalStateException("fresh mark price not found for " + symbol));
    }

    private MarkPriceValues freshMarkPrices(Duration maxAge) {
        if (markPriceCache == null) {
            throw new IllegalStateException("mark price cache is not configured");
        }
        List<MarkPriceEvent> snapshots = markPriceCache.freshSnapshots(maxAge);
        if (snapshots.isEmpty()) {
            return MarkPriceValues.empty();
        }
        StringBuilder values = new StringBuilder();
        List<Object> args = new ArrayList<>(snapshots.size() * 5);
        for (MarkPriceEvent snapshot : snapshots) {
            if (!values.isEmpty()) {
                values.append(", ");
            }
            values.append("(?::TEXT, ?::BIGINT, ?::NUMERIC, ?::NUMERIC, ?::TIMESTAMPTZ)");
            args.add(snapshot.symbol());
            args.add(snapshot.instrumentVersion());
            args.add(snapshot.markPrice());
            args.add(snapshot.indexPrice());
            args.add(Timestamp.from(snapshot.eventTime()));
        }
        return new MarkPriceValues("mark_prices(symbol, instrument_version, mark_price, index_price, event_time) "
                + "AS (VALUES " + values + ")", List.copyOf(args));
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

    private void requireCompleteBatch(int[] rows, int expectedRows, String operation) {
        if (rows == null || rows.length != expectedRows) {
            throw new IllegalStateException("failed to write " + operation);
        }
        for (int row : rows) {
            if (row != 1 && row != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("failed to write " + operation);
            }
        }
    }

    private void validatePaymentResult(PaymentResult result) {
        if (result == null || result.commandId() == null || result.commandId().isBlank()) {
            throw new IllegalArgumentException("funding payment commandId is required");
        }
        if (result.userId() <= 0L) {
            throw new IllegalArgumentException("funding payment userId must be positive");
        }
        if (!"APPLIED".equals(result.status()) && !"REJECTED".equals(result.status())) {
            throw new IllegalArgumentException("funding payment requires a terminal account status");
        }
        if (result.completedAt() == null) {
            throw new IllegalArgumentException("funding payment completedAt is required");
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
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

    private record PaymentState(
            long paymentId,
            long settlementId,
            String commandId,
            long userId,
            String status) {
    }

    public record FundingSettlementWork(
            long settlementId,
            String symbol,
            Instant fundingTime,
            long fundingRatePpm,
            long instrumentVersion,
            long markPriceTicks,
            FundingPaymentCursor cursor) {
    }

    public record FundingPaymentCursor(long userId, String marginMode, String positionSide) {

        public FundingPaymentCursor {
            marginMode = marginMode == null ? "" : marginMode;
            positionSide = positionSide == null ? "" : positionSide;
        }

        public static FundingPaymentCursor from(FundingPaymentCandidate payment) {
            return new FundingPaymentCursor(payment.userId(), payment.marginMode().name(),
                    payment.positionSide().name());
        }
    }

    public record FundingPaymentPage(
            List<FundingPaymentCandidate> items,
            FundingPaymentCursor nextCursor,
            boolean hasMore) {

        public FundingPaymentPage {
            items = items == null ? List.of() : List.copyOf(items);
        }

        private static FundingPaymentPage empty(FundingPaymentCursor cursor) {
            return new FundingPaymentPage(List.of(), cursor, false);
        }
    }

    public record FundingPaymentWrite(
            long paymentId,
            String commandId,
            FundingPaymentCandidate payment) {
    }

    public record PaymentResult(
            String commandId,
            long userId,
            String status,
            String errorCode,
            String errorMessage,
            Instant completedAt) {
    }

    private record MarkPriceValues(String cte, List<Object> args) {

        private static MarkPriceValues empty() {
            return new MarkPriceValues("", List.of());
        }

        private boolean isEmpty() {
            return args.isEmpty();
        }
    }
}
