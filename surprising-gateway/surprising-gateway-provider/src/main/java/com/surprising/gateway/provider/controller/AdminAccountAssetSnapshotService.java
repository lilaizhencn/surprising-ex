package com.surprising.gateway.provider.controller;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdminAccountAssetSnapshotService {

    private static final int MAX_SNAPSHOT_LIMIT = 1000;
    private static final SnapshotSort DEFAULT_SNAPSHOT_SORT = new SnapshotSort(true);

    private final JdbcTemplate jdbcTemplate;

    public AdminAccountAssetSnapshotService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int writeSnapshot(LocalDate snapshotDate,
                             String valuationAsset,
                             Long createdByUserId,
                             String createdByUsername) {
        try {
            return jdbcTemplate.update("""
                    WITH balances AS (
                        SELECT 'BASIC' AS account_type,
                               b.user_id,
                               b.asset,
                               b.available_units,
                               b.locked_units,
                               b.available_units + b.locked_units - COALESCE(d.deficit_units, 0) AS equity_units,
                               b.updated_at
                          FROM account_balances b
                          LEFT JOIN account_deficits d USING (user_id, asset)
                        UNION ALL
                        SELECT b.account_type,
                               b.user_id,
                               b.asset,
                               b.available_units,
                               b.locked_units,
                               b.available_units + b.locked_units - COALESCE(d.deficit_units, 0) AS equity_units,
                               b.updated_at
                          FROM account_product_balances b
                          LEFT JOIN account_product_deficits d USING (account_type, user_id, asset)
                    ),
                    aggregates AS (
                        SELECT account_type,
                               asset,
                               SUM(available_units)::numeric AS total_available_units,
                               SUM(locked_units)::numeric AS total_locked_units,
                               SUM(equity_units)::numeric AS total_equity_units,
                               COUNT(DISTINCT user_id) AS user_count,
                               COUNT(*) AS balance_count,
                               MAX(updated_at) AS source_updated_at
                          FROM balances
                         GROUP BY account_type, asset
                        HAVING SUM(available_units) <> 0 OR SUM(locked_units) <> 0 OR SUM(equity_units) <> 0
                    ),
                    priced AS (
                        SELECT a.*,
                               COALESCE(s.scale_units, 1) AS scale_units,
                               CASE
                                   WHEN a.asset = ? THEN 1::numeric
                                   WHEN direct_rate.rate IS NOT NULL THEN direct_rate.rate
                                   WHEN inverse_rate.rate IS NOT NULL THEN 1 / inverse_rate.rate
                                   ELSE NULL
                               END AS valuation_rate,
                               CASE
                                   WHEN a.asset = ? THEN 'PAR'
                                   WHEN direct_rate.rate IS NOT NULL THEN direct_rate.provider
                                   WHEN inverse_rate.rate IS NOT NULL THEN inverse_rate.provider || ':INVERSE'
                                   ELSE 'MISSING'
                               END AS valuation_source,
                               CASE
                                   WHEN a.asset = ? THEN a.source_updated_at
                                   ELSE COALESCE(direct_rate.updated_at, inverse_rate.updated_at)
                               END AS rate_updated_at
                          FROM aggregates a
                          LEFT JOIN account_asset_scales s ON s.asset = a.asset
                          LEFT JOIN price_exchange_rates direct_rate
                            ON direct_rate.base_currency = a.asset
                           AND direct_rate.quote_currency = ?
                          LEFT JOIN price_exchange_rates inverse_rate
                            ON inverse_rate.base_currency = ?
                           AND inverse_rate.quote_currency = a.asset
                    )
                    INSERT INTO gateway_admin_account_asset_snapshots (
                        snapshot_date, valuation_asset, account_type, asset, total_available_units,
                        total_locked_units, total_equity_units, valuation_rate, total_value, valuation_source,
                        rate_updated_at, source_updated_at, user_count, balance_count, created_by_user_id,
                        created_by_username, created_at
                    )
                    SELECT ?, ?, account_type, asset, total_available_units, total_locked_units, total_equity_units,
                           valuation_rate,
                           CASE
                               WHEN valuation_rate IS NULL THEN NULL
                               ELSE (total_equity_units / scale_units::numeric) * valuation_rate
                           END,
                           valuation_source, rate_updated_at, source_updated_at, user_count, balance_count,
                           ?, ?, now()
                      FROM priced
                    ON CONFLICT (snapshot_date, valuation_asset, account_type, asset) DO UPDATE
                       SET total_available_units = EXCLUDED.total_available_units,
                           total_locked_units = EXCLUDED.total_locked_units,
                           total_equity_units = EXCLUDED.total_equity_units,
                           valuation_rate = EXCLUDED.valuation_rate,
                           total_value = EXCLUDED.total_value,
                           valuation_source = EXCLUDED.valuation_source,
                           rate_updated_at = EXCLUDED.rate_updated_at,
                           source_updated_at = EXCLUDED.source_updated_at,
                           user_count = EXCLUDED.user_count,
                           balance_count = EXCLUDED.balance_count,
                           created_by_user_id = EXCLUDED.created_by_user_id,
                           created_by_username = EXCLUDED.created_by_username,
                           created_at = EXCLUDED.created_at
                    """, valuationAsset, valuationAsset, valuationAsset, valuationAsset, valuationAsset,
                    Date.valueOf(snapshotDate), valuationAsset, createdByUserId, createdByUsername);
        } catch (DataAccessException ex) {
            throw new IllegalStateException("failed to write account asset snapshot: " + ex.getMessage(), ex);
        }
    }

    public boolean snapshotExists(LocalDate snapshotDate, String valuationAsset) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM gateway_admin_account_asset_snapshots
                 WHERE snapshot_date = ?
                   AND valuation_asset = ?
                """, Long.class, Date.valueOf(snapshotDate), valuationAsset);
        return count != null && count > 0;
    }

    public List<AdminAccountAssetReportController.AccountAssetSnapshotRow> snapshotRows(LocalDate snapshotDate,
                                                                                        String valuationAsset,
                                                                                        String accountType,
                                                                                        String asset,
                                                                                        int limit) {
        return snapshotRowsPage(snapshotDate, valuationAsset, accountType, asset, limit, null, null).items();
    }

    public SnapshotPage snapshotRowsPage(LocalDate snapshotDate,
                                         String valuationAsset,
                                         String accountType,
                                         String asset,
                                         int limit,
                                         String cursor,
                                         String sort) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_SNAPSHOT_LIMIT));
        SnapshotSort snapshotSort = parseSnapshotSort(sort);
        SnapshotCursor decodedCursor = decodeSnapshotCursor(cursor);
        try {
            List<Object> args = new ArrayList<>();
            args.add(snapshotDate == null ? null : Date.valueOf(snapshotDate));
            args.add(snapshotDate == null ? null : Date.valueOf(snapshotDate));
            args.add(valuationAsset);
            args.add(valuationAsset);
            args.add(accountType);
            args.add(accountType);
            args.add(asset);
            args.add(asset);
            StringBuilder sql = new StringBuilder("""
                    SELECT snapshot_id, snapshot_date, valuation_asset, account_type, asset,
                           total_available_units, total_locked_units, total_equity_units,
                           valuation_rate, total_value, valuation_source, rate_updated_at, source_updated_at,
                           user_count, balance_count, created_by_user_id, created_by_username, created_at
                      FROM gateway_admin_account_asset_snapshots
                     WHERE (CAST(? AS text) IS NULL OR snapshot_date = ?)
                       AND (CAST(? AS text) IS NULL OR valuation_asset = ?)
                       AND (CAST(? AS text) IS NULL OR account_type = ?)
                       AND (CAST(? AS text) IS NULL OR asset = ?)
                    """);
            appendSnapshotCursorCondition(sql, args, decodedCursor, snapshotSort);
            sql.append("""
                     ORDER BY snapshot_date %s, total_value %s NULLS LAST, snapshot_id %s
                     LIMIT ?
                    """.formatted(snapshotSort.directionSql(), snapshotSort.directionSql(),
                    snapshotSort.directionSql()));
            args.add(safeLimit + 1);
            List<AdminAccountAssetReportController.AccountAssetSnapshotRow> fetchedRows = jdbcTemplate
                    .queryForList(sql.toString(), args.toArray())
                    .stream()
                    .map(this::toSnapshotRow)
                    .toList();
            boolean hasMore = fetchedRows.size() > safeLimit;
            List<AdminAccountAssetReportController.AccountAssetSnapshotRow> rows = hasMore
                    ? List.copyOf(fetchedRows.subList(0, safeLimit))
                    : List.copyOf(fetchedRows);
            String nextCursor = null;
            if (hasMore && !rows.isEmpty()) {
                nextCursor = encodeSnapshotCursor(rows.get(rows.size() - 1));
            }
            return new SnapshotPage(rows, nextCursor, hasMore, snapshotSort.token(), safeLimit);
        } catch (DataAccessException ex) {
            throw new IllegalStateException("failed to load account asset snapshots: " + ex.getMessage(), ex);
        }
    }

    public List<AccountAssetSnapshotDiscrepancy> discrepancies(LocalDate currentDate,
                                                               LocalDate previousDate,
                                                               String valuationAsset,
                                                               long thresholdPpm,
                                                               BigDecimal minAbsoluteValue,
                                                               int limit) {
        BigDecimal minAbs = minAbsoluteValue == null ? BigDecimal.ZERO : minAbsoluteValue.abs();
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return jdbcTemplate.queryForList("""
                WITH current_rows AS (
                    SELECT account_type, asset, total_value
                      FROM gateway_admin_account_asset_snapshots
                     WHERE snapshot_date = ?
                       AND valuation_asset = ?
                ),
                previous_rows AS (
                    SELECT account_type, asset, total_value
                      FROM gateway_admin_account_asset_snapshots
                     WHERE snapshot_date = ?
                       AND valuation_asset = ?
                ),
                joined AS (
                    SELECT COALESCE(c.account_type, p.account_type) AS account_type,
                           COALESCE(c.asset, p.asset) AS asset,
                           c.total_value AS current_value,
                           p.total_value AS previous_value
                      FROM current_rows c
                      FULL OUTER JOIN previous_rows p
                        ON p.account_type = c.account_type
                       AND p.asset = c.asset
                ),
                calculated AS (
                    SELECT account_type,
                           asset,
                           current_value,
                           previous_value,
                           COALESCE(current_value, 0) - COALESCE(previous_value, 0) AS diff_value,
                           ABS(COALESCE(current_value, 0) - COALESCE(previous_value, 0)) AS abs_diff_value,
                           CASE
                               WHEN previous_value IS NULL OR previous_value = 0 THEN
                                   CASE WHEN current_value IS NULL OR current_value = 0 THEN 0 ELSE 1000000 END
                               ELSE ROUND(ABS(COALESCE(current_value, 0) - previous_value) * 1000000.0 / ABS(previous_value))
                           END AS diff_ppm
                      FROM joined
                )
                SELECT account_type, asset, current_value, previous_value, diff_value, diff_ppm
                  FROM calculated
                 WHERE current_value IS NULL
                    OR previous_value IS NULL
                    OR abs_diff_value >= ?
                    OR diff_ppm >= ?
                 ORDER BY diff_ppm DESC, abs_diff_value DESC, account_type, asset
                 LIMIT ?
                """, Date.valueOf(currentDate), valuationAsset,
                Date.valueOf(previousDate), valuationAsset,
                minAbs, thresholdPpm, safeLimit).stream()
                .map(row -> new AccountAssetSnapshotDiscrepancy(
                        stringValue(row.get("account_type")),
                        stringValue(row.get("asset")),
                        decimalValue(row.get("current_value")),
                        decimalValue(row.get("previous_value")),
                        decimalValue(row.get("diff_value")),
                        longValue(row.get("diff_ppm"))))
                .toList();
    }

    private AdminAccountAssetReportController.AccountAssetSnapshotRow toSnapshotRow(Map<String, Object> row) {
        return new AdminAccountAssetReportController.AccountAssetSnapshotRow(
                longValue(row.get("snapshot_id")),
                dateValue(row.get("snapshot_date")),
                stringValue(row.get("valuation_asset")),
                stringValue(row.get("account_type")),
                stringValue(row.get("asset")),
                decimalValue(row.get("total_available_units")),
                decimalValue(row.get("total_locked_units")),
                decimalValue(row.get("total_equity_units")),
                decimalValue(row.get("valuation_rate")),
                decimalValue(row.get("total_value")),
                stringValue(row.get("valuation_source")),
                instantValue(row.get("rate_updated_at")),
                instantValue(row.get("source_updated_at")),
                longValue(row.get("user_count")),
                longValue(row.get("balance_count")),
                nullableLong(row.get("created_by_user_id")),
                stringValue(row.get("created_by_username")),
                instantValue(row.get("created_at")));
    }

    private SnapshotSort parseSnapshotSort(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_SNAPSHOT_SORT;
        }
        return switch (value.trim()) {
            case "snapshotDate.desc" -> DEFAULT_SNAPSHOT_SORT;
            case "snapshotDate.asc" -> new SnapshotSort(false);
            default -> throw new IllegalArgumentException("unsupported sort: " + value);
        };
    }

    private void appendSnapshotCursorCondition(StringBuilder sql,
                                               List<Object> args,
                                               SnapshotCursor cursor,
                                               SnapshotSort sort) {
        if (cursor == null) {
            return;
        }
        String operator = sort.descending() ? "<" : ">";
        String valueCondition = sort.descending()
                ? "total_value IS NULL OR total_value < ? OR (total_value = ? AND snapshot_id < ?)"
                : "total_value > ? OR (total_value = ? AND snapshot_id > ?) OR total_value IS NULL";
        sql.append("""
                       AND (
                            snapshot_date %s ?
                         OR (snapshot_date = ? AND (
                                (? = FALSE AND (%s))
                             OR (? = TRUE AND total_value IS NULL AND snapshot_id %s ?)
                            ))
                       )
                    """.formatted(operator, valueCondition, operator));
        args.add(Date.valueOf(cursor.snapshotDate()));
        args.add(Date.valueOf(cursor.snapshotDate()));
        boolean valueIsNull = cursor.totalValue() == null;
        args.add(valueIsNull);
        args.add(cursor.totalValue());
        args.add(cursor.totalValue());
        args.add(cursor.snapshotId());
        args.add(valueIsNull);
        args.add(cursor.snapshotId());
    }

    private String encodeSnapshotCursor(AdminAccountAssetReportController.AccountAssetSnapshotRow row) {
        if (row.snapshotDate() == null) {
            throw new IllegalArgumentException("cursor snapshotDate is required");
        }
        String value = row.totalValue() == null ? "" : row.totalValue().toPlainString();
        String raw = row.snapshotDate() + "|" + value + "|" + row.snapshotId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private SnapshotCursor decodeSnapshotCursor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value.trim()), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("invalid cursor");
            }
            LocalDate snapshotDate = LocalDate.parse(parts[0]);
            BigDecimal totalValue = parts[1].isBlank() ? null : new BigDecimal(parts[1]);
            long snapshotId = Long.parseLong(parts[2]);
            return new SnapshotCursor(snapshotDate, totalValue, snapshotId);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid cursor", ex);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long longValue(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private Long nullableLong(Object value) {
        return value == null ? null : longValue(value);
    }

    private BigDecimal decimalValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            if (number instanceof Float || number instanceof Double) {
                return BigDecimal.valueOf(number.doubleValue());
            }
            return BigDecimal.valueOf(number.longValue());
        }
        return new BigDecimal(value.toString());
    }

    private Instant instantValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return Instant.parse(value.toString());
    }

    private LocalDate dateValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        return LocalDate.parse(value.toString());
    }

    public record AccountAssetSnapshotDiscrepancy(
            String accountType,
            String asset,
            BigDecimal currentValue,
            BigDecimal previousValue,
            BigDecimal diffValue,
            long diffPpm) {
    }

    public record SnapshotPage(
            List<AdminAccountAssetReportController.AccountAssetSnapshotRow> items,
            String nextCursor,
            boolean hasMore,
            String sort,
            int limit) {
    }

    private record SnapshotCursor(LocalDate snapshotDate, BigDecimal totalValue, long snapshotId) {
    }

    private record SnapshotSort(boolean descending) {
        String token() {
            return "snapshotDate." + (descending ? "desc" : "asc");
        }

        String directionSql() {
            return descending ? "DESC" : "ASC";
        }
    }
}
