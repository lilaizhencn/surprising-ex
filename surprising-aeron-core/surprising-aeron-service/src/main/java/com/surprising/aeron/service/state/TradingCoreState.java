package com.surprising.aeron.service.state;

import com.surprising.product.api.ProductLine;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record TradingCoreState(
        ProductLine productLine,
        long revision,
        Map<Long, CoreUserState> users,
        Map<Long, CoreOrderState> orders,
        CoreBookState bookState,
        Map<String, CoreInstrumentState> instruments,
        CoreRiskState riskState,
        CoreTreasuryState treasuryState,
        Map<CoreLeverageKey, Long> leverages,
        Map<Long, CoreAlgoOrderState> algoOrders,
        Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers,
        Map<ClientOrderKey, Long> clientOrderIndex,
        Map<Long, CoreTriggerOrderState> triggerOrders) {

    public TradingCoreState {
        if (productLine == null || revision < 0 || users == null || orders == null || bookState == null
                || instruments == null || riskState == null || treasuryState == null || leverages == null
                || algoOrders == null || cancelAllAfterTimers == null || clientOrderIndex == null
                || triggerOrders == null) {
            throw new IllegalArgumentException("invalid trading core state");
        }
        Map<Long, CoreUserState> sortedUsers = Collections.unmodifiableMap(new TreeMap<>(users));
        Map<Long, CoreOrderState> sortedOrders = Collections.unmodifiableMap(new TreeMap<>(orders));
        sortedUsers.forEach((userId, user) -> {
            if (userId != user.userId() || user.productLine() != productLine) {
                throw new IllegalArgumentException("user state belongs to another partition");
            }
        });
        sortedOrders.forEach((orderId, order) -> {
            if (orderId != order.orderId() || order.productLine() != productLine
                    || !sortedUsers.containsKey(order.userId())) {
                throw new IllegalArgumentException("order state belongs to another partition");
            }
        });
        Map<ClientOrderKey, Long> derivedIndex = new TreeMap<>();
        sortedOrders.values().stream().filter(order -> !order.clientOrderId().isEmpty()).forEach(order -> {
            ClientOrderKey key = new ClientOrderKey(order.userId(), order.clientOrderId());
            if (derivedIndex.put(key, order.orderId()) != null) {
                throw new IllegalArgumentException("duplicate clientOrderId for user");
            }
        });
        if (!derivedIndex.equals(clientOrderIndex)) {
            throw new IllegalArgumentException("client order index does not match authoritative orders");
        }
        bookState.openOrders().forEach((orderId, bookOrder) -> {
            CoreOrderState order = sortedOrders.get(orderId);
            if (order == null || order.status() != CoreOrderStatus.OPEN
                    || order.userId() != bookOrder.userId() || !order.symbol().equals(bookOrder.symbol())
                    || order.side() != bookOrder.side() || order.priceTicks() != bookOrder.priceTicks()
                    || order.remainingQuantitySteps() != bookOrder.remainingQuantitySteps()) {
                throw new IllegalArgumentException("book order does not match authoritative order state");
            }
        });
        users = sortedUsers;
        orders = sortedOrders;
        instruments = Collections.unmodifiableMap(new TreeMap<>(instruments));
        leverages = Collections.unmodifiableMap(new TreeMap<>(leverages));
        algoOrders = Collections.unmodifiableMap(new TreeMap<>(algoOrders));
        Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> sortedTimers = new TreeMap<>(cancelAllAfterTimers);
        sortedTimers.forEach((key, timer) -> {
            if (!key.equals(timer.key())) {
                throw new IllegalArgumentException("cancel-all-after key does not match authoritative timer");
            }
        });
        cancelAllAfterTimers = Collections.unmodifiableMap(sortedTimers);
        clientOrderIndex = Collections.unmodifiableMap(derivedIndex);
        Map<Long, CoreTriggerOrderState> sortedTriggers = new TreeMap<>(triggerOrders);
        sortedTriggers.forEach((id, trigger) -> {
            if (id != trigger.triggerOrderId() || trigger.productLine() != productLine
                    || !sortedUsers.containsKey(trigger.userId())) {
                throw new IllegalArgumentException("trigger order state belongs to another partition");
            }
        });
        triggerOrders = Collections.unmodifiableMap(sortedTriggers);
    }

    public TradingCoreState(ProductLine productLine, long revision, Map<Long, CoreUserState> users,
                            Map<Long, CoreOrderState> orders, CoreBookState bookState,
                            Map<String, CoreInstrumentState> instruments, CoreRiskState riskState,
                            CoreTreasuryState treasuryState) {
        this(productLine, revision, users, orders, bookState, instruments, riskState, treasuryState,
                Map.of(), Map.of(), Map.of(), deriveClientOrderIndex(orders), Map.of());
    }

    public TradingCoreState(ProductLine productLine, long revision, Map<Long, CoreUserState> users,
                            Map<Long, CoreOrderState> orders, CoreBookState bookState,
                            Map<String, CoreInstrumentState> instruments, CoreRiskState riskState,
                            CoreTreasuryState treasuryState, Map<CoreLeverageKey, Long> leverages) {
        this(productLine, revision, users, orders, bookState, instruments, riskState, treasuryState,
                leverages, Map.of(), Map.of(), deriveClientOrderIndex(orders), Map.of());
    }

    public TradingCoreState(ProductLine productLine, long revision, Map<Long, CoreUserState> users,
                            Map<Long, CoreOrderState> orders, CoreBookState bookState,
                            Map<String, CoreInstrumentState> instruments, CoreRiskState riskState,
                            CoreTreasuryState treasuryState, Map<CoreLeverageKey, Long> leverages,
                            Map<Long, CoreAlgoOrderState> algoOrders) {
        this(productLine, revision, users, orders, bookState, instruments, riskState, treasuryState,
                leverages, algoOrders, Map.of(), deriveClientOrderIndex(orders), Map.of());
    }

    public TradingCoreState(ProductLine productLine, long revision, Map<Long, CoreUserState> users,
                            Map<Long, CoreOrderState> orders, CoreBookState bookState,
                            Map<String, CoreInstrumentState> instruments, CoreRiskState riskState,
                            CoreTreasuryState treasuryState, Map<CoreLeverageKey, Long> leverages,
                            Map<Long, CoreAlgoOrderState> algoOrders,
                            Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers) {
        this(productLine, revision, users, orders, bookState, instruments, riskState, treasuryState,
                leverages, algoOrders, cancelAllAfterTimers, deriveClientOrderIndex(orders), Map.of());
    }

    public TradingCoreState(ProductLine productLine, long revision, Map<Long, CoreUserState> users,
                            Map<Long, CoreOrderState> orders, CoreBookState bookState,
                            Map<String, CoreInstrumentState> instruments, CoreRiskState riskState,
                            CoreTreasuryState treasuryState, Map<CoreLeverageKey, Long> leverages,
                            Map<Long, CoreAlgoOrderState> algoOrders,
                            Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers,
                            Map<Long, CoreTriggerOrderState> triggerOrders) {
        this(productLine, revision, users, orders, bookState, instruments, riskState, treasuryState,
                leverages, algoOrders, cancelAllAfterTimers, deriveClientOrderIndex(orders), triggerOrders);
    }

    public static TradingCoreState empty(ProductLine productLine) {
        return new TradingCoreState(productLine, 0, Map.of(), Map.of(), CoreBookState.empty(),
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
        Map<Long, CoreOrderState> stamped = new TreeMap<>(orders);
        boolean changed = false;
        for (CoreOrderState order : orders.values()) {
            CoreOrderState previous = before.orders().get(order.orderId());
            if (!order.equals(previous)) {
                stamped.put(order.orderId(), order.withCommitMetadata(timestamp, clusterPosition));
                changed = true;
            }
        }
        return changed ? new TradingCoreState(productLine, revision, users, stamped, bookState,
                instruments, riskState, treasuryState, leverages, algoOrders, cancelAllAfterTimers) : this;
    }

    public long businessStateHash() {
        long hash = CoreStateHash.mix(CoreStateHash.start(), productLine.ordinal());
        hash = CoreStateHash.mix(hash, revision);
        for (CoreUserState user : users.values()) {
            hash = hashUser(hash, user);
        }
        for (CoreOrderState order : orders.values()) {
            hash = hashOrder(hash, order);
        }
        hash = CoreStateHash.mix(hash, bookState.stateHash(null));
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
        }
        for (CoreRiskState.RiskScan scan : riskState.scans().values()) {
            hash = CoreStateHash.mix(hash, scan.symbol());
            hash = CoreStateHash.mix(hash, scan.priceSequence());
            hash = CoreStateHash.mix(hash, scan.scanStartPriceSequence());
            hash = CoreStateHash.mix(hash, scan.lastUserId());
            hash = CoreStateHash.mix(hash, scan.complete());
        }
        hash = CoreStateHash.mix(hash, riskState.nextLiquidationId());
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
        for (Map.Entry<String, Long> entry : treasuryState.fundingSettlements().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        for (Map.Entry<String, Long> entry : treasuryState.lifecycleSettlements().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        return hash;
    }

    public long bookStateHash(String symbol) {
        return bookState.stateHash(symbol);
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

    private static long hashUser(long initial, CoreUserState user) {
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

    private static long hashOrder(long initial, CoreOrderState order) {
        long hash = CoreStateHash.mix(initial, order.orderId());
        hash = CoreStateHash.mix(hash, order.productLine().ordinal());
        hash = CoreStateHash.mix(hash, order.userId());
        hash = CoreStateHash.mix(hash, order.symbol());
        hash = CoreStateHash.mix(hash, order.instrumentVersion());
        hash = CoreStateHash.mix(hash, order.side().wireCode());
        hash = CoreStateHash.mix(hash, order.priceTicks());
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
