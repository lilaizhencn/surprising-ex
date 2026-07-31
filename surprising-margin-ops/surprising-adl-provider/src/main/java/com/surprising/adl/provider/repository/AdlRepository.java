package com.surprising.adl.provider.repository;

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

/**
 * 提供 ADL 在线安全决策所需的权威输入。
 *
 * <p>不可拆原因：残余缺口扫描必须在同一数据库快照中同时确认账户缺口与保险基金已无可用余额；
 * 候选排序和执行前复查必须把持仓、合约参数、资产精度、逐仓保证金、账户缺口及同版本标记价组合计算。
 * 拆成单表查询会在并发结算或版本切换窗口选择错误用户或错误减仓数量。这里不提供后台时间线、资金对账或
 * 运营报表；ADL 事件查询已由单表 Repository 承担。</p>
 */
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

    /**
     * 仅在对应资产的保险基金为空时领取已达到等待时间的缺口，确保保险基金优先吸收穿仓损失。
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

    private String accountType() {
        return normalizeAccountType(properties.getKafka().getAccountType());
    }

    private String normalizeAccountType(String accountType) {
        return accountType == null || accountType.isBlank()
                ? DEFAULT_ACCOUNT_TYPE
                : accountType.trim().toUpperCase();
    }

    private boolean productTopicsEnabled() {
        return properties.getKafka().isProductTopicsEnabled();
    }

    private String productLine() {
        return properties.getKafka().getProductLine().name();
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

}
