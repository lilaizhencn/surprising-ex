package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreBalanceView;
import com.surprising.aeron.protocol.CoreLeverageView;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CorePositionView;
import com.surprising.aeron.protocol.CoreReservationView;
import com.surprising.aeron.protocol.CoreUserStateView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class RuntimeStateQueryService {

    public static final int MAX_USER_QUERY_ENTITIES = 4_096;

    private RuntimeStateQueryService() {
    }

    public static UserQueryResult userState(
            TradingRuntimeState runtime,
            RuntimeIdentityRegistry identities,
            long userId) {
        UserRuntime user = runtime.user(userId);
        if (user == null) return UserQueryResult.notFound();

        var balancesByAsset = runtime.balancesForUser(userId);
        int balanceCount = balancesByAsset == null ? 0 : balancesByAsset.size();
        int reservationCount = runtime.reservationCountForUser(userId);
        int positionCount = runtime.positionCountForUser(userId);
        var leverageKeys = runtime.leverageKeysForUser(userId);
        int entityCount = Math.addExact(balanceCount,
                Math.addExact(reservationCount, Math.addExact(positionCount, leverageKeys.size())));
        if (entityCount > MAX_USER_QUERY_ENTITIES) return UserQueryResult.oversized();
        long[] reservationIds = runtime.reservationIdsForUser(userId).toArray();
        long[] positionKeys = runtime.positionKeysForUser(userId).toArray();

        ArrayList<CoreBalanceView> balances = new ArrayList<>(balanceCount);
        if (balancesByAsset != null) {
            balancesByAsset.forEachValue(balance -> balances.add(new CoreBalanceView(
                    identities.asset(balance.assetId()), balance.availableUnits(), balance.lockedUnits())));
            balances.sort(Comparator.comparing(CoreBalanceView::asset));
        }

        Arrays.sort(reservationIds);
        ArrayList<CoreReservationView> reservations = new ArrayList<>(reservationIds.length);
        for (long orderId : reservationIds) {
            ReservationRuntime reservation = runtime.reservation(orderId);
            if (reservation == null) continue;
            reservations.add(new CoreReservationView(reservation.orderId(),
                    identities.symbol(reservation.symbolId()), reservation.instrumentVersion(), reservation.kind(),
                    identities.asset(reservation.assetId()), reservation.totalReservedUnits(),
                    reservation.releasedUnits(), reservation.consumedUnits(), reservation.orderQuantitySteps()));
        }

        ArrayList<PositionEntry> positionEntries = new ArrayList<>(positionKeys.length);
        for (long positionKey : positionKeys) {
            PositionRuntime position = runtime.position(positionKey);
            if (position == null) continue;
            String symbol = identities.symbol(position.symbolId());
            CorePositionView view = new CorePositionView(symbol, identities.asset(position.assetId()),
                    position.marginMode(), position.positionSide(), position.instrumentVersion(),
                    position.signedQuantitySteps(), position.entryPriceTicks(), position.entryValueTicks(),
                    position.realizedPnlUnits(), position.positionMarginUnits());
            String key = position.positionSide() == com.surprising.aeron.protocol.CorePositionSide.NET
                    ? symbol : symbol + ':' + position.positionSide().name();
            positionEntries.add(new PositionEntry(key, view));
        }
        positionEntries.sort(Comparator.comparing(PositionEntry::key));
        List<CorePositionView> positions = positionEntries.stream().map(PositionEntry::view).toList();

        ArrayList<CoreLeverageView> leverages = new ArrayList<>(leverageKeys.size());
        for (CoreLeverageKey key : leverageKeys) {
            leverages.add(new CoreLeverageView(key.symbol(), key.marginMode(), runtime.leverage(key)));
        }
        CoreUserStateView view = new CoreUserStateView(user.productLine(), user.userId(), user.revision(),
                user.positionMode(), balances, reservations, positions, leverages);
        return UserQueryResult.found(view, userStateHash(view));
    }

    public static OrderQueryResult orderState(
            TradingRuntimeState runtime,
            RuntimeIdentityRegistry identities,
            long orderId) {
        OrderRuntime order = runtime.order(orderId);
        if (order == null) return OrderQueryResult.notFound();
        CoreOrderStateView view = new CoreOrderStateView(order.orderId(), order.productLine(), order.userId(),
                identities.symbol(order.symbolId()), order.instrumentVersion(), order.side(), order.priceTicks(),
                order.quantitySteps(), order.executedQuantitySteps(), order.remainingQuantitySteps(),
                order.reduceOnly(), order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(),
                order.postOnly(), order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(),
                order.takerFeeRatePpm(), order.cumulativeFeeUnits(), order.createdAtEpochMillis(),
                order.updatedAtEpochMillis(), order.clusterPosition(), order.status().name(), order.revision());
        return OrderQueryResult.found(view, orderStateHash(order, identities.symbol(order.symbolId())));
    }

    public static OrderQueryResult clientOrderState(
            TradingRuntimeState runtime,
            RuntimeIdentityRegistry identities,
            long userId,
            String clientOrderId) {
        Long clientKey = identities.findClientKey(userId, clientOrderId);
        if (clientKey == null) return OrderQueryResult.notFound();
        Long orderId = runtime.orderIdByClient(userId, clientKey);
        return orderId == null ? OrderQueryResult.notFound() : orderState(runtime, identities, orderId);
    }

    private static long userStateHash(CoreUserStateView user) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), user.productLine().ordinal());
        hash = CoreStateHash.mix(hash, user.userId());
        hash = CoreStateHash.mix(hash, user.revision());
        hash = CoreStateHash.mix(hash, user.positionMode().wireCode());
        for (CoreBalanceView balance : user.balances()) {
            hash = CoreStateHash.mix(hash, balance.asset());
            hash = CoreStateHash.mix(hash, balance.availableUnits());
            hash = CoreStateHash.mix(hash, balance.lockedUnits());
        }
        for (CoreReservationView reservation : user.reservations()) {
            if (reservation.reservedUnits() - reservation.releasedUnits() - reservation.consumedUnits() == 0) {
                continue;
            }
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
        for (CorePositionView position : user.positions()) {
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
        for (CoreLeverageView leverage : user.leverages()) {
            hash = CoreStateHash.mix(hash, leverage.symbol());
            hash = CoreStateHash.mix(hash, leverage.marginMode().wireCode());
            hash = CoreStateHash.mix(hash, leverage.leveragePpm());
        }
        return hash;
    }

    private static long orderStateHash(OrderRuntime order, String symbol) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), order.orderId());
        hash = CoreStateHash.mix(hash, order.productLine().ordinal());
        hash = CoreStateHash.mix(hash, order.userId());
        hash = CoreStateHash.mix(hash, symbol);
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

    private record PositionEntry(String key, CorePositionView view) {
    }

    public record UserQueryResult(boolean found, boolean tooLarge, CoreUserStateView view, long stateHash) {
        static UserQueryResult found(CoreUserStateView view, long stateHash) {
            return new UserQueryResult(true, false, view, stateHash);
        }

        static UserQueryResult notFound() {
            return new UserQueryResult(false, false, null, 0);
        }

        static UserQueryResult oversized() {
            return new UserQueryResult(false, true, null, 0);
        }
    }

    public record OrderQueryResult(boolean found, CoreOrderStateView view, long stateHash) {
        static OrderQueryResult found(CoreOrderStateView view, long stateHash) {
            return new OrderQueryResult(true, view, stateHash);
        }

        static OrderQueryResult notFound() {
            return new OrderQueryResult(false, null, 0);
        }
    }
}
