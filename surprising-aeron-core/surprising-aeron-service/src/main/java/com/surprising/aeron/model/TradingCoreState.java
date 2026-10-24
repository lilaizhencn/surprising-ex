package com.surprising.aeron.model;

import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.state.OrderReservation;
import com.surprising.aeron.service.snapshot.state.CoreStateHash;
import com.surprising.aeron.service.snapshot.state.StateMapSupport;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.TreeMap;

/**
 * 单个产品线的冷状态视图，用于恢复、导出和明确边界上的业务核对。保存用户、订单、合约、风险、财库及条件任务，在线可变账户仍由 TradingAccountRuntime 和所属 Lane 管理。映射按键排序并封闭写入口，不在撮合热路径每条命令重建此记录。
 * @param productLine 本状态唯一所属产品线
 * @param revision 本状态非负业务修订
 * @param users 按用户 ID 保存的冷用户正文
 * @param orders 按订单号保存的冷订单正文，可包含保留终态
 * @param instruments 按永久数字标的文本保存的合约配置
 * @param riskState 标记价、风险快照、强平及扫描的冷状态
 * @param treasuryState 系统财库余额、资金费及到期结算进度
 * @param leverages 按用户、标的及保证金模式保存的杠杆值
 * @param algoOrders 按算法 ID 保存的条件算法状态
 * @param cancelAllAfterTimers 按用户与范围键保存的全撤计时器
 * @param clientOrderIndex 用户客户端单号到原订单号的已有派生索引，主构造不允许缺失
 * @param triggerOrders 按触发单 ID 保存的状态
 */
public record TradingCoreState(
        ProductLine productLine,
        long revision,
        Map<Long, CoreUserState> users,
        Map<Long, CoreOrderState> orders,
        Map<String, CoreInstrument> instruments,
        CoreRiskState riskState,
        CoreTreasuryState treasuryState,
        Map<CoreLeverageKey, Long> leverages,
        Map<Long, CoreAlgoOrderState> algoOrders,
        Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers,
        Map<ClientOrderKey, Long> clientOrderIndex,
        Map<Long, CoreTriggerOrderState> triggerOrders) {

    /** 检查完整冷状态的身份、客户端索引、计时器和触发单，再冻结有序映射。 */
    public TradingCoreState {
        if (productLine == null || revision < 0 || users == null || orders == null
                || instruments == null || riskState == null || treasuryState == null || leverages == null
                || algoOrders == null || cancelAllAfterTimers == null
                || triggerOrders == null) {
            throw new IllegalArgumentException("invalid trading core state");
        }
        Map<Long, CoreUserState> sortedUsers = StateMapSupport.freezeSorted(users);
        Map<Long, CoreOrderState> sortedOrders = StateMapSupport.freezeSorted(orders);
        sortedUsers.forEach((userId, user) -> validateUser(productLine, userId, user));
        sortedOrders.forEach((orderId, order) -> validateOrder(productLine, sortedUsers, orderId, order));
        if (clientOrderIndex == null) {
            throw new IllegalArgumentException("client order index is required for an authoritative state transition");
        }
        Map<ClientOrderKey, Long> sortedClientOrders = StateMapSupport.freezeSorted(clientOrderIndex);
        if (!sortedClientOrders.equals(deriveClientOrderIndex(sortedOrders))) {
            throw new IllegalArgumentException("client order index does not match authoritative orders");
        }
        users = sortedUsers;
        orders = sortedOrders;
        instruments = StateMapSupport.freezeSorted(instruments);
        leverages = StateMapSupport.freezeSorted(leverages);
        algoOrders = StateMapSupport.freezeSorted(algoOrders);
        Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> sortedTimers = StateMapSupport.freezeSorted(cancelAllAfterTimers);
        sortedTimers.forEach(TradingCoreState::validateTimer);
        cancelAllAfterTimers = sortedTimers;
        clientOrderIndex = sortedClientOrders;
        Map<Long, CoreTriggerOrderState> sortedTriggers = StateMapSupport.freezeSorted(triggerOrders);
        sortedTriggers.forEach((id, trigger) -> validateTrigger(productLine, sortedUsers, id, trigger));
        triggerOrders = sortedTriggers;
    }

    /**
     * 构造基础冷状态，杠杆及条件任务为空，从给定订单推导客户端单号索引。
     * @param productLine 本状态唯一所属产品线
     * @param revision 本状态非负业务修订
     * @param users 按用户 ID 保存的冷用户正文
     * @param orders 按订单号保存的冷订单正文，可包含保留终态
     * @param instruments 按永久数字标的文本保存的合约配置
     * @param riskState 标记价、风险快照、强平及扫描的冷状态
     * @param treasuryState 系统财库余额、资金费及到期结算进度
     */
    public TradingCoreState(ProductLine productLine, long revision, Map<Long, CoreUserState> users,
                            Map<Long, CoreOrderState> orders,
                            Map<String, CoreInstrument> instruments, CoreRiskState riskState,
                            CoreTreasuryState treasuryState) {
        this(productLine, revision, users, orders, instruments, riskState, treasuryState,
                Map.of(), Map.of(), Map.of(), deriveClientOrderIndex(orders), Map.of());
    }

    /**
     * 构造带杠杆的冷状态，条件任务为空，从订单推导客户端单号索引。
     * @param productLine 本状态唯一所属产品线
     * @param revision 本状态非负业务修订
     * @param users 按用户 ID 保存的冷用户正文
     * @param orders 按订单号保存的冷订单正文，可包含保留终态
     * @param instruments 按永久数字标的文本保存的合约配置
     * @param riskState 标记价、风险快照、强平及扫描的冷状态
     * @param treasuryState 系统财库余额、资金费及到期结算进度
     * @param leverages 按用户、标的及保证金模式保存的杠杆值
     */
    public TradingCoreState(ProductLine productLine, long revision, Map<Long, CoreUserState> users,
                            Map<Long, CoreOrderState> orders,
                            Map<String, CoreInstrument> instruments, CoreRiskState riskState,
                            CoreTreasuryState treasuryState, Map<CoreLeverageKey, Long> leverages) {
        this(productLine, revision, users, orders, instruments, riskState, treasuryState,
                leverages, Map.of(), Map.of(), deriveClientOrderIndex(orders), Map.of());
    }

    /**
     * 构造带杠杆及算法单的冷状态，其余条件任务为空，推导客户端单号索引。
     * @param productLine 本状态唯一所属产品线
     * @param revision 本状态非负业务修订
     * @param users 按用户 ID 保存的冷用户正文
     * @param orders 按订单号保存的冷订单正文，可包含保留终态
     * @param instruments 按永久数字标的文本保存的合约配置
     * @param riskState 标记价、风险快照、强平及扫描的冷状态
     * @param treasuryState 系统财库余额、资金费及到期结算进度
     * @param leverages 按用户、标的及保证金模式保存的杠杆值
     * @param algoOrders 按算法 ID 保存的条件算法状态
     */
    public TradingCoreState(ProductLine productLine, long revision, Map<Long, CoreUserState> users,
                            Map<Long, CoreOrderState> orders,
                            Map<String, CoreInstrument> instruments, CoreRiskState riskState,
                            CoreTreasuryState treasuryState, Map<CoreLeverageKey, Long> leverages,
                            Map<Long, CoreAlgoOrderState> algoOrders) {
        this(productLine, revision, users, orders, instruments, riskState, treasuryState,
                leverages, algoOrders, Map.of(), deriveClientOrderIndex(orders), Map.of());
    }

    /**
     * 构造带算法单和全撤计时器的冷状态，触发单为空，推导客户端单号索引。
     * @param productLine 本状态唯一所属产品线
     * @param revision 本状态非负业务修订
     * @param users 按用户 ID 保存的冷用户正文
     * @param orders 按订单号保存的冷订单正文，可包含保留终态
     * @param instruments 按永久数字标的文本保存的合约配置
     * @param riskState 标记价、风险快照、强平及扫描的冷状态
     * @param treasuryState 系统财库余额、资金费及到期结算进度
     * @param leverages 按用户、标的及保证金模式保存的杠杆值
     * @param algoOrders 按算法 ID 保存的条件算法状态
     * @param cancelAllAfterTimers 按用户与范围键保存的全撤计时器
     */
    public TradingCoreState(ProductLine productLine, long revision, Map<Long, CoreUserState> users,
                            Map<Long, CoreOrderState> orders,
                            Map<String, CoreInstrument> instruments, CoreRiskState riskState,
                            CoreTreasuryState treasuryState, Map<CoreLeverageKey, Long> leverages,
                            Map<Long, CoreAlgoOrderState> algoOrders,
                            Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers) {
        this(productLine, revision, users, orders, instruments, riskState, treasuryState,
                leverages, algoOrders, cancelAllAfterTimers, deriveClientOrderIndex(orders), Map.of());
    }

    /**
     * 构造带全部条件任务的冷状态，从订单推导客户端单号索引，再交主构造检查。
     * @param productLine 本状态唯一所属产品线
     * @param revision 本状态非负业务修订
     * @param users 按用户 ID 保存的冷用户正文
     * @param orders 按订单号保存的冷订单正文，可包含保留终态
     * @param instruments 按永久数字标的文本保存的合约配置
     * @param riskState 标记价、风险快照、强平及扫描的冷状态
     * @param treasuryState 系统财库余额、资金费及到期结算进度
     * @param leverages 按用户、标的及保证金模式保存的杠杆值
     * @param algoOrders 按算法 ID 保存的条件算法状态
     * @param cancelAllAfterTimers 按用户与范围键保存的全撤计时器
     * @param triggerOrders 按触发单 ID 保存的状态
     */
    public TradingCoreState(ProductLine productLine, long revision, Map<Long, CoreUserState> users,
                            Map<Long, CoreOrderState> orders,
                            Map<String, CoreInstrument> instruments, CoreRiskState riskState,
                            CoreTreasuryState treasuryState, Map<CoreLeverageKey, Long> leverages,
                            Map<Long, CoreAlgoOrderState> algoOrders,
                            Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers,
                            Map<Long, CoreTriggerOrderState> triggerOrders) {
        this(productLine, revision, users, orders, instruments, riskState, treasuryState,
                leverages, algoOrders, cancelAllAfterTimers, deriveClientOrderIndex(orders), triggerOrders);
    }

    /**
     * 建立指定产品线、零修订且各业务表均为空的冷状态，产品线仍必须明确。
     * @param productLine 本状态唯一所属产品线
     */
    public static TradingCoreState empty(ProductLine productLine) {
        return new TradingCoreState(productLine, 0, Map.of(), Map.of(),
                Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
    }

    /**
     * 按用户 ID 查冷状态用户，未知返回空。
     * @param userId 待读取、验证或排序的用户 ID
     */
    public CoreUserState user(long userId) {
        return users.get(userId);
    }

    /**
     * 按订单 ID 查冷状态正文，未知返回空。
     * @param orderId 待读取、验证或盖提交元数据的订单号
     */
    public CoreOrderState order(long orderId) {
        return orders.get(orderId);
    }

    /**
     * 核对图键与用户自身 ID 一致，并属于该产品线。
     * @param productLine 本状态唯一所属产品线
     * @param userId 待读取、验证或排序的用户 ID
     * @param user 当前要验证或混合哈希的冷用户正文
     */
    private static void validateUser(ProductLine productLine, long userId, CoreUserState user) {
        if (userId != user.userId() || user.productLine() != productLine) {
            throw new IllegalArgumentException("user state belongs to another partition");
        }
    }

    /**
     * 核对图键、订单自身 ID 及产品线，且订单用户必须已在本状态中。
     * @param productLine 本状态唯一所属产品线
     * @param users 按用户 ID 保存的冷用户正文
     * @param orderId 待读取、验证或盖提交元数据的订单号
     * @param order 当前要验证或混合哈希的冷订单正文
     */
    private static void validateOrder(ProductLine productLine, Map<Long, CoreUserState> users,
                                      long orderId, CoreOrderState order) {
        if (orderId != order.orderId() || order.productLine() != productLine
                || !users.containsKey(order.userId())) {
            throw new IllegalArgumentException("order state belongs to another partition");
        }
    }

    /**
     * 核对图键等于计时器自身用户和范围键。
     * @param key 计时器的用户与范围键
     * @param timer 应与该业务键一致的冷计时器状态
     */
    private static void validateTimer(CoreCancelAllAfterKey key, CoreCancelAllAfterState timer) {
        if (!key.equals(timer.key())) {
            throw new IllegalArgumentException("cancel-all-after key does not match authoritative timer");
        }
    }

    /**
     * 核对图键、触发单 ID 及产品线，并要求用户存在。
     * @param productLine 本状态唯一所属产品线
     * @param users 按用户 ID 保存的冷用户正文
     * @param id 触发单映射键，必须等于状态自身 ID
     * @param trigger 当前要验证用户及产品线归属的触发单
     */
    private static void validateTrigger(ProductLine productLine, Map<Long, CoreUserState> users,
                                         Object id, CoreTriggerOrderState trigger) {
        if (!id.equals(trigger.triggerOrderId()) || trigger.productLine() != productLine
                || !users.containsKey(trigger.userId())) {
            throw new IllegalArgumentException("trigger order state belongs to another partition");
        }
    }

    /**
     * 进入本冷状态的完整业务哈希，不包含运行时外层另行组合的费用政策及待完成划转。
     */
    public long businessStateHash() {
        return fullBusinessStateHash();
    }

    /**
     * 按有序冷状态的固定选取字段混合产品线、修订、用户及未终态订单、杠杆、未终态算法、仍 open 的触发单、未终态强平、计时器、价格风险与扫描进度、财库余额及生命周期进度。跳过的保留终态和纯派生索引不作为另一份业务事实；这是全量核对入口，不是密码学证明。
     */
    public long fullBusinessStateHash() {
        // 按既定业务字段顺序不断混合的哈希累计值
        long hash = CoreStateHash.mix(CoreStateHash.start(), com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(productLine));
        hash = CoreStateHash.mix(hash, revision);
        for (CoreUserState /* 当前冷用户值或校验回调参数，删除项为空时跳过 */ user : users.values()) {
            hash = hashUser(hash, user);
        }
        for (CoreOrderState /* 当前校验、比较提交元数据、派生客户端索引或混合哈希的冷订单 */ order : orders.values()) {
            if (order.status().terminal()) continue;
            hash = hashOrder(hash, order);
        }
        for (Map.Entry<CoreLeverageKey, Long> /* 当前杠杆或财库分类表中的业务键值对，按该表字段顺序混合 */ entry : leverages.entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey().userId());
            hash = CoreStateHash.mix(hash, entry.getKey().instrumentId());
            hash = CoreStateHash.mix(hash, entry.getKey().marginMode().wireCode());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        for (CoreAlgoOrderState /* 当前参与哈希的非终态算法单状态 */ algo : algoOrders.values()) {
            if (algo.terminal()) continue;
            hash = CoreStateHash.mix(hash, algo.algoOrderId()); hash = CoreStateHash.mix(hash, algo.userId());
            hash = CoreStateHash.mix(hash, algo.instrumentId()); hash = CoreStateHash.mix(hash, algo.statusCode());
            hash = CoreStateHash.mix(hash, algo.updatedAtEpochMillis()); hash = CoreStateHash.mix(hash, algo.revision());
            if (algo.retiredExecutedQuantitySteps() != 0 || algo.pendingClosedChildId() != 0) {
                hash = CoreStateHash.mix(hash, "algo-retired-progress");
                hash = CoreStateHash.mix(hash, algo.retiredExecutedQuantitySteps());
                hash = CoreStateHash.mix(hash, algo.pendingClosedChildId());
            }
            for (long /* 当前算法记录的子订单 ID，按原清单顺序混合 */ childOrderId : algo.childOrderIds()) hash = CoreStateHash.mix(hash, childOrderId);
        }
        for (CoreCancelAllAfterState /* 当前变化槽对应计时器，或参与哈希的完整计时器状态 */ timer : cancelAllAfterTimers.values()) {
            hash = CoreStateHash.mix(hash, timer.userId());
            hash = CoreStateHash.mix(hash, timer.symbolScope());
            hash = CoreStateHash.mix(hash, timer.status().wireCode());
            hash = CoreStateHash.mix(hash, timer.triggerAtEpochMillis());
            hash = CoreStateHash.mix(hash, timer.updatedAtEpochMillis());
            hash = CoreStateHash.mix(hash, timer.revision());
        }
        for (CoreTriggerOrderState /* 当前待校验或混合的触发单状态，哈希只纳入仍 open 的项 */ trigger : triggerOrders.values()) {
            if (!trigger.status().open()) continue;
            hash = CoreStateHash.mix(hash, trigger.triggerOrderId());
            hash = CoreStateHash.mix(hash, trigger.userId());
            hash = CoreStateHash.mix(hash, trigger.instrumentId());
            hash = CoreStateHash.mix(hash, trigger.side().wireCode());
            hash = CoreStateHash.mix(hash, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(trigger.triggerType()));
            hash = CoreStateHash.mix(hash, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(trigger.triggerCondition()));
            hash = CoreStateHash.mix(hash, trigger.triggerPriceTicks());
            hash = CoreStateHash.mix(hash, trigger.highestPriceTicks());
            hash = CoreStateHash.mix(hash, trigger.lowestPriceTicks());
            hash = CoreStateHash.mix(hash, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(trigger.status()));
            hash = CoreStateHash.mix(hash, trigger.placedOrderId());
            hash = CoreStateHash.mix(hash, trigger.triggerSequence());
            hash = CoreStateHash.mix(hash, trigger.triggeredPriceTicks());
            hash = CoreStateHash.mix(hash, trigger.updatedAtEpochMillis());
            hash = CoreStateHash.mix(hash, trigger.revision());
            if (trigger.priceSource() != com.surprising.aeron.protocol.CoreTriggerPriceSource.MARK) {
                hash = CoreStateHash.mix(hash, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(trigger.priceSource()));
            }
        }
        for (CoreMarkPriceState /* 当前标记、指数、远期及可选最新成交价状态 */ mark : riskState.markPrices().values()) {
            hash = CoreStateHash.mix(hash, mark.instrumentId());
            hash = CoreStateHash.mix(hash, mark.markPriceTicks());
            hash = CoreStateHash.mix(hash, mark.indexPriceTicks());
            if (productLine.isOptionProduct()) hash = CoreStateHash.mix(hash, mark.forwardPriceTicks());
            hash = CoreStateHash.mix(hash, mark.priceSequence());
            hash = CoreStateHash.mix(hash, mark.generatedAtEpochMillis());
            if (mark.lastPriceTicks() > 0) hash = CoreStateHash.mix(hash, mark.lastPriceTicks());
        }
        for (CoreRiskSnapshot /* 当前用户持仓风险估值快照 */ risk : riskState.snapshots().values()) {
            hash = CoreStateHash.mix(hash, risk.userId());
            hash = CoreStateHash.mix(hash, risk.instrumentId());
            hash = CoreStateHash.mix(hash, risk.positionSide().wireCode());
            hash = CoreStateHash.mix(hash, risk.priceSequence());
            hash = CoreStateHash.mix(hash, risk.equityUnits());
            hash = CoreStateHash.mix(hash, risk.unrealizedPnlUnits());
            hash = CoreStateHash.mix(hash, risk.maintenanceMarginUnits());
            hash = CoreStateHash.mix(hash, risk.marginRatioPpm());
            hash = CoreStateHash.mix(hash, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(risk.status()));
        }
        for (CoreLiquidationState /* 当前参与哈希的未结束强平状态 */ liquidation : riskState.liquidations().values()) {
            if (liquidation.terminal()) continue;
            hash = CoreStateHash.mix(hash, liquidation.liquidationId());
            hash = CoreStateHash.mix(hash, liquidation.userId());
            hash = CoreStateHash.mix(hash, liquidation.instrumentId());
            hash = CoreStateHash.mix(hash, liquidation.marginMode().wireCode());
            hash = CoreStateHash.mix(hash, liquidation.positionSide().wireCode());
            hash = CoreStateHash.mix(hash, liquidation.triggerPriceSequence());
            hash = CoreStateHash.mix(hash, liquidation.signedQuantitySteps());
            hash = CoreStateHash.mix(hash, liquidation.closeQuantitySteps());
            hash = CoreStateHash.mix(hash, liquidation.deficitUnits());
            hash = CoreStateHash.mix(hash, liquidation.executionPriceTicks());
            hash = CoreStateHash.mix(hash, liquidation.liquidationFeeRatePpm());
            hash = CoreStateHash.mix(hash, liquidation.liquidationFeeUnits());
            hash = CoreStateHash.mix(hash, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(liquidation.status()));
            hash = CoreStateHash.mix(hash, liquidation.nextCancelOrderId());
        }
        for (CoreRiskState.RiskScan /* 当前标的风险与触发扫描游标及累积进度 */ scan : riskState.scans().values()) {
            hash = CoreStateHash.mix(hash, scan.instrumentId());
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
            hash = CoreStateHash.mix(hash, scan.lastScheduledRevision());
        }
        hash = CoreStateHash.mix(hash, riskState.nextLiquidationId());
        hash = CoreStateHash.mix(hash, riskState.marketRevision());
        // 全局风险扫描控制配置，包含版本、开关、预算及审计信息
        var scanControl = riskState.scanControl();
        hash = CoreStateHash.mix(hash, scanControl.version());
        hash = CoreStateHash.mix(hash, scanControl.ruleName());
        hash = CoreStateHash.mix(hash, scanControl.enabled());
        hash = CoreStateHash.mix(hash, scanControl.scanDelayMs());
        hash = CoreStateHash.mix(hash, scanControl.scanBatchSize());
        hash = CoreStateHash.mix(hash, scanControl.updatedBy());
        hash = CoreStateHash.mix(hash, scanControl.reason());
        hash = CoreStateHash.mix(hash, scanControl.updatedAtEpochMillis());
        hash = CoreStateHash.mix(hash, "treasuryFee");
        for (Map.Entry<String, Long> /* 当前杠杆或财库分类表中的业务键值对，按该表字段顺序混合 */ entry : treasuryState.feeBalances().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        hash = CoreStateHash.mix(hash, "treasuryInsurance");
        for (Map.Entry<String, Long> /* 当前杠杆或财库分类表中的业务键值对，按该表字段顺序混合 */ entry : treasuryState.insuranceBalances().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        hash = CoreStateHash.mix(hash, "treasuryDeficit");
        for (Map.Entry<String, Long> /* 当前杠杆或财库分类表中的业务键值对，按该表字段顺序混合 */ entry : treasuryState.insuranceDeficits().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        hash = CoreStateHash.mix(hash, "treasuryLiquidationFee");
        for (Map.Entry<String, Long> /* 当前杠杆或财库分类表中的业务键值对，按该表字段顺序混合 */ entry : treasuryState.liquidationFeeBalances().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        hash = CoreStateHash.mix(hash, "treasuryFundingResidual");
        for (Map.Entry<String, Long> /* 当前杠杆或财库分类表中的业务键值对，按该表字段顺序混合 */ entry : treasuryState.fundingResidualBalances().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        hash = CoreStateHash.mix(hash, "treasuryRoundingResidual");
        for (Map.Entry<String, Long> /* 当前杠杆或财库分类表中的业务键值对，按该表字段顺序混合 */ entry : treasuryState.roundingResidualBalances().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        hash = CoreStateHash.mix(hash, "treasuryClearingPnl");
        for (Map.Entry<String, Long> /* 当前杠杆或财库分类表中的业务键值对，按该表字段顺序混合 */ entry : treasuryState.clearingPnlBalances().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        hash = CoreStateHash.mix(hash, "treasuryFundingSettlement");
        for (Map.Entry<String, Long> /* 当前杠杆或财库分类表中的业务键值对，按该表字段顺序混合 */ entry : treasuryState.fundingSettlements().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        hash = CoreStateHash.mix(hash, "treasuryFundingProgress");
        for (Map.Entry<String, CoreTreasuryState.FundingProgress> /* 当前杠杆或财库分类表中的业务键值对，按该表字段顺序混合 */ entry : treasuryState.fundingProgress().entrySet()) {
            // 当前资金费或到期结算尚未完成的财库进度
            CoreTreasuryState.FundingProgress progress = entry.getValue();
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, progress.settlementId());
            hash = CoreStateHash.mix(hash, progress.fundingRatePpm());
            hash = CoreStateHash.mix(hash, progress.markPriceTicks());
            hash = CoreStateHash.mix(hash, progress.priceSequence());
            hash = CoreStateHash.mix(hash, progress.nextCursorUserId());
        }
        hash = CoreStateHash.mix(hash, "treasuryLifecycleSettlement");
        for (Map.Entry<String, Long> /* 当前杠杆或财库分类表中的业务键值对，按该表字段顺序混合 */ entry : treasuryState.lifecycleSettlements().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        hash = CoreStateHash.mix(hash, "treasuryLifecycleProgress");
        for (Map.Entry<String, CoreTreasuryState.LifecycleProgress> /* 当前杠杆或财库分类表中的业务键值对，按该表字段顺序混合 */ entry
                : treasuryState.lifecycleProgress().entrySet()) {
            // 当前资金费或到期结算尚未完成的财库进度
            CoreTreasuryState.LifecycleProgress progress = entry.getValue();
            hash = CoreStateHash.mix(hash, progress.requiredInsuranceUnits());
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, progress.settlementId());
            hash = CoreStateHash.mix(hash, progress.settlementPriceTicks());
            hash = CoreStateHash.mix(hash, progress.ordersComplete());
            hash = CoreStateHash.mix(hash, progress.nextCursorOrderId());
            hash = CoreStateHash.mix(hash, progress.nextCursorUserId());
        }
        return hash;
    }

    /**
     * 未知用户返回零；混合该用户账户正文及按用户有序的杠杆字段，不包含全局订单表中的订单正文。
     * @param userId 待读取、验证或排序的用户 ID
     */
    public long userStateHash(long userId) {
        // 当前冷用户值或校验回调参数，删除项为空时跳过
        CoreUserState user = users.get(userId);
        if (user == null) return 0;
        // 按既定业务字段顺序不断混合的哈希累计值
        long hash = hashUser(CoreStateHash.start(), user);
        for (Map.Entry<CoreLeverageKey, Long> /* 当前杠杆或财库分类表中的业务键值对，按该表字段顺序混合 */ entry : leverages.entrySet()) {
            // 有序杠杆项所属用户，用于跳过前部及在越过目标后停止
            long entryUserId = entry.getKey().userId();
            if (entryUserId < userId) continue;
            if (entryUserId > userId) break;
            hash = CoreStateHash.mix(hash, entry.getKey().instrumentId());
            hash = CoreStateHash.mix(hash, entry.getKey().marginMode().wireCode());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        return hash;
    }

    /**
     * 未知订单返回零，存在时混合该订单完整业务及提交字段；单项查询不因订单终态而跳过。
     * @param orderId 待读取、验证或盖提交元数据的订单号
     */
    public long orderStateHash(long orderId) {
        // 当前校验、比较提交元数据、派生客户端索引或混合哈希的冷订单
        CoreOrderState order = orders.get(orderId);
        return order == null ? 0 : hashOrder(CoreStateHash.start(), order);
    }

    /**
     * 在既有哈希上混合用户身份、修订、持仓模式、余额、剩余预留非零的冻结及全部持仓字段；不把已释放的零剩余预留作为新事实。
     * @param initial 此前已经累计的哈希状态
     * @param user 当前要验证或混合哈希的冷用户正文
     */
    static long hashUser(long initial, CoreUserState user) {
        // 按既定业务字段顺序不断混合的哈希累计值
        long hash = CoreStateHash.mix(initial, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(user.productLine()));
        hash = CoreStateHash.mix(hash, user.userId());
        hash = CoreStateHash.mix(hash, user.revision());
        hash = CoreStateHash.mix(hash, user.positionMode().wireCode());
        for (AssetBalance /* 当前用户账户中的资产余额 */ balance : user.balances().values()) {
            hash = CoreStateHash.mix(hash, balance.asset());
            hash = CoreStateHash.mix(hash, balance.availableUnits());
            hash = CoreStateHash.mix(hash, balance.lockedUnits());
        }
        for (OrderReservation /* 当前用户的订单预留，剩余为零时不参与业务哈希 */ reservation : user.reservations().values()) {
            if (reservation.remainingUnits() == 0) continue;
            hash = CoreStateHash.mix(hash, reservation.orderId());
            hash = CoreStateHash.mix(hash, reservation.instrumentId());
            hash = CoreStateHash.mix(hash, reservation.kind().wireCode());
            hash = CoreStateHash.mix(hash, reservation.asset());
            hash = CoreStateHash.mix(hash, reservation.reservedUnits());
            hash = CoreStateHash.mix(hash, reservation.releasedUnits());
            hash = CoreStateHash.mix(hash, reservation.consumedUnits());
            hash = CoreStateHash.mix(hash, reservation.orderQuantitySteps());
        }
        for (CorePositionState /* 当前用户的冷持仓正文，按全部持仓字段混合 */ position : user.positions().values()) {
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
        return hash;
    }

    /**
     * 在既有哈希上按固定顺序混合订单业务字段、费用、成交价值高低位及时间、日志位置、状态修订；枚举用稳定协议编码。
     * @param initial 此前已经累计的哈希状态
     * @param order 当前要验证或混合哈希的冷订单正文
     */
    static long hashOrder(long initial, CoreOrderState order) {
        // 按既定业务字段顺序不断混合的哈希累计值
        long hash = CoreStateHash.mix(initial, order.orderId());
        hash = CoreStateHash.mix(hash, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(order.productLine()));
        hash = CoreStateHash.mix(hash, order.userId());
        hash = CoreStateHash.mix(hash, order.instrumentId());
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
     * 扫描全部冷订单，包括保留的终态；非空客户端单号按用户建索引，同用户重复单号拒绝，不按订单状态自行丢身份。
     * @param orders 按订单号保存的冷订单正文，可包含保留终态
     */
    private static Map<ClientOrderKey, Long> deriveClientOrderIndex(Map<Long, CoreOrderState> orders) {
        // 本次从订单原客户端单号派生的用户范围有序映射
        Map<ClientOrderKey, Long> index = new TreeMap<>();
        if (orders != null) {
            for (CoreOrderState /* 当前校验、比较提交元数据、派生客户端索引或混合哈希的冷订单 */ order : orders.values()) {
                if (!order.clientOrderId().isEmpty()) {
                    // 用户与客户端单号组成的索引键
                    ClientOrderKey key = new ClientOrderKey(order.userId(), order.clientOrderId());
                    if (index.put(key, order.orderId()) != null) {
                        throw new IllegalArgumentException("duplicate clientOrderId for user");
                    }
                }
            }
        }
        return index;
    }

    /**
     * 客户端单号的用户范围排序键，避免不同用户相同单号互相覆盖。
     * @param userId 待读取、验证或排序的用户 ID
     * @param clientOrderId 用户原始客户端单号，查询空白返回空，排序键构造拒绝空白
     */
    public record ClientOrderKey(long userId, String clientOrderId) implements Comparable<ClientOrderKey> {
        /**
         * 要求用户为正、原客户端单号非空白；不修剪单号文本。
         */
        public ClientOrderKey {
            if (userId <= 0 || clientOrderId == null || clientOrderId.isBlank()) {
                throw new IllegalArgumentException("invalid client order key");
            }
        }

        /**
         * 先按用户数字比较，同用户再按原客户端单号字典序比较。
         * @param other 使用同一用户范围单号规则比较的另一排序键
         */
        @Override
        public int compareTo(ClientOrderKey other) {
            // 两个客户端排序键的用户 ID 比较结果
            int userComparison = Long.compare(userId, other.userId);
            return userComparison != 0 ? userComparison : clientOrderId.compareTo(other.clientOrderId);
        }
    }
}
