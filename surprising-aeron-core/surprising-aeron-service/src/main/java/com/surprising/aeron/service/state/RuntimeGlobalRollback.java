package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreInstrumentMaintenance;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.service.state.account.TransferRuntime;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.market.MarkPriceRuntime;
import com.surprising.aeron.service.state.model.CoreAlgoOrderState;
import com.surprising.aeron.service.state.model.CoreCancelAllAfterKey;
import com.surprising.aeron.service.state.model.CoreCancelAllAfterState;
import com.surprising.aeron.service.state.model.CoreFeePolicyState;
import com.surprising.aeron.service.state.model.CoreLeverageKey;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;
import com.surprising.aeron.service.state.risk.RiskScanRuntime;
import com.surprising.aeron.service.state.risk.RiskSnapshotRuntime;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Before-images for owner-owned and account Lane cold state, retained until commit or rollback. */
final class RuntimeGlobalRollback {
    private final TradingRuntimeState state;

    private ConcurrentHashMap<Long, Before<LiquidationRuntime>> liquidations = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, Before<RiskSnapshotRuntime>> riskSnapshots = new ConcurrentHashMap<>();
    private ConcurrentHashMap<CoreLeverageKey, Before<Long>> leverages = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, Before<CoreAlgoOrderState>> algoOrders = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, Before<CoreTriggerOrderState>> triggerOrders = new ConcurrentHashMap<>();
    private ConcurrentHashMap<CoreCancelAllAfterKey, Before<CoreCancelAllAfterState>> timers = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Integer, Before<MarkPriceRuntime>> markPrices = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Integer, Before<RiskScanRuntime>> riskScans = new ConcurrentHashMap<>();
    private final HashSet<String> registeredInstruments = new HashSet<>();
    private ConcurrentHashMap<String, CoreInstrumentMaintenance> instrumentMaintenance = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, Before<TransferRuntime>> pendingTransfers = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, Before<CoreFeePolicyState>> feePolicies = new ConcurrentHashMap<>();
    private long nextLiquidationIdBefore;
    private boolean nextLiquidationIdChanged;
    private long marketRevisionBefore;
    private boolean marketRevisionChanged;
    private CoreRiskScanControlView riskScanControlBefore;
    private boolean riskScanControlChanged;

    RuntimeGlobalRollback(TradingRuntimeState state) {
        this.state = java.util.Objects.requireNonNull(state);
    }

    void captureLiquidation(long liquidationId) {
        state.rejectUnsupportedOrderBatchMutation("liquidation state");
        liquidations.computeIfAbsent(liquidationId, id -> new Before<>(state.liquidation(id)));
    }

    void captureRiskSnapshot(long positionKey) {
        state.rejectUnsupportedOrderBatchMutation("risk snapshot state");
        riskSnapshots.computeIfAbsent(positionKey, key -> new Before<>(state.riskSnapshot(key)));
    }

    void captureLeverage(CoreLeverageKey key) {
        leverages.computeIfAbsent(key, value -> new Before<>(state.leverage(value)));
    }

    void captureAlgoOrder(long algoOrderId) {
        algoOrders.computeIfAbsent(algoOrderId, id -> new Before<>(state.algoOrder(id)));
    }

    void captureTriggerOrder(long triggerOrderId, CoreTriggerOrderState current) {
        triggerOrders.computeIfAbsent(triggerOrderId, id -> new Before<>(current));
    }

    void captureTriggerOrder(long triggerOrderId) {
        triggerOrders.computeIfAbsent(triggerOrderId, id -> new Before<>(state.triggerOrder(id)));
    }

    void captureTimer(CoreCancelAllAfterKey key) {
        timers.computeIfAbsent(key, value -> new Before<>(state.cancelAllAfterTimers.get(value)));
    }

    void captureMarkPrice(int symbolId) {
        state.rejectUnsupportedOrderBatchMutation("mark-price state");
        markPrices.computeIfAbsent(symbolId, id -> new Before<>(state.markPrices.get(id)));
    }

    void captureRiskScan(int symbolId) {
        state.rejectUnsupportedOrderBatchMutation("risk-scan state");
        riskScans.computeIfAbsent(symbolId, id -> new Before<>(state.riskScans.get(id)));
    }

    void captureRegisteredInstrument(String symbol) {
        registeredInstruments.add(symbol);
    }

    void captureInstrumentMaintenance(CoreInstrument instrument) {
        instrumentMaintenance.computeIfAbsent(instrument.symbol(), symbol -> instrument.maintenance());
    }

    void capturePendingTransfer(long transferId, TransferRuntime current) {
        pendingTransfers.computeIfAbsent(transferId, id -> new Before<>(current));
    }

    void capturePendingTransfer(long transferId) {
        pendingTransfers.computeIfAbsent(transferId, id -> new Before<>(state.pendingTransfers.get(id)));
    }

    void captureFeePolicy(long policyId, CoreFeePolicyState current) {
        feePolicies.computeIfAbsent(policyId, id -> new Before<>(current));
    }

    void captureNextLiquidationId() {
        if (!nextLiquidationIdChanged) {
            nextLiquidationIdBefore = state.nextLiquidationId;
            nextLiquidationIdChanged = true;
        }
    }

    void captureMarketRevision() {
        if (!marketRevisionChanged) {
            marketRevisionBefore = state.marketRevision;
            marketRevisionChanged = true;
        }
    }

    void captureRiskScanControl() {
        if (!riskScanControlChanged) {
            riskScanControlBefore = state.riskScanControl;
            riskScanControlChanged = true;
        }
    }

    boolean hasCaptured() {
        return !liquidations.isEmpty() || !riskSnapshots.isEmpty() || !leverages.isEmpty()
                || !algoOrders.isEmpty() || !triggerOrders.isEmpty() || !timers.isEmpty()
                || !markPrices.isEmpty() || !riskScans.isEmpty() || !registeredInstruments.isEmpty()
                || !instrumentMaintenance.isEmpty() || !pendingTransfers.isEmpty()
                || !feePolicies.isEmpty() || nextLiquidationIdChanged || marketRevisionChanged
                || riskScanControlChanged;
    }

    /** Restore account-owned cold state before the owner publishes restored references. */
    void restoreLane(AccountLaneState lane) {
        lane.assertOwner();
        int laneId = lane.laneId();
        liquidations.forEach((id, before) -> {
            LiquidationRuntime current = lane.cold.liquidations.remove(id);
            if (current != null) TradingRuntimeState.removeActiveLiquidation(lane, current);
            LiquidationRuntime restored = before.value();
            if (restored != null && state.topology.accountLaneId(restored.userId()) == laneId) {
                lane.cold.liquidations.put(id, restored);
                TradingRuntimeState.indexActiveLiquidation(lane, restored);
            }
        });
        riskSnapshots.forEach((key, before) -> {
            lane.cold.riskSnapshots.remove(key);
            RiskSnapshotRuntime restored = before.value();
            if (restored != null && state.topology.accountLaneId(restored.userId()) == laneId)
                lane.cold.riskSnapshots.put(key, restored);
        });
        leverages.forEach((key, before) -> {
            if (state.topology.accountLaneId(key.userId()) != laneId) return;
            if (before.value() == null) {
                lane.cold.leverages.remove(key);
                Set<CoreLeverageKey> keys = lane.cold.leverageKeysByUser.get(key.userId());
                if (keys != null) {
                    keys.remove(key);
                    if (keys.isEmpty()) lane.cold.leverageKeysByUser.remove(key.userId());
                }
            } else {
                lane.cold.leverages.put(key, before.value());
                lane.cold.leverageKeysByUser.getIfAbsentPut(key.userId(), HashSet::new).add(key);
            }
        });
        algoOrders.forEach((id, before) -> {
            lane.cold.algoOrders.remove(id);
            CoreAlgoOrderState restored = before.value();
            if (restored != null && state.topology.accountLaneId(restored.userId()) == laneId)
                lane.cold.algoOrders.put(id, restored);
        });
        triggerOrders.forEach((id, before) -> {
            lane.removeTrigger(id);
            CoreTriggerOrderState restored = before.value();
            if (restored != null && state.topology.accountLaneId(restored.userId()) == laneId)
                lane.putTrigger(restored);
        });
    }

    /** Called only after every Lane has restored its account and cold state. */
    void restoreOwner() {
        state.assertOwner();
        liquidations.forEach((id, before) -> TradingRuntimeState.putOrRemove(state.publishedLiquidations, id, before.value()));
        riskSnapshots.forEach((id, before) -> TradingRuntimeState.putOrRemove(state.publishedRiskSnapshots, id, before.value()));
        algoOrders.forEach((id, before) -> TradingRuntimeState.putOrRemove(state.publishedAlgoOrders, id, before.value()));
        triggerOrders.forEach((id, before) -> TradingRuntimeState.putOrRemove(state.publishedTriggerOrders, id, before.value()));
        timers.forEach((key, before) -> TradingRuntimeState.putOrRemove(state.cancelAllAfterTimers, key, before.value()));
        markPrices.forEach((id, before) -> TradingRuntimeState.putOrRemove(state.markPrices, id, before.value()));
        riskScans.forEach((id, before) -> TradingRuntimeState.putOrRemove(state.riskScans, id, before.value()));
        registeredInstruments.forEach(state.instruments::remove);
        instrumentMaintenance.forEach((symbol, maintenance) -> {
            CoreInstrument instrument = state.instruments.get(symbol);
            if (instrument != null) instrument.updateMaintenance(maintenance);
        });
        pendingTransfers.forEach((id, before) -> TradingRuntimeState.putOrRemove(state.pendingTransfers, id, before.value()));
        feePolicies.forEach((id, before) -> TradingRuntimeState.putOrRemove(state.feePolicies, id, before.value()));
        if (nextLiquidationIdChanged) state.nextLiquidationId = nextLiquidationIdBefore;
        if (marketRevisionChanged) state.marketRevision = marketRevisionBefore;
        if (riskScanControlChanged) state.riskScanControl = riskScanControlBefore;
    }

    void clear() {
        liquidations = clearCaptured(liquidations);
        riskSnapshots = clearCaptured(riskSnapshots);
        leverages = clearCaptured(leverages);
        algoOrders = clearCaptured(algoOrders);
        triggerOrders = clearCaptured(triggerOrders);
        timers = clearCaptured(timers);
        markPrices = clearCaptured(markPrices);
        riskScans = clearCaptured(riskScans);
        registeredInstruments.clear();
        instrumentMaintenance = clearCaptured(instrumentMaintenance);
        pendingTransfers = clearCaptured(pendingTransfers);
        feePolicies = clearCaptured(feePolicies);
        nextLiquidationIdChanged = false;
        marketRevisionChanged = false;
        riskScanControlChanged = false;
    }

    static <K, V> ConcurrentHashMap<K, V> clearCaptured(ConcurrentHashMap<K, V> values) {
        if (values.isEmpty()) return values;
        if (values.size() >= TradingRuntimeState.CHANGE_KEY_COMPACTION_THRESHOLD) return new ConcurrentHashMap<>();
        values.clear();
        return values;
    }

    private record Before<T>(T value) {}
}
