package com.surprising.adl.provider.repository;

import com.surprising.adl.api.model.AdminCursorPage;
import com.surprising.adl.api.model.AdlEventResponse;
import com.surprising.adl.api.model.AdlSide;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.adl.provider.model.AdlCandidate;
import com.surprising.adl.provider.model.DeficitRow;
import com.surprising.adl.provider.service.AdlMath;
import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdlRepository {

    private static final String DEFAULT_ACCOUNT_TYPE = "USDT_PERPETUAL";

    private final JdbcTemplate jdbcTemplate;
    private final AdlProperties properties;
    private final LatestMarkPriceCache markPriceCache;

    public AdlRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new AdlProperties(), null);
    }

    public AdlRepository(JdbcTemplate jdbcTemplate, AdlProperties properties) {
        this(jdbcTemplate, properties, null);
    }

    @Autowired
    public AdlRepository(JdbcTemplate jdbcTemplate,
                         AdlProperties properties,
                         LatestMarkPriceCache markPriceCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new AdlProperties() : properties;
        this.markPriceCache = markPriceCache;
    }

    public long nextAdlSequence(String sequenceName) {
        Long value = jdbcTemplate.queryForObject("""
                INSERT INTO adl_sequences (sequence_name, sequence_value, updated_at)
                VALUES (?, 1, now())
                ON CONFLICT (sequence_name) DO UPDATE SET
                    sequence_value = adl_sequences.sequence_value + 1,
                    updated_at = now()
                RETURNING sequence_value
                """, Long.class, sequenceName);
        if (value == null) {
            throw new IllegalStateException("failed to allocate adl sequence " + sequenceName);
        }
        return value;
    }

    /**
     * ADL only claims aged deficits when the insurance fund for the asset is empty.
     * This gives insurance-provider the first chance to absorb bankruptcy losses.
     */
    public List<DeficitRow> claimResidualDeficits(int batchSize, Duration minAge) {
        String accountType = accountType();
        if (properties.getKafka().isProductTopicsEnabled()) {
            return jdbcTemplate.query("""
                    SELECT d.account_type, d.user_id, d.asset,
                           d.deficit_units - d.reserved_units AS deficit_units
                      FROM account_product_deficits d
                      LEFT JOIN insurance_fund_balances f
                        ON f.account_type = d.account_type AND f.asset = d.asset
                     WHERE d.account_type = ?
                       AND d.deficit_units - d.reserved_units > 0
                       AND d.updated_at <= now() - (? * INTERVAL '1 millisecond')
                       AND COALESCE(f.balance_units, 0) = 0
                     ORDER BY d.updated_at ASC
                     LIMIT ?
                    """, (rs, rowNum) -> new DeficitRow(
                    rs.getString("account_type"),
                    rs.getLong("user_id"),
                    rs.getString("asset"),
                    rs.getLong("deficit_units")), accountType, minAge.toMillis(), batchSize);
        }
        return jdbcTemplate.query("""
                SELECT ? AS account_type, d.user_id, d.asset,
                       d.deficit_units - d.reserved_units AS deficit_units
                  FROM account_deficits d
                  LEFT JOIN insurance_fund_balances f
                    ON f.account_type = ? AND f.asset = d.asset
                 WHERE d.deficit_units - d.reserved_units > 0
                   AND d.updated_at <= now() - (? * INTERVAL '1 millisecond')
                   AND COALESCE(f.balance_units, 0) = 0
                 ORDER BY d.updated_at ASC
                 LIMIT ?
                """, (rs, rowNum) -> new DeficitRow(
                rs.getString("account_type"),
                rs.getLong("user_id"),
                rs.getString("asset"),
                rs.getLong("deficit_units")), accountType, accountType, minAge.toMillis(), batchSize);
    }

    public List<AdlCandidate> queue(String asset, int limit, Duration maxMarkAge) {
        return queue(asset, 0L, limit, maxMarkAge);
    }

    public List<String> candidateAssets() {
        return jdbcTemplate.query("""
                SELECT DISTINCT i.settle_asset
                  FROM account_positions p
                  JOIN instruments i ON i.symbol = p.symbol AND i.version = p.instrument_version
                 WHERE p.product_line = ? AND p.signed_quantity_steps <> 0
                 ORDER BY i.settle_asset ASC
                """, (rs, rowNum) -> rs.getString(1), properties.getKafka().getProductLine().name());
    }

    public List<AdlCandidate> queue(String asset, long excludedUserId, int limit, Duration maxMarkAge) {
        MarkPriceValues markPrices = freshMarkPrices(maxMarkAge, null);
        if (markPrices.isEmpty()) {
            return List.of();
        }
        int fetchLimit = Math.min(5000, Math.max(limit, limit * 5));
        String sql = "WITH " + markPrices.cte() + "\n" + """
                SELECT *
                  FROM (
                """ + candidateSelect() + """
                       AND (? = 0 OR p.user_id <> ?)
                  ) q
                 ORDER BY q.user_id ASC, q.symbol ASC
                 LIMIT ?
                """;
        List<Object> args = new ArrayList<>(markPrices.args());
        args.addAll(candidateArgs(asset));
        args.add(excludedUserId);
        args.add(excludedUserId);
        args.add(fetchLimit);
        return jdbcTemplate.query(sql, (rs, rowNum) -> toCandidate(rs), args.toArray())
                .stream()
                .filter(candidate -> candidate.profitTicksPerStep() > 0 && candidate.unrealizedProfitUnits() > 0)
                .sorted((left, right) -> {
                    int score = Long.compare(right.priorityScorePpm(), left.priorityScorePpm());
                    return score != 0 ? score : Long.compare(right.unrealizedProfitUnits(), left.unrealizedProfitUnits());
                })
                .limit(limit)
                .toList();
    }

    public Optional<AdlCandidate> lockCandidate(long userId,
                                                String symbol,
                                                MarginMode marginMode,
                                                PositionSide positionSide,
                                                String asset,
                                                Duration maxMarkAge) {
        MarkPriceValues markPrices = freshMarkPrices(maxMarkAge, symbol);
        if (markPrices.isEmpty()) {
            return Optional.empty();
        }
        String sql = "WITH " + markPrices.cte() + "\n" + candidateSelect() + """
                   AND p.user_id = ?
                   AND p.symbol = ?
                   AND p.margin_mode = ?
                   AND p.position_side = ?
                """;
        List<Object> args = new ArrayList<>(markPrices.args());
        args.addAll(candidateArgs(asset));
        args.add(userId);
        args.add(symbol);
        args.add(MarginMode.defaultIfNull(marginMode).name());
        args.add(PositionSide.defaultIfNull(positionSide).name());
        return jdbcTemplate.query(sql, (rs, rowNum) -> toCandidate(rs), args.toArray())
                .stream()
                .filter(candidate -> candidate.profitTicksPerStep() > 0 && candidate.unrealizedProfitUnits() > 0)
                .findFirst();
    }

    public Optional<AdlCandidate> lockCandidate(long userId, String symbol, String asset, Duration maxMarkAge) {
        return lockCandidate(userId, symbol, MarginMode.CROSS, PositionSide.NET, asset, maxMarkAge);
    }

    public List<AdlEventResponse> events(Long userId, String asset, String symbol, int limit) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM adl_events
                 WHERE account_type = ?
                   AND (CAST(? AS text) IS NULL OR deficit_user_id = ? OR target_user_id = ?)
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                 ORDER BY created_at DESC
                 LIMIT ?
                """, (rs, rowNum) -> toEvent(rs), accountType(), userId, userId, userId, asset, asset, symbol,
                symbol, limit);
    }

    public AdminCursorPage.CursorPage<AdlEventResponse> eventsPage(
            Long userId,
            String asset,
            String symbol,
            int limit,
            String cursor,
            String sort) {
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec sortSpec = parseCreatedAtSort(sort);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(accountType());
        args.add(userId);
        args.add(userId);
        args.add(userId);
        args.add(asset);
        args.add(asset);
        args.add(symbol);
        args.add(symbol);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        String sql = """
                SELECT *
                  FROM adl_events
                 WHERE account_type = ?
                   AND (CAST(? AS text) IS NULL OR deficit_user_id = ? OR target_user_id = ?)
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                """ + AdminCursorPage.seekCondition(sortSpec, decodedCursor) + """
                 ORDER BY created_at %s, event_id %s
                 LIMIT ?
                """.formatted(sortSpec.directionSql(), sortSpec.directionSql());
        List<AdlEventResponse> rows = jdbcTemplate.query(sql, (rs, rowNum) -> toEvent(rs), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, AdlEventResponse::createdAt,
                AdlEventResponse::eventId);
    }

    private AdminCursorPage.SortSpec parseCreatedAtSort(String value) {
        AdminCursorPage.SortSpec createdAtDesc = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", "event_id", true);
        AdminCursorPage.SortSpec createdAtAsc = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", "event_id", false);
        return AdminCursorPage.parseSort(value, createdAtDesc, List.of(createdAtDesc, createdAtAsc));
    }

    private String accountType() {
        return normalizeAccountType(properties.getKafka().getAccountType());
    }

    private String normalizeAccountType(String accountType) {
        return accountType == null || accountType.isBlank()
                ? DEFAULT_ACCOUNT_TYPE
                : accountType.trim().toUpperCase();
    }

    private void requireProviderAccountType(String accountType) {
        if (productTopicsEnabled() && !accountType().equals(accountType)) {
            throw new IllegalArgumentException("ADL deficit account type " + accountType
                    + " does not match provider account type " + accountType());
        }
    }

    private boolean productTopicsEnabled() {
        return properties.getKafka().isProductTopicsEnabled();
    }

    private String productLine() {
        return properties.getKafka().getProductLine().name();
    }

    private String productLinePredicate() {
        return productTopicsEnabled() ? "product_line = ?" : "1 = 1";
    }

    private Object[] productLineArgs(Object... rest) {
        if (!productTopicsEnabled()) {
            return rest;
        }
        Object[] args = new Object[rest.length + 1];
        System.arraycopy(rest, 0, args, 0, rest.length);
        args[rest.length] = productLine();
        return args;
    }

    private String candidateSelect() {
        String deficitJoin = productTopicsEnabled()
                ? """
                  LEFT JOIN account_product_deficits d
                    ON d.account_type = ?
                   AND d.user_id = p.user_id
                   AND d.asset = i.settle_asset
                """
                : """
                  LEFT JOIN account_deficits d
                    ON d.user_id = p.user_id
                   AND d.asset = i.settle_asset
                """;
        String productFilter = productTopicsEnabled()
                ? "   AND p.product_line = ?\n"
                : "";
        return """
                SELECT p.user_id,
                       i.settle_asset AS asset,
                       p.symbol,
                       p.margin_mode,
                       p.position_side,
                       i.contract_type,
                       i.notional_multiplier_units,
                       i.price_tick_units,
                       ss.scale_units AS settle_scale_units,
                       p.signed_quantity_steps,
                       p.entry_price_ticks,
                       pm.mark_price_ticks,
                       COALESCE(m.margin_units, 0) AS margin_units
                  FROM account_positions p
                  JOIN instruments i ON i.symbol = p.symbol AND i.version = p.instrument_version
                  JOIN account_asset_scales ss ON ss.asset = i.settle_asset
                  JOIN mark_prices pm
                    ON pm.symbol = p.symbol
                   AND pm.instrument_version = p.instrument_version
                  LEFT JOIN account_position_margins m
                    ON m.user_id = p.user_id
                   AND m.symbol = p.symbol
                   AND m.asset = i.settle_asset
                   AND m.margin_mode = p.margin_mode
                   AND m.position_side = p.position_side
                   AND m.product_line = p.product_line
                %s
                 WHERE i.settle_asset = ?
                   AND p.signed_quantity_steps <> 0
                   AND COALESCE(d.deficit_units, 0) = 0
                %s
                """.formatted(deficitJoin, productFilter);
    }

    private MarkPriceValues freshMarkPrices(Duration maxAge, String symbol) {
        if (markPriceCache == null) {
            throw new IllegalStateException("mark price cache is not configured");
        }
        List<MarkPriceEvent> snapshots = symbol == null
                ? markPriceCache.freshSnapshots(maxAge)
                : markPriceCache.fresh(symbol, maxAge).stream().toList();
        if (snapshots.isEmpty()) {
            return MarkPriceValues.empty();
        }
        StringBuilder values = new StringBuilder();
        List<Object> args = new ArrayList<>(snapshots.size() * 3);
        for (MarkPriceEvent snapshot : snapshots) {
            if (!values.isEmpty()) {
                values.append(", ");
            }
            values.append("(?::TEXT, ?::BIGINT, ?::BIGINT)");
            args.add(snapshot.symbol());
            args.add(snapshot.instrumentVersion());
            args.add(snapshot.markPriceTicks());
        }
        return new MarkPriceValues("mark_prices(symbol, instrument_version, mark_price_ticks) AS (VALUES "
                + values + ")", List.copyOf(args));
    }

    private List<Object> candidateArgs(String asset) {
        List<Object> args = new ArrayList<>();
        if (productTopicsEnabled()) {
            args.add(accountType());
        }
        args.add(asset);
        if (productTopicsEnabled()) {
            args.add(productLine());
        }
        return args;
    }

    private record MarkPriceValues(String cte, List<Object> args) {

        private static MarkPriceValues empty() {
            return new MarkPriceValues("", List.of());
        }

        private boolean isEmpty() {
            return args.isEmpty();
        }
    }

    private AdlCandidate toCandidate(java.sql.ResultSet rs) throws java.sql.SQLException {
        ContractType contractType = ContractType.valueOf(rs.getString("contract_type"));
        long signedQuantity = rs.getLong("signed_quantity_steps");
        long entryPriceTicks = rs.getLong("entry_price_ticks");
        long markPriceTicks = rs.getLong("mark_price_ticks");
        long notionalMultiplierUnits = rs.getLong("notional_multiplier_units");
        long priceTickUnits = rs.getLong("price_tick_units");
        long settleScaleUnits = rs.getLong("settle_scale_units");
        long notionalUnits = PerpetualContractMath.notionalUnits(contractType, signedQuantity,
                markPriceTicks, notionalMultiplierUnits, priceTickUnits, settleScaleUnits);
        long profitUnits = Math.max(0L, PerpetualContractMath.unrealizedPnlUnits(contractType, signedQuantity,
                entryPriceTicks, markPriceTicks, notionalMultiplierUnits, priceTickUnits, settleScaleUnits));
        long absQuantitySteps = Math.absExact(signedQuantity);
        long profitTicksPerStep = signedQuantity > 0
                ? Math.subtractExact(markPriceTicks, entryPriceTicks)
                : Math.subtractExact(entryPriceTicks, markPriceTicks);
        long marginUnits = rs.getLong("margin_units");
        long profitRatePpm = AdlMath.profitRatePpm(profitUnits, notionalUnits);
        long effectiveLeveragePpm = AdlMath.effectiveLeveragePpm(notionalUnits, marginUnits);
        long priorityScorePpm = AdlMath.priorityScorePpm(profitRatePpm, effectiveLeveragePpm);
        return new AdlCandidate(
                rs.getLong("user_id"),
                rs.getString("asset"),
                rs.getString("symbol"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                signedQuantity > 0 ? AdlSide.LONG : AdlSide.SHORT,
                signedQuantity,
                absQuantitySteps,
                entryPriceTicks,
                markPriceTicks,
                profitTicksPerStep,
                notionalUnits,
                profitUnits,
                marginUnits,
                profitRatePpm,
                effectiveLeveragePpm,
                priorityScorePpm);
    }

    private AdlEventResponse toEvent(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AdlEventResponse(
                rs.getLong("event_id"),
                rs.getLong("deficit_user_id"),
                rs.getLong("target_user_id"),
                rs.getString("asset"),
                rs.getString("symbol"),
                AdlSide.valueOf(rs.getString("target_side")),
                PositionSide.fromNullableDbValue(rs.getString("target_position_side")),
                rs.getLong("closed_quantity_steps"),
                rs.getLong("entry_price_ticks"),
                rs.getLong("mark_price_ticks"),
                rs.getLong("requested_deficit_units"),
                rs.getLong("realized_profit_units"),
                rs.getLong("covered_units"),
                rs.getLong("remaining_deficit_units"),
                rs.getLong("priority_score_ppm"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant());
    }
}
