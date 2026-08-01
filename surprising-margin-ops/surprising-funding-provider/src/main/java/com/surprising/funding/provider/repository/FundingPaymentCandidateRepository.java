package com.surprising.funding.provider.repository;

import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.funding.provider.model.FundingPaymentCursor;
import com.surprising.funding.provider.model.FundingPaymentPage;
import com.surprising.funding.provider.model.FundingSettlementWork;
import com.surprising.funding.provider.service.FundingMath;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 读取单页资金费结算候选持仓。
 *
 * <p>结算金额只把 account_positions 中的业务状态与本地不可变合约快照、资产精度快照组合计算；
 * 数据库不再读取合约表。该查询只用于在线结算，不提供后台时间线、资金对账或运营报表。</p>
 */
@Repository
public class FundingPaymentCandidateRepository {

    private final JdbcTemplate jdbcTemplate;
    private final FundingProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    public FundingPaymentCandidateRepository(JdbcTemplate jdbcTemplate, FundingProperties properties) {
        this(jdbcTemplate, properties, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FundingPaymentCandidateRepository(JdbcTemplate jdbcTemplate,
                                             FundingProperties properties,
                                             @org.springframework.beans.factory.annotation.Qualifier("fundingInstrumentSnapshotCache")
                                             InstrumentSnapshotCache snapshotCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    public FundingPaymentPage findPage(FundingSettlementWork settlement, int limit) {
        Optional<ProductLine> fundingProductLine = currentFundingProductLine();
        if (fundingProductLine.isEmpty()) {
            return FundingPaymentPage.empty(settlement.cursor());
        }
        ProductLine productLine = fundingProductLine.get();
        if (snapshotCache == null || !snapshotCache.initialized(productLine)) {
            throw new IllegalStateException("资金费合约 JVM 快照尚未就绪");
        }
        return findPageFromSnapshot(settlement, productLine, limit);
    }

    private FundingPaymentPage findPageFromSnapshot(FundingSettlementWork settlement,
                                                     ProductLine productLine,
                                                     int limit) {
        int safeLimit = Math.max(1, limit);
        List<PositionRow> rows = jdbcTemplate.query("""
                SELECT p.user_id, p.symbol, p.margin_mode, p.position_side,
                       p.instrument_version, p.signed_quantity_steps
                  FROM account_positions p
                 WHERE p.symbol = ?
                   AND p.product_line = ?
                   AND p.instrument_version = ?
                   AND p.signed_quantity_steps <> 0
                   AND (p.user_id, p.margin_mode, p.position_side) > (?, ?, ?)
                 ORDER BY p.user_id ASC, p.margin_mode ASC, p.position_side ASC
                 LIMIT ?
                """, (rs, rowNum) -> new PositionRow(
                rs.getLong("user_id"), rs.getString("symbol"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                rs.getLong("instrument_version"), rs.getLong("signed_quantity_steps")),
                settlement.symbol(), productLine.name(), settlement.instrumentVersion(),
                settlement.cursor().userId(), settlement.cursor().marginMode(), settlement.cursor().positionSide(),
                safeLimit + 1);
        List<FundingPaymentCandidate> candidates = rows.stream().map(row -> {
            var instrument = snapshotCache.version(productLine, row.symbol(), row.instrumentVersion())
                    .orElseThrow(() -> new IllegalStateException("资金费合约快照不存在: " + row.symbol()));
            long settleScale = snapshotCache.scale(productLine, instrument.settleAsset())
                    .orElseThrow(() -> new IllegalStateException("资金费资产精度不存在: " + instrument.settleAsset()));
            long notionalUnits = PerpetualContractMath.notionalUnits(
                    instrument.contractType(), row.signedQuantitySteps(), settlement.markPriceTicks(),
                    instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), settleScale);
            return new FundingPaymentCandidate(row.userId(), row.symbol(), row.marginMode(), row.positionSide(),
                    instrument.settleAsset(), row.signedQuantitySteps(), notionalUnits,
                    settlement.fundingRatePpm(),
                    FundingMath.paymentAmount(row.signedQuantitySteps(), notionalUnits,
                            settlement.fundingRatePpm()));
        }).toList();
        boolean hasMore = candidates.size() > safeLimit;
        List<FundingPaymentCandidate> items = hasMore
                ? List.copyOf(candidates.subList(0, safeLimit)) : List.copyOf(candidates);
        FundingPaymentCursor nextCursor = items.isEmpty()
                ? settlement.cursor() : FundingPaymentCursor.from(items.getLast());
        return new FundingPaymentPage(items, nextCursor, hasMore);
    }

    private Optional<ProductLine> currentFundingProductLine() {
        ProductLine productLine = properties.getKafka().isProductTopicsEnabled()
                ? properties.getKafka().getProductLine()
                : ProductLine.LINEAR_PERPETUAL;
        return productLine.isFundingProduct() ? Optional.of(productLine) : Optional.empty();
    }

    private record PositionRow(long userId,
                               String symbol,
                               MarginMode marginMode,
                               PositionSide positionSide,
                               long instrumentVersion,
                               long signedQuantitySteps) {
    }
}
