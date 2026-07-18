package com.surprising.funding.provider.repository;

import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.funding.api.model.AdminCursorPage;
import com.surprising.funding.api.model.FundingPaymentResponse;
import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.api.model.FundingSettlementResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.funding.provider.model.FundingPaymentDispatch;
import com.surprising.funding.provider.model.FundingRateInput;
import com.surprising.funding.provider.service.FundingMath;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
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
        MarkPriceEvent markPrice = requireMarkPrice(rate.symbol());
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
                       ?::BIGINT AS mark_price_ticks,
                       rate_row.funding_rate_ppm
                  FROM account_positions p
                  JOIN instruments i
                    ON i.symbol = p.symbol
                   AND i.version = p.instrument_version
                   AND i.contract_type = ?
                  JOIN account_asset_scales ss ON ss.asset = i.settle_asset
                  CROSS JOIN rate_row
                 WHERE p.symbol = ?
                   AND p.product_line = ?
                   AND p.instrument_version = ?
                   AND p.signed_quantity_steps <> 0
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
        }, rate.symbol(), Timestamp.from(rate.fundingTime()), markPrice.markPriceTicks(),
                productLine.contractTypeCode(), rate.symbol(), productLine.name(), markPrice.instrumentVersion());
    }

    public Optional<FundingPaymentDispatch> insertPayment(long settlementId,
                                                          FundingPaymentCandidate payment,
                                                          Instant now) {
        if (payment.amountUnits() == 0L) {
            return Optional.empty();
        }
        long paymentId = nextSequence("funding-payment");
        String commandId = "FUNDING:" + properties.getKafka().getProductLine().name()
                + ":" + settlementId + ":" + paymentId;
        int rows = jdbcTemplate.update("""
                INSERT INTO funding_payments (
                    payment_id, settlement_id, user_id, symbol, margin_mode, position_side, asset,
                    signed_quantity_steps, notional_units, funding_rate_ppm,
                    amount_units, command_id, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                ON CONFLICT (settlement_id, user_id, symbol, margin_mode, position_side) DO NOTHING
                """, paymentId, settlementId, payment.userId(), payment.symbol(), payment.marginMode().name(),
                payment.positionSide().name(), payment.asset(),
                payment.signedQuantitySteps(), payment.notionalUnits(), payment.fundingRatePpm(),
                payment.amountUnits(), commandId, Timestamp.from(now), Timestamp.from(now));
        return rows == 1
                ? Optional.of(new FundingPaymentDispatch(paymentId, commandId))
                : Optional.empty();
    }

    public void awaitAccountSettlement(long settlementId,
                                       long totalLongPaymentUnits,
                                       long totalShortPaymentUnits,
                                       int positionCount,
                                       Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE funding_settlements
                   SET total_long_payment_units = ?,
                       total_short_payment_units = ?,
                       position_count = ?,
                       expected_payment_count = ?,
                       status = CASE WHEN ? = 0 THEN 'COMPLETED' ELSE 'WAITING_ACCOUNTS' END,
                       updated_at = ?
                 WHERE settlement_id = ? AND status = 'PROCESSING'
                """, totalLongPaymentUnits, totalShortPaymentUnits, positionCount, positionCount, positionCount,
                Timestamp.from(now), settlementId);
        requireSingleRow(rows, "funding settlement dispatch");
    }

    public boolean completePayment(String commandId,
                                   long expectedUserId,
                                   String terminalStatus,
                                   String errorCode,
                                   String errorMessage,
                                   Instant completedAt) {
        PaymentState payment = jdbcTemplate.query("""
                SELECT payment_id, settlement_id, user_id, status
                  FROM funding_payments
                 WHERE command_id = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new PaymentState(
                rs.getLong("payment_id"), rs.getLong("settlement_id"),
                rs.getLong("user_id"), rs.getString("status")), commandId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("funding payment not found for " + commandId));
        if (payment.userId() != expectedUserId) {
            throw new IllegalStateException("funding payment user mismatch for " + commandId);
        }
        if (!"APPLIED".equals(terminalStatus) && !"REJECTED".equals(terminalStatus)) {
            throw new IllegalArgumentException("funding payment requires a terminal account status");
        }
        if (!"PENDING".equals(payment.status())) {
            if (!payment.status().equals(terminalStatus)) {
                throw new IllegalStateException("conflicting funding payment result for " + commandId);
            }
            return false;
        }
        lockSettlement(payment.settlementId());
        boolean applied = "APPLIED".equals(terminalStatus);
        int rows = jdbcTemplate.update("""
                UPDATE funding_payments
                   SET status = ?,
                       applied_at = CASE WHEN ? THEN CAST(? AS TIMESTAMPTZ) ELSE NULL END,
                       rejected_at = CASE WHEN ? THEN NULL ELSE CAST(? AS TIMESTAMPTZ) END,
                       error_code = ?,
                       error_message = ?,
                       updated_at = ?
                 WHERE payment_id = ? AND status = 'PENDING'
                """, terminalStatus, applied, Timestamp.from(completedAt), applied, Timestamp.from(completedAt),
                errorCode, truncate(errorMessage), Timestamp.from(completedAt), payment.paymentId());
        requireSingleRow(rows, "funding payment terminal result");
        refreshSettlement(payment.settlementId(), completedAt);
        return true;
    }

    private void lockSettlement(long settlementId) {
        Long lockedSettlementId = jdbcTemplate.queryForObject("""
                SELECT settlement_id
                  FROM funding_settlements
                 WHERE settlement_id = ?
                 FOR UPDATE
                """, Long.class, settlementId);
        if (lockedSettlementId == null || lockedSettlementId != settlementId) {
            throw new IllegalStateException("funding settlement not found for payment: " + settlementId);
        }
    }

    public List<TerminalAccountCommand> terminalAccountCommandsForPendingPayments(int limit) {
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
                """, (rs, rowNum) -> new TerminalAccountCommand(
                rs.getString("command_id"), rs.getLong("user_id"), rs.getString("status"),
                rs.getString("error_code"), rs.getString("error_message"),
                rs.getTimestamp("completed_at").toInstant()),
                properties.getKafka().getProductLine().name(), Math.max(1, limit));
    }

    private void refreshSettlement(long settlementId, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE funding_settlements s
                   SET applied_payment_count = counts.applied_count,
                       rejected_payment_count = counts.rejected_count,
                       status = CASE
                           WHEN counts.rejected_count > 0 THEN 'FAILED'
                           WHEN counts.applied_count = s.expected_payment_count THEN 'COMPLETED'
                           ELSE 'WAITING_ACCOUNTS'
                       END,
                       updated_at = ?
                  FROM (
                      SELECT settlement_id,
                             count(*) FILTER (WHERE status = 'APPLIED')::INTEGER AS applied_count,
                             count(*) FILTER (WHERE status = 'REJECTED')::INTEGER AS rejected_count
                        FROM funding_payments
                       WHERE settlement_id = ?
                       GROUP BY settlement_id
                  ) counts
                 WHERE s.settlement_id = counts.settlement_id
                """, Timestamp.from(now), settlementId);
        requireSingleRow(rows, "funding settlement account progress");
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

    private record PaymentState(long paymentId, long settlementId, long userId, String status) {
    }

    public record TerminalAccountCommand(
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
