package com.surprising.aeron.service.settlement.lifecycle;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.service.account.BalanceRuntime;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.index.ActiveOrderIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter.CancellationOrder;
import com.surprising.aeron.service.order.state.ReservationRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.product.ProductTradingRules;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

/** 按固定结算价结束合约持仓：先撤单，再按页准备余额与持仓后值，确认保险够用后应用账户并更新财库进度。RuntimeLifecycleSettlementContinuation 负责分页推进，本类提供实际 Lane 计算、账户应用及进度校验。 构造约定：工具类只提供静态结算步骤，不创建实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeLifecycleSettlement {

    /**
     * 一个用户尚未应用的结算后值。只暂存跨 Lane 保险预算核对所需的数据，账户权威状态仍在所属 Lane；键与持仓数组保留原容量，只能应用非空持仓槽。
     * @param balance 按结算价和保险补足计算的最终余额
     * @param keys 持仓内部键数组，与 positions 按下标配对，尾部可能未使用
     * @param positions 归零并累计盈亏后的持仓数组，空槽表示该下标未产生结算计划
     * @param clearing 本用户全部持仓结算盈亏的反号，供财库清算子账应用
     * @param insurance 本用户结算权益不足零时需要保险补足的非负金额
     */
    record UserSettlement(BalanceRuntime balance, long[] keys,
                          PositionRuntime[] positions, long clearing, long insurance) { }

    /**
     * 在所属账户 Lane 只计算本页用户的结算后值，不写余额和持仓。逐仓亏损先按仓补足，全仓再合并到可用权益；不足零的部分计入保险需求，持仓归零并累计已实现盈亏，供整页保险核对后应用。
     * @param runtime 原权威运行时；Lane 操作依赖当前线程持有对应账户作用域
     * @param instrument 本次固定结算所用币对配置
     * @param kernel 该币对产品对应的结算计算规则
     * @param command 固定结算价、结算编号及订单和用户分页参数
     * @param users 当前 Lane 本页要检查的用户编号列表
     * @param symbolId 币对的内部编号
     * @param assetId 结算资产的内部编号
     */
    static List<UserSettlement> prepareLane(TradingAccountRuntime runtime, CoreInstrument instrument,
                                            ProductTradingRules kernel, SettleInstrumentCommand command,
                                            LongArrayList users, int symbolId, int assetId) {
        // 当前 Lane 准备好的用户结算后值列表，尚未写回账户。
        ArrayList<UserSettlement> plans = new ArrayList<>();
        for (/* 当前用户在 Lane 输入列表中的下标。 */ int userIndex = 0; userIndex < users.size(); userIndex++) {
            // 本轮正在处理的用户编号。
            long userId = users.get(userIndex);
            if (runtime.user(userId) == null) continue;
            // 该用户在本币对上的持仓内部键索引。
            LongArrayList indexedKeys = runtime.positionKeysForUserAndSymbolPrimitive(userId, symbolId);
            if (indexedKeys.isEmpty()) continue;
            // 按全部索引键容量分配的计划键数组，尾部未用部分不代表有效持仓。
            long[] keys = new long[indexedKeys.size()];
            // 与键数组配对的归零持仓后值，未用尾部为空。
            PositionRuntime[] positions = new PositionRuntime[keys.length];
            // 结算前账户余额，冻结额随后扣除已释放的持仓保证金。
            BalanceRuntime balance = runtime.balance(userId, assetId);
            if (balance == null) throw new IllegalStateException("settlement balance is missing");
            // 逐仓结算结果已经加入后的可用余额中间值。
            long available = balance.availableUnits();
            // 扣除本页持仓保证金后的冻结余额。
            long locked = balance.lockedUnits();
            // 全仓持仓结算盈亏的累计值。
            // 本次释放的全仓保证金累计值。
            // 该用户本币对所有有效持仓的结算盈亏总额。
            // 该用户逐仓或汇总权益不足零时所需保险总额。
            long crossPnl = 0, crossMargin = 0, totalPnl = 0, insurance = 0;
            // 当前有效计划的写入下标或应用时扫描的配对数组下标。
            int index = 0;
            for (/* 当前持仓键在用户索引中的下标。 */ int positionIndex = 0; positionIndex < indexedKeys.size(); positionIndex++) {
                // 当前持仓内部键。
                long key = indexedKeys.get(positionIndex);
                // 结算前持仓；不存在或已归零的索引项不产生计划。
                PositionRuntime position = runtime.position(key);
                if (position == null || position.signedQuantitySteps() == 0) continue;
                // 按本产品结算公式和固定结算价算出的有符号盈亏。
                long pnl = kernel.lifecycleSettlementCashDeltaUnits(
                        instrument, position.signedQuantitySteps(), position.entryPriceTicks(),
                        command.settlementPriceTicks());
                totalPnl = Math.addExact(totalPnl, pnl);
                // 本持仓结束后要从冻结余额释放的保证金。
                long margin = position.positionMarginUnits();
                locked = Math.subtractExact(locked, margin);
                if (position.marginMode() == CoreMarginMode.CROSS) {
                    crossPnl = Math.addExact(crossPnl, pnl);
                    crossMargin = Math.addExact(crossMargin, margin);
                } else {
                    // 单个逐仓持仓的保证金加结算盈亏，负值需要保险补足。
                    long equity = Math.addExact(margin, pnl);
                    available = Math.addExact(available, Math.max(0, equity));
                    if (equity < 0) insurance = Math.addExact(insurance, Math.negateExact(equity));
                }
                keys[index] = key;
                positions[index++] = new PositionRuntime(userId, symbolId, assetId, position.marginMode(),
                        position.positionSide(), position.instrument(), 0, 0, 0,
                        Math.addExact(position.realizedPnlUnits(), pnl), 0);
            }
            if (index == 0) continue;
            // 合并可用余额、全仓保证金与全仓盈亏后的账户权益，负值需要保险补足。
            long equity = Math.addExact(Math.addExact(available, crossMargin), crossPnl);
            if (equity < 0) insurance = Math.addExact(insurance, Math.negateExact(equity));
            Math.incrementExact(runtime.user(userId).revision());
            plans.add(new UserSettlement(
                    new BalanceRuntime(userId, assetId, Math.max(0, equity), locked),
                    keys, positions, Math.negateExact(totalPnl), insurance));
        }
        return plans;
    }

    /**
     * 在所属账户 Lane 应用保险已确认的后值：替换有效持仓、替换余额并推进用户修订号，返回清算盈亏差额供财库应用。此步骤不再次检查保险，也不在这里实现跨 Lane 回滚。
     * @param runtime 原权威运行时；Lane 操作依赖当前线程持有对应账户作用域
     * @param plans 本 Lane 已完成计算且已确认整页保险预算的账户后值
     */
    static long applyLane(TradingAccountRuntime runtime, List<UserSettlement> plans) {
        // 当前 Lane 应用账户后返回的财库清算变化。
        long clearing = 0;
        for (UserSettlement /* 当前用户的余额、持仓及财库变化计划。 */ plan : plans) {
            for (/* 当前有效计划的写入下标或应用时扫描的配对数组下标。 */ int index = 0; index < plan.keys().length; index++) {
                if (plan.positions()[index] != null)
                    runtime.replacePosition(plan.keys()[index], plan.positions()[index]);
            }
            runtime.replaceBalance(plan.balance());
            runtime.advanceUserRevision(plan.balance().userId());
            clearing = Math.addExact(clearing, plan.clearing());
        }
        return clearing;
    }

    /** 按币对的全局活动订单索引选页，校验每条订单路由并返回后续游标。 */
    static com.surprising.aeron.service.core.TradingCoreRuntime.LifecycleOrderChunk selectOrders(
            ActiveOrderIndex index, String instrumentId, long startCursorOrderId, int limit) {
        // 索引选出的当前页及续页位置。
        var page = index.page(0, instrumentId, startCursorOrderId, limit);
        // 本页由活动订单路由转换出的撤单记录。
        ArrayList<CancellationOrder> selected = new ArrayList<>(page.orderIds().size());
        for (long /* 当前活动订单编号。 */ orderId : page.orderIds()) {
            // 当前订单路由，包含其用户与币对。
            var order = index.activeOrderRoute(orderId);
            if (order == null) {
                throw new IllegalStateException("settlement active order index differs from runtime");
            }
            selected.add(new CancellationOrder(orderId, order.userId(), order.instrumentId()));
        }
        return new com.surprising.aeron.service.core.TradingCoreRuntime.LifecycleOrderChunk(
                selected, page.nextCursorOrderId());
    }

    /**
     * 只处理当前 Lane 所属用户的订单；要求预留存在，按剩余预留撤单以释放冻结资金。这里不直接操作 Matcher 订单簿。
     * @param runtime 原权威运行时；Lane 操作依赖当前线程持有对应账户作用域
     * @param orders 本次要在账户侧取消的订单路由集合
     */
    static void cancelOrdersOnLane(TradingAccountRuntime runtime, Collection<CancellationOrder> orders) {
        if (orders == null || orders.isEmpty()) return;
        for (var /* 当前订单路由，包含其用户与币对。 */ order : orders) {
            if (!runtime.currentLaneOwns(order.userId())) continue;
            // 订单尚未释放的资金预留，撤单必须与该额度对应。
            ReservationRuntime reservation = runtime.reservation(order.orderId());
            if (reservation == null) throw new IllegalStateException("settlement reservation is missing");
            runtime.cancelOrder(order.orderId(), order.userId(), reservation.reservedUnits());
        }
    }

    /**
     * 把非空用户分组转换成参与 Lane 位图；每个列表应已初始化。
     * @param groups 按账户 Lane 编号排列的分组数组
     */
    static long laneMask(LongArrayList[] groups) {
        // 由非空分组逐位合成的参与 Lane 位图。
        long mask = 0;
        for (/* 当前账户 Lane 编号。 */ int lane = 0; lane < groups.length; lane++) if (!groups[lane].isEmpty()) mask |= 1L << lane;
        return mask;
    }

    /**
     * 把存在且非空的订单分组转换成参与 Lane 位图，未分配的分组直接跳过。
     * @param groups 按账户 Lane 编号排列的分组数组
     */
    static long laneMask(ArrayList<CancellationOrder>[] groups) {
        // 由非空分组逐位合成的参与 Lane 位图。
        long mask = 0;
        for (/* 当前账户 Lane 编号。 */ int lane = 0; lane < groups.length; lane++) {
            if (groups[lane] != null && !groups[lane].isEmpty()) mask |= 1L << lane;
        }
        return mask;
    }

    /**
     * 读取命令指定币对，缺失时返回业务拒绝；本方法不执行产品结算规则验证。
     * @param runtime 原权威运行时；Lane 操作依赖当前线程持有对应账户作用域
     * @param command 固定结算价、结算编号及订单和用户分页参数
     */
    static CoreInstrument requireInstrument(TradingAccountRuntime runtime,
                                                 SettleInstrumentCommand command) {
        // 已确认存在的本次结算币对配置。
        CoreInstrument instrument = runtime.instrument(command.instrumentId());
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        return instrument;
    }

    /**
     * 首次分页必须从双零游标开始；已有进度时，结算编号、价格、撤单阶段与两个游标必须完全匹配，否则拒绝续页。这里不另比较命令 UUID 或币对对象身份。
     * @param progress 该币对已保存的结算进度，尚未开始时为空
     * @param command 固定结算价、结算编号及订单和用户分页参数
     */
    static void validateProgress(TreasuryRuntime.LifecycleProgressRuntime progress,
                                 SettleInstrumentCommand command) {
        if (progress == null && (command.cursorUserId() != 0 || command.cursorOrderId() != 0)) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor must start at zero");
        }
        if (progress != null && (progress.settlementId() != command.settlementId()
                || progress.settlementPriceTicks() != command.settlementPriceTicks()
                || progress.ordersComplete() != (command.cursorOrderId() == 0)
                || progress.nextCursorOrderId() != command.cursorOrderId()
                || progress.nextCursorUserId() != command.cursorUserId())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor does not match progress");
        }
    }
}
