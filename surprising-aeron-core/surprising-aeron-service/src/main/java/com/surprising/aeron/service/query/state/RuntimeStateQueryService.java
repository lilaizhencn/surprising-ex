package com.surprising.aeron.service.query.state;

import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.protocol.CoreBalanceView;
import com.surprising.aeron.protocol.CoreLeverageView;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CorePositionView;
import com.surprising.aeron.protocol.CoreReservationView;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.account.UserRuntime;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.core.TradingRuntimeState;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.order.state.ReservationRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.snapshot.state.CoreStateHash;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 账户 Lane 内的用户及订单只读查询。先按用户范围检查物化规模，再把整数身份转换成协议视图，并按固定字段顺序计算状态比较哈希；缺失和响应过大使用独立结果标记。 构造约定：只提供账户视图组装和状态比较计算，不另存账户权威数据。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeStateQueryService {

    /** 单用户完整查询允许的余额、冻结、持仓及杠杆实体数量合计上限 4096。 */
    public static final int MAX_USER_QUERY_ENTITIES = 4_096;

    /**
     * 在指定 Lane 定位用户，先统计余额、冻结、持仓和杠杆记录总量，超限则不物化；随后组装按资产、订单编号和持仓业务键排序的视图，杠杆沿原键集合顺序读取，并返回用户状态哈希。
     * @param runtime 当前查询运行时，账户实体通过显式 Lane 参数读取
     * @param lane 目标用户或订单所属的账户 Lane，调用方应在合法读取范围使用
     * @param identities 账户内资产、币对、客户端及持仓业务身份的映射
     * @param userId 本次用户或客户端订单查询所属的用户编号
     */
    public static UserQueryResult userState(
            TradingRuntimeState runtime,
            AccountLaneState lane,
            RuntimeIdentityRegistry identities,
            long userId) {
        // 指定账户 Lane 内的用户记录，缺失时不再读取其他用户实体。
        UserRuntime user = runtime.user(lane, userId);
        if (user == null) return UserQueryResult.notFound();

        // 该用户按资产编号组织的余额表，没有余额表时按零条统计。
        var balancesByAsset = runtime.balancesForUser(lane, userId);
        // 当前用户余额记录条数，加入完整视图物化预算。
        int balanceCount = balancesByAsset == null ? 0 : balancesByAsset.size();
        // 当前用户冻结预占记录条数，加入实体总量预算。
        int reservationCount = runtime.reservationCountForUser(lane, userId);
        // 当前用户持仓索引记录条数，先计数量再复制键和视图。
        int positionCount = runtime.positionCountForUser(lane, userId);
        // 该用户现有杠杆配置键，含合约和全仓或逐仓模式。
        var leverageKeys = runtime.leverageKeysForUser(lane, userId);
        // 余额、冻结、持仓及杠杆条数的精确合计，超过上限就返回过大标记。
        int entityCount = Math.addExact(balanceCount,
                Math.addExact(reservationCount, Math.addExact(positionCount, leverageKeys.size())));
        if (entityCount > MAX_USER_QUERY_ENTITIES) return UserQueryResult.oversized();
        // 该用户冻结记录的订单编号数组，按编号排序后读取对应视图。
        long[] reservationIds = runtime.reservationIdsForUser(lane, userId).toArray();
        // 该用户的整数持仓键数组，后续转为按业务键排序的持仓项。
        long[] positionKeys = runtime.positionKeysForUser(lane, userId).toArray();

        // 该用户资产代码及可用、冻结余额视图，按资产代码排序。
        ArrayList<CoreBalanceView> balances = new ArrayList<>(balanceCount);
        if (balancesByAsset != null) {
            balancesByAsset.forEachValue(/* 当前转换或计算哈希的一项资产余额，保留可用与冻结金额。 */ balance -> balances.add(new CoreBalanceView(
                    identities.asset(balance.assetId()), balance.availableUnits(), balance.lockedUnits())));
            balances.sort(Comparator.comparing(CoreBalanceView::asset));
        }

        Arrays.sort(reservationIds);
        // 按订单编号排列的资金预占视图列表，包含累计预占、释放和消费信息。
        ArrayList<CoreReservationView> reservations = new ArrayList<>(reservationIds.length);
        for (long /* 当前冻结或客户端订单映射对应的普通订单编号。 */ orderId : reservationIds) {
            // 当前资金冻结记录；生成视图时保留字段，算哈希时跳过已用完的记录。
            ReservationRuntime reservation = runtime.reservation(lane, orderId);
            if (reservation == null) continue;
            reservations.add(new CoreReservationView(reservation.orderId(),
                    identities.instrumentId(reservation.symbolId()), reservation.kind(),
                    identities.asset(reservation.assetId()), reservation.totalReservedUnits(),
                    reservation.releasedUnits(), reservation.consumedUnits(), reservation.orderQuantitySteps()));
        }

        // 带业务排序键的临时持仓项，避免按内部整数分配顺序输出。
        ArrayList<PositionEntry> positionEntries = new ArrayList<>(positionKeys.length);
        for (long /* 当前需要在指定 Lane 读取的整数持仓键。 */ positionKey : positionKeys) {
            // 当前读取或计算哈希的持仓，保证金模式、持仓腿及金额字段都保留。
            PositionRuntime position = runtime.position(lane, positionKey);
            if (position == null) continue;
            // 当前持仓整数币对编号对应的外部合约编号。
            String instrumentId = identities.instrumentId(position.symbolId());
            // 当前持仓转换成的协议视图，稍后与业务键一起排序。
            CorePositionView view = new CorePositionView(instrumentId, identities.asset(position.assetId()),
                    position.marginMode(), position.positionSide(),
                    position.signedQuantitySteps(), position.entryPriceTicks(), position.entryValueTicks(),
                    position.realizedPnlUnits(), position.positionMarginUnits());
            // 当前持仓的业务排序键，净仓和双向腿使用不同写法。
            String key = position.positionSide() == com.surprising.aeron.protocol.CorePositionSide.NET
                    ? instrumentId : instrumentId + ':' + position.positionSide().name();
            positionEntries.add(new PositionEntry(key, view));
        }
        positionEntries.sort(Comparator.comparing(PositionEntry::key));
        // 按持仓业务键排序后提取的最终持仓协议视图列表。
        List<CorePositionView> positions = positionEntries.stream().map(PositionEntry::view).toList();

        // 该用户各合约和保证金模式的杠杆视图，沿原键集合顺序保存。
        ArrayList<CoreLeverageView> leverages = new ArrayList<>(leverageKeys.size());
        for (CoreLeverageKey /* 当前用户一项按合约及保证金模式区分的杠杆配置键。 */ key : leverageKeys) {
            leverages.add(new CoreLeverageView(key.instrumentId(), key.marginMode(), runtime.leverage(lane, key)));
        }
        // 余额、冻结、持仓及杠杆都组装后的完整用户视图。
        CoreUserStateView view = new CoreUserStateView(user.productLine(), user.userId(), user.revision(),
                user.positionMode(), balances, reservations, positions, leverages);
        return UserQueryResult.found(view, userStateHash(view));
    }

    /**
     * 在指定 Lane 读取订单，把币对整数键换为外部编号并保留成交、费用、时间及版本字段，同时计算包括内部撮合价在内的订单状态哈希；缺失返回未找到。
     * @param runtime 当前查询运行时，账户实体通过显式 Lane 参数读取
     * @param lane 目标用户或订单所属的账户 Lane，调用方应在合法读取范围使用
     * @param identities 账户内资产、币对、客户端及持仓业务身份的映射
     * @param orderId 需要读取的 Core 订单编号
     */
    public static OrderQueryResult orderState(
            TradingRuntimeState runtime,
            AccountLaneState lane,
            RuntimeIdentityRegistry identities,
            long orderId) {
        // 目标订单的当前运行时记录，缺失时直接返回未找到。
        OrderRuntime order = runtime.order(lane, orderId);
        if (order == null) return OrderQueryResult.notFound();
        // 目标订单包含委托、成交、费用和提交信息的协议视图。
        CoreOrderStateView view = new CoreOrderStateView(order.orderId(), order.productLine(), order.userId(),
                identities.instrumentId(order.symbolId()), order.side(), order.priceTicks(),
                order.quantitySteps(), order.executedQuantitySteps(), order.remainingQuantitySteps(),
                order.reduceOnly(), order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(),
                order.postOnly(), order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(),
                order.takerFeeRatePpm(), order.cumulativeFeeUnits(), order.executedValueHigh(), order.executedValueLow(), order.createdAtEpochMillis(),
                order.updatedAtEpochMillis(), order.clusterPosition(), order.status().name(), order.revision());
        return OrderQueryResult.found(view, orderStateHash(order, identities.instrumentId(order.symbolId())));
    }

    /**
     * 先在该 Lane 定位用户的客户端订单身份，再查实际订单编号，任一身份或订单映射不存在时返回未找到，否则复用普通订单查询。
     * @param runtime 当前查询运行时，账户实体通过显式 Lane 参数读取
     * @param lane 目标用户或订单所属的账户 Lane，调用方应在合法读取范围使用
     * @param identities 账户内资产、币对、客户端及持仓业务身份的映射
     * @param userId 本次用户或客户端订单查询所属的用户编号
     * @param clientOrderId 该用户在客户端使用的订单标识，与普通 Core 订单编号分开定位
     */
    public static OrderQueryResult clientOrderState(
            TradingRuntimeState runtime,
            AccountLaneState lane,
            RuntimeIdentityRegistry identities,
            long userId,
            String clientOrderId) {
        // 当前 Lane 中该用户客户端订单标识对应的整数身份，0 表示没有注册。
        long clientKey = identities.findClientKeyValueInLane(lane, userId, clientOrderId);
        if (clientKey == 0) return OrderQueryResult.notFound();
        // 当前冻结或客户端订单映射对应的普通订单编号。
        long orderId = runtime.orderIdByClientValue(lane, userId, clientKey);
        return orderId == 0 ? OrderQueryResult.notFound() : orderState(runtime, lane, identities, orderId);
    }

    /**
     * 按用户身份、版本、模式及各视图列表的固定字段顺序混合状态哈希；已全部释放或消费的冻结记录不参与哈希，持仓和杠杆字段继续完整计入。
     * @param user 已组装的完整用户视图，各列表顺序会参与哈希计算
     */
    private static long userStateHash(CoreUserStateView user) {
        // 按固定字段顺序不断混合的业务状态哈希，返回前计入所有要求的版本字段。
        long hash = CoreStateHash.mix(CoreStateHash.start(), com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(user.productLine()));
        hash = CoreStateHash.mix(hash, user.userId());
        hash = CoreStateHash.mix(hash, user.revision());
        hash = CoreStateHash.mix(hash, user.positionMode().wireCode());
        for (CoreBalanceView /* 当前转换或计算哈希的一项资产余额，保留可用与冻结金额。 */ balance : user.balances()) {
            hash = CoreStateHash.mix(hash, balance.asset());
            hash = CoreStateHash.mix(hash, balance.availableUnits());
            hash = CoreStateHash.mix(hash, balance.lockedUnits());
        }
        for (CoreReservationView /* 当前资金冻结记录；生成视图时保留字段，算哈希时跳过已用完的记录。 */ reservation : user.reservations()) {
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
        for (CorePositionView /* 当前读取或计算哈希的持仓，保证金模式、持仓腿及金额字段都保留。 */ position : user.positions()) {
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
        for (CoreLeverageView /* 本轮加入用户状态哈希的一项杠杆配置，保留合约、保证金模式及 ppm 杠杆值。 */ leverage : user.leverages()) {
            hash = CoreStateHash.mix(hash, leverage.instrumentId());
            hash = CoreStateHash.mix(hash, leverage.marginMode().wireCode());
            hash = CoreStateHash.mix(hash, leverage.leveragePpm());
        }
        return hash;
    }

    /**
     * 按订单身份、委托、成交、费率、费用、时间及状态版本逐字段混合哈希；还包含对外订单视图未单独展示的内部撮合价格。
     * @param order 需要计算状态哈希的运行时订单，包含内部撮合价及完整版本信息
     * @param instrumentId 该订单币对整数身份对应的外部编号
     */
    private static long orderStateHash(OrderRuntime order, String instrumentId) {
        // 按固定字段顺序不断混合的业务状态哈希，返回前计入所有要求的版本字段。
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

    /**
     * 组装用户视图时的持仓排序项，把实际持仓视图与净仓或双向腿业务键配对，排序后再提取视图。
     * @param key 净仓使用合约编号、双向仓再拼持仓腿的业务排序键
     * @param view 本结果已经组装的协议层只读视图，找到记录时有效
     */
    private record PositionEntry(String key, CorePositionView view) {
    }

    /**
     * 用户查询结果，分别表达找到、未找到或实体规模过大；只有找到时携带完整用户视图和状态哈希。
     * @param found 是否实际找到用户或订单，未找到时视图为空、哈希为 0
     * @param tooLarge 用户实体总数是否超过物化上限，与用户不存在分别表达
     * @param view 本结果已经组装的协议层只读视图，找到记录时有效
     * @param stateHash 按该查询固定业务字段及顺序计算的状态比较哈希
     */
    public record UserQueryResult(boolean found, boolean tooLarge, CoreUserStateView view, long stateHash) {
        /**
         * 封装找到的用户视图和哈希，明确关闭响应过大标记。
         * @param view 本结果已经组装的协议层只读视图，找到记录时有效
         * @param stateHash 按该查询固定业务字段及顺序计算的状态比较哈希
         */
        static UserQueryResult found(CoreUserStateView view, long stateHash) {
            return new UserQueryResult(true, false, view, stateHash);
        }

        /**
         * 封装用户不存在的结果，视图为空、哈希为 0，不能与实体过大混同。
         */
        static UserQueryResult notFound() {
            return new UserQueryResult(false, false, null, 0);
        }

        /**
         * 封装用户实体总量超过物化上限的结果，不返回部分视图。
         */
        static UserQueryResult oversized() {
            return new UserQueryResult(false, true, null, 0);
        }
    }

    /**
     * 订单查询结果，区分找到与不存在；找到时携带协议视图及按运行时订单字段计算的状态哈希。
     * @param found 是否实际找到用户或订单，未找到时视图为空、哈希为 0
     * @param view 本结果已经组装的协议层只读视图，找到记录时有效
     * @param stateHash 按该查询固定业务字段及顺序计算的状态比较哈希
     */
    public record OrderQueryResult(boolean found, CoreOrderStateView view, long stateHash) {
        /**
         * 封装已经找到的订单协议视图和对应运行时状态哈希。
         * @param view 本结果已经组装的协议层只读视图，找到记录时有效
         * @param stateHash 按该查询固定业务字段及顺序计算的状态比较哈希
         */
        static OrderQueryResult found(CoreOrderStateView view, long stateHash) {
            return new OrderQueryResult(true, view, stateHash);
        }

        /**
         * 封装订单不存在的结果，视图为空且哈希为 0。
         */
        static OrderQueryResult notFound() {
            return new OrderQueryResult(false, null, 0);
        }
    }
}
