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

    public long nextAccountSequence(String sequenceName) {
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

    /**
     * ADL only claims aged deficits when the insurance fund for the asset is empty.
     * This gives insurance-provider the first chance to absorb bankruptcy losses.
     */
    public List<DeficitRow> claimResidualDeficits(int batchSize, Duration minAge) {
        String accountType = accountType();
        if (properties.getKafka().isProductTopicsEnabled()) {
            return jdbcTemplate.query("""
                    SELECT d.account_type, d.user_id, d.asset, d.deficit_units
                      FROM account_product_deficits d
                      LEFT JOIN insurance_fund_balances f
                        ON f.account_type = d.account_type AND f.asset = d.asset
                     WHERE d.account_type = ?
                       AND d.deficit_units > 0
                       AND d.updated_at <= now() - (? * INTERVAL '1 millisecond')
                       AND COALESCE(f.balance_units, 0) = 0
                     ORDER BY d.updated_at ASC
                     LIMIT ?
                     FOR UPDATE OF d SKIP LOCKED
                    """, (rs, rowNum) -> new DeficitRow(
                    rs.getString("account_type"),
                    rs.getLong("user_id"),
                    rs.getString("asset"),
                    rs.getLong("deficit_units")), accountType, minAge.toMillis(), batchSize);
        }
        return jdbcTemplate.query("""
                SELECT ? AS account_type, d.user_id, d.asset, d.deficit_units
                  FROM account_deficits d
                  LEFT JOIN insurance_fund_balances f
                    ON f.account_type = ? AND f.asset = d.asset
                 WHERE d.deficit_units > 0
                   AND d.updated_at <= now() - (? * INTERVAL '1 millisecond')
                   AND COALESCE(f.balance_units, 0) = 0
                 ORDER BY d.updated_at ASC
                 LIMIT ?
                 FOR UPDATE OF d SKIP LOCKED
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
                 FOR UPDATE OF p SKIP LOCKED
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

    public long executeAdl(DeficitRow deficit, AdlCandidate candidate, long remainingDeficitUnits) {
        String accountType = normalizeAccountType(deficit.accountType());
        requireProviderAccountType(accountType);
        if (remainingDeficitUnits <= 0 || insuranceBalance(accountType, candidate.asset()) > 0) {
            return remainingDeficitUnits;
        }
        long closeSteps = AdlMath.closeStepsForCover(remainingDeficitUnits, candidate.absQuantitySteps(),
                candidate.unrealizedProfitUnits());
        long realizedProfitUnits = AdlMath.proportionalUnits(candidate.unrealizedProfitUnits(), closeSteps,
                candidate.absQuantitySteps());
        long coveredUnits = Math.min(remainingDeficitUnits, realizedProfitUnits);
        if (closeSteps <= 0 || realizedProfitUnits <= 0 || coveredUnits <= 0) {
            return remainingDeficitUnits;
        }

        Instant now = Instant.now();
        long eventId = nextAdlSequence("adl-event");
        long realizedPnlUnits = realizedProfitUnits;
        reduceTargetPosition(candidate, closeSteps, realizedPnlUnits, now);
        releaseTargetMargin(accountType, candidate, closeSteps, now);
        applyTargetProfitAndTransfer(accountType, candidate.userId(), candidate.asset(),
                realizedProfitUnits, coveredUnits, eventId, now);
        long nextRemaining = Math.subtractExact(remainingDeficitUnits, coveredUnits);
        reduceDeficit(accountType, deficit, nextRemaining, now);
        insertAccountLedger(accountType, deficit.userId(), deficit.asset(), coveredUnits, -nextRemaining,
                "ADL_COVERAGE", String.valueOf(eventId), "ADL_DEFICIT_COVERAGE", now);
        insertAdlEvent(accountType, eventId, deficit, candidate, closeSteps, realizedProfitUnits, coveredUnits,
                nextRemaining, now);
        return nextRemaining;
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

    private String balanceTable() {
        return productTopicsEnabled() ? "account_product_balances" : "account_balances";
    }

    private String deficitTable() {
        return productTopicsEnabled() ? "account_product_deficits" : "account_deficits";
    }

    private String productUsingPrefix() {
        return productTopicsEnabled() ? "account_type, " : "";
    }

    private String accountTypePredicate() {
        return productTopicsEnabled() ? "account_type = ? AND " : "";
    }

    private String accountTypePredicate(String alias) {
        return productTopicsEnabled() ? alias + ".account_type = ? AND " : "";
    }

    private Object[] accountTypeArgs(String accountType, Object... rest) {
        return scopedArgs(accountType, 0, rest);
    }

    private Object[] scopedArgs(String accountType, int accountTypeIndex, Object... rest) {
        if (!productTopicsEnabled()) {
            return rest;
        }
        Object[] args = new Object[rest.length + 1];
        System.arraycopy(rest, 0, args, 0, accountTypeIndex);
        args[accountTypeIndex] = normalizeAccountType(accountType);
        System.arraycopy(rest, accountTypeIndex, args, accountTypeIndex + 1, rest.length - accountTypeIndex);
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

    private long insuranceBalance(String accountType, String asset) {
        jdbcTemplate.update("""
                INSERT INTO insurance_fund_balances (account_type, asset, balance_units, updated_at)
                VALUES (?, ?, 0, now())
                ON CONFLICT (account_type, asset) DO NOTHING
                """, accountType, asset);
        return jdbcTemplate.query("""
                SELECT balance_units
                  FROM insurance_fund_balances
                 WHERE account_type = ? AND asset = ?
                 FOR UPDATE
                """, (rs, rowNum) -> rs.getLong(1), accountType, asset).stream().findFirst().orElse(0L);
    }

    private void reduceTargetPosition(AdlCandidate candidate,
                                      long closeSteps,
                                      long realizedPnlUnits,
                                      Instant now) {
        long nextAbs = Math.subtractExact(candidate.absQuantitySteps(), closeSteps);
        long nextSignedQuantity = candidate.signedQuantitySteps() > 0 ? nextAbs : -nextAbs;
        long nextEntryPrice = nextAbs == 0 ? 0L : candidate.entryPriceTicks();
        int rows = jdbcTemplate.update("""
                UPDATE account_positions
                   SET signed_quantity_steps = ?,
                       instrument_version = CASE WHEN ? = 0 THEN NULL ELSE instrument_version END,
                       entry_price_ticks = ?,
                       entry_value_ticks = CASE
                           WHEN ? = 0 THEN 0
                           ELSE entry_value_ticks
                               - ((entry_value_ticks::numeric * ?) / ?)::BIGINT
                       END,
                       realized_pnl_units = realized_pnl_units + ?,
                       updated_at = ?
                 WHERE user_id = ? AND symbol = ? AND margin_mode = ? AND position_side = ?
                   AND %s
                """.formatted(productLinePredicate()), productLineArgs(nextSignedQuantity, nextSignedQuantity,
                        nextEntryPrice, nextSignedQuantity, closeSteps, candidate.absQuantitySteps(), realizedPnlUnits,
                        Timestamp.from(now), candidate.userId(), candidate.symbol(), candidate.marginMode().name(),
                        candidate.positionSide().name()));
        requireSingleRow(rows, "ADL target position update");
        updateSymbolOpenInterest(candidate.symbol(), candidate.signedQuantitySteps(), nextSignedQuantity, now);
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
                    product_line, symbol, long_quantity_steps, short_quantity_steps, open_quantity_steps, updated_at
                ) VALUES (?, ?, 0, 0, 0, ?)
                ON CONFLICT (product_line, symbol) DO NOTHING
                """, productLine(), symbol, Timestamp.from(now));
        int rows = jdbcTemplate.update("""
                UPDATE trading_symbol_open_interest
                   SET long_quantity_steps = long_quantity_steps + ?,
                       short_quantity_steps = short_quantity_steps + ?,
                       open_quantity_steps = GREATEST(long_quantity_steps + ?, short_quantity_steps + ?),
                       updated_at = ?
                 WHERE symbol = ?
                   AND product_line = ?
                   AND long_quantity_steps + ? >= 0
                   AND short_quantity_steps + ? >= 0
                """, longDelta, shortDelta, longDelta, shortDelta, Timestamp.from(now), symbol,
                productLine(), longDelta, shortDelta);
        requireSingleRow(rows, "ADL symbol open interest update");
    }

    private long longQuantitySteps(long signedQuantitySteps) {
        return signedQuantitySteps > 0 ? signedQuantitySteps : 0L;
    }

    private long shortQuantitySteps(long signedQuantitySteps) {
        return signedQuantitySteps < 0 ? Math.negateExact(signedQuantitySteps) : 0L;
    }

    private void releaseTargetMargin(String accountType, AdlCandidate candidate, long closeSteps, Instant now) {
        var margins = jdbcTemplate.query("""
                SELECT asset, margin_mode, position_side, margin_units
                  FROM account_position_margins
                 WHERE user_id = ? AND symbol = ? AND asset = ?
                   AND margin_mode = ?
                   AND position_side = ?
                   AND margin_units > 0
                   AND %s
                 FOR UPDATE
                """.formatted(productLinePredicate()), (rs, rowNum) -> new PositionMargin(rs.getString("asset"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                rs.getLong("margin_units")),
                productLineArgs(candidate.userId(), candidate.symbol(), candidate.asset(),
                        candidate.marginMode().name(), candidate.positionSide().name()));
        for (PositionMargin margin : margins) {
            long releaseUnits = AdlMath.proportionalUnits(margin.marginUnits(), closeSteps,
                    candidate.absQuantitySteps());
            if (releaseUnits <= 0) {
                continue;
            }
            ensureBalanceRows(accountType, candidate.userId(), margin.asset(), now);
            int rows = jdbcTemplate.update("""
                    UPDATE %s
                       SET locked_units = locked_units - ?,
                           available_units = available_units + ?,
                           updated_at = ?
                     WHERE %s user_id = ? AND asset = ?
                       AND locked_units >= ?
                    """.formatted(balanceTable(), accountTypePredicate()), scopedArgs(accountType, 3,
                    releaseUnits, releaseUnits, Timestamp.from(now), candidate.userId(), margin.asset(),
                    releaseUnits));
            if (rows != 1) {
                throw new IllegalStateException("insufficient locked margin for ADL release");
            }
            int marginRows = jdbcTemplate.update("""
                    UPDATE account_position_margins
                       SET margin_units = margin_units - ?,
                           updated_at = ?
                     WHERE user_id = ? AND symbol = ? AND asset = ?
                       AND margin_mode = ?
                       AND position_side = ?
                       AND margin_units >= ?
                       AND %s
                    """.formatted(productLinePredicate()), productLineArgs(releaseUnits, Timestamp.from(now),
                    candidate.userId(), candidate.symbol(), margin.asset(), margin.marginMode().name(),
                    margin.positionSide().name(), releaseUnits));
            requireSingleRow(marginRows, "ADL target position margin release");
            jdbcTemplate.update("""
                    DELETE FROM account_position_margins
                     WHERE user_id = ? AND symbol = ? AND asset = ?
                       AND margin_mode = ? AND position_side = ? AND margin_units = 0
                       AND %s
                    """.formatted(productLinePredicate()), productLineArgs(candidate.userId(), candidate.symbol(),
                    margin.asset(), margin.marginMode().name(), margin.positionSide().name()));
        }
    }

    private void applyTargetProfitAndTransfer(String accountType,
                                              long userId,
                                              String asset,
                                              long realizedProfitUnits,
                                              long coveredUnits,
                                              long eventId,
                                              Instant now) {
        long balanceAfterProfit = applyPositiveAmount(accountType, userId, asset, realizedProfitUnits, now);
        insertAccountLedger(accountType, userId, asset, realizedProfitUnits, balanceAfterProfit, "ADL_REALIZED_PNL",
                String.valueOf(eventId), "ADL_POSITION_DELEVERAGED", now);
        long balanceAfterTransfer = deductAvailable(accountType, userId, asset, coveredUnits, now);
        insertAccountLedger(accountType, userId, asset, -coveredUnits, balanceAfterTransfer, "ADL_TRANSFER",
                String.valueOf(eventId), "ADL_DEFICIT_TRANSFER", now);
    }

    private long applyPositiveAmount(String accountType, long userId, String asset, long amountUnits, Instant now) {
        ensureBalanceRows(accountType, userId, asset, now);
        BalanceState current = lockBalance(accountType, userId, asset);
        long deficitOffset = Math.min(current.deficitUnits(), amountUnits);
        long nextDeficit = Math.subtractExact(current.deficitUnits(), deficitOffset);
        long nextAvailable = Math.addExact(current.availableUnits(), Math.subtractExact(amountUnits, deficitOffset));
        updateBalance(accountType, userId, asset, nextAvailable, current.lockedUnits(), nextDeficit, now);
        return Math.subtractExact(Math.addExact(nextAvailable, current.lockedUnits()), nextDeficit);
    }

    private long deductAvailable(String accountType, long userId, String asset, long amountUnits, Instant now) {
        BalanceState current = lockBalance(accountType, userId, asset);
        if (current.availableUnits() < amountUnits) {
            throw new IllegalStateException("insufficient ADL realized profit for transfer");
        }
        long nextAvailable = Math.subtractExact(current.availableUnits(), amountUnits);
        updateBalance(accountType, userId, asset, nextAvailable, current.lockedUnits(), current.deficitUnits(), now);
        return Math.subtractExact(Math.addExact(nextAvailable, current.lockedUnits()), current.deficitUnits());
    }

    private void reduceDeficit(String accountType, DeficitRow deficit, long remainingDeficitUnits, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE %s
                   SET deficit_units = ?,
                       updated_at = ?
                 WHERE %s user_id = ? AND asset = ?
                """.formatted(deficitTable(), accountTypePredicate()), scopedArgs(accountType, 2,
                remainingDeficitUnits, Timestamp.from(now), deficit.userId(), deficit.asset()));
        requireSingleRow(rows, "ADL deficit reduction");
    }

    private void ensureBalanceRows(String accountType, long userId, String asset, Instant now) {
        accountType = normalizeAccountType(accountType);
        if (productTopicsEnabled()) {
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
            return;
        }
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
    }

    private BalanceState lockBalance(String accountType, long userId, String asset) {
        accountType = normalizeAccountType(accountType);
        return jdbcTemplate.queryForObject("""
                SELECT b.available_units, b.locked_units, d.deficit_units
                  FROM %s b
                  JOIN %s d USING (%suser_id, asset)
                 WHERE %sb.user_id = ? AND b.asset = ?
                 FOR UPDATE OF b, d
                """.formatted(balanceTable(), deficitTable(), productUsingPrefix(), accountTypePredicate("b")),
                (rs, rowNum) -> new BalanceState(
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("deficit_units")), accountTypeArgs(accountType, userId, asset));
    }

    private void updateBalance(String accountType,
                               long userId,
                               String asset,
                               long availableUnits,
                               long lockedUnits,
                               long deficitUnits,
                               Instant now) {
        accountType = normalizeAccountType(accountType);
        int balanceRows = jdbcTemplate.update("""
                UPDATE %s
                   SET available_units = ?,
                       locked_units = ?,
                       updated_at = ?
                 WHERE %suser_id = ? AND asset = ?
                """.formatted(balanceTable(), accountTypePredicate()), scopedArgs(accountType, 3,
                availableUnits, lockedUnits, Timestamp.from(now), userId, asset));
        requireSingleRow(balanceRows, "ADL account balance update");
        int deficitRows = jdbcTemplate.update("""
                UPDATE %s
                   SET deficit_units = ?,
                       updated_at = ?
                 WHERE %suser_id = ? AND asset = ?
                """.formatted(deficitTable(), accountTypePredicate()), scopedArgs(accountType, 2,
                deficitUnits, Timestamp.from(now), userId, asset));
        requireSingleRow(deficitRows, "ADL account deficit update");
    }

    private void insertAccountLedger(String accountType,
                                     long userId,
                                     String asset,
                                     long amountUnits,
                                     long balanceAfterUnits,
                                     String referenceType,
                                     String referenceId,
                                     String reason,
                                     Instant now) {
        accountType = normalizeAccountType(accountType);
        int rows = productTopicsEnabled()
                ? jdbcTemplate.update("""
                    INSERT INTO account_product_ledger_entries (
                        entry_id, account_type, user_id, asset, amount_units, balance_after_units,
                        reference_type, reference_id, reason, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                    """, nextAccountSequence("ledger-entry"), accountType, userId, asset, amountUnits,
                        balanceAfterUnits, referenceType, referenceId, reason, Timestamp.from(now))
                : jdbcTemplate.update("""
                    INSERT INTO account_ledger_entries (
                        entry_id, user_id, asset, amount_units, balance_after_units,
                        reference_type, reference_id, reason, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                    """, nextAccountSequence("ledger-entry"), userId, asset, amountUnits, balanceAfterUnits,
                        referenceType, referenceId, reason, Timestamp.from(now));
        requireSingleRow(rows, "ADL account ledger insert");
    }

    private void insertAdlEvent(String accountType,
                                long eventId,
                                DeficitRow deficit,
                                AdlCandidate candidate,
                                long closedSteps,
                                long realizedProfitUnits,
                                long coveredUnits,
                                long remainingDeficitUnits,
                                Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO adl_events (
                    event_id, account_type, deficit_user_id, target_user_id, asset, symbol, target_side,
                    target_position_side,
                    closed_quantity_steps, entry_price_ticks, mark_price_ticks, requested_deficit_units,
                    realized_profit_units, covered_units, remaining_deficit_units,
                    priority_score_ppm, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ADL_DEFICIT_COVERAGE', ?)
                """, eventId, normalizeAccountType(accountType), deficit.userId(), candidate.userId(),
                candidate.asset(), candidate.symbol(), candidate.side().name(), candidate.positionSide().name(),
                closedSteps, candidate.entryPriceTicks(), candidate.markPriceTicks(), deficit.deficitUnits(),
                realizedProfitUnits, coveredUnits, remainingDeficitUnits, candidate.priorityScorePpm(),
                Timestamp.from(now));
        requireSingleRow(rows, "ADL event insert");
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
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

    private record PositionMargin(String asset, MarginMode marginMode, PositionSide positionSide, long marginUnits) {
        private PositionMargin {
            marginMode = MarginMode.defaultIfNull(marginMode);
            positionSide = PositionSide.defaultIfNull(positionSide);
        }
    }

    private record BalanceState(long availableUnits, long lockedUnits, long deficitUnits) {
    }
}
