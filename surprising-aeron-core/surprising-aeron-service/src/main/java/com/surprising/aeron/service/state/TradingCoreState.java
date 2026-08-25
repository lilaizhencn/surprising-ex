package com.surprising.aeron.service.state;

import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.Collections;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public record TradingCoreState(
        ProductLine productLine,
        long revision,
        Map<Long, CoreUserState> users,
        Map<Long, CoreOrderState> orders,
        Map<String, CoreInstrumentState> instruments,
        CoreRiskState riskState,
        CoreTreasuryState treasuryState,
        Map<CoreLeverageKey, Long> leverages,
        Map<Long, CoreAlgoOrderState> algoOrders,
        Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers,
        Map<ClientOrderKey, Long> clientOrderIndex,
        Map<Long, CoreTriggerOrderState> triggerOrders) {

    public TradingCoreState {
        if (productLine == null || revision < 0 || users == null || orders == null
                || instruments == null || riskState == null || treasuryState == null || leverages == null
                || algoOrders == null || cancelAllAfterTimers == null
                || triggerOrders == null) {
            throw new IllegalArgumentException("invalid trading core state");
        }
        boolean usersDelta = StateMapSupport.isDelta(users);
        boolean ordersDelta = StateMapSupport.isDelta(orders);
        Map<Long, CoreUserState> sortedUsers = StateMapSupport.freezeSorted(users);
        Map<Long, CoreOrderState> sortedOrders = StateMapSupport.freezeSorted(orders);
        if (!usersDelta) {
            sortedUsers.forEach((userId, user) -> validateUser(productLine, userId, user));
        } else {
            for (Object key : StateMapSupport.changedKeys(users)) {
                CoreUserState user = sortedUsers.get(key);
                if (user != null) validateUser(productLine, (Long) key, user);
            }
        }
        if (!ordersDelta) {
            sortedOrders.forEach((orderId, order) -> validateOrder(productLine, sortedUsers, orderId, order));
        } else {
            for (Object key : StateMapSupport.changedKeys(orders)) {
                CoreOrderState order = sortedOrders.get(key);
                if (order != null) validateOrder(productLine, sortedUsers, (Long) key, order);
            }
        }
        Map<ClientOrderKey, Long> derivedIndex;
        if (clientOrderIndex != null && StateMapSupport.isDelta(clientOrderIndex)) {
            derivedIndex = StateMapSupport.freezeSorted(clientOrderIndex);
            for (Object key : StateMapSupport.changedKeys(clientOrderIndex)) {
                Long orderId = derivedIndex.get(key);
                if (orderId != null) {
                    CoreOrderState order = sortedOrders.get(orderId);
                    if (order == null || order.clientOrderId().isEmpty()
                            || !new ClientOrderKey(order.userId(), order.clientOrderId()).equals(key)) {
                        throw new IllegalArgumentException("client order index does not match authoritative orders");
                    }
                }
            }
        } else if (clientOrderIndex == null) {
            throw new IllegalArgumentException("client order index is required for an authoritative state transition");
        } else {
            derivedIndex = StateMapSupport.freezeSorted(clientOrderIndex);
            if (!derivedIndex.equals(deriveClientOrderIndex(sortedOrders))) {
                throw new IllegalArgumentException("client order index does not match authoritative orders");
            }
        }
        users = sortedUsers;
        orders = sortedOrders;
        instruments = StateMapSupport.freezeSorted(instruments);
        leverages = StateMapSupport.freezeSorted(leverages);
        algoOrders = StateMapSupport.freezeSorted(algoOrders);
        Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> sortedTimers = StateMapSupport.freezeSorted(cancelAllAfterTimers);
        if (!StateMapSupport.isDelta(cancelAllAfterTimers)) {
            sortedTimers.forEach(TradingCoreState::validateTimer);
        } else {
            for (Object key : StateMapSupport.changedKeys(cancelAllAfterTimers)) {
                CoreCancelAllAfterState timer = sortedTimers.get(key);
                if (timer != null) validateTimer((CoreCancelAllAfterKey) key, timer);
            }
        }
        cancelAllAfterTimers = sortedTimers;
        clientOrderIndex = StateMapSupport.freezeSorted(derivedIndex);
        Map<Long, CoreTriggerOrderState> sortedTriggers = StateMapSupport.freezeSorted(triggerOrders);
        if (!StateMapSupport.isDelta(triggerOrders)) {
            sortedTriggers.forEach((id, trigger) -> validateTrigger(productLine, sortedUsers, id, trigger));
        } else {
            for (Object id : StateMapSupport.changedKeys(triggerOrders)) {
                CoreTriggerOrderState trigger = sortedTriggers.get(id);
                if (trigger != null) validateTrigger(productLine, sortedUsers, id, trigger);
            }
        }
        triggerOrders = sortedTriggers;
    }

    public TradingCoreState(ProductLine productLine, long revision, Map<Long, CoreUserState> users,
                            Map<Long, CoreOrderState> orders,
                            Map<String, CoreInstrumentState> instruments, CoreRiskState riskState,
                            CoreTreasuryState treasuryState) {
        this(productLine, revision, users, orders, instruments, riskState, treasuryState,
                Map.of(), Map.of(), Map.of(), deriveClientOrderIndex(orders), Map.of());
    }

    public TradingCoreState(ProductLine productLine, long revision, Map<Long, CoreUserState> users,
                            Map<Long, CoreOrderState> orders,
                            Map<String, CoreInstrumentState> instruments, CoreRiskState riskState,
                            CoreTreasuryState treasuryState, Map<CoreLeverageKey, Long> leverages) {
        this(productLine, revision, users, orders, instruments, riskState, treasuryState,
                leverages, Map.of(), Map.of(), deriveClientOrderIndex(orders), Map.of());
    }

    public TradingCoreState(ProductLine productLine, long revision, Map<Long, CoreUserState> users,
                            Map<Long, CoreOrderState> orders,
                            Map<String, CoreInstrumentState> instruments, CoreRiskState riskState,
                            CoreTreasuryState treasuryState, Map<CoreLeverageKey, Long> leverages,
                            Map<Long, CoreAlgoOrderState> algoOrders) {
        this(productLine, revision, users, orders, instruments, riskState, treasuryState,
                leverages, algoOrders, Map.of(), deriveClientOrderIndex(orders), Map.of());
    }

    public TradingCoreState(ProductLine productLine, long revision, Map<Long, CoreUserState> users,
                            Map<Long, CoreOrderState> orders,
                            Map<String, CoreInstrumentState> instruments, CoreRiskState riskState,
                            CoreTreasuryState treasuryState, Map<CoreLeverageKey, Long> leverages,
                            Map<Long, CoreAlgoOrderState> algoOrders,
                            Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers) {
        this(productLine, revision, users, orders, instruments, riskState, treasuryState,
                leverages, algoOrders, cancelAllAfterTimers, deriveClientOrderIndex(orders), Map.of());
    }

    public TradingCoreState(ProductLine productLine, long revision, Map<Long, CoreUserState> users,
                            Map<Long, CoreOrderState> orders,
                            Map<String, CoreInstrumentState> instruments, CoreRiskState riskState,
                            CoreTreasuryState treasuryState, Map<CoreLeverageKey, Long> leverages,
                            Map<Long, CoreAlgoOrderState> algoOrders,
                            Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers,
                            Map<Long, CoreTriggerOrderState> triggerOrders) {
        this(productLine, revision, users, orders, instruments, riskState, treasuryState,
                leverages, algoOrders, cancelAllAfterTimers, deriveClientOrderIndex(orders), triggerOrders);
    }

    public static TradingCoreState empty(ProductLine productLine) {
        return new TradingCoreState(productLine, 0, Map.of(), Map.of(),
                Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
    }

    public CoreUserState user(long userId) {
        return users.get(userId);
    }

    public CoreOrderState order(long orderId) {
        return orders.get(orderId);
    }

    public CoreOrderState order(long userId, String clientOrderId) {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            return null;
        }
        Long orderId = clientOrderIndex.get(new ClientOrderKey(userId, clientOrderId));
        return orderId == null ? null : orders.get(orderId);
    }

    public TradingCoreState stampOrderChanges(
            TradingCoreState before,
            long timestamp,
            long clusterPosition) {
        return stampOrderChanges(before, timestamp, clusterPosition, null);
    }

    public TradingCoreState stampOrderChanges(
            TradingCoreState before,
            long timestamp,
            long clusterPosition,
            Iterable<Long> changedOrderIds) {
        Map<Long, CoreOrderState> stamped = changedOrderIds == null
                ? new TreeMap<>(orders) : StateMapSupport.delta(orders);
        boolean changed = false;
        if (changedOrderIds == null) {
            for (CoreOrderState order : orders.values()) {
                CoreOrderState previous = before.orders().get(order.orderId());
                if (!order.equals(previous)) {
                    stamped.put(order.orderId(), order.withCommitMetadata(timestamp, clusterPosition));
                    changed = true;
                }
            }
        } else {
            for (Long orderId : changedOrderIds) {
                if (orderId == null) continue;
                CoreOrderState order = orders.get(orderId);
                if (order == null) continue;
                CoreOrderState previous = before.orders().get(orderId);
                if (!order.equals(previous)) {
                    stamped.put(orderId, order.withCommitMetadata(timestamp, clusterPosition));
                    changed = true;
                }
            }
        }
        if (!changed) return this;
        if (changedOrderIds == null) {
            return new TradingCoreState(productLine, revision, users, stamped,
                    instruments, riskState, treasuryState, leverages, algoOrders, cancelAllAfterTimers, triggerOrders);
        }
        return new TradingCoreState(productLine, revision, users, stamped,
                instruments, riskState, treasuryState, leverages, algoOrders, cancelAllAfterTimers,
                StateMapSupport.delta(clientOrderIndex), triggerOrders);
    }

    private static void validateUser(ProductLine productLine, long userId, CoreUserState user) {
        if (userId != user.userId() || user.productLine() != productLine) {
            throw new IllegalArgumentException("user state belongs to another partition");
        }
    }

    private static void validateOrder(ProductLine productLine, Map<Long, CoreUserState> users,
                                      long orderId, CoreOrderState order) {
        if (orderId != order.orderId() || order.productLine() != productLine
                || !users.containsKey(order.userId())) {
            throw new IllegalArgumentException("order state belongs to another partition");
        }
    }

    private static void validateTimer(CoreCancelAllAfterKey key, CoreCancelAllAfterState timer) {
        if (!key.equals(timer.key())) {
            throw new IllegalArgumentException("cancel-all-after key does not match authoritative timer");
        }
    }

    private static void validateTrigger(ProductLine productLine, Map<Long, CoreUserState> users,
                                         Object id, CoreTriggerOrderState trigger) {
        if (!id.equals(trigger.triggerOrderId()) || trigger.productLine() != productLine
                || !users.containsKey(trigger.userId())) {
            throw new IllegalArgumentException("trigger order state belongs to another partition");
        }
    }

    public long businessStateHash() {
        return RollingBusinessStateHash.compute(this);
    }

    long fullBusinessStateHash() {
        long hash = CoreStateHash.mix(CoreStateHash.start(), productLine.ordinal());
        hash = CoreStateHash.mix(hash, revision);
        for (CoreUserState user : users.values()) {
            hash = hashUser(hash, user);
        }
        for (CoreOrderState order : orders.values()) {
            if (order.status().terminal()) continue;
            hash = hashOrder(hash, order);
        }
        for (CoreInstrumentState instrument : instruments.values()) {
            hash = CoreStateHash.mix(hash, instrument.symbol());
            hash = CoreStateHash.mix(hash, instrument.version());
            hash = CoreStateHash.mix(hash, instrument.contractType().ordinal());
            hash = CoreStateHash.mix(hash, instrument.baseAsset());
            hash = CoreStateHash.mix(hash, instrument.quoteAsset());
            hash = CoreStateHash.mix(hash, instrument.settleAsset());
            hash = CoreStateHash.mix(hash, instrument.notionalMultiplierUnits());
            hash = CoreStateHash.mix(hash, instrument.priceTickUnits());
            hash = CoreStateHash.mix(hash, instrument.settleScaleUnits());
            hash = CoreStateHash.mix(hash, instrument.initialMarginRatePpm());
            hash = CoreStateHash.mix(hash, instrument.maintenanceMarginRatePpm());
            hash = CoreStateHash.mix(hash, instrument.makerFeeRatePpm());
            hash = CoreStateHash.mix(hash, instrument.takerFeeRatePpm());
            hash = CoreStateHash.mix(hash, instrument.expiryEpochMillis());
            hash = CoreStateHash.mix(hash, instrument.optionType() == null ? -1 : instrument.optionType().ordinal());
            hash = CoreStateHash.mix(hash, instrument.strikePriceTicks());
            hash = CoreStateHash.mix(hash, instrument.maxLeveragePpm());
            hash = CoreStateHash.mix(hash, instrument.maxPositionNotionalUnits());
            hash = CoreStateHash.mix(hash, instrument.userOpenInterestLimitRatePpm());
            hash = CoreStateHash.mix(hash, instrument.userOpenInterestLimitFloorUnits());
            for (var bracket : instrument.riskLimitBrackets()) {
                hash = CoreStateHash.mix(hash, bracket.bracketNo());
                hash = CoreStateHash.mix(hash, bracket.notionalFloorUnits());
                hash = CoreStateHash.mix(hash, bracket.notionalCapUnits());
                hash = CoreStateHash.mix(hash, bracket.maxLeveragePpm());
                hash = CoreStateHash.mix(hash, bracket.initialMarginRatePpm());
                hash = CoreStateHash.mix(hash, bracket.maintenanceMarginRatePpm());
            }
        }
        for (Map.Entry<CoreLeverageKey, Long> entry : leverages.entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey().userId());
            hash = CoreStateHash.mix(hash, entry.getKey().symbol());
            hash = CoreStateHash.mix(hash, entry.getKey().marginMode().wireCode());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        for (CoreAlgoOrderState algo : algoOrders.values()) {
            if (algo.terminal()) continue;
            hash = CoreStateHash.mix(hash, algo.algoOrderId()); hash = CoreStateHash.mix(hash, algo.userId());
            hash = CoreStateHash.mix(hash, algo.symbol()); hash = CoreStateHash.mix(hash, algo.statusCode());
            hash = CoreStateHash.mix(hash, algo.updatedAtEpochMillis()); hash = CoreStateHash.mix(hash, algo.revision());
            for (long childOrderId : algo.childOrderIds()) hash = CoreStateHash.mix(hash, childOrderId);
        }
        for (CoreCancelAllAfterState timer : cancelAllAfterTimers.values()) {
            hash = CoreStateHash.mix(hash, timer.userId());
            hash = CoreStateHash.mix(hash, timer.symbolScope());
            hash = CoreStateHash.mix(hash, timer.countdownMillis());
            hash = CoreStateHash.mix(hash, timer.status().wireCode());
            hash = CoreStateHash.mix(hash, timer.triggerAtEpochMillis());
            hash = CoreStateHash.mix(hash, timer.updatedAtEpochMillis());
            hash = CoreStateHash.mix(hash, timer.canceledOrders());
            hash = CoreStateHash.mix(hash, timer.canceledTriggerOrders());
            hash = CoreStateHash.mix(hash, timer.revision());
        }
        for (CoreTriggerOrderState trigger : triggerOrders.values()) {
            if (!trigger.status().open()) continue;
            hash = CoreStateHash.mix(hash, trigger.triggerOrderId());
            hash = CoreStateHash.mix(hash, trigger.userId());
            hash = CoreStateHash.mix(hash, trigger.symbol());
            hash = CoreStateHash.mix(hash, trigger.side().wireCode());
            hash = CoreStateHash.mix(hash, trigger.triggerType().ordinal());
            hash = CoreStateHash.mix(hash, trigger.triggerCondition().ordinal());
            hash = CoreStateHash.mix(hash, trigger.triggerPriceTicks());
            hash = CoreStateHash.mix(hash, trigger.highestPriceTicks());
            hash = CoreStateHash.mix(hash, trigger.lowestPriceTicks());
            hash = CoreStateHash.mix(hash, trigger.status().ordinal());
            hash = CoreStateHash.mix(hash, trigger.placedOrderId());
            hash = CoreStateHash.mix(hash, trigger.triggerSequence());
            hash = CoreStateHash.mix(hash, trigger.triggeredPriceTicks());
            hash = CoreStateHash.mix(hash, trigger.updatedAtEpochMillis());
            hash = CoreStateHash.mix(hash, trigger.revision());
        }
        for (CoreMarkPriceState mark : riskState.markPrices().values()) {
            hash = CoreStateHash.mix(hash, mark.symbol());
            hash = CoreStateHash.mix(hash, mark.instrumentVersion());
            hash = CoreStateHash.mix(hash, mark.markPriceTicks());
            hash = CoreStateHash.mix(hash, mark.priceSequence());
            hash = CoreStateHash.mix(hash, mark.generatedAtEpochMillis());
        }
        for (CoreRiskSnapshot risk : riskState.snapshots().values()) {
            hash = CoreStateHash.mix(hash, risk.userId());
            hash = CoreStateHash.mix(hash, risk.symbol());
            hash = CoreStateHash.mix(hash, risk.positionSide().wireCode());
            hash = CoreStateHash.mix(hash, risk.priceSequence());
            hash = CoreStateHash.mix(hash, risk.equityUnits());
            hash = CoreStateHash.mix(hash, risk.unrealizedPnlUnits());
            hash = CoreStateHash.mix(hash, risk.maintenanceMarginUnits());
            hash = CoreStateHash.mix(hash, risk.marginRatioPpm());
            hash = CoreStateHash.mix(hash, risk.status().ordinal());
        }
        for (CoreLiquidationState liquidation : riskState.liquidations().values()) {
            if (liquidation.terminal()) continue;
            hash = CoreStateHash.mix(hash, liquidation.liquidationId());
            hash = CoreStateHash.mix(hash, liquidation.userId());
            hash = CoreStateHash.mix(hash, liquidation.symbol());
            hash = CoreStateHash.mix(hash, liquidation.marginMode().wireCode());
            hash = CoreStateHash.mix(hash, liquidation.positionSide().wireCode());
            hash = CoreStateHash.mix(hash, liquidation.instrumentVersion());
            hash = CoreStateHash.mix(hash, liquidation.triggerPriceSequence());
            hash = CoreStateHash.mix(hash, liquidation.signedQuantitySteps());
            hash = CoreStateHash.mix(hash, liquidation.closeQuantitySteps());
            hash = CoreStateHash.mix(hash, liquidation.deficitUnits());
            hash = CoreStateHash.mix(hash, liquidation.executionPriceTicks());
            hash = CoreStateHash.mix(hash, liquidation.liquidationFeeRatePpm());
            hash = CoreStateHash.mix(hash, liquidation.liquidationFeeUnits());
            hash = CoreStateHash.mix(hash, liquidation.status().ordinal());
            hash = CoreStateHash.mix(hash, liquidation.nextCancelOrderId());
        }
        for (CoreRiskState.RiskScan scan : riskState.scans().values()) {
            hash = CoreStateHash.mix(hash, scan.symbol());
            hash = CoreStateHash.mix(hash, scan.priceSequence());
            hash = CoreStateHash.mix(hash, scan.scanStartPriceSequence());
            hash = CoreStateHash.mix(hash, scan.lastUserId());
            hash = CoreStateHash.mix(hash, scan.riskComplete());
            hash = CoreStateHash.mix(hash, scan.riskUserId());
            hash = CoreStateHash.mix(hash, scan.riskPhase());
            hash = CoreStateHash.mix(hash, scan.riskPositionCursor());
            hash = CoreStateHash.mix(hash, scan.riskReservationCursor());
            hash = CoreStateHash.mix(hash, scan.riskUnrealizedPnlUnits());
            hash = CoreStateHash.mix(hash, scan.riskMaintenanceMarginUnits());
            hash = CoreStateHash.mix(hash, scan.riskIsolatedMarginUnits());
            hash = CoreStateHash.mix(hash, scan.riskIsolatedReservationUnits());
            hash = CoreStateHash.mix(hash, scan.triggerComplete());
            hash = CoreStateHash.mix(hash, scan.triggerPhase());
            hash = CoreStateHash.mix(hash, scan.triggerPriceCursor());
            hash = CoreStateHash.mix(hash, scan.triggerOrderCursor());
            hash = CoreStateHash.mix(hash, scan.triggerUpperId());
            hash = CoreStateHash.mix(hash, scan.triggerMarkPriceTicks());
            hash = CoreStateHash.mix(hash, scan.triggerGeneratedAtEpochMillis());
            hash = CoreStateHash.mix(hash, scan.triggerOcoOrderId());
            hash = CoreStateHash.mix(hash, scan.triggerOcoCursor());
        }
        hash = CoreStateHash.mix(hash, riskState.nextLiquidationId());
        var scanControl = riskState.scanControl();
        hash = CoreStateHash.mix(hash, scanControl.version());
        hash = CoreStateHash.mix(hash, scanControl.ruleName());
        hash = CoreStateHash.mix(hash, scanControl.enabled());
        hash = CoreStateHash.mix(hash, scanControl.scanDelayMs());
        hash = CoreStateHash.mix(hash, scanControl.scanBatchSize());
        hash = CoreStateHash.mix(hash, scanControl.updatedBy());
        hash = CoreStateHash.mix(hash, scanControl.reason());
        hash = CoreStateHash.mix(hash, scanControl.updatedAtEpochMillis());
        for (Map.Entry<String, Long> entry : treasuryState.feeBalances().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        for (Map.Entry<String, Long> entry : treasuryState.insuranceBalances().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        for (Map.Entry<String, Long> entry : treasuryState.insuranceDeficits().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        for (Map.Entry<String, Long> entry : treasuryState.liquidationFeeBalances().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        for (Map.Entry<String, Long> entry : treasuryState.fundingResidualBalances().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        for (Map.Entry<String, Long> entry : treasuryState.roundingResidualBalances().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        for (Map.Entry<String, Long> entry : treasuryState.clearingPnlBalances().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        for (Map.Entry<String, Long> entry : treasuryState.fundingSettlements().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        for (Map.Entry<String, CoreTreasuryState.FundingProgress> entry : treasuryState.fundingProgress().entrySet()) {
            CoreTreasuryState.FundingProgress progress = entry.getValue();
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, progress.settlementId());
            hash = CoreStateHash.mix(hash, progress.instrumentVersion());
            hash = CoreStateHash.mix(hash, progress.fundingRatePpm());
            hash = CoreStateHash.mix(hash, progress.nextCursorUserId());
            hash = CoreStateHash.mix(hash, progress.commandId().getMostSignificantBits());
            hash = CoreStateHash.mix(hash, progress.commandId().getLeastSignificantBits());
        }
        for (Map.Entry<String, Long> entry : treasuryState.lifecycleSettlements().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        for (Map.Entry<String, CoreTreasuryState.LifecycleProgress> entry
                : treasuryState.lifecycleProgress().entrySet()) {
            CoreTreasuryState.LifecycleProgress progress = entry.getValue();
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, progress.settlementId());
            hash = CoreStateHash.mix(hash, progress.instrumentVersion());
            hash = CoreStateHash.mix(hash, progress.settlementPriceTicks());
            hash = CoreStateHash.mix(hash, progress.optionCashUnitsPerContract());
            hash = CoreStateHash.mix(hash, progress.ordersComplete());
            hash = CoreStateHash.mix(hash, progress.nextCursorOrderId());
            hash = CoreStateHash.mix(hash, progress.nextCursorUserId());
            hash = CoreStateHash.mix(hash, progress.commandId().getMostSignificantBits());
            hash = CoreStateHash.mix(hash, progress.commandId().getLeastSignificantBits());
        }
        return hash;
    }

    public long userStateHash(long userId) {
        CoreUserState user = users.get(userId);
        if (user == null) return 0;
        long hash = hashUser(CoreStateHash.start(), user);
        for (Map.Entry<CoreLeverageKey, Long> entry : leverages.entrySet()) {
            if (entry.getKey().userId() != userId) continue;
            hash = CoreStateHash.mix(hash, entry.getKey().symbol());
            hash = CoreStateHash.mix(hash, entry.getKey().marginMode().wireCode());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        return hash;
    }

    public long orderStateHash(long orderId) {
        CoreOrderState order = orders.get(orderId);
        return order == null ? 0 : hashOrder(CoreStateHash.start(), order);
    }

    public Set<Long> changedLiquidationIds() {
        return StateMapSupport.changedKeys(riskState.liquidations());
    }

    public Set<String> changedRiskSnapshotKeys() {
        return StateMapSupport.changedKeys(riskState.snapshots());
    }

    public Set<Long> changedUserIds() {
        return StateMapSupport.changedKeys(users);
    }

    public Set<Long> changedOrderIds() {
        return StateMapSupport.changedKeys(orders);
    }

    public Set<Long> changedTriggerOrderIds() {
        return StateMapSupport.changedKeys(triggerOrders);
    }


    public Set<Long> changedAlgoOrderIds() {
        return StateMapSupport.changedKeys(algoOrders);
    }

    public Set<ClientOrderKey> changedClientOrderKeys() {
        return StateMapSupport.changedKeys(clientOrderIndex);
    }

    public Set<CoreCancelAllAfterKey> changedCancelAllAfterKeys() {
        return StateMapSupport.changedKeys(cancelAllAfterTimers);
    }

    public Set<String> changedTreasuryAssets() {
        return treasuryState.changedAssets();
    }

    public void requireIncrementalLineage(TradingCoreState before) {
        if (before == null || before == this) return;
        requireLineage("users", before.users, users);
        requireLineage("orders", before.orders, orders);
        requireLineage("instruments", before.instruments, instruments);
        requireLineage("leverages", before.leverages, leverages);
        requireLineage("algo orders", before.algoOrders, algoOrders);
        requireLineage("cancel-all-after timers", before.cancelAllAfterTimers, cancelAllAfterTimers);
        requireLineage("client order index", before.clientOrderIndex, clientOrderIndex);
        requireLineage("trigger orders", before.triggerOrders, triggerOrders);
        requireLineage("mark prices", before.riskState.markPrices(), riskState.markPrices());
        requireLineage("risk snapshots", before.riskState.snapshots(), riskState.snapshots());
        requireLineage("liquidations", before.riskState.liquidations(), riskState.liquidations());
        requireLineage("risk scans", before.riskState.scans(), riskState.scans());
        requireLineage("fee balances", before.treasuryState.feeBalances(), treasuryState.feeBalances());
        requireLineage("insurance balances", before.treasuryState.insuranceBalances(), treasuryState.insuranceBalances());
        requireLineage("insurance deficits", before.treasuryState.insuranceDeficits(), treasuryState.insuranceDeficits());
        requireLineage("liquidation fee balances", before.treasuryState.liquidationFeeBalances(),
                treasuryState.liquidationFeeBalances());
        requireLineage("funding residual balances", before.treasuryState.fundingResidualBalances(),
                treasuryState.fundingResidualBalances());
        requireLineage("rounding residual balances", before.treasuryState.roundingResidualBalances(),
                treasuryState.roundingResidualBalances());
        requireLineage("clearing pnl balances", before.treasuryState.clearingPnlBalances(),
                treasuryState.clearingPnlBalances());
        requireLineage("funding settlements", before.treasuryState.fundingSettlements(), treasuryState.fundingSettlements());
        requireLineage("lifecycle settlements", before.treasuryState.lifecycleSettlements(), treasuryState.lifecycleSettlements());
        requireLineage("funding progress", before.treasuryState.fundingProgress(), treasuryState.fundingProgress());
        requireLineage("lifecycle progress", before.treasuryState.lifecycleProgress(), treasuryState.lifecycleProgress());
        for (Long userId : StateMapSupport.changedKeys(users)) {
            CoreUserState previous = before.users.get(userId);
            CoreUserState current = users.get(userId);
            if (previous != null && current != null) current.requireIncrementalLineage(previous);
        }
    }

    private static void requireLineage(String name, Map<?, ?> before, Map<?, ?> after) {
        if (before != after && !StateMapSupport.isDeltaDescendantOf(before, after)) {
            throw new IllegalStateException(name + " lineage is unavailable");
        }
    }

    private static void requireOnlineLineage(String name, Map<?, ?> before, Map<?, ?> after) {
        StateMapSupport.requireDeltaLineage(before, after, name);
    }

    private static void requireOnlineTreasuryLineage(String name, Map<?, ?> before, Map<?, ?> after) {
        if (before == after || java.util.Objects.equals(before, after)) return;
        if (!StateMapSupport.isDeltaDescendantOf(before, after)) {
            throw new IllegalStateException("online treasury transition is not a delta: " + name);
        }
    }

    public void requireOnlineDeltaLineage(TradingCoreState before) {
        if (before == null || before.productLine() != productLine) {
            throw new IllegalArgumentException("invalid online runtime transition");
        }
        requireOnlineLineage("users", before.users, users);
        requireOnlineLineage("orders", before.orders, orders);
        requireOnlineLineage("instruments", before.instruments, instruments);
        requireOnlineLineage("leverages", before.leverages, leverages);
        requireOnlineLineage("algo orders", before.algoOrders, algoOrders);
        requireOnlineLineage("cancel-all-after timers", before.cancelAllAfterTimers, cancelAllAfterTimers);
        requireOnlineLineage("client order index", before.clientOrderIndex, clientOrderIndex);
        requireOnlineLineage("trigger orders", before.triggerOrders, triggerOrders);
        requireOnlineLineage("mark prices", before.riskState.markPrices(), riskState.markPrices());
        requireOnlineLineage("risk snapshots", before.riskState.snapshots(), riskState.snapshots());
        requireOnlineLineage("liquidations", before.riskState.liquidations(), riskState.liquidations());
        requireOnlineLineage("risk scans", before.riskState.scans(), riskState.scans());
        requireOnlineTreasuryLineage("fee balances", before.treasuryState.feeBalances(), treasuryState.feeBalances());
        requireOnlineTreasuryLineage("insurance balances", before.treasuryState.insuranceBalances(), treasuryState.insuranceBalances());
        requireOnlineTreasuryLineage("insurance deficits", before.treasuryState.insuranceDeficits(), treasuryState.insuranceDeficits());
        requireOnlineTreasuryLineage("liquidation fee balances", before.treasuryState.liquidationFeeBalances(),
                treasuryState.liquidationFeeBalances());
        requireOnlineTreasuryLineage("funding residual balances", before.treasuryState.fundingResidualBalances(),
                treasuryState.fundingResidualBalances());
        requireOnlineTreasuryLineage("rounding residual balances", before.treasuryState.roundingResidualBalances(),
                treasuryState.roundingResidualBalances());
        requireOnlineTreasuryLineage("clearing pnl balances", before.treasuryState.clearingPnlBalances(),
                treasuryState.clearingPnlBalances());
        requireOnlineTreasuryLineage("funding settlements", before.treasuryState.fundingSettlements(), treasuryState.fundingSettlements());
        requireOnlineTreasuryLineage("lifecycle settlements", before.treasuryState.lifecycleSettlements(), treasuryState.lifecycleSettlements());
        requireOnlineTreasuryLineage("funding progress", before.treasuryState.fundingProgress(), treasuryState.fundingProgress());
        requireOnlineTreasuryLineage("lifecycle progress", before.treasuryState.lifecycleProgress(), treasuryState.lifecycleProgress());
        for (Long userId : StateMapSupport.changedKeys(before.users, users)) {
            CoreUserState previous = before.users.get(userId);
            CoreUserState current = users.get(userId);
            if (previous != null && current != null) current.requireIncrementalLineage(previous);
        }
    }

    static long hashUser(long initial, CoreUserState user) {
        long hash = CoreStateHash.mix(initial, user.productLine().ordinal());
        hash = CoreStateHash.mix(hash, user.userId());
        hash = CoreStateHash.mix(hash, user.revision());
        hash = CoreStateHash.mix(hash, user.positionMode().wireCode());
        for (AssetBalance balance : user.balances().values()) {
            hash = CoreStateHash.mix(hash, balance.asset());
            hash = CoreStateHash.mix(hash, balance.availableUnits());
            hash = CoreStateHash.mix(hash, balance.lockedUnits());
        }
        for (OrderReservation reservation : user.reservations().values()) {
            if (reservation.remainingUnits() == 0) continue;
            hash = CoreStateHash.mix(hash, reservation.orderId());
            hash = CoreStateHash.mix(hash, reservation.symbol());
            hash = CoreStateHash.mix(hash, reservation.instrumentVersion());
            hash = CoreStateHash.mix(hash, reservation.kind().wireCode());
            hash = CoreStateHash.mix(hash, reservation.asset());
            hash = CoreStateHash.mix(hash, reservation.reservedUnits());
            hash = CoreStateHash.mix(hash, reservation.releasedUnits());
            hash = CoreStateHash.mix(hash, reservation.consumedUnits());
            hash = CoreStateHash.mix(hash, reservation.orderQuantitySteps());
        }
        for (CorePositionState position : user.positions().values()) {
            hash = CoreStateHash.mix(hash, position.symbol());
            hash = CoreStateHash.mix(hash, position.marginAsset());
            hash = CoreStateHash.mix(hash, position.marginMode().wireCode());
            hash = CoreStateHash.mix(hash, position.positionSide().wireCode());
            hash = CoreStateHash.mix(hash, position.instrumentVersion());
            hash = CoreStateHash.mix(hash, position.signedQuantitySteps());
            hash = CoreStateHash.mix(hash, position.entryPriceTicks());
            hash = CoreStateHash.mix(hash, position.entryValueTicks());
            hash = CoreStateHash.mix(hash, position.realizedPnlUnits());
            hash = CoreStateHash.mix(hash, position.positionMarginUnits());
        }
        return hash;
    }

    static long hashOrder(long initial, CoreOrderState order) {
        long hash = CoreStateHash.mix(initial, order.orderId());
        hash = CoreStateHash.mix(hash, order.productLine().ordinal());
        hash = CoreStateHash.mix(hash, order.userId());
        hash = CoreStateHash.mix(hash, order.symbol());
        hash = CoreStateHash.mix(hash, order.instrumentVersion());
        hash = CoreStateHash.mix(hash, order.side().wireCode());
        hash = CoreStateHash.mix(hash, order.priceTicks());
        hash = CoreStateHash.mix(hash, order.matchingPriceTicks());
        hash = CoreStateHash.mix(hash, order.quantitySteps());
        hash = CoreStateHash.mix(hash, order.executedQuantitySteps());
        hash = CoreStateHash.mix(hash, order.remainingQuantitySteps());
        hash = CoreStateHash.mix(hash, order.reduceOnly());
        hash = CoreStateHash.mix(hash, order.marginMode().wireCode());
        hash = CoreStateHash.mix(hash, order.positionSide().wireCode());
        hash = CoreStateHash.mix(hash, order.orderType().wireCode());
        hash = CoreStateHash.mix(hash, order.timeInForce().wireCode());
        hash = CoreStateHash.mix(hash, order.postOnly());
        hash = CoreStateHash.mix(hash, order.clientOrderId());
        hash = CoreStateHash.mix(hash, order.commandId().getMostSignificantBits());
        hash = CoreStateHash.mix(hash, order.commandId().getLeastSignificantBits());
        hash = CoreStateHash.mix(hash, order.makerFeeRatePpm());
        hash = CoreStateHash.mix(hash, order.takerFeeRatePpm());
        hash = CoreStateHash.mix(hash, order.cumulativeFeeUnits());
        hash = CoreStateHash.mix(hash, order.createdAtEpochMillis());
        hash = CoreStateHash.mix(hash, order.updatedAtEpochMillis());
        hash = CoreStateHash.mix(hash, order.clusterPosition());
        hash = CoreStateHash.mix(hash, order.status().ordinal());
        return CoreStateHash.mix(hash, order.revision());
    }

    private static Map<ClientOrderKey, Long> deriveClientOrderIndex(Map<Long, CoreOrderState> orders) {
        Map<ClientOrderKey, Long> index = new TreeMap<>();
        if (orders != null) {
            orders.values().stream().filter(order -> !order.clientOrderId().isEmpty()).forEach(order -> {
                ClientOrderKey key = new ClientOrderKey(order.userId(), order.clientOrderId());
                if (index.put(key, order.orderId()) != null) {
                    throw new IllegalArgumentException("duplicate clientOrderId for user");
                }
            });
        }
        return index;
    }

    public record ClientOrderKey(long userId, String clientOrderId) implements Comparable<ClientOrderKey> {
        public ClientOrderKey {
            if (userId <= 0 || clientOrderId == null || clientOrderId.isBlank()) {
                throw new IllegalArgumentException("invalid client order key");
            }
        }

        @Override
        public int compareTo(ClientOrderKey other) {
            int userComparison = Long.compare(userId, other.userId);
            return userComparison != 0 ? userComparison : clientOrderId.compareTo(other.clientOrderId);
        }
    }
}
