package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.service.state.account.TransferRuntime;

import com.surprising.aeron.service.state.market.MarkPriceRuntime;
import com.surprising.aeron.service.state.PositionRuntime;
import com.surprising.aeron.service.state.risk.RiskScanRuntime;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.LiquidationRuntime;
import com.surprising.aeron.service.state.RuntimeStateMaterializer;
import java.util.Map;

/**
 * 已提交运行时的读视图边界。
 *
 * <p>实时可变状态仍由 {@link com.surprising.aeron.service.state.TradingRuntimeState} 持有；
 * 本类只负责在查询/快照边界物化不可变状态并维护对应版本缓存，以及按标识解析查询所需的
 * 风险、资金和持仓视图。</p>
 */
final class CoreRuntimeStateView {

    private final TradingCoreRuntime owner;
    private TradingCoreState materializedStateCache;
    private long materializedStateCacheRevision = Long.MIN_VALUE;
    private long materializedStateCacheMarketRevision = Long.MIN_VALUE;
    private long materializedStateCacheSequence = Long.MIN_VALUE;
    private long materializedStateCacheBusinessHash = Long.MIN_VALUE;

    CoreRuntimeStateView(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    TradingCoreState tradingState() {
        return materializedTradingState();
    }

    TradingCoreState snapshotTradingState() {
        CoreSnapshotLifecycle.SnapshotFence fence = owner.snapshots.snapshotFence;
        if (fence != null && fence.snapshotState != null) return fence.snapshotState;
        return materializedTradingState();
    }

    private TradingCoreState materializedTradingState() {
        owner.runtimeState.requireSnapshotFenceReady();
        long revision = owner.runtimeState.revision();
        long marketRevision = owner.runtimeState.marketRevision();
        long sequence = owner.runtimeProjectionJournal.publishedSequence();
        if (materializedStateCache != null
                && materializedStateCacheRevision == revision
                && materializedStateCacheMarketRevision == marketRevision
                && materializedStateCacheSequence == sequence
                && materializedStateCacheBusinessHash == owner.cachedBusinessStateHash) {
            return materializedStateCache;
        }
        TradingCoreState materialized = RuntimeStateMaterializer.materialize(
                owner.runtimeState, owner.identities);
        materializedStateCache = materialized;
        materializedStateCacheRevision = revision;
        materializedStateCacheMarketRevision = marketRevision;
        materializedStateCacheSequence = sequence;
        materializedStateCacheBusinessHash = owner.cachedBusinessStateHash;
        return materialized;
    }

    int incompleteRiskScanCount() { return owner.runtimeState.incompleteRiskScanCount(); }
    int incompleteFundingCount() { return owner.runtimeState.treasury().incompleteFundingCount(); }
    int activeOrderCount() { return owner.activeOrderIndex.count(); }
    int positionCount() { return owner.runtimeState.publishedPositionCount(); }
    int triggerOrderCount() { return owner.triggerOrderIndex.ids().size(); }

    long snapshotBusinessStateHash() { return owner.currentBusinessStateHash(); }
    long snapshotBusinessAuditBaseHash() { return owner.auditBusinessStateHash; }
    long snapshotFundsStateHash() { return owner.auditFundsStateHash; }
    long snapshotProjectionSequence() { return owner.runtimeProjectionJournal.publishedSequence(); }
    long snapshotProjectionFreezeCount() { return owner.runtimeProjectionJournal.projectionFreezeCount(); }

    boolean runtimeRiskScanComplete() { return owner.runtimeState.firstIncompleteRiskScan() == null; }

    boolean runtimeRiskScanComplete(String symbol) {
        Integer symbolId = owner.identities.findSymbolId(symbol);
        RiskScanRuntime scan = symbolId == null ? null : owner.runtimeState.riskScan(symbolId);
        return scan == null || scan.complete();
    }

    RiskScanRuntime runtimeRiskScan(String symbol) {
        Integer symbolId = owner.identities.findSymbolId(symbol);
        return symbolId == null ? null : owner.runtimeState.riskScan(symbolId);
    }

    MarkPriceRuntime runtimeMarkPrice(String symbol) {
        Integer symbolId = owner.identities.findSymbolId(symbol);
        return symbolId == null ? null : owner.runtimeState.markPrice(symbolId);
    }

    long runtimeFundingSettlement(String symbol) {
        Integer symbolId = owner.identities.findSymbolId(symbol);
        return symbolId == null ? 0 : owner.runtimeState.treasury().fundingSettlement(symbolId);
    }

    long runtimeInsurance(String asset) {
        Integer assetId = owner.identities.findAssetId(asset);
        return assetId == null ? 0 : owner.runtimeState.treasury().insurance(assetId);
    }

    LiquidationRuntime runtimeLiquidation(long liquidationId) {
        return owner.runtimeState.liquidation(liquidationId);
    }

    PositionRuntime runtimePosition(long userId, String positionKey) {
        Long key = owner.identities.findPositionKey(userId, positionKey);
        return key == null ? null : owner.runtimeState.position(key);
    }

    boolean snapshotHasPendingCommands() {
        return owner.factContextActive || owner.directCommand.active()
                || owner.laneCommandContexts.inFlight() != 0;
    }

    Map<Long, com.surprising.aeron.service.state.model.CoreFeePolicyState> feePolicies() {
        return owner.runtimeState.feePoliciesSnapshot();
    }

    void restoreFeePolicies(Map<Long, com.surprising.aeron.service.state.model.CoreFeePolicyState> policies) {
        long beforeBusinessStateHash = owner.cachedBusinessStateHash;
        owner.runtimeState.restoreFeePolicies(policies);
        owner.cachedFeePolicyHash = TradingCoreRuntime.computeFeePolicyHash(policies);
        owner.cachedBusinessStateHash = owner.currentBusinessStateHash();
        owner.runtimeProjectionJournal.rebaseInitialBusinessStateHash(
                beforeBusinessStateHash, owner.cachedBusinessStateHash);
    }

    Map<Long, com.surprising.aeron.service.state.account.TransferRuntime> pendingTransfers() {
        return owner.runtimeState.pendingTransfersSnapshot();
    }

    void restorePendingTransfers(Map<Long, com.surprising.aeron.service.state.account.TransferRuntime> transfers) {
        long beforeBusinessStateHash = owner.cachedBusinessStateHash;
        owner.runtimeState.restorePendingTransfers(transfers);
        owner.cachedTransferHash = TradingCoreRuntime.computeTransferHash(transfers);
        owner.cachedBusinessStateHash = owner.currentBusinessStateHash();
        owner.runtimeProjectionJournal.rebaseInitialBusinessStateHash(
                beforeBusinessStateHash, owner.cachedBusinessStateHash);
    }
}
