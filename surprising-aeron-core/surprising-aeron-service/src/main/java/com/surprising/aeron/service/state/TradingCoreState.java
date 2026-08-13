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
        CoreTreasuryState treasuryState) {

    public TradingCoreState {
        if (productLine == null || revision < 0 || users == null || orders == null || bookState == null
                || instruments == null || riskState == null || treasuryState == null) {
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
            hash = CoreStateHash.mix(hash, liquidation.positionSide().wireCode());
            hash = CoreStateHash.mix(hash, liquidation.instrumentVersion());
            hash = CoreStateHash.mix(hash, liquidation.triggerPriceSequence());
            hash = CoreStateHash.mix(hash, liquidation.closeQuantitySteps());
            hash = CoreStateHash.mix(hash, liquidation.deficitUnits());
            hash = CoreStateHash.mix(hash, liquidation.status().ordinal());
        }
        hash = CoreStateHash.mix(hash, riskState.scan().symbol());
        hash = CoreStateHash.mix(hash, riskState.scan().priceSequence());
        hash = CoreStateHash.mix(hash, riskState.scan().lastUserId());
        hash = CoreStateHash.mix(hash, riskState.scan().complete());
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
        return user == null ? 0 : hashUser(CoreStateHash.start(), user);
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
        hash = CoreStateHash.mix(hash, order.status().ordinal());
        return CoreStateHash.mix(hash, order.revision());
    }
}
