package com.surprising.liquidation.provider.repository;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.ClaimedCandidate;
import com.surprising.liquidation.provider.model.LiquidationCloseState;
import com.surprising.liquidation.provider.model.LiquidationPricingDecision;
import com.surprising.liquidation.provider.model.LiquidationPricingInput;
import com.surprising.liquidation.provider.model.LiquidationSizingInput;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 强平执行一致性仓储。
 *
 * <p>不可拆原因：候选输入从数据库读取风险状态、持仓和挂单等业务状态，再使用本地不可变合约快照补齐合约参数；
 * 完成候选与取消候选仍在同一事务中联动检查强平订单状态，避免资金与持仓状态发生竞态。
 * 本仓储只服务实时强平安全链路，不承载后台时间线、资金对账或运营报表查询。
 */
@Repository
public class LiquidationRepository {

    private static final String DEFAULT_ACCOUNT_TYPE = "USDT_PERPETUAL";

    private final JdbcTemplate jdbcTemplate;
    private final LiquidationProperties properties;
    private final LatestMarkPriceCache markPriceCache;
    private final InstrumentSnapshotCache snapshotCache;

    public LiquidationRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new LiquidationProperties(), null, null);
    }

    public LiquidationRepository(JdbcTemplate jdbcTemplate, LiquidationProperties properties) {
        this(jdbcTemplate, properties, null, null);
    }

    public LiquidationRepository(JdbcTemplate jdbcTemplate,
                                 LiquidationProperties properties,
                                 LatestMarkPriceCache markPriceCache) {
        this(jdbcTemplate, properties, markPriceCache, null);
    }

    @Autowired
    public LiquidationRepository(JdbcTemplate jdbcTemplate,
                                 LiquidationProperties properties,
                                 LatestMarkPriceCache markPriceCache,
                                 InstrumentSnapshotCache snapshotCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new LiquidationProperties() : properties;
        this.markPriceCache = markPriceCache;
        this.snapshotCache = snapshotCache;
    }

    public Map<Long, CandidateInputs> candidateInputs(List<CandidateInputRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return Map.of();
        }
        String values = String.join(", ", java.util.Collections.nCopies(requests.size(),
                "(CAST(? AS bigint), CAST(? AS bigint), CAST(? AS bigint), CAST(? AS text), CAST(? AS text), "
                        + "CAST(? AS text), CAST(? AS bigint), CAST(? AS text), CAST(? AS text), CAST(? AS text), "
                        + "CAST(? AS bigint))"));
        List<Object> args = new ArrayList<>(requests.size() * 11);
        for (CandidateInputRequest request : requests) {
            ClaimedCandidate candidate = request.candidate();
            args.add(candidate.candidateId());
            args.add(candidate.snapshotId());
            args.add(candidate.userId());
            args.add(candidate.symbol());
            args.add(candidate.marginMode().name());
            args.add(candidate.positionSide().name());
            args.add(candidate.instrumentVersion());
            args.add(normalizeAccountType(candidate.accountType()));
            args.add(candidate.settleAsset());
            args.add(currentProductLine().name());
            args.add(request.markPriceTicks());
        }
        List<Map.Entry<Long, CandidateInputs>> rows = jdbcTemplate.query("""
                WITH requested(candidate_id, snapshot_id, user_id, symbol, margin_mode, position_side,
                               instrument_version, account_type, settle_asset, product_line, mark_price_ticks) AS (
                    VALUES %s
                )
                SELECT r.candidate_id,
                       r.product_line,
                       r.symbol,
                       r.instrument_version,
                       r.settle_asset,
                       COALESCE(latest_position.status, latest_account.status) AS latest_status,
                       p.signed_quantity_steps AS current_signed_quantity_steps,
                       ps.signed_quantity_steps AS snapshot_signed_quantity_steps,
                       r.mark_price_ticks,
                       CASE
                           WHEN ps.margin_mode = 'ISOLATED'
                               THEN ps.position_margin_units + ps.unrealized_pnl_units
                           ELSE acc.equity_units
                       END AS equity_units,
                       ps.maintenance_margin_units
                  FROM requested r
                  JOIN account_positions p
                    ON p.user_id = r.user_id
                   AND p.product_line = r.product_line
                   AND p.symbol = r.symbol
                   AND p.margin_mode = r.margin_mode
                   AND p.position_side = r.position_side
                   AND p.instrument_version = r.instrument_version
                   AND p.signed_quantity_steps <> 0
                  JOIN risk_position_snapshots ps
                    ON ps.snapshot_id = r.snapshot_id
                   AND ps.user_id = r.user_id
                   AND ps.product_line = r.product_line
                   AND ps.symbol = r.symbol
                   AND ps.margin_mode = r.margin_mode
                   AND ps.position_side = r.position_side
                   AND ps.instrument_version = r.instrument_version
                   AND ps.signed_quantity_steps <> 0
                  JOIN risk_account_snapshots acc
                    ON acc.snapshot_id = ps.snapshot_id
                   AND acc.user_id = ps.user_id
                   AND acc.product_line = ps.product_line
                   AND acc.settle_asset = ps.settle_asset
             LEFT JOIN LATERAL (
                    SELECT s.status
                      FROM risk_account_snapshots s
                     WHERE r.margin_mode = 'CROSS'
                       AND s.user_id = r.user_id
                       AND s.product_line = r.product_line
                       AND s.account_type = r.account_type
                       AND s.settle_asset = r.settle_asset
                       AND s.snapshot_id >= r.snapshot_id
                     ORDER BY s.snapshot_id DESC
                     LIMIT 1
                ) latest_account ON TRUE
             LEFT JOIN LATERAL (
                    SELECT s.status
                      FROM risk_position_snapshots s
                     WHERE r.margin_mode <> 'CROSS'
                       AND s.user_id = r.user_id
                       AND s.product_line = r.product_line
                       AND s.symbol = r.symbol
                       AND s.margin_mode = r.margin_mode
                       AND s.position_side = r.position_side
                       AND s.instrument_version = r.instrument_version
                       AND s.snapshot_id >= r.snapshot_id
                     ORDER BY s.snapshot_id DESC
                     LIMIT 1
                ) latest_position ON TRUE
                """.formatted(values), (rs, rowNum) -> {
            String latestStatus = rs.getString("latest_status");
            if (latestStatus == null) {
                throw new IllegalStateException("risk snapshot missing for liquidation candidate "
                        + rs.getLong("candidate_id"));
            }
            if (snapshotCache == null) {
                throw new IllegalStateException("强平合约 JVM 快照尚未配置");
            }
            ProductLine productLine = ProductLine.valueOf(rs.getString("product_line"));
            String candidateSymbol = rs.getString("symbol");
            var instrument = snapshotCache.version(productLine, candidateSymbol,
                            rs.getLong("instrument_version"))
                    .orElseThrow(() -> new IllegalStateException("强平合约快照不存在: " + candidateSymbol));
            if (!instrument.settleAsset().equals(rs.getString("settle_asset"))) {
                throw new IllegalStateException("强平合约结算资产与风险快照不一致: " + rs.getString("symbol"));
            }
            long settleScaleUnits = snapshotCache.scale(productLine, instrument.settleAsset())
                    .orElseThrow(() -> new IllegalStateException("强平结算资产精度快照不存在: " + instrument.settleAsset()));
            ContractType contractType = instrument.contractType();
            long currentSignedQuantity = rs.getLong("current_signed_quantity_steps");
            long markPriceTicks = rs.getLong("mark_price_ticks");
            long notionalMultiplierUnits = instrument.notionalMultiplierUnits();
            long priceTickUnits = instrument.priceTickUnits();
            long notionalUnits = PerpetualContractMath.notionalUnits(contractType, currentSignedQuantity,
                    markPriceTicks, notionalMultiplierUnits, priceTickUnits, settleScaleUnits);
            long notionalPerStepUnits = Math.max(1L, PerpetualContractMath.notionalPerStepUnits(contractType,
                    markPriceTicks, notionalMultiplierUnits, priceTickUnits, settleScaleUnits));
            long bracketFloor = instrument.riskLimitBrackets() == null ? 0L : instrument.riskLimitBrackets().stream()
                    .filter(bracket -> bracket.notionalFloorUnits() <= notionalUnits)
                    .mapToLong(bracket -> bracket.notionalFloorUnits()).max().orElse(0L);
            LiquidationPricingInput pricing = new LiquidationPricingInput(contractType,
                    rs.getLong("snapshot_signed_quantity_steps"), markPriceTicks, rs.getLong("equity_units"),
                    rs.getLong("maintenance_margin_units"), notionalMultiplierUnits, priceTickUnits,
                    settleScaleUnits);
            long positionAbsSteps = Math.absExact(currentSignedQuantity);
            LiquidationSizingInput sizing = new LiquidationSizingInput(positionAbsSteps, positionAbsSteps,
                    notionalUnits, notionalPerStepUnits, bracketFloor);
            return Map.entry(rs.getLong("candidate_id"), new CandidateInputs(RiskStatus.valueOf(latestStatus),
                    new LiquidationCloseState(currentSignedQuantity), pricing, sizing));
        }, args.toArray());
        return rows.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private MarkPriceEvent requireMarkPrice(String symbol, long instrumentVersion) {
        if (markPriceCache == null) {
            throw new IllegalStateException("mark price cache is not configured");
        }
        MarkPriceEvent markPrice = markPriceCache.fresh(symbol, properties.getRisk().getMaxMarkAge())
                .orElseThrow(() -> new IllegalStateException("fresh mark price not found for " + symbol));
        if (markPrice.instrumentVersion() != instrumentVersion) {
            throw new IllegalStateException("mark price instrument version mismatch for " + symbol
                    + ": expected=" + instrumentVersion + ", actual=" + markPrice.instrumentVersion());
        }
        return markPrice;
    }

    public OptionalLong freshMarkPriceTicks(String symbol, long instrumentVersion) {
        try {
            return OptionalLong.of(requireMarkPrice(symbol, instrumentVersion).markPriceTicks());
        } catch (IllegalStateException ex) {
            return OptionalLong.empty();
        }
    }

    public int completeSettledCandidates(int limit) {
        return jdbcTemplate.update("""
                WITH settled AS (
                    SELECT c.candidate_id
                      FROM risk_liquidation_candidates c
                      JOIN liquidation_orders lo
                        ON lo.candidate_id = c.candidate_id
                       AND lo.status = 'FILLED'
                 LEFT JOIN account_positions p
                        ON p.user_id = c.user_id
                       AND p.product_line = c.product_line
                       AND p.symbol = c.symbol
                       AND p.margin_mode = c.margin_mode
                       AND p.position_side = c.position_side
                       AND p.instrument_version = c.instrument_version
                     WHERE c.status = 'PROCESSING'
                       AND (p.user_id IS NULL OR p.signed_quantity_steps <> c.signed_quantity_steps)
                       AND (
                            (c.margin_mode = 'CROSS' AND EXISTS (
                                SELECT 1
                                  FROM risk_account_snapshots next_acc
                                 WHERE next_acc.user_id = c.user_id
                                   AND next_acc.product_line = c.product_line
                                   AND next_acc.account_type = c.account_type
                                   AND next_acc.settle_asset = c.settle_asset
                                   AND next_acc.snapshot_id > c.snapshot_id
                            ))
                            OR
                            (c.margin_mode <> 'CROSS' AND EXISTS (
                                SELECT 1
                                  FROM risk_position_snapshots next_pos
                                 WHERE next_pos.user_id = c.user_id
                                   AND next_pos.product_line = c.product_line
                                   AND next_pos.symbol = c.symbol
                                   AND next_pos.margin_mode = c.margin_mode
                                   AND next_pos.position_side = c.position_side
                                   AND next_pos.instrument_version = c.instrument_version
                                   AND next_pos.snapshot_id > c.snapshot_id
                            ))
                       )
                     ORDER BY c.candidate_id
                     LIMIT ?
                     FOR UPDATE OF c SKIP LOCKED
                )
                UPDATE risk_liquidation_candidates c
                   SET status = 'COMPLETED',
                       updated_at = now()
                  FROM settled s
                 WHERE c.candidate_id = s.candidate_id
                """, Math.max(1, limit));
    }

    public Optional<CanceledCandidate> cancelCandidateIfSafe(long candidateId, Instant now) {
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(now));
        args.add(candidateId);
        StringBuilder sql = new StringBuilder("""
                UPDATE risk_liquidation_candidates c
                   SET status = 'CANCELED',
                       updated_at = ?
                """);
        sql.append("""
                 WHERE c.candidate_id = ?
                   AND c.status IN ('NEW', 'PROCESSING')
                   AND NOT EXISTS (
                       SELECT 1
                         FROM liquidation_orders lo
                        WHERE lo.candidate_id = c.candidate_id
                          AND lo.status IN ('SUBMITTED', 'PARTIALLY_FILLED')
                   )
                """);
        appendCandidateProductLineFilter(sql, "c", args);
        sql.append("""
                RETURNING c.candidate_id, c.status, c.updated_at
                """);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new CanceledCandidate(
                rs.getLong("candidate_id"),
                rs.getString("status"),
                rs.getTimestamp("updated_at").toInstant()), args.toArray())
                .stream().findFirst();
    }

    public record CandidateInputRequest(ClaimedCandidate candidate, long markPriceTicks) {
    }

    public record CandidateInputs(RiskStatus latestRiskStatus,
                                  LiquidationCloseState closeState,
                                  LiquidationPricingInput pricingInput,
                                  LiquidationSizingInput sizingInput) {
    }

    public record CanceledCandidate(long candidateId,
                                    String status,
                                    Instant updatedAt) {
    }

    private ProductLine currentProductLine() {
        return properties.getKafka().isProductTopicsEnabled()
                ? properties.getKafka().getProductLine()
                : ProductLine.LINEAR_PERPETUAL;
    }

    private String candidateProductLineFilter(String alias, List<Object> args) {
        ProductLine productLine = currentProductLine();
        if (!productLine.isMarginProduct()) {
            return "AND 1 = 0";
        }
        args.add(productLine.name());
        String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
        return "AND " + prefix + "product_line = ?";
    }

    private void appendCandidateProductLineFilter(StringBuilder sql, String alias, List<Object> args) {
        if (properties.getKafka().isProductTopicsEnabled()) {
            sql.append(candidateProductLineFilter(alias, args)).append('\n');
        }
    }

    private String normalizeAccountType(String accountType) {
        return accountType == null || accountType.isBlank()
                ? DEFAULT_ACCOUNT_TYPE
                : accountType.trim().toUpperCase();
    }

}
