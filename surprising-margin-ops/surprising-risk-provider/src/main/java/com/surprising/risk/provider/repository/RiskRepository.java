package com.surprising.risk.provider.repository;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineSql;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.CachedRiskGroup;
import com.surprising.risk.provider.model.CachedRiskPosition;
import com.surprising.risk.provider.model.RiskInstrumentSpec;
import com.surprising.risk.provider.model.RiskGroupKey;
import com.surprising.risk.provider.model.RiskMaintenanceBracket;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 风险实时计算输入仓储。
 *
 * <p>不可拆原因：
 * <ul>
 *   <li>风险组发现必须在同一数据库快照内关联持仓与合约，避免漏算刚形成的风险组；</li>
 *   <li>钱包权益必须原子汇总余额、负债、逐仓保证金和活动订单冻结，否则可能误触发或漏触发强平；</li>
 *   <li>Redis 风险组刷新必须同时读取持仓、合约与逐仓保证金，保证整组投影来自同一权威时点；</li>
 *   <li>合约风险参数必须同时读取合约版本、资产精度与风险档位，避免混用不同版本配置。</li>
 * </ul>
 *
 * <p>这些查询只服务交易链路的实时风控，不承担后台报表、资金对账或运营查询。
 */
@Repository
public class RiskRepository {

    private static final String DEFAULT_ACCOUNT_TYPE = "USDT_PERPETUAL";

    private final JdbcTemplate jdbcTemplate;
    private final RiskProperties properties;

    public RiskRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new RiskProperties());
    }

    @Autowired
    public RiskRepository(JdbcTemplate jdbcTemplate, RiskProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new RiskProperties() : properties;
    }

    public List<RiskGroupKey> riskGroups(RiskGroupKey after, int limit) {
        long afterUserId = after == null ? 0L : after.userId();
        String afterAccountType = after == null ? "" : after.accountType();
        String afterSettleAsset = after == null ? "" : after.settleAsset();
        int cappedLimit = Math.max(1, limit);
        List<Object> args = new ArrayList<>();
        String productLineFilter = positionProductLineFilter("p", args);
        String sql = """
                WITH open_groups AS (
                    SELECT p.user_id,
                           %s AS account_type,
                           i.settle_asset
                      FROM account_positions p
                      JOIN instruments i ON i.symbol = p.symbol AND i.version = p.instrument_version
                     WHERE p.signed_quantity_steps <> 0
                       %s
                     GROUP BY p.user_id, %s, i.settle_asset
                )
                SELECT user_id, account_type, settle_asset
                  FROM open_groups
                 WHERE (? = 0
                     OR user_id > ?
                     OR (user_id = ? AND account_type > ?)
                     OR (user_id = ? AND account_type = ? AND settle_asset > ?))
                 ORDER BY user_id ASC, account_type ASC, settle_asset ASC
                 LIMIT ?
                """.formatted(accountTypeExpression("i"), productLineFilter, accountTypeExpression("i"));
        args.add(afterUserId);
        args.add(afterUserId);
        args.add(afterUserId);
        args.add(afterAccountType);
        args.add(afterUserId);
        args.add(afterAccountType);
        args.add(afterSettleAsset);
        args.add(cappedLimit);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new RiskGroupKey(rs.getLong("user_id"),
                rs.getString("account_type"), rs.getString("settle_asset")), args.toArray());
    }

    public long walletBalanceUnits(long userId, String settleAsset) {
        return walletBalanceUnits(userId, DEFAULT_ACCOUNT_TYPE, settleAsset);
    }

    public long walletBalanceUnits(long userId, String accountType, String settleAsset) {
        String normalizedAccountType = normalizeAccountType(accountType);
        return jdbcTemplate.query("""
                WITH account_context AS (
                    SELECT ? AS account_type
                ),
                isolated_position_locks AS (
                    SELECT COALESCE(SUM(m.margin_units), 0) AS units
                      FROM account_position_margins m
                      JOIN account_positions p
                        ON p.user_id = m.user_id
                       AND p.symbol = m.symbol
                       AND p.margin_mode = m.margin_mode
                       AND p.position_side = m.position_side
                       AND p.product_line = m.product_line
                      JOIN instruments i
                        ON i.symbol = p.symbol
                       AND i.version = p.instrument_version
                     CROSS JOIN account_context ctx
                     WHERE m.user_id = ?
                       AND m.asset = ?
                       AND m.margin_mode = 'ISOLATED'
                       AND %s = ctx.account_type
                ),
                isolated_order_locks AS (
                    SELECT COALESCE(SUM(
                               CASE WHEN o.quantity_steps = 0 THEN 0
                                    ELSE o.reserved_units * o.remaining_quantity_steps / o.quantity_steps END
                           ), 0) AS units
                      FROM trading_orders o
                     CROSS JOIN account_context ctx
                     WHERE o.user_id = ?
                       AND o.reservation_asset = ?
                       AND o.reservation_account_type = ctx.account_type
                       AND o.margin_mode = 'ISOLATED'
                       AND o.status IN ('PENDING_RESERVE', 'ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                       AND o.remaining_quantity_steps > 0
                )
                SELECT CASE
                           WHEN ctx.account_type = 'USDT_PERPETUAL' THEN COALESCE(
                               b.available_units + b.locked_units
                               - isolated_position_locks.units
                               - isolated_order_locks.units
                               - COALESCE(d.deficit_units, 0), 0)
                           ELSE COALESCE(
                               pb.available_units + pb.locked_units
                               - isolated_position_locks.units
                               - isolated_order_locks.units
                               - COALESCE(pd.deficit_units, 0), 0)
                       END
                  FROM account_context ctx
                  CROSS JOIN isolated_position_locks
                  CROSS JOIN isolated_order_locks
                  LEFT JOIN account_balances b
                    ON b.user_id = ?
                   AND b.asset = ?
                  LEFT JOIN account_deficits d
                    ON d.user_id = b.user_id
                   AND d.asset = b.asset
                  LEFT JOIN account_product_balances pb
                    ON pb.account_type = ctx.account_type
                   AND pb.user_id = ?
                   AND pb.asset = ?
                  LEFT JOIN account_product_deficits pd
                    ON pd.account_type = pb.account_type
                   AND pd.user_id = pb.user_id
                   AND pd.asset = pb.asset
                """.formatted(accountTypeExpression("i")), (rs, rowNum) -> rs.getLong(1),
                normalizedAccountType, userId, settleAsset, userId, settleAsset,
                userId, settleAsset, userId, settleAsset)
                .stream()
                .findFirst()
                .orElse(0L);
    }

    /** 加载用于整体替换单个 Redis 风险组的权威账户输入。 */
    public CachedRiskGroup cachedRiskGroup(RiskGroupKey key) {
        List<Object> args = new ArrayList<>();
        args.add(key.userId());
        args.add(key.accountType());
        args.add(key.settleAsset());
        String productLineFilter = positionProductLineFilter("p", args);
        List<CachedRiskPosition> positions = jdbcTemplate.query("""
                SELECT p.symbol,
                       p.margin_mode,
                       p.position_side,
                       p.instrument_version,
                       i.settle_asset,
                       p.signed_quantity_steps,
                       p.entry_price_ticks,
                       COALESCE(m.margin_units, 0) AS position_margin_units
                  FROM account_positions p
                  JOIN instruments i
                    ON i.symbol = p.symbol
                   AND i.version = p.instrument_version
                  LEFT JOIN LATERAL (
                      SELECT COALESCE(SUM(pm.margin_units), 0) AS margin_units
                        FROM account_position_margins pm
                       WHERE pm.product_line = p.product_line
                         AND pm.user_id = p.user_id
                         AND pm.symbol = p.symbol
                         AND pm.margin_mode = p.margin_mode
                         AND pm.position_side = p.position_side
                         AND pm.asset = i.settle_asset
                  ) m ON TRUE
                 WHERE p.user_id = ?
                   AND %s = ?
                   AND i.settle_asset = ?
                   AND p.signed_quantity_steps <> 0
                   %s
                 ORDER BY p.symbol, p.margin_mode, p.position_side
                """.formatted(accountTypeExpression("i"), productLineFilter), (rs, rowNum) ->
                new CachedRiskPosition(
                        rs.getString("symbol"),
                        MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                        PositionSide.fromNullableDbValue(rs.getString("position_side")),
                        rs.getLong("instrument_version"),
                        rs.getString("settle_asset"),
                        rs.getLong("signed_quantity_steps"),
                        rs.getLong("entry_price_ticks"),
                        rs.getLong("position_margin_units")), args.toArray());
        return new CachedRiskGroup(key,
                walletBalanceUnits(key.userId(), key.accountType(), key.settleAsset()),
                positions,
                Instant.now());
    }

    public Optional<RiskInstrumentSpec> riskInstrumentSpec(String symbol, long version) {
        List<RiskInstrumentSpecRow> rows = jdbcTemplate.query("""
                SELECT i.symbol,
                       i.version,
                       i.contract_type,
                       i.settle_asset,
                       i.notional_multiplier_units,
                       i.price_tick_units,
                       s.scale_units AS settle_scale_units,
                       i.maintenance_margin_rate_ppm,
                       b.notional_floor_units,
                       b.maintenance_margin_rate_ppm AS bracket_maintenance_margin_rate_ppm
                  FROM instruments i
                  JOIN account_asset_scales s ON s.asset = i.settle_asset
                  LEFT JOIN instrument_risk_brackets b
                    ON b.symbol = i.symbol
                   AND b.version = i.version
                 WHERE i.symbol = ?
                   AND i.version = ?
                 ORDER BY b.notional_floor_units ASC
                """, (rs, rowNum) -> new RiskInstrumentSpecRow(
                rs.getString("symbol"),
                rs.getLong("version"),
                ContractType.valueOf(rs.getString("contract_type")),
                rs.getString("settle_asset"),
                rs.getLong("notional_multiplier_units"),
                rs.getLong("price_tick_units"),
                rs.getLong("settle_scale_units"),
                rs.getLong("maintenance_margin_rate_ppm"),
                (Long) rs.getObject("notional_floor_units"),
                (Long) rs.getObject("bracket_maintenance_margin_rate_ppm")), symbol, version);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        RiskInstrumentSpecRow first = rows.getFirst();
        List<RiskMaintenanceBracket> brackets = rows.stream()
                .filter(row -> row.notionalFloorUnits() != null && row.bracketMaintenanceMarginRatePpm() != null)
                .map(row -> new RiskMaintenanceBracket(
                        row.notionalFloorUnits(), row.bracketMaintenanceMarginRatePpm()))
                .toList();
        return Optional.of(new RiskInstrumentSpec(
                first.symbol(), first.version(), first.contractType(), first.settleAsset(),
                first.notionalMultiplierUnits(), first.priceTickUnits(), first.settleScaleUnits(),
                first.baseMaintenanceMarginRatePpm(), brackets));
    }

    private String normalizeAccountType(String accountType) {
        return accountType == null || accountType.isBlank()
                ? DEFAULT_ACCOUNT_TYPE
                : accountType.trim().toUpperCase();
    }

    private ProductLine currentProductLine() {
        return properties.getKafka().isProductTopicsEnabled()
                ? properties.getKafka().getProductLine()
                : ProductLine.LINEAR_PERPETUAL;
    }

    private ProductLine productLineForAccountType(String accountType) {
        return ProductLine.fromAccountTypeCode(normalizeAccountType(accountType))
                .orElse(currentProductLine());
    }

    private String instrumentProductLineFilter(String alias, List<Object> args) {
        if (!properties.getKafka().isProductTopicsEnabled()) {
            return "";
        }
        ProductLine productLine = currentProductLine();
        if (!productLine.isMarginProduct()) {
            return "AND 1 = 0";
        }
        args.add(productLine.contractTypeCode());
        return "AND " + alias + ".contract_type = ?";
    }

    private String positionProductLineFilter(String alias, List<Object> args) {
        if (!properties.getKafka().isProductTopicsEnabled()) {
            return "";
        }
        return productLineColumnFilter(alias, args);
    }

    private String productLineColumnFilter(String alias, List<Object> args) {
        ProductLine productLine = currentProductLine();
        if (!productLine.isMarginProduct()) {
            return "AND 1 = 0";
        }
        args.add(productLine.name());
        String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
        return "AND " + prefix + "product_line = ?";
    }

    private static String accountTypeExpression(String instrumentAlias) {
        return ProductLineSql.contractTypeAccountTypeCase(instrumentAlias + ".contract_type");
    }

    private record RiskInstrumentSpecRow(
            String symbol,
            long version,
            ContractType contractType,
            String settleAsset,
            long notionalMultiplierUnits,
            long priceTickUnits,
            long settleScaleUnits,
            long baseMaintenanceMarginRatePpm,
            Long notionalFloorUnits,
            Long bracketMaintenanceMarginRatePpm) {
    }

}
