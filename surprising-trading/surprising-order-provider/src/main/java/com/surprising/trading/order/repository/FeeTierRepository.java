package com.surprising.trading.order.repository;

import com.surprising.trading.api.model.FeeScheduleSourceType;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeTierAssignmentResponse;
import com.surprising.trading.api.model.FeeTierQualificationMode;
import com.surprising.trading.api.model.FeeTierQueryResponse;
import com.surprising.trading.api.model.FeeTierResponse;
import com.surprising.trading.api.model.FeeTierUpsertRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FeeTierRepository {

    private static final long MAX_ABS_FEE_RATE_PPM = 1_000_000L;

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
        int normalizedLimit = Math.max(1, Math.min(limit, 500));
        String statusName = status == null ? null : status.name();
        List<FeeTierResponse> tiers = jdbcTemplate.query("""
                SELECT *
                  FROM trading_fee_tiers
                 WHERE (? IS NULL OR status = ?)
                 ORDER BY priority DESC, min_30d_volume_units DESC, min_asset_balance_units DESC, tier_code ASC
                 LIMIT ?
                """, (rs, rowNum) -> toTierResponse(rs), statusName, statusName, normalizedLimit);
        return new FeeTierQueryResponse(tiers.size(), tiers);
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
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        long trailingVolume = number(jdbcTemplate.queryForObject("""
                WITH user_fills AS (
                    SELECT taker_user_id AS user_id, symbol, taker_instrument_version AS instrument_version,
                           price_ticks, quantity_steps, event_time
                      FROM trading_match_trades
                     WHERE taker_user_id = ?
                       AND event_time >= ?
                    UNION ALL
                    SELECT maker_user_id AS user_id, symbol, maker_instrument_version AS instrument_version,
                           price_ticks, quantity_steps, event_time
                      FROM trading_match_trades
                     WHERE maker_user_id = ?
                       AND event_time >= ?
                ),
                filled_notional AS (
                    SELECT CASE
                               WHEN i.contract_type IN ('LINEAR_PERPETUAL', 'SPOT')
                               THEN t.price_ticks::numeric * t.quantity_steps::numeric
                                    * i.notional_multiplier_units::numeric
                               ELSE t.quantity_steps::numeric * i.notional_multiplier_units::numeric
                           END AS notional_units
                      FROM user_fills t
                      JOIN instruments i
                        ON i.symbol = t.symbol AND i.version = t.instrument_version
                )
                SELECT LEAST(COALESCE(SUM(notional_units), 0), 9223372036854775807)::bigint
                  FROM filled_notional
                """, Number.class, userId, Timestamp.from(since), userId, Timestamp.from(since)));
        long assetBalance = number(jdbcTemplate.queryForObject("""
                WITH valued_balances AS (
                    SELECT CASE
                               WHEN b.asset IN ('USDT', 'USD')
                               THEN (b.available_units + b.locked_units)::numeric
                               ELSE COALESCE(
                                   (b.available_units + b.locked_units)::numeric
                                   * pm.mark_price_units::numeric / s.scale_units::numeric,
                                   0)
                           END AS value_units
                      FROM account_balances b
                      JOIN account_asset_scales s
                        ON s.asset = b.asset
                 LEFT JOIN LATERAL (
                           SELECT m.mark_price_units
                             FROM instrument_current_versions c
                             JOIN instruments i
                               ON i.symbol = c.symbol AND i.version = c.version
                             JOIN price_mark_ticks m
                               ON m.symbol = i.symbol
                            WHERE i.base_asset = b.asset
                              AND i.quote_asset IN ('USDT', 'USD')
                              AND i.status = 'TRADING'
                            ORDER BY CASE WHEN i.quote_asset = 'USDT' THEN 0 ELSE 1 END,
                                     m.event_time DESC
                            LIMIT 1
                       ) pm ON TRUE
                     WHERE b.user_id = ?
                )
                SELECT LEAST(COALESCE(SUM(value_units), 0), 9223372036854775807)::bigint
                  FROM valued_balances
                """, Number.class, userId));
        return new FeeTierMetrics(trailingVolume, assetBalance);
    }

    public List<Long> candidateUsers(Instant since, int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, 10_000));
        return jdbcTemplate.query("""
                SELECT user_id
                  FROM (
                        SELECT taker_user_id AS user_id
                          FROM trading_match_trades
                         WHERE event_time >= ?
                        UNION
                        SELECT maker_user_id AS user_id
                          FROM trading_match_trades
                         WHERE event_time >= ?
                        UNION
                        SELECT user_id
                          FROM account_balances
                         WHERE available_units + locked_units > 0
                        UNION
                        SELECT user_id
                          FROM trading_user_fee_tiers
                         WHERE status = 'ACTIVE'
                       ) u
                 WHERE user_id > 0
                 ORDER BY user_id ASC
                 LIMIT ?
                """, (rs, rowNum) -> rs.getLong("user_id"), Timestamp.from(since), Timestamp.from(since),
                normalizedLimit);
    }

    public FeeTierAssignmentRecord lockAssignment(long userId, long proposedFeeScheduleId, Instant now) {
        if (userId <= 0 || proposedFeeScheduleId <= 0) {
            throw new IllegalArgumentException("userId and feeScheduleId must be positive");
        }
        jdbcTemplate.update("""
                INSERT INTO trading_user_fee_tiers (
                    user_id, fee_schedule_id, maker_fee_rate_ppm, taker_fee_rate_ppm,
                    trailing_30d_volume_units, total_asset_balance_units, status,
                    effective_time, calculated_at, created_at, updated_at
                ) VALUES (?, ?, 0, 0, 0, 0, 'DISABLED', ?, ?, ?, ?)
                ON CONFLICT (user_id) DO NOTHING
                """, userId, proposedFeeScheduleId, Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now));
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_user_fee_tiers
                 WHERE user_id = ?
                 FOR UPDATE
                """, (rs, rowNum) -> toAssignmentRecord(rs), userId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("fee tier assignment row missing: " + userId));
    }

    public void activateAssignment(long userId,
                                   FeeTierResponse tier,
                                   long feeScheduleId,
                                   FeeTierMetrics metrics,
                                   Instant effectiveTime,
                                   Instant now) {
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
                 WHERE user_id = ?
                   AND fee_schedule_id = ?
                """, tier.tierCode(), tier.sourceType().name(), tier.makerFeeRatePpm(), tier.takerFeeRatePpm(),
                metrics.trailing30dVolumeUnits(), metrics.totalAssetBalanceUnits(), Timestamp.from(effectiveTime),
                Timestamp.from(now), Timestamp.from(now), userId, feeScheduleId);
        requireSingleRow(rows, "fee tier assignment activation");
    }

    public void disableAssignment(long userId, FeeTierMetrics metrics, Instant now) {
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
                 WHERE user_id = ?
                """, metrics.trailing30dVolumeUnits(), metrics.totalAssetBalanceUnits(), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now), userId);
        requireSingleRow(rows, "fee tier assignment disable");
    }

    public Optional<FeeTierAssignmentResponse> currentAssignment(long userId) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_user_fee_tiers
                 WHERE user_id = ?
                """, (rs, rowNum) -> toAssignmentResponse(rs), userId).stream().findFirst();
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

    public record FeeTierMetrics(
            long trailing30dVolumeUnits,
            long totalAssetBalanceUnits) {
    }

    public record FeeTierAssignmentRecord(
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

        public FeeTierAssignmentResponse toResponse() {
            return new FeeTierAssignmentResponse(userId, tierCode, sourceType, feeScheduleId, makerFeeRatePpm,
                    takerFeeRatePpm, trailing30dVolumeUnits, totalAssetBalanceUnits, status, effectiveTime,
                    calculatedAt);
        }
    }
}
