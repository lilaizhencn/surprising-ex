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
    private static final Object NULL_VALUE = new Object();

    @SuppressWarnings("unchecked")
    private static <T> T unwrap(Object value) {
        return value == NULL_VALUE ? null : (T) value;
    }

    private static Object wrap(Object value) {
        return value == null ? NULL_VALUE : value;
    }

    private final TradingRuntimeState state;

    private ConcurrentHashMap<Long, Object> liquidations = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, Object> riskSnapshots = new ConcurrentHashMap<>();
    private ConcurrentHashMap<CoreLeverageKey, Object> leverages = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, Object> algoOrders = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, Object> triggerOrders = new ConcurrentHashMap<>();
    private ConcurrentHashMap<CoreCancelAllAfterKey, Object> timers = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Integer, Object> markPrices = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Integer, Object> riskScans = new ConcurrentHashMap<>();
    private final HashSet<String> registeredInstruments = new HashSet<>();
    private ConcurrentHashMap<String, CoreInstrumentMaintenance> instrumentMaintenance = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, CoreInstrument.Configuration> instrumentConfigurations = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, Object> pendingTransfers = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, Object> feePolicies = new ConcurrentHashMap<>();
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
        if (!liquidations.containsKey(liquidationId)) {
            liquidations.putIfAbsent(liquidationId, wrap(state.liquidation(liquidationId)));
        }
    }

    void captureRiskSnapshot(long positionKey) {
        state.rejectUnsupportedOrderBatchMutation("risk snapshot state");
        if (!riskSnapshots.containsKey(positionKey)) {
            riskSnapshots.putIfAbsent(positionKey, wrap(state.riskSnapshot(positionKey)));
        }
    }

    void captureLeverage(CoreLeverageKey key) {
        if (!leverages.containsKey(key)) {
            leverages.putIfAbsent(key, wrap(state.leverage(key)));
        }
    }

    void captureAlgoOrder(long algoOrderId) {
        if (!algoOrders.containsKey(algoOrderId)) {
            algoOrders.putIfAbsent(algoOrderId, wrap(state.algoOrder(algoOrderId)));
        }
    }

    void captureTriggerOrder(long triggerOrderId, CoreTriggerOrderState current) {
        if (!triggerOrders.containsKey(triggerOrderId)) {
            triggerOrders.putIfAbsent(triggerOrderId, wrap(current));
        }
    }

    void captureTriggerOrder(long triggerOrderId) {
        if (!triggerOrders.containsKey(triggerOrderId)) {
            triggerOrders.putIfAbsent(triggerOrderId, wrap(state.triggerOrder(triggerOrderId)));
        }
    }

    void captureTimer(CoreCancelAllAfterKey key) {
        if (!timers.containsKey(key)) {
            timers.putIfAbsent(key, wrap(state.cancelAllAfterTimers.get(key)));
        }
    }

    void captureMarkPrice(int symbolId) {
        state.rejectUnsupportedOrderBatchMutation("mark-price state");
        if (!markPrices.containsKey(symbolId)) {
            markPrices.putIfAbsent(symbolId, wrap(state.markPrices.get(symbolId)));
        }
    }

    void captureRiskScan(int symbolId) {
        state.rejectUnsupportedOrderBatchMutation("risk-scan state");
        if (!riskScans.containsKey(symbolId)) {
            riskScans.putIfAbsent(symbolId, wrap(state.riskScans.get(symbolId)));
        }
    }

    void captureRegisteredInstrument(String instrumentId) {
        registeredInstruments.add(instrumentId);
    }

    void captureInstrumentMaintenance(CoreInstrument instrument) {
        if (!instrumentMaintenance.containsKey(instrument.instrumentId())) {
            var maint = instrument.maintenance();
            if (maint != null) instrumentMaintenance.putIfAbsent(instrument.instrumentId(), maint);
        }
    }

    void captureInstrumentConfiguration(CoreInstrument instrument) {
        if (!instrumentConfigurations.containsKey(instrument.instrumentId())) {
            var config = instrument.configuration();
            if (config != null) instrumentConfigurations.putIfAbsent(instrument.instrumentId(), config);
        }
    }

    void capturePendingTransfer(long transferId, TransferRuntime current) {
        if (!pendingTransfers.containsKey(transferId)) {
            pendingTransfers.putIfAbsent(transferId, wrap(current));
        }
    }

    void capturePendingTransfer(long transferId) {
        if (!pendingTransfers.containsKey(transferId)) {
            pendingTransfers.putIfAbsent(transferId, wrap(state.pendingTransfers.get(transferId)));
        }
    }

    void captureFeePolicy(long policyId, CoreFeePolicyState current) {
        if (!feePolicies.containsKey(policyId)) {
            feePolicies.putIfAbsent(policyId, wrap(current));
        }
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
                || !instrumentMaintenance.isEmpty() || !instrumentConfigurations.isEmpty() || !pendingTransfers.isEmpty()
                || !feePolicies.isEmpty() || nextLiquidationIdChanged || marketRevisionChanged
                || riskScanControlChanged;
    }

    /** Restore account-owned cold state in its own Lane. */
    void restoreLane(AccountLaneState lane) {
        lane.assertOwner();
        int laneId = lane.laneId();
        liquidations.forEach((id, val) -> {
            LiquidationRuntime current = lane.cold.liquidations.remove(id);
            if (current != null) TradingRuntimeState.removeActiveLiquidation(lane, current);
            LiquidationRuntime restored = unwrap(val);
            if (restored != null && state.topology.accountLaneId(restored.userId()) == laneId) {
                lane.cold.liquidations.put(id, restored);
                TradingRuntimeState.indexActiveLiquidation(lane, restored);
            }
        });
        riskSnapshots.forEach((key, val) -> {
            lane.cold.riskSnapshots.remove(key);
            RiskSnapshotRuntime restored = unwrap(val);
            if (restored != null && state.topology.accountLaneId(restored.userId()) == laneId)
                lane.cold.riskSnapshots.put(key, restored);
        });
        leverages.forEach((key, val) -> {
            if (state.topology.accountLaneId(key.userId()) != laneId) return;
            Long restored = unwrap(val);
            if (restored == null) {
                lane.cold.leverages.remove(key);
                Set<CoreLeverageKey> keys = lane.cold.leverageKeysByUser.get(key.userId());
                if (keys != null) {
                    keys.remove(key);
                    if (keys.isEmpty()) lane.cold.leverageKeysByUser.remove(key.userId());
                }
            } else {
                lane.cold.leverages.put(key, restored);
                lane.cold.leverageKeysByUser.getIfAbsentPut(key.userId(), HashSet::new).add(key);
            }
        });
        algoOrders.forEach((id, val) -> {
            lane.cold.algoOrders.remove(id);
            CoreAlgoOrderState restored = unwrap(val);
            if (restored != null && state.topology.accountLaneId(restored.userId()) == laneId)
                lane.cold.algoOrders.put(id, restored);
        });
        triggerOrders.forEach((id, val) -> {
            lane.removeTrigger(id);
            CoreTriggerOrderState restored = unwrap(val);
            if (restored != null && state.topology.accountLaneId(restored.userId()) == laneId)
                lane.putTrigger(restored);
        });
    }

    /** Called only after every Lane has restored its account and cold state. */
    void restoreOwner() {
        state.assertOwner();
        timers.forEach((key, val) -> TradingRuntimeState.putOrRemove(state.cancelAllAfterTimers, key, unwrap(val)));
        markPrices.forEach((id, val) -> TradingRuntimeState.putOrRemove(state.markPrices, id, unwrap(val)));
        riskScans.forEach((id, val) -> TradingRuntimeState.putOrRemove(state.riskScans, id, unwrap(val)));
        registeredInstruments.forEach(state.instruments::remove);
        instrumentMaintenance.forEach((instrumentId, maintenance) -> {
            CoreInstrument instrument = state.instruments.get(instrumentId);
            if (instrument != null) instrument.updateMaintenance(maintenance);
        });
        instrumentConfigurations.forEach((instrumentId, configuration) -> {
            CoreInstrument instrument = state.instruments.get(instrumentId);
            if (instrument != null) instrument.restoreConfiguration(configuration);
        });
        pendingTransfers.forEach((id, val) -> TradingRuntimeState.putOrRemove(state.pendingTransfers, id, unwrap(val)));
        feePolicies.forEach((id, val) -> TradingRuntimeState.putOrRemove(state.feePolicies, id, unwrap(val)));
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
        instrumentConfigurations = clearCaptured(instrumentConfigurations);
        pendingTransfers = clearCaptured(pendingTransfers);
        feePolicies = clearCaptured(feePolicies);
        nextLiquidationIdChanged = false;
        marketRevisionChanged = false;
        riskScanControlChanged = false;
    }

    @SuppressWarnings("unchecked")
    static <K, V> ConcurrentHashMap<K, V> clearCaptured(ConcurrentHashMap<K, V> values) {
        if (values.isEmpty()) return values;
        if (values.size() >= TradingRuntimeState.CHANGE_KEY_COMPACTION_THRESHOLD) return new ConcurrentHashMap<>();
        values.clear();
        return values;
    }
}
