package com.surprising.aeron.service.query;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.protocol.CoreBalanceView;
import com.surprising.aeron.protocol.CoreLeverageView;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CorePositionView;
import com.surprising.aeron.protocol.CoreReservationView;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.snapshot.state.CoreStateHash;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 账户 Lane 内的用户及订单只读查询。先按用户范围检查物化规模，再把整数身份转换成协议视图，并按固定字段顺序计算状态比较哈希。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StateQueries {

    public static final int MAX_USER_QUERY_ENTITIES = 4_096;

    /** 在指定 Lane 定位用户，先核对实体数量上限，再整理有序明细。 */
    public static UserQueryResult userState(
            AccountBook accountBook,
            AccountPartition lane,
            IdIndex identities,
            long userId, boolean hashOnly) {
        UserAccount user = accountBook.user(lane, userId);
        if (user == null) return UserQueryResult.notFound();

        var balancesByAsset = accountBook.balancesForUser(lane, userId);
        int balanceCount = balancesByAsset == null ? 0 : balancesByAsset.size();
        int reservationCount = accountBook.reservationCountForUser(lane, userId);
        int positionCount = accountBook.positionCountForUser(lane, userId);
        var leverageKeys = accountBook.leverageKeysForUser(lane, userId);
        int entityCount = Math.addExact(balanceCount,
                Math.addExact(reservationCount, Math.addExact(positionCount, leverageKeys.size())));
        if (entityCount > MAX_USER_QUERY_ENTITIES) return UserQueryResult.oversized();
        long[] reservationIds = accountBook.reservationIdsForUser(lane, userId);
        long[] positionKeys = accountBook.positionKeysForUser(lane, userId);

        ArrayList<CoreBalanceView> balances = new ArrayList<>(balanceCount);
        if (balancesByAsset != null) {
            balancesByAsset.forEachValue(balance -> balances.add(new CoreBalanceView(
                    identities.asset(balance.assetId()), balance.availableUnits(), balance.lockedUnits())));
            balances.sort(Comparator.comparing(CoreBalanceView::asset));
        }

        Arrays.sort(reservationIds);
        ArrayList<CoreReservationView> reservations = new ArrayList<>(reservationIds.length);
        for (long orderId : reservationIds) {
            OrderFundsReservation reservation = accountBook.reservation(lane, orderId);
            if (reservation == null) continue;
            reservations.add(new CoreReservationView(reservation.orderId(),
                    identities.instrumentId(reservation.symbolId()), reservation.kind(),
                    identities.asset(reservation.assetId()), reservation.totalReservedUnits(),
                    reservation.releasedUnits(), reservation.consumedUnits(), reservation.orderQuantitySteps()));
        }

        ArrayList<PositionEntry> positionEntries = new ArrayList<>(positionKeys.length);
        for (long positionKey : positionKeys) {
            Position position = accountBook.position(lane, positionKey);
            if (position == null) continue;
            String instrumentId = identities.instrumentId(position.symbolId());
            CorePositionView view = new CorePositionView(instrumentId, identities.asset(position.assetId()),
                    position.marginMode(), position.positionSide(),
                    position.signedQuantitySteps(), position.entryPriceTicks(), position.entryValueTicks(),
                    position.realizedPnlUnits(), position.positionMarginUnits());
            String key = position.positionSide() == com.surprising.aeron.protocol.CorePositionSide.NET
                    ? instrumentId : instrumentId + ':' + position.positionSide().name();
            positionEntries.add(new PositionEntry(key, view));
        }
        positionEntries.sort(Comparator.comparing(PositionEntry::key));
        List<CorePositionView> positions = positionEntries.stream().map(PositionEntry::view).toList();

        ArrayList<CoreLeverageView> leverages = new ArrayList<>(leverageKeys.size());
        for (CoreLeverageKey key : leverageKeys) {
            leverages.add(new CoreLeverageView(key.instrumentId(), key.marginMode(), accountBook.leverage(lane, key)));
        }
        if (hashOnly) return UserQueryResult.found(null,
                userStateHash(user, balances, reservations, positions, leverages));
        CoreUserStateView view = new CoreUserStateView(user.productLine(), user.userId(), user.revision(),
                user.positionMode(), balances, reservations, positions, leverages);
        return UserQueryResult.found(view, 0);
    }

    /** 在指定 Lane 读取订单，按请求构造视图或计算包括内部撮合价在内的状态哈希。 */
    public static OrderQueryResult orderState(
            AccountBook accountBook,
            AccountPartition lane,
            IdIndex identities,
            long orderId, boolean hashOnly) {
        Order order = accountBook.order(lane, orderId);
        if (order == null) return OrderQueryResult.notFound();
        if (hashOnly) return OrderQueryResult.found(null,
                orderStateHash(order, identities.instrumentId(order.symbolId())));
        CoreOrderStateView view = new CoreOrderStateView(order.orderId(), order.productLine(), order.userId(),
                identities.instrumentId(order.symbolId()), order.side(), order.priceTicks(),
                order.quantitySteps(), order.executedQuantitySteps(), order.remainingQuantitySteps(),
                order.reduceOnly(), order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(),
                order.postOnly(), order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(),
                order.takerFeeRatePpm(), order.cumulativeFeeUnits(), order.executedValueHigh(), order.executedValueLow(), order.createdAtEpochMillis(),
                order.updatedAtEpochMillis(), order.clusterPosition(), order.status().name(), order.revision());
        return OrderQueryResult.found(view, 0);
    }

    /** 先在该 Lane 定位用户的客户端订单身份，再查实际订单编号，任一身份或订单映射不存在时返回未找到，否则复用普通订单查询。 */
    public static OrderQueryResult clientOrderState(
            AccountBook accountBook,
            AccountPartition lane,
            IdIndex identities,
            long userId,
            String clientOrderId) {
        long clientKey = identities.findClientKeyValueInLane(lane, userId, clientOrderId);
        if (clientKey == 0) return OrderQueryResult.notFound();
        long orderId = accountBook.orderIdByClientValue(lane, userId, clientKey);
        return orderId == 0 ? OrderQueryResult.notFound() : orderState(accountBook, lane, identities, orderId, false);
    }

    /** 按用户身份、版本、模式及各视图列表的固定字段顺序混合状态哈希。 */
    private static long userStateHash(UserAccount user, List<CoreBalanceView> balances,
            List<CoreReservationView> reservations, List<CorePositionView> positions, List<CoreLeverageView> leverages) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(user.productLine()));
        hash = CoreStateHash.mix(hash, user.userId());
        hash = CoreStateHash.mix(hash, user.revision());
        hash = CoreStateHash.mix(hash, user.positionMode().wireCode());
        for (CoreBalanceView balance : balances) {
            hash = CoreStateHash.mix(hash, balance.asset());
            hash = CoreStateHash.mix(hash, balance.availableUnits());
            hash = CoreStateHash.mix(hash, balance.lockedUnits());
        }
        for (CoreReservationView reservation : reservations) {
            if (reservation.reservedUnits() - reservation.releasedUnits() - reservation.consumedUnits() == 0) {
                continue;
            }
            hash = CoreStateHash.mix(hash, reservation.orderId());
            hash = CoreStateHash.mix(hash, reservation.instrumentId());
            hash = CoreStateHash.mix(hash, reservation.kind().wireCode());
            hash = CoreStateHash.mix(hash, reservation.asset());
            hash = CoreStateHash.mix(hash, reservation.reservedUnits());
            hash = CoreStateHash.mix(hash, reservation.releasedUnits());
            hash = CoreStateHash.mix(hash, reservation.consumedUnits());
            hash = CoreStateHash.mix(hash, reservation.orderQuantitySteps());
        }
        for (CorePositionView position : positions) {
            hash = CoreStateHash.mix(hash, position.instrumentId());
            hash = CoreStateHash.mix(hash, position.marginAsset());
            hash = CoreStateHash.mix(hash, position.marginMode().wireCode());
            hash = CoreStateHash.mix(hash, position.positionSide().wireCode());
            hash = CoreStateHash.mix(hash, position.signedQuantitySteps());
            hash = CoreStateHash.mix(hash, position.entryPriceTicks());
            hash = CoreStateHash.mix(hash, position.entryValueTicks());
            hash = CoreStateHash.mix(hash, position.realizedPnlUnits());
            hash = CoreStateHash.mix(hash, position.positionMarginUnits());
        }
        for (CoreLeverageView leverage : leverages) {
            hash = CoreStateHash.mix(hash, leverage.instrumentId());
            hash = CoreStateHash.mix(hash, leverage.marginMode().wireCode());
            hash = CoreStateHash.mix(hash, leverage.leveragePpm());
        }
        return hash;
    }

    private static long orderStateHash(Order order, String instrumentId) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), order.orderId());
        hash = CoreStateHash.mix(hash, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(order.productLine()));
        hash = CoreStateHash.mix(hash, order.userId());
        hash = CoreStateHash.mix(hash, instrumentId);
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
        hash = CoreStateHash.mix(hash, order.executedValueHigh());
        hash = CoreStateHash.mix(hash, order.executedValueLow());
        hash = CoreStateHash.mix(hash, order.createdAtEpochMillis());
        hash = CoreStateHash.mix(hash, order.updatedAtEpochMillis());
        hash = CoreStateHash.mix(hash, order.clusterPosition());
        hash = CoreStateHash.mix(hash, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(order.status()));
        return CoreStateHash.mix(hash, order.revision());
    }

    /** 组装用户视图时的持仓排序项，把实际持仓视图与净仓或双向腿业务键配对，排序后再提取视图。 */
    private record PositionEntry(String key, CorePositionView view) {
    }

    /** 用户查询结果，分别表达找到、未找到或实体规模过大。 */
    public record UserQueryResult(boolean found, boolean tooLarge, CoreUserStateView view, long stateHash) {
        /** 封装找到的用户视图和哈希，明确关闭响应过大标记。 */
        static UserQueryResult found(CoreUserStateView view, long stateHash) {
            return new UserQueryResult(true, false, view, stateHash);
        }

        /** 封装用户不存在的结果，视图为空、哈希为 0，不能与实体过大混同。 */
        static UserQueryResult notFound() {
            return new UserQueryResult(false, false, null, 0);
        }

        /** 封装用户实体总量超过物化上限的结果，不返回部分视图。 */
        static UserQueryResult oversized() {
            return new UserQueryResult(false, true, null, 0);
        }
    }

    /** 订单查询结果，区分找到与不存在。 */
    public record OrderQueryResult(boolean found, CoreOrderStateView view, long stateHash) {
        /** 封装已经找到的订单协议视图和对应运行时状态哈希。 */
        static OrderQueryResult found(CoreOrderStateView view, long stateHash) {
            return new OrderQueryResult(true, view, stateHash);
        }

        /** 封装订单不存在的结果，视图为空且哈希为 0。 */
        static OrderQueryResult notFound() {
            return new OrderQueryResult(false, null, 0);
        }
    }
}
