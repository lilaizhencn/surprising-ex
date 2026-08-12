package com.surprising.risk.provider.repository;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.CachedRiskGroup;
import com.surprising.risk.provider.model.CachedRiskPosition;
import com.surprising.risk.provider.model.RiskInstrumentSpec;
import com.surprising.risk.provider.model.RiskGroupKey;
import com.surprising.risk.provider.model.RiskMaintenanceBracket;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 风险实时计算输入仓储。
 *
 * <p>不可拆原因：业务状态查询与本地合约快照组合完成实时风控：
 * <ul>
 *   <li>风险组发现从持仓业务表读取状态，再按本地合约快照归组，避免实时路径读取合约表；</li>
 *   <li>钱包权益必须原子汇总余额、负债、逐仓保证金和活动订单冻结，否则可能误触发或漏触发强平；</li>
 *   <li>Redis 风险组刷新读取持仓、逐仓保证金，并使用同一 JVM 合约快照保证版本一致；</li>
 *   <li>合约风险参数、资产精度与风险档位统一从 JVM 快照读取，避免混用不同版本配置。</li>
 * </ul>
 *
 * <p>这些查询只服务交易链路的实时风控，不承担后台报表、资金对账或运营查询。
 */
@Repository
public class RiskRepository {

    private static final String DEFAULT_ACCOUNT_TYPE = "USDT_PERPETUAL";

    private final JdbcTemplate jdbcTemplate;
    private final RiskProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    public RiskRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new RiskProperties(), null);
    }

    public RiskRepository(JdbcTemplate jdbcTemplate,
                          RiskProperties properties) {
        this(jdbcTemplate, properties, null);
    }

    @Autowired
    public RiskRepository(JdbcTemplate jdbcTemplate,
                          RiskProperties properties,
                          @org.springframework.beans.factory.annotation.Qualifier("riskInstrumentSnapshotCache")
                          InstrumentSnapshotCache snapshotCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new RiskProperties() : properties;
        this.snapshotCache = snapshotCache;
    }

    public List<RiskGroupKey> riskGroups(RiskGroupKey after, int limit) {
        requireSnapshotReady();
        ProductLine productLine = currentProductLine();
        List<PositionInstrumentRow> rows = jdbcTemplate.query("""
                SELECT user_id, symbol, instrument_version
                  FROM account_positions
                 WHERE product_line = ?
                   AND signed_quantity_steps <> 0
                """, (rs, rowNum) -> new PositionInstrumentRow(rs.getLong("user_id"),
                rs.getString("symbol"), rs.getLong("instrument_version")), productLine.name());
        java.util.Set<RiskGroupKey> groups = new java.util.HashSet<>();
        for (PositionInstrumentRow row : rows) {
            snapshotCache.version(productLine, row.symbol(), row.instrumentVersion()).ifPresent(instrument ->
                    groups.add(new RiskGroupKey(row.userId(), instrument.contractType().productLine().accountTypeCode(),
                            instrument.settleAsset())));
        }
        long afterUserId = after == null ? Long.MIN_VALUE : after.userId();
        String afterAccountType = after == null ? "" : after.accountType();
        String afterSettleAsset = after == null ? "" : after.settleAsset();
        return groups.stream()
                .sorted(java.util.Comparator.comparingLong(RiskGroupKey::userId)
                        .thenComparing(RiskGroupKey::accountType)
                        .thenComparing(RiskGroupKey::settleAsset))
                .filter(group -> after == null
                        || group.userId() > afterUserId
                        || (group.userId() == afterUserId && group.accountType().compareTo(afterAccountType) > 0)
                        || (group.userId() == afterUserId && group.accountType().equals(afterAccountType)
                        && group.settleAsset().compareTo(afterSettleAsset) > 0))
                .limit(Math.max(1, limit))
                .toList();
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
                     CROSS JOIN account_context ctx
                     WHERE m.user_id = ?
                       AND m.asset = ?
                       AND m.margin_mode = 'ISOLATED'
                       AND p.product_line = ?
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
                """, (rs, rowNum) -> rs.getLong(1),
                normalizedAccountType, userId, settleAsset, productLineForAccountType(normalizedAccountType).name(),
                userId, settleAsset,
                userId, settleAsset, userId, settleAsset)
                .stream()
                .findFirst()
                .orElse(0L);
    }

    /** 加载用于整体替换单个 Redis 风险组的权威账户输入。 */
    public CachedRiskGroup cachedRiskGroup(RiskGroupKey key) {
        requireSnapshotReady();
        ProductLine productLine = productLineForAccountType(key.accountType());
        List<CachedRiskPositionRow> rows = jdbcTemplate.query("""
                SELECT p.symbol,
                       p.margin_mode,
                       p.position_side,
                       p.instrument_version,
                       p.signed_quantity_steps,
                       p.entry_price_ticks,
                       COALESCE(m.margin_units, 0) AS position_margin_units
                  FROM account_positions p
                  LEFT JOIN LATERAL (
                      SELECT COALESCE(SUM(pm.margin_units), 0) AS margin_units
                        FROM account_position_margins pm
                       WHERE pm.product_line = p.product_line
                         AND pm.user_id = p.user_id
                         AND pm.symbol = p.symbol
                         AND pm.margin_mode = p.margin_mode
                         AND pm.position_side = p.position_side
                         AND pm.asset = ?
                  ) m ON TRUE
                 WHERE p.user_id = ?
                   AND p.product_line = ?
                   AND p.signed_quantity_steps <> 0
                 ORDER BY p.symbol, p.margin_mode, p.position_side
                """, (rs, rowNum) -> new CachedRiskPositionRow(
                        rs.getString("symbol"),
                        MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                        PositionSide.fromNullableDbValue(rs.getString("position_side")),
                        rs.getLong("instrument_version"),
                        rs.getLong("signed_quantity_steps"),
                        rs.getLong("entry_price_ticks"),
                        rs.getLong("position_margin_units"),
                        productLine), key.settleAsset(), key.userId(), productLine.name());
        List<CachedRiskPosition> positions = rows.stream()
                .map(row -> snapshotCache.version(productLine, row.symbol(), row.instrumentVersion())
                        .filter(instrument -> instrument.settleAsset().equals(key.settleAsset())
                                && instrument.contractType().productLine().accountTypeCode().equals(key.accountType()))
                        .map(instrument -> new CachedRiskPosition(row.symbol(), row.marginMode(), row.positionSide(),
                                row.instrumentVersion(), instrument.settleAsset(), row.signedQuantitySteps(),
                                row.entryPriceTicks(), row.positionMarginUnits()))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        return new CachedRiskGroup(key,
                walletBalanceUnits(key.userId(), key.accountType(), key.settleAsset()),
                positions,
                Instant.now());
    }

    public Optional<RiskInstrumentSpec> riskInstrumentSpec(String symbol, long version) {
        requireSnapshotReady();
        ProductLine productLine = currentProductLine();
        Optional<com.surprising.instrument.api.model.InstrumentResponse> instrument =
                snapshotCache.version(productLine, symbol, version);
        if (instrument.isEmpty()) {
            return Optional.empty();
        }
        var value = instrument.get();
        long settleScaleUnits = snapshotCache.scale(productLine, value.settleAsset())
                .orElseThrow(() -> new IllegalStateException("风险结算资产精度快照不存在: " + value.settleAsset()));
        List<RiskMaintenanceBracket> brackets = value.riskLimitBrackets() == null ? List.of()
                : value.riskLimitBrackets().stream()
                .map(bracket -> new RiskMaintenanceBracket(bracket.notionalFloorUnits(),
                        bracket.maintenanceMarginRatePpm()))
                .toList();
        return Optional.of(new RiskInstrumentSpec(
                value.symbol(), value.version(), value.contractType(), value.settleAsset(),
                value.notionalMultiplierUnits(), value.priceTickUnits(), settleScaleUnits,
                value.maintenanceMarginRatePpm(), brackets));
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

    private void requireSnapshotReady() {
        ProductLine productLine = currentProductLine();
        if (snapshotCache == null || !snapshotCache.initialized(productLine)) {
            throw new IllegalStateException("风控合约 JVM 快照尚未就绪");
        }
    }

    private record PositionInstrumentRow(long userId, String symbol, long instrumentVersion) {
    }

    private record CachedRiskPositionRow(String symbol,
                                         MarginMode marginMode,
                                         PositionSide positionSide,
                                         long instrumentVersion,
                                         long signedQuantitySteps,
                                         long entryPriceTicks,
                                         long positionMarginUnits,
                                         ProductLine productLine) {
    }

}
