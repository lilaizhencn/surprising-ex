package com.surprising.funding.provider.repository;

import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.funding.provider.model.FundingPaymentCursor;
import com.surprising.funding.provider.model.FundingPaymentPage;
import com.surprising.funding.provider.model.FundingSettlementWork;
import com.surprising.funding.provider.service.FundingMath;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.product.api.ProductLine;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

/**
 * 从账户完整 JVM 快照读取一页资金费结算候选持仓。
 *
 * <p>数据库中的 account_positions 只是异步投影，不能参与资金费在线裁决。账户快照未追赶到
 * Kafka 最新位点、合约快照缺失或修订发生间隙时直接失败关闭，等待恢复后重试。</p>
 */
@Repository
public class FundingPaymentCandidateRepository {

    private final FundingProperties properties;
    private final InstrumentSnapshotCache instrumentSnapshotCache;
    private final PerpetualAccountStateSnapshotCache accountStateSnapshotCache;

    @Autowired
    public FundingPaymentCandidateRepository(
            FundingProperties properties,
            @Qualifier("fundingInstrumentSnapshotCache") InstrumentSnapshotCache instrumentSnapshotCache,
            @Qualifier("fundingAccountStateSnapshotCache")
            PerpetualAccountStateSnapshotCache accountStateSnapshotCache) {
        this.properties = properties;
        this.instrumentSnapshotCache = instrumentSnapshotCache;
        this.accountStateSnapshotCache = accountStateSnapshotCache;
    }

    public FundingPaymentPage findPage(FundingSettlementWork settlement, int limit) {
        ProductLine productLine = currentFundingProductLine();
        if (productLine == null) {
            return FundingPaymentPage.empty(settlement.cursor());
        }
        if (!accountStateSnapshotCache.ready()) {
            throw new IllegalStateException("资金费账户 JVM 快照尚未追赶到最新位点");
        }
        if (!instrumentSnapshotCache.initialized(productLine)) {
            throw new IllegalStateException("资金费合约 JVM 快照尚未就绪");
        }
        int safeLimit = Math.max(1, limit);
        FundingPaymentCursor cursor = settlement.cursor();
        List<FundingPaymentCandidate> candidates = accountStateSnapshotCache.states().stream()
                .flatMap(state -> state.positions().stream()
                        .filter(position -> position.signedQuantitySteps() != 0L)
                        .filter(position -> position.symbol().equalsIgnoreCase(settlement.symbol()))
                        .filter(position -> position.instrumentVersion() == settlement.instrumentVersion())
                        .map(position -> toCandidate(productLine, state.userId(), position, settlement)))
                .filter(candidate -> after(candidate, cursor))
                .sorted(Comparator.comparingLong(FundingPaymentCandidate::userId)
                        .thenComparing(candidate -> candidate.marginMode().name())
                        .thenComparing(candidate -> candidate.positionSide().name()))
                .limit((long) safeLimit + 1L)
                .toList();
        boolean hasMore = candidates.size() > safeLimit;
        List<FundingPaymentCandidate> items = hasMore
                ? List.copyOf(candidates.subList(0, safeLimit)) : List.copyOf(candidates);
        FundingPaymentCursor nextCursor = items.isEmpty()
                ? settlement.cursor() : FundingPaymentCursor.from(items.getLast());
        return new FundingPaymentPage(items, nextCursor, hasMore);
    }

    private FundingPaymentCandidate toCandidate(ProductLine productLine,
                                                long userId,
                                                com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent.Position position,
                                                FundingSettlementWork settlement) {
        var instrument = instrumentSnapshotCache.version(productLine, position.symbol(), position.instrumentVersion())
                .orElseThrow(() -> new IllegalStateException("资金费合约快照不存在: " + position.symbol()));
        long settleScale = instrumentSnapshotCache.scale(productLine, instrument.settleAsset())
                .orElseThrow(() -> new IllegalStateException("资金费资产精度不存在: " + instrument.settleAsset()));
        long notionalUnits = PerpetualContractMath.notionalUnits(
                instrument.contractType(), position.signedQuantitySteps(), settlement.markPriceTicks(),
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), settleScale);
        return new FundingPaymentCandidate(userId, position.symbol(), position.marginMode(), position.positionSide(),
                instrument.settleAsset(), position.signedQuantitySteps(), notionalUnits,
                settlement.fundingRatePpm(), FundingMath.paymentAmount(position.signedQuantitySteps(), notionalUnits,
                        settlement.fundingRatePpm()));
    }

    private boolean after(FundingPaymentCandidate candidate, FundingPaymentCursor cursor) {
        int userComparison = Long.compare(candidate.userId(), cursor.userId());
        if (userComparison != 0) {
            return userComparison > 0;
        }
        int marginComparison = candidate.marginMode().name().compareTo(cursor.marginMode());
        if (marginComparison != 0) {
            return marginComparison > 0;
        }
        return candidate.positionSide().name().compareTo(cursor.positionSide()) > 0;
    }

    private ProductLine currentFundingProductLine() {
        ProductLine productLine = properties.getKafka().isProductTopicsEnabled()
                ? properties.getKafka().getProductLine() : ProductLine.LINEAR_PERPETUAL;
        return productLine.isFundingProduct() ? productLine : null;
    }
}
