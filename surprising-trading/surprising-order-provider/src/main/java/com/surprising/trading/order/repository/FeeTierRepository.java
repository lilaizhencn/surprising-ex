package com.surprising.trading.order.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineSql;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.trading.api.model.FeeScheduleSourceType;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeTierAssignmentResponse;
import com.surprising.trading.api.model.FeeTierQualificationMode;
import com.surprising.trading.api.model.FeeTierQueryResponse;
import com.surprising.trading.api.model.FeeTierResponse;
import com.surprising.trading.api.model.FeeTierUpsertRequest;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FeeTierRepository {

    private static final long MAX_ABS_FEE_RATE_PPM = 1_000_000L;
    private static final int MAX_QUERY_LIMIT = 500;
    private static final TierSortSpec TIER_PRIORITY_DESC = new TierSortSpec("priority.desc", true);
    private static final List<TierSortSpec> TIER_SORTS = List.of(
            TIER_PRIORITY_DESC,
            new TierSortSpec("priority.asc", false));

    private final JdbcTemplate jdbcTemplate;

    public FeeTierRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsertTier(FeeTierUpsertRequest request, Instant now) {
        validateTier(request);
        FeeScheduleSourceType sourceType = sourceType(request.sourceType());
        FeeTierQualificationMode qualificationMode = qualificationMode(request.qualificationMode());
        FeeScheduleStatus status = status(request.status());
        jdbcTemplate.update("""
                INSERT INTO trading_fee_tiers (
                    tier_code, source_type, qualification_mode, min_30d_volume_units,
                    min_asset_balance_units, maker_fee_rate_ppm, taker_fee_rate_ppm,
                    priority, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tier_code) DO UPDATE SET
                    source_type = EXCLUDED.source_type,
                    qualification_mode = EXCLUDED.qualification_mode,
                    min_30d_volume_units = EXCLUDED.min_30d_volume_units,
                    min_asset_balance_units = EXCLUDED.min_asset_balance_units,
                    maker_fee_rate_ppm = EXCLUDED.maker_fee_rate_ppm,
                    taker_fee_rate_ppm = EXCLUDED.taker_fee_rate_ppm,
                    priority = EXCLUDED.priority,
                    status = EXCLUDED.status,
                    updated_at = EXCLUDED.updated_at
                """, normalizeTierCode(request.tierCode()), sourceType.name(), qualificationMode.name(),
                request.min30dVolumeUnits(), request.minAssetBalanceUnits(), request.makerFeeRatePpm(),
                request.takerFeeRatePpm(), request.priority(), status.name(), Timestamp.from(now),
                Timestamp.from(now));
    }

    public FeeTierQueryResponse queryTiers(FeeScheduleStatus status, int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, MAX_QUERY_LIMIT));
        String statusName = status == null ? null : status.name();
        List<FeeTierResponse> tiers = jdbcTemplate.query("""
                SELECT *
                  FROM trading_fee_tiers
                 WHERE (CAST(? AS text) IS NULL OR status = ?)
                 ORDER BY priority DESC, min_30d_volume_units DESC, min_asset_balance_units DESC, tier_code ASC
                 LIMIT ?
                """, (rs, rowNum) -> toTierResponse(rs), statusName, statusName, normalizedLimit);
        return new FeeTierQueryResponse(tiers.size(), tiers);
    }

    public FeeTierQueryResponse queryTiersPage(FeeScheduleStatus status, int limit, String cursor, String sort) {
        int normalizedLimit = Math.max(1, Math.min(limit, MAX_QUERY_LIMIT));
        String statusName = status == null ? null : status.name();
        TierSortSpec sortSpec = parseTierSort(sort);
        TierCursor decodedCursor = decodeTierCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(statusName);
        args.add(statusName);
        String sql = """
                SELECT *
                  FROM trading_fee_tiers
                 WHERE (CAST(? AS text) IS NULL OR status = ?)
                """ + tierSeekCondition(sortSpec, decodedCursor) + """
                 ORDER BY %s
                 LIMIT ?
                """.formatted(sortSpec.orderBy());
        addTierCursorArgs(args, decodedCursor);
        args.add(normalizedLimit + 1);
        List<FeeTierResponse> fetchedRows = jdbcTemplate.query(sql, (rs, rowNum) -> toTierResponse(rs),
                args.toArray());
        boolean hasMore = fetchedRows.size() > normalizedLimit;
        List<FeeTierResponse> rows = hasMore
                ? List.copyOf(fetchedRows.subList(0, normalizedLimit))
                : List.copyOf(fetchedRows);
        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            nextCursor = encodeTierCursor(rows.get(rows.size() - 1));
        }
        return new FeeTierQueryResponse(rows.size(), rows, nextCursor, hasMore, sortSpec.token(),
                normalizedLimit);
    }

    public Optional<FeeTierResponse> findTier(String tierCode) {
        String normalizedTierCode = normalizeTierCode(tierCode);
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_fee_tiers
                 WHERE tier_code = ?
                """, (rs, rowNum) -> toTierResponse(rs), normalizedTierCode).stream().findFirst();
    }

    public Optional<FeeTierResponse> eligibleTier(long trailing30dVolumeUnits, long totalAssetBalanceUnits) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_fee_tiers
                 WHERE status = 'ACTIVE'
                   AND (
                       (qualification_mode = 'VOLUME_ONLY' AND ? >= min_30d_volume_units)
                    OR (qualification_mode = 'BALANCE_ONLY' AND ? >= min_asset_balance_units)
                    OR (qualification_mode = 'VOLUME_OR_BALANCE'
                        AND (? >= min_30d_volume_units OR ? >= min_asset_balance_units))
                    OR (qualification_mode = 'VOLUME_AND_BALANCE'
                        AND ? >= min_30d_volume_units AND ? >= min_asset_balance_units)
                   )
                 ORDER BY priority DESC, min_30d_volume_units DESC, min_asset_balance_units DESC, tier_code ASC
                 LIMIT 1
                """, (rs, rowNum) -> toTierResponse(rs), trailing30dVolumeUnits, totalAssetBalanceUnits,
                trailing30dVolumeUnits, totalAssetBalanceUnits, trailing30dVolumeUnits, totalAssetBalanceUnits)
                .stream().findFirst();
    }

    public FeeTierMetrics metrics(long userId, Instant since) {
        return metrics(ProductLine.LINEAR_PERPETUAL, userId, since);
    }

    public FeeTierMetrics metrics(ProductLine productLine, long userId, Instant since) {
        return metrics(productLine, userId, since, List.of());
    }

    public FeeTierMetrics metrics(ProductLine productLine,
                                  long userId,
                                  Instant since,
                                  List<MarkPriceEvent> markPrices) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        ProductLine resolvedProductLine = productLine(productLine);
        String productLineName = resolvedProductLine.name();
        String accountType = resolvedProductLine.accountTypeCode();
        long trailingVolume = number(jdbcTemplate.queryForObject("""
                WITH user_fills AS (
                    SELECT taker_user_id AS user_id, symbol, taker_instrument_version AS instrument_version,
                           price_ticks, quantity_steps, event_time
                      FROM trading_match_trades
                     WHERE taker_user_id = ?
                       AND event_time >= ?
                       AND product_line = ?
                    UNION ALL
                    SELECT maker_user_id AS user_id, symbol, maker_instrument_version AS instrument_version,
                           price_ticks, quantity_steps, event_time
                      FROM trading_match_trades
                     WHERE maker_user_id = ?
                       AND event_time >= ?
                       AND product_line = ?
                ),
                filled_notional AS (
                    SELECT CASE
                               WHEN i.contract_type IN ('LINEAR_PERPETUAL', 'LINEAR_DELIVERY', 'SPOT')
                               THEN t.price_ticks::numeric * t.quantity_steps::numeric
                                    * i.notional_multiplier_units::numeric
                               ELSE t.quantity_steps::numeric * i.notional_multiplier_units::numeric
                           END AS notional_units
                      FROM user_fills t
                      JOIN instruments i
                        ON i.symbol = t.symbol AND i.version = t.instrument_version
                     WHERE %s = ?
                )
                SELECT LEAST(COALESCE(SUM(notional_units), 0), 9223372036854775807)::bigint
                  FROM filled_notional
                """.formatted(productLineExpression("i")), Number.class, userId, Timestamp.from(since),
                productLineName, userId, Timestamp.from(since), productLineName, productLineName));
        List<MarkPriceEvent> prices = markPrices == null ? List.of() : markPrices;
        List<Object> balanceArgs = new ArrayList<>();
        String markPriceRows;
        if (prices.isEmpty()) {
            markPriceRows = """
                    (SELECT CAST(NULL AS text) AS symbol,
                            CAST(NULL AS bigint) AS instrument_version,
                            CAST(NULL AS bigint) AS mark_price_units
                       WHERE FALSE)
                    """;
        } else {
            markPriceRows = "(VALUES " + String.join(", ", java.util.Collections.nCopies(prices.size(),
                    "(?, ?, ?)")) + ")";
            for (MarkPriceEvent price : prices) {
                balanceArgs.add(price.symbol());
                balanceArgs.add(price.instrumentVersion());
                balanceArgs.add(price.markPriceUnits());
            }
        }
        balanceArgs.add(accountType);
        balanceArgs.add(userId);
        balanceArgs.add(accountType);
        balanceArgs.add(accountType);
        balanceArgs.add(userId);
        balanceArgs.add(userId);
        long assetBalance = number(jdbcTemplate.queryForObject("""
                WITH mark_prices(symbol, instrument_version, mark_price_units) AS %s,
                raw_balances AS (
                    SELECT b.user_id, b.asset, b.available_units, b.locked_units
                      FROM account_balances b
                     WHERE ? = 'USDT_PERPETUAL'
                       AND b.user_id = ?
                    UNION ALL
                    SELECT b.user_id, b.asset, b.available_units, b.locked_units
                      FROM account_product_balances b
                     WHERE ? <> 'USDT_PERPETUAL'
                       AND b.account_type = ?
                       AND b.user_id = ?
                ),
                valued_balances AS (
                    SELECT CASE
                               WHEN b.asset IN ('USDT', 'USD')
                               THEN (b.available_units + b.locked_units)::numeric
                               ELSE COALESCE(
                                   (b.available_units + b.locked_units)::numeric
                                   * pm.mark_price_units::numeric / s.scale_units::numeric,
                                   0)
                           END AS value_units
                      FROM raw_balances b
                      JOIN account_asset_scales s
                        ON s.asset = b.asset
                 LEFT JOIN LATERAL (
                           SELECT mp.mark_price_units
                             FROM instruments i
                             JOIN mark_prices mp
                               ON mp.symbol = i.symbol AND mp.instrument_version = i.version
                            WHERE i.base_asset = b.asset
                              AND i.quote_asset IN ('USDT', 'USD')
                              AND i.status = 'TRADING'
                            ORDER BY CASE WHEN i.quote_asset = 'USDT' THEN 0 ELSE 1 END,
                                     mp.symbol
                            LIMIT 1
                       ) pm ON TRUE
                     WHERE b.user_id = ?
                )
                SELECT LEAST(COALESCE(SUM(value_units), 0), 9223372036854775807)::bigint
                  FROM valued_balances
                """.formatted(markPriceRows), Number.class, balanceArgs.toArray()));
        return new FeeTierMetrics(trailingVolume, assetBalance);
    }

    public List<Long> candidateUsers(Instant since, int limit) {
        return candidateUsers(ProductLine.LINEAR_PERPETUAL, since, limit);
    }

    public List<Long> candidateUsers(ProductLine productLine, Instant since, int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, 10_000));
        ProductLine resolvedProductLine = productLine(productLine);
        String productLineName = resolvedProductLine.name();
        String accountType = resolvedProductLine.accountTypeCode();
        return jdbcTemplate.query("""
                SELECT user_id
                  FROM (
                        SELECT t.taker_user_id AS user_id
                          FROM trading_match_trades t
                          JOIN instruments i
                            ON i.symbol = t.symbol AND i.version = t.taker_instrument_version
                         WHERE t.event_time >= ?
                           AND t.product_line = ?
                           AND %s = ?
                        UNION
                        SELECT t.maker_user_id AS user_id
                          FROM trading_match_trades t
                          JOIN instruments i
                            ON i.symbol = t.symbol AND i.version = t.maker_instrument_version
                         WHERE t.event_time >= ?
                           AND t.product_line = ?
                           AND %s = ?
                        UNION
                        SELECT user_id
                          FROM account_balances
                         WHERE ? = 'USDT_PERPETUAL'
                           AND available_units + locked_units > 0
                        UNION
                        SELECT user_id
                          FROM account_product_balances
                         WHERE ? <> 'USDT_PERPETUAL'
                           AND account_type = ?
                           AND available_units + locked_units > 0
                        UNION
                        SELECT user_id
                          FROM trading_user_fee_tiers
                         WHERE product_line = ?
                           AND status = 'ACTIVE'
                       ) u
                 WHERE user_id > 0
                 ORDER BY user_id ASC
                 LIMIT ?
                """.formatted(productLineExpression("i"), productLineExpression("i")),
                (rs, rowNum) -> rs.getLong("user_id"), Timestamp.from(since), productLineName, productLineName,
                Timestamp.from(since), productLineName, productLineName, accountType, accountType, accountType,
                productLineName, normalizedLimit);
    }

    public FeeTierAssignmentRecord lockAssignment(long userId, long proposedFeeScheduleId, Instant now) {
        return lockAssignment(ProductLine.LINEAR_PERPETUAL, userId, proposedFeeScheduleId, now);
    }

    public FeeTierAssignmentRecord lockAssignment(ProductLine productLine,
                                                  long userId,
                                                  long proposedFeeScheduleId,
                                                  Instant now) {
        if (userId <= 0 || proposedFeeScheduleId <= 0) {
            throw new IllegalArgumentException("userId and feeScheduleId must be positive");
        }
        String productLineName = productLineName(productLine);
        jdbcTemplate.update("""
                INSERT INTO trading_user_fee_tiers (
                    product_line, user_id, fee_schedule_id, maker_fee_rate_ppm, taker_fee_rate_ppm,
                    trailing_30d_volume_units, total_asset_balance_units, status,
                    effective_time, calculated_at, created_at, updated_at
                ) VALUES (?, ?, ?, 0, 0, 0, 0, 'DISABLED', ?, ?, ?, ?)
                ON CONFLICT (product_line, user_id) DO NOTHING
                """, productLineName, userId, proposedFeeScheduleId, Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now));
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_user_fee_tiers
                 WHERE product_line = ?
                   AND user_id = ?
                 FOR UPDATE
                """, (rs, rowNum) -> toAssignmentRecord(rs), productLineName, userId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("fee tier assignment row missing: " + userId));
    }

    public void activateAssignment(long userId,
                                   FeeTierResponse tier,
                                   long feeScheduleId,
                                   FeeTierMetrics metrics,
                                   Instant effectiveTime,
                                   Instant now) {
        activateAssignment(ProductLine.LINEAR_PERPETUAL, userId, tier, feeScheduleId, metrics, effectiveTime, now);
    }

    public void activateAssignment(ProductLine productLine,
                                   long userId,
                                   FeeTierResponse tier,
                                   long feeScheduleId,
                                   FeeTierMetrics metrics,
                                   Instant effectiveTime,
                                   Instant now) {
        String productLineName = productLineName(productLine);
        int rows = jdbcTemplate.update("""
                UPDATE trading_user_fee_tiers
                   SET tier_code = ?,
                       source_type = ?,
                       maker_fee_rate_ppm = ?,
                       taker_fee_rate_ppm = ?,
                       trailing_30d_volume_units = ?,
                       total_asset_balance_units = ?,
                       status = 'ACTIVE',
                       effective_time = ?,
                       calculated_at = ?,
                       updated_at = ?
                 WHERE product_line = ?
                   AND user_id = ?
                   AND fee_schedule_id = ?
                """, tier.tierCode(), tier.sourceType().name(), tier.makerFeeRatePpm(), tier.takerFeeRatePpm(),
                metrics.trailing30dVolumeUnits(), metrics.totalAssetBalanceUnits(), Timestamp.from(effectiveTime),
                Timestamp.from(now), Timestamp.from(now), productLineName, userId, feeScheduleId);
        requireSingleRow(rows, "fee tier assignment activation");
    }

    public void disableAssignment(long userId, FeeTierMetrics metrics, Instant now) {
        disableAssignment(ProductLine.LINEAR_PERPETUAL, userId, metrics, now);
    }

    public void disableAssignment(ProductLine productLine, long userId, FeeTierMetrics metrics, Instant now) {
        String productLineName = productLineName(productLine);
        int rows = jdbcTemplate.update("""
                UPDATE trading_user_fee_tiers
                   SET status = 'DISABLED',
                       tier_code = NULL,
                       source_type = NULL,
                       maker_fee_rate_ppm = 0,
                       taker_fee_rate_ppm = 0,
                       trailing_30d_volume_units = ?,
                       total_asset_balance_units = ?,
                       effective_time = ?,
                       calculated_at = ?,
                       updated_at = ?
                 WHERE product_line = ?
                   AND user_id = ?
                """, metrics.trailing30dVolumeUnits(), metrics.totalAssetBalanceUnits(), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now), productLineName, userId);
        requireSingleRow(rows, "fee tier assignment disable");
    }

    public Optional<FeeTierAssignmentResponse> currentAssignment(long userId) {
        return currentAssignment(ProductLine.LINEAR_PERPETUAL, userId);
    }

    public Optional<FeeTierAssignmentResponse> currentAssignment(ProductLine productLine, long userId) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_user_fee_tiers
                 WHERE product_line = ?
                   AND user_id = ?
                """, (rs, rowNum) -> toAssignmentResponse(rs), productLineName(productLine), userId)
                .stream().findFirst();
    }

    public static void validateTier(FeeTierUpsertRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("fee tier request is required");
        }
        normalizeTierCode(request.tierCode());
        FeeScheduleSourceType sourceType = sourceType(request.sourceType());
        if (sourceType != FeeScheduleSourceType.VIP && sourceType != FeeScheduleSourceType.MARKET_MAKER) {
            throw new IllegalArgumentException("fee tier sourceType must be VIP or MARKET_MAKER");
        }
        if (request.min30dVolumeUnits() < 0 || request.minAssetBalanceUnits() < 0) {
            throw new IllegalArgumentException("fee tier thresholds must be non-negative");
        }
        if (request.priority() < 0) {
            throw new IllegalArgumentException("priority must be non-negative");
        }
        validateFeeRate(request.makerFeeRatePpm(), "makerFeeRatePpm");
        validateFeeRate(request.takerFeeRatePpm(), "takerFeeRatePpm");
        if (request.makerFeeRatePpm() > request.takerFeeRatePpm()) {
            throw new IllegalArgumentException("makerFeeRatePpm cannot exceed takerFeeRatePpm");
        }
    }

    private FeeTierResponse toTierResponse(ResultSet rs) throws SQLException {
        return new FeeTierResponse(
                rs.getString("tier_code"),
                FeeScheduleSourceType.valueOf(rs.getString("source_type")),
                FeeTierQualificationMode.valueOf(rs.getString("qualification_mode")),
                rs.getLong("min_30d_volume_units"),
                rs.getLong("min_asset_balance_units"),
                rs.getLong("maker_fee_rate_ppm"),
                rs.getLong("taker_fee_rate_ppm"),
                rs.getInt("priority"),
                FeeScheduleStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private FeeTierAssignmentRecord toAssignmentRecord(ResultSet rs) throws SQLException {
        return new FeeTierAssignmentRecord(
                ProductLine.valueOf(rs.getString("product_line")),
                rs.getLong("user_id"),
                stringOrNull(rs, "tier_code"),
                sourceTypeOrNull(rs, "source_type"),
                rs.getLong("fee_schedule_id"),
                rs.getLong("maker_fee_rate_ppm"),
                rs.getLong("taker_fee_rate_ppm"),
                rs.getLong("trailing_30d_volume_units"),
                rs.getLong("total_asset_balance_units"),
                FeeScheduleStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("effective_time").toInstant(),
                rs.getTimestamp("calculated_at").toInstant());
    }

    private FeeTierAssignmentResponse toAssignmentResponse(ResultSet rs) throws SQLException {
        FeeTierAssignmentRecord record = toAssignmentRecord(rs);
        return record.toResponse();
    }

    private static String normalizeTierCode(String tierCode) {
        if (tierCode == null || tierCode.isBlank()) {
            throw new IllegalArgumentException("tierCode is required");
        }
        String normalized = tierCode.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("invalid tierCode: " + tierCode);
        }
        return normalized;
    }

    private static FeeScheduleSourceType sourceType(FeeScheduleSourceType sourceType) {
        return sourceType == null ? FeeScheduleSourceType.VIP : sourceType;
    }

    private static FeeTierQualificationMode qualificationMode(FeeTierQualificationMode qualificationMode) {
        return qualificationMode == null ? FeeTierQualificationMode.VOLUME_OR_BALANCE : qualificationMode;
    }

    private static FeeScheduleStatus status(FeeScheduleStatus status) {
        return status == null ? FeeScheduleStatus.ACTIVE : status;
    }

    private static ProductLine productLine(ProductLine productLine) {
        return productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
    }

    private static String productLineName(ProductLine productLine) {
        return productLine(productLine).name();
    }

    private static String productLineExpression(String instrumentAlias) {
        return ProductLineSql.contractTypeProductLineCase(instrumentAlias + ".contract_type");
    }

    private static void validateFeeRate(long feeRatePpm, String field) {
        if (feeRatePpm < -MAX_ABS_FEE_RATE_PPM || feeRatePpm > MAX_ABS_FEE_RATE_PPM) {
            throw new IllegalArgumentException(field + " must be within +/- 100%");
        }
    }

    private static void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + " affected " + rows + " rows");
        }
    }

    private static long number(Number number) {
        return number == null ? 0L : number.longValue();
    }

    private static String stringOrNull(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return rs.wasNull() ? null : value;
    }

    private static FeeScheduleSourceType sourceTypeOrNull(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return rs.wasNull() ? null : FeeScheduleSourceType.valueOf(value);
    }

    private static TierSortSpec parseTierSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return TIER_PRIORITY_DESC;
        }
        String normalized = sort.trim();
        return TIER_SORTS.stream()
                .filter(spec -> spec.token().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported sort: " + sort));
    }

    private static String tierSeekCondition(TierSortSpec sort, TierCursor cursor) {
        if (cursor == null) {
            return "";
        }
        String operator = sort.numberOperator();
        return """
                   AND (priority %1$s ?
                        OR (priority = ? AND min_30d_volume_units %1$s ?)
                        OR (priority = ? AND min_30d_volume_units = ? AND min_asset_balance_units %1$s ?)
                        OR (priority = ? AND min_30d_volume_units = ? AND min_asset_balance_units = ?
                            AND tier_code > ?))
                """.formatted(operator);
    }

    private static void addTierCursorArgs(List<Object> args, TierCursor cursor) {
        if (cursor == null) {
            return;
        }
        args.add(cursor.priority());
        args.add(cursor.priority());
        args.add(cursor.min30dVolumeUnits());
        args.add(cursor.priority());
        args.add(cursor.min30dVolumeUnits());
        args.add(cursor.minAssetBalanceUnits());
        args.add(cursor.priority());
        args.add(cursor.min30dVolumeUnits());
        args.add(cursor.minAssetBalanceUnits());
        args.add(cursor.tierCode());
    }

    private static TierCursor decodeTierCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 4);
            if (parts.length != 4) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return new TierCursor(Integer.parseInt(parts[0]), Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]), normalizeTierCode(parts[3]));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid cursor", ex);
        }
    }

    private static String encodeTierCursor(FeeTierResponse tier) {
        String raw = tier.priority() + ":" + tier.min30dVolumeUnits() + ":"
                + tier.minAssetBalanceUnits() + ":" + tier.tierCode();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private record TierSortSpec(String token, boolean descending) {
        String numberOperator() {
            return descending ? "<" : ">";
        }

        String numberDirectionSql() {
            return descending ? "DESC" : "ASC";
        }

        String orderBy() {
            return "priority " + numberDirectionSql()
                    + ", min_30d_volume_units " + numberDirectionSql()
                    + ", min_asset_balance_units " + numberDirectionSql()
                    + ", tier_code ASC";
        }
    }

    private record TierCursor(
            int priority,
            long min30dVolumeUnits,
            long minAssetBalanceUnits,
            String tierCode) {
    }

    public record FeeTierMetrics(
            long trailing30dVolumeUnits,
            long totalAssetBalanceUnits) {
    }

    public record FeeTierAssignmentRecord(
            ProductLine productLine,
            long userId,
            String tierCode,
            FeeScheduleSourceType sourceType,
            long feeScheduleId,
            long makerFeeRatePpm,
            long takerFeeRatePpm,
            long trailing30dVolumeUnits,
            long totalAssetBalanceUnits,
            FeeScheduleStatus status,
            Instant effectiveTime,
            Instant calculatedAt) {

        public FeeTierAssignmentRecord(long userId,
                                       String tierCode,
                                       FeeScheduleSourceType sourceType,
                                       long feeScheduleId,
                                       long makerFeeRatePpm,
                                       long takerFeeRatePpm,
                                       long trailing30dVolumeUnits,
                                       long totalAssetBalanceUnits,
                                       FeeScheduleStatus status,
                                       Instant effectiveTime,
                                       Instant calculatedAt) {
            this(ProductLine.LINEAR_PERPETUAL, userId, tierCode, sourceType, feeScheduleId, makerFeeRatePpm,
                    takerFeeRatePpm, trailing30dVolumeUnits, totalAssetBalanceUnits, status, effectiveTime,
                    calculatedAt);
        }

        public FeeTierAssignmentResponse toResponse() {
            return new FeeTierAssignmentResponse(productLine, userId, tierCode, sourceType, feeScheduleId,
                    makerFeeRatePpm, takerFeeRatePpm, trailing30dVolumeUnits, totalAssetBalanceUnits, status,
                    effectiveTime, calculatedAt);
        }
    }
}
