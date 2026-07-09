package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/reports/account-assets")
public class AdminAccountAssetReportController {

    private static final ValuationSort DEFAULT_VALUATION_SORT = new ValuationSort(true);

    private final AuthService authService;
    private final JdbcTemplate jdbcTemplate;
    private final AdminAccountAssetSnapshotService snapshotService;

    public AdminAccountAssetReportController(AuthService authService,
                                             JdbcTemplate jdbcTemplate,
                                             AdminAccountAssetSnapshotService snapshotService) {
        this.authService = authService;
        this.jdbcTemplate = jdbcTemplate;
        this.snapshotService = snapshotService;
    }

    @GetMapping("/valuation")
    public AccountAssetValuationResponse valuation(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                                   @RequestParam(value = "valuationAsset", defaultValue = "USDT")
                                                   String valuationAsset,
                                                   @RequestParam(value = "userId", required = false) Long userId,
                                                   @RequestParam(value = "accountType", required = false)
                                                   String accountType,
                                                   @RequestParam(value = "asset", required = false) String asset,
                                                   @RequestParam(value = "nonZeroOnly", defaultValue = "true")
                                                   boolean nonZeroOnly,
                                                   @RequestParam(value = "limit", defaultValue = "200") int limit,
                                                   @RequestParam(value = "cursor", required = false)
                                                   String cursor,
                                                   @RequestParam(value = "sort", defaultValue = "valuationValue.desc")
                                                   String sort) {
        try {
            authService.requireAdminPermission(authorization, "admin.reports.read");
            String normalizedValuationAsset = normalizeAsset(valuationAsset, "valuationAsset");
            String normalizedAccountType = normalizeOptionalAccountType(accountType);
            String normalizedAsset = normalizeOptionalAsset(asset);
            int boundedLimit = Math.max(1, Math.min(limit, 1000));
            AccountAssetValuationPage page = valuationRowsPage(normalizedValuationAsset, userId,
                    normalizedAccountType, normalizedAsset, nonZeroOnly, boundedLimit, cursor, sort);
            List<AccountAssetValuationRow> rows = page.rows();
            List<AccountAssetReportWarning> warnings = valuationWarnings(rows);
            return new AccountAssetValuationResponse(
                    Instant.now(),
                    normalizedValuationAsset,
                    new AccountAssetValuationTotals(rows.size(), uniqueUsers(rows), pricedRows(rows),
                            missingRateRows(rows), totalValue(rows)),
                    rows,
                    warnings,
                    page.nextCursor(),
                    page.hasMore(),
                    page.sort(),
                    page.limit());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/snapshots")
    public AccountAssetSnapshotGenerationResponse createSnapshot(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestParam(value = "valuationAsset", defaultValue = "USDT") String valuationAsset,
            @RequestParam(value = "snapshotDate", required = false) String snapshotDate) {
        try {
            JwtPrincipal principal = authService.requireAdminPermission(authorization, "admin.reports.write");
            String normalizedValuationAsset = normalizeAsset(valuationAsset, "valuationAsset");
            LocalDate normalizedDate = normalizeSnapshotDate(snapshotDate);
            int writtenRows = snapshotService.writeSnapshot(normalizedDate, normalizedValuationAsset,
                    principal.userId(), principal.username());
            var snapshots = snapshotService.snapshotRows(normalizedDate, normalizedValuationAsset, null, null, 1000);
            return new AccountAssetSnapshotGenerationResponse(normalizedDate, normalizedValuationAsset,
                    writtenRows, snapshots);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/snapshots")
    public AccountAssetSnapshotQueryResponse snapshots(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                                       @RequestParam(value = "snapshotDate", required = false)
                                                       String snapshotDate,
                                                       @RequestParam(value = "valuationAsset", required = false)
                                                       String valuationAsset,
                                                       @RequestParam(value = "accountType", required = false)
                                                       String accountType,
                                                       @RequestParam(value = "asset", required = false) String asset,
                                                       @RequestParam(value = "limit", defaultValue = "200")
                                                       int limit,
                                                       @RequestParam(value = "cursor", required = false)
                                                       String cursor,
                                                       @RequestParam(value = "sort", defaultValue = "snapshotDate.desc")
                                                       String sort) {
        try {
            authService.requireAdminPermission(authorization, "admin.reports.read");
            LocalDate normalizedDate = snapshotDate == null || snapshotDate.isBlank()
                    ? null
                    : normalizeSnapshotDate(snapshotDate);
            String normalizedValuationAsset = valuationAsset == null || valuationAsset.isBlank()
                    ? null
                    : normalizeAsset(valuationAsset, "valuationAsset");
            String normalizedAccountType = normalizeOptionalAccountType(accountType);
            String normalizedAsset = normalizeOptionalAsset(asset);
            int boundedLimit = Math.max(1, Math.min(limit, 1000));
            var page = snapshotService.snapshotRowsPage(normalizedDate, normalizedValuationAsset,
                    normalizedAccountType, normalizedAsset, boundedLimit, cursor, sort);
            return new AccountAssetSnapshotQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                    page.hasMore(), page.sort(), page.limit());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private List<AccountAssetValuationRow> valuationRows(String valuationAsset,
                                                         Long userId,
                                                         String accountType,
                                                         String asset,
                                                         boolean nonZeroOnly,
                                                         int limit) {
        return valuationRowsPage(valuationAsset, userId, accountType, asset, nonZeroOnly, limit, null, null).rows();
    }

    private AccountAssetValuationPage valuationRowsPage(String valuationAsset,
                                                        Long userId,
                                                        String accountType,
                                                        String asset,
                                                        boolean nonZeroOnly,
                                                        int limit,
                                                        String cursor,
                                                        String sort) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        ValuationSort valuationSort = parseValuationSort(sort);
        ValuationCursor decodedCursor = decodeValuationCursor(cursor);
        try {
            List<Object> args = new ArrayList<>();
            args.add(valuationAsset);
            args.add(valuationAsset);
            args.add(valuationAsset);
            args.add(valuationAsset);
            args.add(valuationAsset);
            args.add(userId);
            args.add(userId);
            args.add(accountType);
            args.add(accountType);
            args.add(asset);
            args.add(asset);
            args.add(nonZeroOnly);
            args.add(nonZeroOnly);
            StringBuilder sql = new StringBuilder("""
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
                    priced AS (
                        SELECT b.*,
                               COALESCE(s.scale_units, 1) AS scale_units,
                               CASE
                                   WHEN b.asset = ? THEN 1::numeric
                                   WHEN direct_rate.rate IS NOT NULL THEN direct_rate.rate
                                   WHEN inverse_rate.rate IS NOT NULL THEN 1 / inverse_rate.rate
                                   ELSE NULL
                               END AS valuation_rate,
                               CASE
                                   WHEN b.asset = ? THEN 'PAR'
                                   WHEN direct_rate.rate IS NOT NULL THEN direct_rate.provider
                                   WHEN inverse_rate.rate IS NOT NULL THEN inverse_rate.provider || ':INVERSE'
                                   ELSE 'MISSING'
                               END AS valuation_source,
                               CASE
                                   WHEN b.asset = ? THEN b.updated_at
                                   ELSE COALESCE(direct_rate.updated_at, inverse_rate.updated_at)
                               END AS rate_updated_at
                          FROM balances b
                          LEFT JOIN account_asset_scales s ON s.asset = b.asset
                          LEFT JOIN price_exchange_rates direct_rate
                            ON direct_rate.base_currency = b.asset
                           AND direct_rate.quote_currency = ?
                          LEFT JOIN price_exchange_rates inverse_rate
                            ON inverse_rate.base_currency = ?
                           AND inverse_rate.quote_currency = b.asset
                    )
                    SELECT account_type, user_id, asset, available_units, locked_units, equity_units,
                           scale_units, valuation_rate, valuation_source, rate_updated_at,
                           CASE
                               WHEN valuation_rate IS NULL THEN NULL
                               ELSE (equity_units::numeric / scale_units::numeric) * valuation_rate
                           END AS valuation_value,
                           updated_at
                      FROM priced
                     WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                       AND (CAST(? AS text) IS NULL OR account_type = ?)
                       AND (CAST(? AS text) IS NULL OR asset = ?)
                       AND (CAST(? AS text) IS NULL OR ? = FALSE OR available_units <> 0 OR locked_units <> 0 OR equity_units <> 0)
                    """);
            appendValuationCursorCondition(sql, args, decodedCursor, valuationSort);
            sql.append("""
                     ORDER BY valuation_value %s NULLS LAST, updated_at %s, user_id %s, account_type ASC, asset ASC
                     LIMIT ?
                    """.formatted(valuationSort.directionSql(), valuationSort.directionSql(),
                    valuationSort.directionSql()));
            args.add(safeLimit + 1);
            List<AccountAssetValuationRow> fetchedRows = jdbcTemplate.queryForList(sql.toString(), args.toArray())
                    .stream()
                    .map(this::toValuationRow)
                    .toList();
            boolean hasMore = fetchedRows.size() > safeLimit;
            List<AccountAssetValuationRow> rows = hasMore
                    ? List.copyOf(fetchedRows.subList(0, safeLimit))
                    : List.copyOf(fetchedRows);
            String nextCursor = null;
            if (hasMore && !rows.isEmpty()) {
                nextCursor = encodeValuationCursor(rows.get(rows.size() - 1));
            }
            return new AccountAssetValuationPage(rows, nextCursor, hasMore, valuationSort.token(), safeLimit);
        } catch (DataAccessException ex) {
            throw new IllegalStateException("failed to load account asset valuation report: " + ex.getMessage(), ex);
        }
    }

    private AccountAssetValuationRow toValuationRow(Map<String, Object> row) {
        return new AccountAssetValuationRow(
                stringValue(row.get("account_type")),
                longValue(row.get("user_id")),
                stringValue(row.get("asset")),
                longValue(row.get("available_units")),
                longValue(row.get("locked_units")),
                longValue(row.get("equity_units")),
                longValue(row.get("scale_units")),
                decimalValue(row.get("valuation_rate")),
                decimalValue(row.get("valuation_value")),
                stringValue(row.get("valuation_source")),
                instantValue(row.get("rate_updated_at")),
                instantValue(row.get("updated_at")));
    }

    private ValuationSort parseValuationSort(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_VALUATION_SORT;
        }
        return switch (value.trim()) {
            case "valuationValue.desc" -> DEFAULT_VALUATION_SORT;
            case "valuationValue.asc" -> new ValuationSort(false);
            default -> throw new IllegalArgumentException("unsupported sort: " + value);
        };
    }

    private void appendValuationCursorCondition(StringBuilder sql,
                                                List<Object> args,
                                                ValuationCursor cursor,
                                                ValuationSort sort) {
        if (cursor == null) {
            return;
        }
        String valueCondition = sort.descending()
                ? "valuation_value IS NULL OR valuation_value < ? OR (valuation_value = ? AND ("
                + valuationTieCondition(sort) + "))"
                : "valuation_value > ? OR valuation_value IS NULL OR (valuation_value = ? AND ("
                + valuationTieCondition(sort) + "))";
        sql.append("""
                       AND (
                            (? = FALSE AND (%s))
                         OR (? = TRUE AND valuation_value IS NULL AND (%s))
                       )
                    """.formatted(valueCondition, valuationTieCondition(sort)));
        boolean valueIsNull = cursor.valuationValue() == null;
        args.add(valueIsNull);
        args.add(cursor.valuationValue());
        args.add(cursor.valuationValue());
        addValuationTieArgs(args, cursor);
        args.add(valueIsNull);
        addValuationTieArgs(args, cursor);
    }

    private String valuationTieCondition(ValuationSort sort) {
        String operator = sort.descending() ? "<" : ">";
        return """
                updated_at %s ?
             OR (updated_at = ? AND user_id %s ?)
             OR (updated_at = ? AND user_id = ? AND account_type > ?)
             OR (updated_at = ? AND user_id = ? AND account_type = ? AND asset > ?)
                """.formatted(operator, operator);
    }

    private void addValuationTieArgs(List<Object> args, ValuationCursor cursor) {
        Timestamp updatedAt = Timestamp.from(cursor.balanceUpdatedAt());
        args.add(updatedAt);
        args.add(updatedAt);
        args.add(cursor.userId());
        args.add(updatedAt);
        args.add(cursor.userId());
        args.add(cursor.accountType());
        args.add(updatedAt);
        args.add(cursor.userId());
        args.add(cursor.accountType());
        args.add(cursor.asset());
    }

    private String encodeValuationCursor(AccountAssetValuationRow row) {
        if (row.balanceUpdatedAt() == null) {
            throw new IllegalArgumentException("cursor balanceUpdatedAt is required");
        }
        String value = row.valuationValue() == null ? "" : row.valuationValue().toPlainString();
        String raw = value + "|" + row.balanceUpdatedAt() + "|" + row.userId() + "|"
                + row.accountType() + "|" + row.asset();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private ValuationCursor decodeValuationCursor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value.trim()), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 5) {
                throw new IllegalArgumentException("invalid cursor");
            }
            BigDecimal valuationValue = parts[0].isBlank() ? null : new BigDecimal(parts[0]);
            Instant balanceUpdatedAt = Instant.parse(parts[1]);
            long userId = Long.parseLong(parts[2]);
            String accountType = normalizeOptionalAccountType(parts[3]);
            if (accountType == null) {
                throw new IllegalArgumentException("invalid cursor accountType");
            }
            String asset = normalizeAsset(parts[4], "asset");
            return new ValuationCursor(valuationValue, balanceUpdatedAt, userId, accountType, asset);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid cursor", ex);
        }
    }

    private List<AccountAssetReportWarning> valuationWarnings(List<AccountAssetValuationRow> rows) {
        List<AccountAssetReportWarning> warnings = new ArrayList<>();
        long missing = missingRateRows(rows);
        if (missing > 0) {
            warnings.add(new AccountAssetReportWarning("valuation", missing
                    + " account asset rows have no exchange rate and are excluded from totalValue"));
        }
        return warnings;
    }

    private long uniqueUsers(List<AccountAssetValuationRow> rows) {
        Set<Long> userIds = new HashSet<>();
        rows.forEach(row -> userIds.add(row.userId()));
        return userIds.size();
    }

    private long pricedRows(List<AccountAssetValuationRow> rows) {
        return rows.stream().filter(row -> row.valuationRate() != null).count();
    }

    private long missingRateRows(List<AccountAssetValuationRow> rows) {
        return rows.stream().filter(row -> row.valuationRate() == null).count();
    }

    private BigDecimal totalValue(List<AccountAssetValuationRow> rows) {
        return rows.stream()
                .map(AccountAssetValuationRow::valuationValue)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String normalizeOptionalAsset(String asset) {
        return asset == null || asset.isBlank() ? null : normalizeAsset(asset, "asset");
    }

    private String normalizeAsset(String asset, String field) {
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = asset.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("invalid " + field + ": " + asset);
        }
        return normalized;
    }

    private String normalizeOptionalAccountType(String accountType) {
        if (accountType == null || accountType.isBlank()) {
            return null;
        }
        String normalized = accountType.trim().toUpperCase(Locale.ROOT);
        if (!List.of("BASIC", "FUNDING", "SPOT", "USDT_PERPETUAL", "COIN_PERPETUAL",
                "USDT_DELIVERY", "COIN_DELIVERY", "OPTION").contains(normalized)) {
            throw new IllegalArgumentException("invalid accountType: " + accountType);
        }
        return normalized;
    }

    private LocalDate normalizeSnapshotDate(String snapshotDate) {
        if (snapshotDate == null || snapshotDate.isBlank()) {
            return LocalDate.now(ZoneOffset.UTC);
        }
        try {
            return LocalDate.parse(snapshotDate.trim());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid snapshotDate: " + snapshotDate, ex);
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

    public record AccountAssetValuationResponse(
            Instant generatedAt,
            String valuationAsset,
            AccountAssetValuationTotals totals,
            List<AccountAssetValuationRow> rows,
            List<AccountAssetReportWarning> warnings,
            String nextCursor,
            boolean hasMore,
            String sort,
            int limit) {
        public AccountAssetValuationResponse(Instant generatedAt,
                                             String valuationAsset,
                                             AccountAssetValuationTotals totals,
                                             List<AccountAssetValuationRow> rows,
                                             List<AccountAssetReportWarning> warnings) {
            this(generatedAt, valuationAsset, totals, rows, warnings, null, false,
                    "valuationValue.desc", rows.size());
        }
    }

    public record AccountAssetValuationTotals(
            int rows,
            long uniqueUsers,
            long pricedRows,
            long missingRateRows,
            BigDecimal totalValue) {
    }

    public record AccountAssetValuationRow(
            String accountType,
            long userId,
            String asset,
            long availableUnits,
            long lockedUnits,
            long equityUnits,
            long scaleUnits,
            BigDecimal valuationRate,
            BigDecimal valuationValue,
            String valuationSource,
            Instant rateUpdatedAt,
            Instant balanceUpdatedAt) {
    }

    private record AccountAssetValuationPage(
            List<AccountAssetValuationRow> rows,
            String nextCursor,
            boolean hasMore,
            String sort,
            int limit) {
    }

    private record ValuationCursor(
            BigDecimal valuationValue,
            Instant balanceUpdatedAt,
            long userId,
            String accountType,
            String asset) {
    }

    private record ValuationSort(boolean descending) {
        String token() {
            return "valuationValue." + (descending ? "desc" : "asc");
        }

        String directionSql() {
            return descending ? "DESC" : "ASC";
        }
    }

    public record AccountAssetReportWarning(
            String area,
            String message) {
    }

    public record AccountAssetSnapshotGenerationResponse(
            LocalDate snapshotDate,
            String valuationAsset,
            int writtenRows,
            List<AccountAssetSnapshotRow> snapshots) {
    }

    public record AccountAssetSnapshotQueryResponse(
            int count,
            List<AccountAssetSnapshotRow> snapshots,
            String nextCursor,
            boolean hasMore,
            String sort,
            int limit) {
        public AccountAssetSnapshotQueryResponse(int count, List<AccountAssetSnapshotRow> snapshots) {
            this(count, snapshots, null, false, "snapshotDate.desc", count);
        }
    }

    public record AccountAssetSnapshotRow(
            long snapshotId,
            LocalDate snapshotDate,
            String valuationAsset,
            String accountType,
            String asset,
            BigDecimal totalAvailableUnits,
            BigDecimal totalLockedUnits,
            BigDecimal totalEquityUnits,
            BigDecimal valuationRate,
            BigDecimal totalValue,
            String valuationSource,
            Instant rateUpdatedAt,
            Instant sourceUpdatedAt,
            long userCount,
            long balanceCount,
            Long createdByUserId,
            String createdByUsername,
            Instant createdAt) {
    }
}
