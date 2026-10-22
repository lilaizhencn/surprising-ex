package com.surprising.aeron.service.settlement.lifecycle;

import com.surprising.aeron.service.account.UserIndexPage;
import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.service.account.BalanceRuntime;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.ActiveOrderIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter.CancellationOrder;
import com.surprising.aeron.service.order.state.ReservationRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.product.ProductTradingRules;
import com.surprising.aeron.service.product.ProductTradingRulesRegistry;
import com.surprising.aeron.service.treasury.RuntimeTreasuryDelta;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

/** 按固定结算价结束合约持仓：先撤单，再按页准备余额与持仓后值，确认保险够用后应用账户并更新财库进度。同步主入口仅用于 System Ledger 未激活的回放或离线路径；在线命令由 RuntimeLifecycleSettlementContinuation 分次推进并复用本类的 Lane 操作。 构造约定：工具类只提供静态结算步骤，不创建实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeLifecycleSettlement {

    /**
     * 从旧状态补齐缺省的用户集合与活动订单索引后执行同步结算。这里只检查必要对象非空，不另核对 before 与 runtime 的产品线或修订号；用户输入顺序仍由调用方保证。
     * @param before 旧状态；apply 用于补齐缺省索引，advanceCancellation 还用来核对产品线和修订号
     * @param command 固定结算价、结算编号及订单和用户分页参数
     * @param indexedUserIds 按全局用户编号递增提供的索引，apply 允许空时使用旧状态用户集合
     * @param chunkCommandId 本页命令 UUID；同步主入口为空表示不分页
     * @param activeOrderIndex 币对活动订单路由索引，apply 允许空时从旧状态构建
     * @param runtime 原权威运行时；Lane 操作依赖当前线程持有对应账户作用域
     * @param identities 把币对和资产名称转换为内部编号的身份表
     */
    public static CoreSettlementProgressView apply(TradingCoreState before, SettleInstrumentCommand command,
                                                   Iterable<Long> indexedUserIds, UUID chunkCommandId,
                                                   ActiveOrderIndex activeOrderIndex, TradingAccountRuntime runtime,
                                                   RuntimeIdentityRegistry identities) {
        if (before == null || command == null || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid runtime settlement");
        }
        // 本次使用的用户来源；未传索引时取旧状态用户集合。
        Iterable<Long> users = indexedUserIds == null ? before.users().keySet() : indexedUserIds;
        // 本次使用的活动订单索引；未传时从旧状态构建。
        ActiveOrderIndex orders = activeOrderIndex == null ? new ActiveOrderIndex(before) : activeOrderIndex;
        return applyRuntime(command, users, chunkCommandId, orders, runtime, identities);
    }

    /**
     * 同步推进本次结算：核对编号与游标，先完成撤单，再准备账户后值并汇总保险需求。保险不足时保存当前用户游标并返回等待，已执行的撤单不在这里撤销；保险足够才应用本页账户、清算差额和分页进度。已完成的同一编号直接返回完成，较旧编号拒绝。
     * @param command 固定结算价、结算编号及订单和用户分页参数
     * @param indexedUserIds 按全局用户编号递增提供的索引，apply 允许空时使用旧状态用户集合
     * @param chunkCommandId 本页命令 UUID；同步主入口为空表示不分页
     * @param activeOrderIndex 币对活动订单路由索引，apply 允许空时从旧状态构建
     * @param runtime 原权威运行时；Lane 操作依赖当前线程持有对应账户作用域
     * @param identities 把币对和资产名称转换为内部编号的身份表
     */
    public static CoreSettlementProgressView applyRuntime(SettleInstrumentCommand command,
                                                          Iterable<Long> indexedUserIds, UUID chunkCommandId,
                                                          ActiveOrderIndex activeOrderIndex,
                                                          TradingAccountRuntime runtime,
                                                          RuntimeIdentityRegistry identities) {
        if (command == null || indexedUserIds == null || activeOrderIndex == null
                || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid runtime settlement");
        }
        if (runtime.systemLedgerStarted()) throw new IllegalStateException("active lifecycle settlement requires its asynchronous continuation");
        // 已确认存在的本次结算币对配置。
        CoreInstrument instrument = requireInstrument(runtime, command);
        // 本币对内部身份编号。
        int symbolId = identities.symbolId(instrument.instrumentIdValue());
        // 该币对已经完整完成的最近结算编号。
        long previousSettlement = runtime.treasury().lifecycleSettlement(symbolId);
        if (command.settlementId() < previousSettlement) {
            throw new CoreStateRejectedException("STALE_SETTLEMENT_ID", "lifecycle settlement id must increase");
        }
        if (command.settlementId() == previousSettlement) {
            return new CoreSettlementProgressView(command.settlementId(), true, true, 0, 0, 0, 0);
        }
        // 本产品的到期或固定价格结算规则。
        ProductTradingRules kernel = ProductTradingRulesRegistry.forInstrument(instrument);
        kernel.validateLifecycleSettlement(instrument, command);
        // 已有撤单或用户结算进度，用来校验本页能否接续。
        TreasuryRuntime.LifecycleProgressRuntime previousProgress = runtime.treasury().lifecycleProgress(symbolId);
        // 是否带有本页命令 UUID；本入口已要求用户来源非空。
        boolean chunked = indexedUserIds != null && chunkCommandId != null;
        validateProgress(previousProgress, command, chunked);
        // 本次是否已完成全部账户侧撤单，完成后才能结算持仓。
        boolean ordersComplete = !chunked || previousProgress != null && previousProgress.ordersComplete();
        // 本次实际选择并处理的撤单路由。
        List<CancellationOrder> selectedOrders = List.of();
        // 活动订单索引是否还留有后续页。
        boolean moreOrders = false;
        if (!chunked) {
            selectedOrders = openOrders(runtime, identities, activeOrderIndex, instrument.instrumentId());
            cancelOrders(runtime, selectedOrders);
            ordersComplete = true;
        } else if (!ordersComplete) {
            // 既有进度中的位置字段；当前选页实现使用全局游标而不按此字段路由。
            int accountLaneId = previousProgress == null ? 0 : previousProgress.accountLaneId();
            // 索引选出的当前页及续页位置。
            OrderPage page = selectOrders(runtime, identities, activeOrderIndex, instrument.instrumentId(),
                    accountLaneId, command.cursorOrderId(), command.maxOrders());
            selectedOrders = page.orders();
            moreOrders = !page.complete();
            cancelOrders(runtime, selectedOrders);
            if (moreOrders) {
                // 本次撤单之后保存的下一订单游标。
                long nextCursor = page.nextCursorOrderId();
                runtime.treasury().setLifecycleProgress(symbolId,
                        new TreasuryRuntime.LifecycleProgressRuntime(command.settlementId(),
                                instrument, command.settlementPriceTicks(),
                                command.optionCashUnitsPerContract(), false, page.accountLaneId(),
                                nextCursor, 0, chunkCommandId));
                runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(),
                        selectedOrders.isEmpty() ? 1 : 2));
                return new CoreSettlementProgressView(command.settlementId(), false, false, nextCursor, 0,
                        selectedOrders.size(), 0);
            }
            ordersComplete = true;
        }
        // 既有进度中的位置字段；当前选页实现使用全局游标而不按此字段路由。
        int accountLaneId = previousProgress == null || !previousProgress.ordersComplete()
                ? 0 : previousProgress.accountLaneId();
        // 按全局用户顺序选出的待结算页。
        UserPage userPage = selectUsers(indexedUserIds, runtime, accountLaneId,
                command.cursorUserId(), chunked ? command.maxUsers() : Integer.MAX_VALUE);
        // 本页选中的用户，不等于每个用户都存在有效持仓。
        List<Long> selectedUserIds = userPage.userIds();
        // 分页结算是否仍有后续用户。
        boolean moreUsers = chunked && !userPage.complete();
        // 本次结算资产的内部编号。
        int assetId = identities.assetId(instrument.settleAsset());
        // 将本页用户按账户所有权分组后的原语列表。
        LongArrayList[] usersByLane = groupUsers(selectedUserIds, runtime);
        // 本页含有用户的账户 Lane 位图。
        long userLaneMask = laneMask(usersByLane);
        // 各 Lane 只计算、不应用的账户后值回执。
        Object[] prepared = runtime.executeLifecycleSettlements(userLaneMask, selectedUserIds.size(),
                /* 当前账户 Lane 编号。 */ lane -> prepareLane(runtime, instrument, kernel, command, usersByLane[lane], symbolId, assetId));
        // 把各 Lane 回执转换成具体结算计划后保存的数组。
        @SuppressWarnings("unchecked")
        List<UserSettlement>[] plans = new List[runtime.topology().accountLaneCount()];
        // 整页所有用户需要补足的保险总额，按精确加法累加。
        long requiredInsurance = 0;
        for (/* 当前账户 Lane 编号。 */ int lane = 0; lane < plans.length; lane++) {
            // 当前 Lane 准备好的用户结算后值。
            @SuppressWarnings("unchecked")
            List<UserSettlement> lanePlans = (List<UserSettlement>) prepared[lane];
            plans[lane] = lanePlans;
            if (lanePlans != null) for (var /* 当前用户的余额、持仓及财库变化计划。 */ plan : lanePlans)
                requiredInsurance = Math.addExact(requiredInsurance, plan.insurance());
        }
        if (requiredInsurance > runtime.treasury().insurance(assetId)) {
            // 保险不足时保存的页标识；非分页入口以结算编号构造固定 UUID。
            UUID progressId = chunkCommandId == null ? new UUID(0, command.settlementId()) : chunkCommandId;
            runtime.treasury().setLifecycleProgress(symbolId, new TreasuryRuntime.LifecycleProgressRuntime(
                    command.settlementId(), instrument, command.settlementPriceTicks(),
                    command.optionCashUnitsPerContract(), true, 0, 0, command.cursorUserId(),
                    progressId, requiredInsurance));
            runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
            return new CoreSettlementProgressView(command.settlementId(), false, true, 0,
                    command.cursorUserId(), selectedOrders.size(), 0, requiredInsurance);
        }
        if (requiredInsurance != 0) runtime.treasury().setInsurance(assetId,
                Math.subtractExact(runtime.treasury().insurance(assetId), requiredInsurance),
                runtime.treasury().insuranceDeficit(assetId));
        // 各 Lane 实际应用账户后返回的财库差额。
        Object[] laneResults = runtime.executeLifecycleSettlements(userLaneMask, selectedUserIds.size(),
                /* 当前账户 Lane 编号。 */ lane -> applyLane(runtime, assetId, plans[lane]));
        // 合并整页账户清算变化的财库差额容器。
        RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
        for (Object /* 当前 Lane 返回的通用回执对象。 */ value : laneResults) {
            if (value instanceof RuntimeTreasuryDelta /* 确认类型后可合并的单 Lane 财库差额。 */ laneDelta) treasuryDelta.merge(laneDelta);
        }
        treasuryDelta.apply(runtime.treasury());
        // 当前是否已经处理完本结算的全部用户页。
        boolean complete = !chunked || !moreUsers;
        // 未完成时指向本页最后用户，完成时归零。
        long nextCursorUserId = complete ? 0 : selectedUserIds.getLast();
        if (complete) {
            runtime.treasury().setLifecycleSettlement(symbolId, command.settlementId());
        } else {
            runtime.treasury().setLifecycleProgress(symbolId,
                    new TreasuryRuntime.LifecycleProgressRuntime(command.settlementId(),
                            instrument, command.settlementPriceTicks(),
                            command.optionCashUnitsPerContract(), true, userPage.accountLaneId(),
                            0, nextCursorUserId, chunkCommandId));
        }
        runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(),
                selectedOrders.isEmpty() ? 1 : 2));
        return new CoreSettlementProgressView(command.settlementId(), complete, ordersComplete, 0,
                nextCursorUserId, selectedOrders.size(), selectedUserIds.size());
    }

    /**
     * 核对旧状态和运行时的产品线、修订号一致，再应用已选撤单页并保存后续游标；这里只处理账户侧撤单与进度，Matcher 的订单簿处理由调用链负责。
     * @param before 旧状态；apply 用于补齐缺省索引，advanceCancellation 还用来核对产品线和修订号
     * @param command 固定结算价、结算编号及订单和用户分页参数
     * @param orders 本次要在账户侧取消的订单路由集合
     * @param nextCursorOrderId 仍有后续撤单页时保存的正数订单游标
     * @param chunkCommandId 本页命令 UUID；同步主入口为空表示不分页
     * @param runtime 原权威运行时；Lane 操作依赖当前线程持有对应账户作用域
     * @param identities 把币对和资产名称转换为内部编号的身份表
     */
    public static void advanceCancellation(TradingCoreState before, SettleInstrumentCommand command,
                                           Collection<CancellationOrder> orders, long nextCursorOrderId,
                                           UUID chunkCommandId, TradingAccountRuntime runtime,
                                           RuntimeIdentityRegistry identities) {
        if (nextCursorOrderId <= 0 || chunkCommandId == null) {
            throw new IllegalArgumentException("settlement cursor must advance");
        }
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid runtime settlement cancellation");
        }
        advanceCancellationRuntime(command, orders, nextCursorOrderId, chunkCommandId, runtime, identities);
    }

    /**
     * 校验币对结算规则和续页参数，在所属 Lane 撤销这页订单的预留，再保存撤单未完成进度及修订号。本入口不另检查 System Ledger 是否激活，线程所有权由调用方遵守。
     * @param command 固定结算价、结算编号及订单和用户分页参数
     * @param orders 本次要在账户侧取消的订单路由集合
     * @param nextCursorOrderId 仍有后续撤单页时保存的正数订单游标
     * @param chunkCommandId 本页命令 UUID；同步主入口为空表示不分页
     * @param runtime 原权威运行时；Lane 操作依赖当前线程持有对应账户作用域
     * @param identities 把币对和资产名称转换为内部编号的身份表
     */
    public static void advanceCancellationRuntime(SettleInstrumentCommand command,
                                                  Collection<CancellationOrder> orders, long nextCursorOrderId,
                                                  UUID chunkCommandId, TradingAccountRuntime runtime,
                                                  RuntimeIdentityRegistry identities) {
        if (nextCursorOrderId <= 0 || chunkCommandId == null || runtime == null || identities == null) {
            throw new IllegalArgumentException("settlement cursor must advance");
        }
        // 已确认存在的本次结算币对配置。
        CoreInstrument instrument = requireInstrument(runtime, command);
        ProductTradingRulesRegistry.forInstrument(instrument)
                .validateLifecycleSettlement(instrument, command);
        // 本币对内部身份编号。
        int symbolId = identities.symbolId(instrument.instrumentIdValue());
        // 续接撤单前读到的原结算进度。
        TreasuryRuntime.LifecycleProgressRuntime progress = runtime.treasury().lifecycleProgress(symbolId);
        validateProgress(progress, command, true);
        cancelOrders(runtime, orders);
        runtime.treasury().setLifecycleProgress(symbolId,
                new TreasuryRuntime.LifecycleProgressRuntime(command.settlementId(), instrument,
                        command.settlementPriceTicks(), command.optionCashUnitsPerContract(), false,
                        progress == null ? 0 : progress.accountLaneId(), nextCursorOrderId, 0, chunkCommandId));
        runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(),
                orders == null || orders.isEmpty() ? 1 : 2));
    }

    /**
     * 一个用户尚未应用的结算后值。只暂存跨 Lane 保险预算核对所需的数据，账户权威状态仍在所属 Lane；键与持仓数组保留原容量，只能应用非空持仓槽。
     * @param userId 该计划所属用户编号
     * @param balance 按结算价和保险补足计算的最终余额
     * @param keys 持仓内部键数组，与 positions 按下标配对，尾部可能未使用
     * @param positions 归零并累计盈亏后的持仓数组，空槽表示该下标未产生结算计划
     * @param clearing 本用户全部持仓结算盈亏的反号，供财库清算子账应用
     * @param insurance 本用户结算权益不足零时需要保险补足的非负金额
     */
    record UserSettlement(long userId, BalanceRuntime balance, long[] keys,
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
            plans.add(new UserSettlement(userId,
                    new BalanceRuntime(userId, assetId, Math.max(0, equity), locked),
                    keys, positions, Math.negateExact(totalPnl), insurance));
        }
        return plans;
    }

    /**
     * 在所属账户 Lane 应用保险已确认的后值：替换有效持仓、替换余额并推进用户修订号，返回清算盈亏差额供财库应用。此步骤不再次检查保险，也不在这里实现跨 Lane 回滚。
     * @param runtime 原权威运行时；Lane 操作依赖当前线程持有对应账户作用域
     * @param assetId 结算资产的内部编号
     * @param plans 本 Lane 已完成计算且已确认整页保险预算的账户后值
     */
    static RuntimeTreasuryDelta applyLane(TradingAccountRuntime runtime, int assetId,
                                          List<UserSettlement> plans) {
        // 当前 Lane 应用账户后返回的财库清算变化。
        RuntimeTreasuryDelta delta = new RuntimeTreasuryDelta();
        for (UserSettlement /* 当前用户的余额、持仓及财库变化计划。 */ plan : plans) {
            for (/* 当前有效计划的写入下标或应用时扫描的配对数组下标。 */ int index = 0; index < plan.keys().length; index++) {
                if (plan.positions()[index] != null)
                    runtime.replacePosition(plan.keys()[index], plan.positions()[index]);
            }
            runtime.replaceBalance(plan.balance());
            runtime.advanceUserRevision(plan.userId());
            delta.addClearing(assetId, plan.clearing());
        }
        return delta;
    }

    /**
     * 复用资金费的全局用户分页规则。runtime 与 startLaneId 当前不参与选页，返回的 Lane 编号固定为零；选出的用户随后再按实际账户 Lane 分组。
     * @param indexedUserIds 按全局用户编号递增提供的索引，apply 允许空时使用旧状态用户集合
     * @param runtime 原权威运行时；Lane 操作依赖当前线程持有对应账户作用域
     * @param startLaneId 保留的 Lane 起点参数，当前全局分页实现不读取
     * @param startCursorUserId 用户页的排他下界，只选择更大编号
     * @param limit 本页最多选取的记录数
     */
    static UserPage selectUsers(Iterable<Long> indexedUserIds, TradingAccountRuntime runtime,
                                int startLaneId, long startCursorUserId, int limit) {
        // 命令游标按全局用户编号推进，与 Core 使用同一顺序；不按账户 Lane 单独分页。
        // 索引选出的当前页及续页位置。
        var page = UserIndexPage.select(indexedUserIds, startCursorUserId, limit);
        return new UserPage(page.userIds(), 0, page.nextCursorUserId(), page.complete());
    }

    /**
     * 从活动订单索引取一页订单路由，再按订单编号找到用户及币对；缺失路由视为索引不一致。当前使用全局索引和零 Lane 编号，runtime、identities、startLaneId 不参与选页。
     * @param runtime 原权威运行时；Lane 操作依赖当前线程持有对应账户作用域
     * @param identities 把币对和资产名称转换为内部编号的身份表
     * @param index 活动订单路由索引
     * @param instrumentId 需要结算并清空订单的币对名称
     * @param startLaneId 保留的 Lane 起点参数，当前全局分页实现不读取
     * @param startCursorOrderId 传给活动订单索引的订单续页游标
     * @param limit 本页最多选取的记录数
     */
    static OrderPage selectOrders(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                                  ActiveOrderIndex index, String instrumentId, int startLaneId,
                                  long startCursorOrderId, int limit) {
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
        return new OrderPage(selected, 0, page.nextCursorOrderId(), page.nextCursorOrderId() == 0);
    }

    /**
     * 取币对全部活动订单编号并按降序转换为撤单路由，缺失路由跳过；空结果用空列表，其余返回只读副本。runtime 和 identities 当前未使用。
     * @param runtime 原权威运行时；Lane 操作依赖当前线程持有对应账户作用域
     * @param identities 把币对和资产名称转换为内部编号的身份表
     * @param index 活动订单路由索引
     * @param instrumentId 需要结算并清空订单的币对名称
     */
    private static List<CancellationOrder> openOrders(TradingAccountRuntime runtime,
                                                   RuntimeIdentityRegistry identities,
                                                   ActiveOrderIndex index, String instrumentId) {
        // 币对活动订单编号的降序数组。
        long[] orderIds = index.sortedIdsDescending(instrumentId);
        // 全部活动订单对应的撤单路由列表。
        ArrayList<CancellationOrder> result = new ArrayList<>(orderIds.length);
        for (long /* 当前活动订单编号。 */ orderId : orderIds) {
            // 当前订单路由，包含其用户与币对。
            var order = index.activeOrderRoute(orderId);
            if (order != null) {
                result.add(new CancellationOrder(orderId, order.userId(), order.instrumentId()));
            }
        }
        return result.isEmpty() ? List.of() : List.copyOf(result);
    }

    /**
     * 把非空订单按用户所属 Lane 分组，并通过现有 Owner 结算派发入口等待账户侧撤单完成。
     * @param runtime 原权威运行时；Lane 操作依赖当前线程持有对应账户作用域
     * @param orders 本次要在账户侧取消的订单路由集合
     */
    private static void cancelOrders(TradingAccountRuntime runtime, Collection<CancellationOrder> orders) {
        if (orders == null || orders.isEmpty()) return;
        // 按用户路由归类的撤单记录数组。
        @SuppressWarnings("unchecked") ArrayList<CancellationOrder>[] ordersByLane =
                groupOrders(orders, runtime);
        // 需要执行账户侧撤单的 Lane 位图。
        long laneMask = laneMask(ordersByLane);
        runtime.executeOwnerSettlements(laneMask, orders.size(),
                /* 当前账户 Lane 编号。 */ lane -> { cancelOrdersOnLane(runtime, ordersByLane[lane]); return null; });
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
     * 按用户路由建立每个账户 Lane 的用户编号列表；即使某个 Lane 没有用户，也保留一个空列表。
     * @param userIds 需要按账户 Lane 分组的用户编号
     * @param runtime 原权威运行时；Lane 操作依赖当前线程持有对应账户作用域
     */
    private static LongArrayList[] groupUsers(List<Long> userIds, TradingAccountRuntime runtime) {
        // 按账户 Lane 编号排列的用户或订单分组数组。
        LongArrayList[] groups = new LongArrayList[runtime.topology().accountLaneCount()];
        for (/* 当前账户 Lane 编号。 */ int lane = 0; lane < groups.length; lane++) groups[lane] = new LongArrayList();
        for (long /* 本轮正在处理的用户编号。 */ userId : userIds) groups[runtime.topology().accountLaneId(userId)].add(userId);
        return groups;
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
     * 跳过空订单，按用户路由把撤单记录归入所属 Lane；只给实际使用的分组分配列表。
     * @param orders 本次要在账户侧取消的订单路由集合
     * @param runtime 原权威运行时；Lane 操作依赖当前线程持有对应账户作用域
     */
    private static ArrayList<CancellationOrder>[] groupOrders(Collection<CancellationOrder> orders,
                                                            TradingAccountRuntime runtime) {
        // 按账户 Lane 编号排列的用户或订单分组数组。
        @SuppressWarnings("unchecked") ArrayList<CancellationOrder>[] groups =
                (ArrayList<CancellationOrder>[]) new ArrayList<?>[runtime.topology().accountLaneCount()];
        for (var /* 当前订单路由，包含其用户与币对。 */ order : orders) {
            if (order == null) continue;
            // 当前账户 Lane 编号。
            int lane = runtime.topology().accountLaneId(order.userId());
            // 当前订单所属 Lane 的可复用列表入口。
            ArrayList<CancellationOrder> group = groups[lane];
            if (group == null) groups[lane] = group = new ArrayList<>();
            group.add(order);
        }
        return groups;
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
     * 首次分页必须从双零游标开始；已有进度时，结算编号、价格、期权参数、撤单阶段与两个游标必须完全匹配，否则拒绝续页。这里不另比较命令 UUID 或币对对象身份。
     * @param progress 该币对已保存的结算进度，尚未开始时为空
     * @param command 固定结算价、结算编号及订单和用户分页参数
     * @param chunked 本次是否启用命令分页
     */
    static void validateProgress(TreasuryRuntime.LifecycleProgressRuntime progress,
                                 SettleInstrumentCommand command, boolean chunked) {
        if (chunked && progress == null && (command.cursorUserId() != 0 || command.cursorOrderId() != 0)) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor must start at zero");
        }
        if (progress != null && (progress.settlementId() != command.settlementId()
                || progress.settlementPriceTicks() != command.settlementPriceTicks()
                || progress.optionCashUnitsPerContract() != command.optionCashUnitsPerContract()
                || progress.ordersComplete() != (command.cursorOrderId() == 0)
                || progress.nextCursorOrderId() != command.cursorOrderId()
                || progress.nextCursorUserId() != command.cursorUserId())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor does not match progress");
        }
    }

    /**
     * 全局用户页的内容与结束标记，Lane 字段仅保留进度结构形状。
     * @param userIds 本页按全局索引顺序选出的用户列表
     * @param accountLaneId 保留的分页位置字段，当前全局分页固定返回零
     * @param nextCursorUserId 未读完时为本页最后用户编号，读完为零
     * @param complete 本次分页是否已经读完后续记录
     */
    record UserPage(List<Long> userIds, int accountLaneId,
                    long nextCursorUserId, boolean complete) {
    }

    /**
     * 活动订单页的路由与后续游标，不代表这些订单已经从 Matcher 撤销。
     * @param orders 本页选出的活动订单路由列表
     * @param accountLaneId 保留的分页位置字段，当前全局分页固定返回零
     * @param nextCursorOrderId 下一订单页游标，零表示已读完
     * @param complete 本次分页是否已经读完后续记录
     */
    record OrderPage(List<CancellationOrder> orders, int accountLaneId,
                     long nextCursorOrderId, boolean complete) {
    }
}
