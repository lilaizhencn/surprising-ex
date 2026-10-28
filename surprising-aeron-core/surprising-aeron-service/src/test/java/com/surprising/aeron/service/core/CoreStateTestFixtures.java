package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.CoreRiskLimitBracket;

import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.order.state.ReservationRuntime;
import com.surprising.aeron.service.order.state.RuntimeTestStateTransitions;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

/**
 * 为状态和 Lane 测试构造最小标的、订单及预留数据。这里可以直接拼运行时对象，不等于生产下单准入；共享标的原引用不应被测试改写，以免用例互相影响。
 */
public final class CoreStateTestFixtures {
    public static final com.surprising.aeron.config.LaneTopology DEFAULT_TOPOLOGY =
            new com.surprising.aeron.config.LaneTopology(1, 2,
                    com.surprising.aeron.config.LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 4096, 4096, 4096);
    public static final com.surprising.aeron.config.LaneTopology SINGLE_LANE_TOPOLOGY =
            new com.surprising.aeron.config.LaneTopology(1, 1,
                    com.surprising.aeron.config.LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 4096, 4096, 4096);


    /** 核对冷用户的资产总额，缺失资产按零计；不执行余额变更。 */
    public static long totalUnits(com.surprising.aeron.service.account.CoreUserState user, String asset) {
        var balance = user.balances().get(com.surprising.aeron.model.AssetBalance.normalizeAsset(asset));
        return balance == null ? 0 : totalUnits(balance);
    }

    public static long totalUnits(com.surprising.aeron.model.AssetBalance balance) {
        return Math.addExact(balance.availableUnits(), balance.lockedUnits());
    }

    private static final java.lang.reflect.Field ACTIVE_ORDER_VALUES =
            indexField(com.surprising.aeron.service.core.index.ActiveOrderIndex.class, "ordersById");

    /** 仅在夹具已完成 Owner 交接后核对现有索引，不物化全表。 */
    public static int activeOrderCount(com.surprising.aeron.service.core.index.ActiveOrderIndex index) {
        try { return ((org.agrona.collections.Long2ObjectHashMap<?>) ACTIVE_ORDER_VALUES.get(index)).size(); }
        catch (IllegalAccessException failure) { throw new AssertionError(failure); }
    }

    private static java.lang.reflect.Field indexField(Class<?> type, String name) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    /** 类加载时创建并在本辅助中共享的正向永续标的一，所有测试订单借用该原对象。 */
    private static final CoreInstrument RUNTIME_INSTRUMENT = CoreInstrument.from(
            ProductLine.LINEAR_PERPETUAL,
            instrument(ProductLine.LINEAR_PERPETUAL, "1", "BTC", "USDT", "USDT"));

    /** 测试在所属 Core 包内取得真实业务索引，不为观察提供生产 API。 */
    public static com.surprising.aeron.service.core.index.RuntimeFactIndexes factIndexes(TradingCoreRuntime core) {
        return core.runtimeState().factIndexes();
    }

    private CoreStateTestFixtures() {
    }

    /**
     * 在指定产品线空冷状态中注册默认标的一，结算资产按正反向产品线选择。
     * @param reducer 已有冷状态转换辅助，用来注册本产品线默认标的
     * @param productLine 测试数据必须归属的独立产品线
     */
    public static TradingCoreState withInstrument(RuntimeTestStateTransitions reducer, ProductLine productLine) {
        return reducer.registerInstrument(TradingCoreState.empty(productLine), instrument(productLine,
                "1", "BTC", "USDT", settleAsset(productLine)));
    }

    /**
     * 将产品线转换成具体合约类型，设置交割/期权到期和期权看涨参数，构造零费率的最小测试标的；反向采用结算比例一千。
     * @param productLine 测试数据必须归属的独立产品线
     * @param instrumentId 测试标的的规范数字 ID 文本
     * @param baseAsset 测试标的基础资产代码
     * @param quoteAsset 测试标的报价资产代码
     * @param settleAsset 测试标的结算资产代码
     */
    public static RegisterInstrumentCommand instrument(
            ProductLine productLine,
            String instrumentId,
            String baseAsset,
            String quoteAsset,
            String settleAsset) {
        // 按产品线代码得到的具体合约类型
        ContractType type = ContractType.valueOf(productLine.contractTypeCode());
        // 交割和期权使用固定未来到期时间，其余为零
        long expiry = type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0;
        // 期权固定使用类型码零，非期权以负一表示不设置
        int optionType = type.isOption() ? 0 : -1;
        // 期权固定行权价一百，非期权为零
        long strike = type.isOption() ? 100 : 0;
        return new RegisterInstrumentCommand(
                instrumentId, baseAsset, quoteAsset, settleAsset, 1, 1, type.isInverse() ? 1_000 : 1,
                (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                        100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                        java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000)))),
                0, 0, expiry, optionType, strike);
    }

    /**
     * 反向永续与反向交割选择 BTC，其余测试产品线选择 USDT。
     * @param productLine 测试数据必须归属的独立产品线
     */
    public static String settleAsset(ProductLine productLine) {
        return productLine == ProductLine.INVERSE_PERPETUAL || productLine == ProductLine.INVERSE_DELIVERY
                ? "BTC" : "USDT";
    }

    /**
     * 借出类加载时建立的共享正向永续标的原对象；不复制配置外壳，调用方避免修改配置及维护。
     */
    public static CoreInstrument runtimeInstrument() {
        return RUNTIME_INSTRUMENT;
    }

    /**
     * 构造未取消的测试买单，其他固定值沿用带取消标志的重载。
     * @param orderId 测试订单及其预留共用的订单 ID
     * @param userId 测试订单或预留所属用户
     * @param symbolId 测试运行时的内部标的索引，不会据此替换共享标的配置
     * @param quantitySteps 测试订单数量，按数量步保存
     */
    public static OrderRuntime order(long orderId, long userId, int symbolId, long quantitySteps) {
        return order(orderId, userId, symbolId, quantitySteps, false);
    }

    /**
     * 直接创建带指定用户、内部标的和数量的全仓净仓限价买单；价格和费率为零，使用共享测试标的，不执行生产下单校验。
     * @param orderId 测试订单及其预留共用的订单 ID
     * @param userId 测试订单或预留所属用户
     * @param symbolId 测试运行时的内部标的索引，不会据此替换共享标的配置
     * @param quantitySteps 测试订单数量，按数量步保存
     * @param canceled 传给运行时订单构造器的取消标志
     */
    public static OrderRuntime order(long orderId, long userId, int symbolId, long quantitySteps, boolean canceled) {
        return new OrderRuntime(orderId, userId, symbolId, RUNTIME_INSTRUMENT, CoreOrderSide.BUY, 0,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, 0, 0, quantitySteps, 0, quantitySteps, canceled);
    }

    /**
     * 直接创建保证金预留，内部标的固定零、订单数量固定一；总预留至少一，故 remainingUnits 非正时实际剩余仍为一。
     * @param orderId 测试订单及其预留共用的订单 ID
     * @param userId 测试订单或预留所属用户
     * @param assetId 预留及冻结使用的内部资产索引
     * @param remainingUnits 希望构造的预留量；正文总量取该值与一的较大者，释放和消耗都为零
     */
    public static ReservationRuntime reservation(long orderId, long userId, int assetId, long remainingUnits) {
        return new ReservationRuntime(orderId, userId, 0, ReservationKind.DERIVATIVE_MARGIN,
                assetId, Math.max(1, remainingUnits), 0, 0, 1);
    }

    /**
     * 先构造订单和至少一单位的预留正文，再以调用方原 reservedUnits 交给运行时登记冻结；正文的最小一处理不改变最后传给运行时的冻结入参。
     * @param runtime 需要直接登记订单和冻结的测试运行时，调用方先满足其访问边界
     * @param orderId 测试订单及其预留共用的订单 ID
     * @param userId 测试订单或预留所属用户
     * @param clientKey 登记订单时使用的客户端订单整数键
     * @param symbolId 测试运行时的内部标的索引，不会据此替换共享标的配置
     * @param quantitySteps 测试订单数量，按数量步保存
     * @param assetId 预留及冻结使用的内部资产索引
     * @param reservedUnits 实际传给运行时的冻结数量；预留正文单独以至少一单位构造
     */
    public static void reserveOrder(TradingAccountRuntime runtime, long orderId, long userId, long clientKey,
                             int symbolId, long quantitySteps, int assetId, long reservedUnits) {
        // 本次待直接登记的测试订单
        OrderRuntime order = order(orderId, userId, symbolId, quantitySteps);
        // 本次和订单一起登记的预留正文，总预留至少一
        ReservationRuntime reservation = new ReservationRuntime(orderId, userId, symbolId,
                ReservationKind.DERIVATIVE_MARGIN, assetId,
                Math.max(1, reservedUnits), 0, 0, quantitySteps);
        runtime.reserveOrder(order, reservation, clientKey, reservedUnits);
    }
    /** 为回滚/完成用例准备待预留事实，继续使用真实 Lane 标记与 Owner 路由登记。 */
    public static void markPendingReservation(TradingAccountRuntime runtime, long userId, long orderId, long sequence) {
        runtime.assertOwner();
        runtime.accountRollback().captureReservationBefore(orderId);
        var tracker = runtime.pendingReservations();
        if (tracker.hasPendingUser(orderId)) throw new IllegalStateException("reservation is already indexed as pending");
        int total = Math.incrementExact(tracker.totalPendingReservations());
        runtime.onLane(userId, lane -> {
            var reservation = lane.reservation(orderId);
            if (reservation == null || reservation.userId() != userId) throw new IllegalStateException("pending reservation is missing");
            runtime.accountRollback().captureBalanceBefore(userId, reservation.assetId());
            lane.markPendingReservation(orderId, sequence);
            runtime.accountRollback().captureBalanceAfter(lane, userId, reservation.assetId());
            return null;
        });
        tracker.indexPendingReservation(userId, orderId, sequence, total);
    }

    /** 测试制造订单删除事实；业务撤单仍走原终态结算。 */
    public static void deleteOrder(TradingAccountRuntime runtime, long orderId) {
        var previous = runtime.order(orderId);
        runtime.accountRollback().captureOrderBefore(orderId);
        runtime.accountRollback().captureUserBefore(previous.userId());
        runtime.onLane(previous.userId(), lane -> { lane.removeOrder(orderId); return null; });
        runtime.publishOrder(orderId, null);
        runtime.changedOrder(orderId, null);
        runtime.markUserChanged(previous.userId());
    }

    /** 测试制造持仓删除事实，继续使用实际索引撤销和回滚前值。 */
    public static void deletePosition(TradingAccountRuntime runtime, long key, long userId) {
        runtime.onLane(userId, lane -> {
            runtime.accountRollback().captureUserBefore(userId);
            runtime.accountRollback().capturePositionBefore(key, userId);
            var previous = com.surprising.aeron.service.account.AccountLaneTestAccess.positions(lane).remove(key);
            TradingAccountRuntime.unindexPosition(lane, key, previous);
            return null;
        });
        runtime.publishPosition(key, null);
        runtime.changedPosition(key, null);
        runtime.markUserChanged(userId);
    }

    /** 为索引与退休测试准备风险删除事实，由原 Lane 完成边界生成身份退休证明。 */
    public static void deleteRiskSnapshot(TradingAccountRuntime runtime, long key) {
        long userId = runtime.riskSnapshot(key).userId();
        runtime.onLane(userId, lane -> {
            com.surprising.aeron.service.account.AccountLaneTestAccess.removeRiskSnapshot(lane, key);
            runtime.publishRiskSnapshot(key, null);
            return null;
        });
    }

    /** 为成员索引测试准备已完成预留的删除，并沿用真实回滚前值。 */
    public static void deleteReservation(TradingAccountRuntime runtime, long orderId, long userId) {
        runtime.onLane(userId, lane -> {
            runtime.accountRollback().captureReservationBefore(orderId, userId);
            lane.removeReservationValue(orderId);
            lane.removeReservationId(userId, orderId);
            return null;
        });
    }

    /** 为触发索引测试准备删除事实；回滚与变化发布仍使用真实 Lane 边界。 */
    public static void deleteTrigger(TradingAccountRuntime runtime, long id) {
        long userId = runtime.triggerOrder(id).userId();
        runtime.onLane(userId, lane -> {
            runtime.accountRollback().captureTriggerOrderBefore(id, userId);
            lane.removeTrigger(id);
            runtime.publishTriggerOrder(id, null);
            return null;
        });
    }

    /** 冷公式测试进入真实结算作用域调用 Lane 释放规则，不另写资金释放算法。 */
    public static long releaseRemainingReservation(TradingAccountRuntime runtime, long orderId) {
        var order = runtime.order(orderId);
        if (order == null || !order.canceled()) return 0;
        var lane = runtime.accountLaneForUser(order.userId());
        var changes = runtime.acquireMatcherSettlementChanges(1L << lane.laneId());
        try {
            long released;
            runtime.enterMatcherSettlementScope(lane, changes);
            try {
                released = runtime.releaseTerminalReservationInLane(lane, order, null);
            } finally {
                runtime.exitMatcherSettlementScope(lane, changes);
            }
            changes.laneDelta(lane.laneId()).preparePublication();
            changes.laneDelta(lane.laneId()).commitTerminalToOwner(runtime, lane.laneId(), null, null, null);
            var balances = changes.balancePatch(lane.laneId());
            for (int index = 0; index < balances.size(); index++) balances.publishAvailableAt(runtime, index);
            return released;
        } finally {
            runtime.releaseMatcherSettlementChanges(changes);
        }
    }

}
