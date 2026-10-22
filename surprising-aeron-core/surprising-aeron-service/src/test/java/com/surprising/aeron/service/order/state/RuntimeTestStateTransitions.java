package com.surprising.aeron.service.order.state;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.AdjustInsuranceFundCommand;
import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreAdlCandidateView;
import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreCancelAllAfterCommand;
import com.surprising.aeron.protocol.CoreFundingPaymentView;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.CoreRiskSnapshotView;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.ExecuteAdlCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.protocol.UpdateRiskScanControlCommand;
import com.surprising.aeron.service.account.DerivativeAccountCommandProcessor;
import com.surprising.aeron.service.account.RuntimeAccountStateTransitions;
import com.surprising.aeron.service.adl.RuntimeAdlExecution;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.ActiveOrderIndex;
import com.surprising.aeron.service.core.index.AdlPositionIndex;
import com.surprising.aeron.service.funding.RuntimePerpetualFundingProcessor;
import com.surprising.aeron.service.liquidation.RuntimeDerivativeLiquidationProcessor;
import com.surprising.aeron.service.liquidation.RuntimeLiquidationResolution;
import com.surprising.aeron.service.order.admission.CoreOrderDecisionResolver;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.admission.RuntimeOrderAdmission;
import com.surprising.aeron.service.query.state.RuntimeRiskQueryService;
import com.surprising.aeron.service.risk.RuntimeDerivativeRiskProcessor;
import com.surprising.aeron.service.risk.RuntimeRiskStateTransitions;
import com.surprising.aeron.service.settlement.RuntimeDerivativeMatchProcessor;
import com.surprising.aeron.service.settlement.RuntimeSpotMatchProcessor;
import com.surprising.aeron.service.settlement.lifecycle.RuntimeLifecycleSettlement;
import com.surprising.aeron.service.snapshot.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.aeron.service.treasury.RuntimeInsuranceFundStateTransitions;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 测试用状态推进器，把给定业务前值投影成冷可变运行时，调用已有生产规则，再在断言边界物化后状态。它不是在线命令执行器，不经过真实 Cluster、Matcher 队列和异步提交链路。
 */
public final class RuntimeTestStateTransitions {

    /**
     * 创建无独立业务状态的测试推进器，每次操作另投影自己的运行时。
     */
    public RuntimeTestStateTransitions() {
    }

    /**
     * 沿用接收拓扑的测试调用契约，但当前实现不使用该参数，运行态仍由每次投影入口创建。
     * @param ignored 沿用测试构造签名的 Lane 拓扑参数，当前实现不读取它
     */
    public RuntimeTestStateTransitions(LaneTopology ignored) {
    }

    /**
     * 按前状态产品创建规范币对，重复注册拒绝；只准备新的币对映射并递增业务版本，其他子状态作为原参数带入后状态。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState registerInstrument(TradingCoreState before, RegisterInstrumentCommand command) {
        // 按前状态产品及注册命令创建的规范币对，重复编号不允许覆盖。
        var instrument = com.surprising.aeron.service.instrument.CoreInstrument.from(
                before.productLine(), command);
        if (before.instruments().containsKey(instrument.instrumentId())) {
            throw new com.surprising.aeron.exception.CoreStateRejectedException(
                    "INVALID_COMMAND", "instrument is already registered");
        }
        // 前状态币对映射的本次浅复制，只加入新币对，不改原映射。
        var instruments = new java.util.HashMap<>(before.instruments());
        instruments.put(instrument.instrumentId(), instrument);
        return new TradingCoreState(before.productLine(), Math.incrementExact(before.revision()),
                before.users(), before.orders(), instruments, before.riskState(), before.treasuryState(),
                before.leverages(), before.algoOrders(), before.cancelAllAfterTimers(),
                before.clientOrderIndex(), before.triggerOrders());
    }

    /**
     * 在冷投影运行时调用正式余额调整规则，再返回物化后的业务状态。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param userId 当前测试命令或风险查询使用的用户编号
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState adjustBalance(TradingCoreState before, long userId, BalanceAdjustmentCommand command) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) ->
                RuntimeAccountStateTransitions.adjustBalance(runtime, identities, userId, command));
    }

    /**
     * 把给定扫描开关、预算和业务时间交给生产风控配置入口，物化其后状态。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     * @param updatedAtEpochMillis 风险扫描配置更新采用的确定业务时间
     */
    public TradingCoreState updateRiskScanControl(TradingCoreState before, UpdateRiskScanControlCommand command,
                                           long updatedAtEpochMillis) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) ->
                RuntimeRiskStateTransitions.updateScanControl(runtime, command, updatedAtEpochMillis));
    }

    /**
     * 调用生产持仓模式检查和更新规则，测试不另行模拟模式转换。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param userId 当前测试命令或风险查询使用的用户编号
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState updatePositionMode(TradingCoreState before, long userId, UpdatePositionModeCommand command) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) ->
                DerivativeAccountCommandProcessor.updatePositionMode(runtime, userId, command));
    }

    /**
     * 调用生产用户杠杆更新及保证金规则，使用本次投影身份表。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param userId 当前测试命令或风险查询使用的用户编号
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState updateLeverage(TradingCoreState before, long userId, UpdateLeverageCommand command) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) ->
                DerivativeAccountCommandProcessor.updateLeverage(runtime, identities, userId, command));
    }

    /**
     * 调用生产逐仓保证金调整规则并物化余额及持仓变化。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param userId 当前测试命令或风险查询使用的用户编号
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState adjustPositionMargin(TradingCoreState before, long userId,
                                          AdjustPositionMarginCommand command) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) ->
                DerivativeAccountCommandProcessor.adjustPositionMargin(runtime, identities, userId, command));
    }

    /**
     * 以前状态全部用户为候选，在冷运行时调用生产标记价及风险处理入口，再物化风险后状态。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState applyMarkPrice(TradingCoreState before, ApplyMarkPriceCommand command) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) -> RuntimeDerivativeRiskProcessor.applyMarkPrice(
                before, command, before.users().keySet(), runtime, identities));
    }

    /**
     * 按给定用户预算和前状态用户候选调用生产续扫规则，返回其物化进度和状态。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param maxUsers 本次风控续扫的用户工作预算
     */
    public TradingCoreState continueRiskScan(TradingCoreState before, int maxUsers) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) -> RuntimeDerivativeRiskProcessor.applyContinuation(
                before, maxUsers, before.users().keySet(), runtime, identities));
    }

    /**
     * 给原始下单意图分配测试随机命令 UUID，OI 使用零，再进入完整下单辅助入口；构造的活动索引参数当前未在该入口读取。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param userId 当前测试命令或风险查询使用的用户编号
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState placeOrder(TradingCoreState before, long userId, PlaceOrderCommand command) {
        return placeOrder(before, userId, command, UUID.randomUUID(), 0, new ActiveOrderIndex(before));
    }

    /**
     * 对已经决策的订单计算预留并调用原账户写入；实际挂单汇总取自冷运行时，不使用本方法另构造的活动索引局部值。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param userId 当前测试命令或风险查询使用的用户编号
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState placeOrder(TradingCoreState before, long userId, ResolvedPlaceOrder command) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) -> {
            // 当前已解析下单夹具构造但未使用的活动索引，实际准入读取运行时挂单汇总。
            ActiveOrderIndex activeOrders = new ActiveOrderIndex(before);
            // 生产账户准入根据本单和原运行态挂单算出的应预留金额。
            long required = RuntimeOrderAdmission.requiredReservation(runtime, identities, userId,
                    command, 0, runtime.admissionOrders(userId, identities.symbolId(command.instrumentId())));
            RuntimeOrderStateTransitions.place(runtime, identities, userId, command, UUID.randomUUID(), required);
        });
    }

    /**
     * 先用冷业务状态决策入口解析原意图，再按给定 OI 和运行态挂单汇总检查预留，调用账户写入；它不执行带集群时间的在线价格鲜度检查。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param userId 当前测试命令或风险查询使用的用户编号
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     * @param commandId 测试订单归属的业务命令 UUID
     * @param openInterestSteps 下单风险检查采用的固定 OI 数量步数
     * @param activeOrders 沿用夹具签名的活动订单索引，当前下单实现未读取它，实际准入使用 runtime.admissionOrders
     */
    public TradingCoreState placeOrder(TradingCoreState before, long userId, PlaceOrderCommand command,
                                UUID commandId, long openInterestSteps, ActiveOrderIndex activeOrders) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) -> {
            // 由冷业务状态决策入口解析的订单，未执行在线集群时间鲜度校验。
            ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(before, command);
            // 生产账户准入根据本单和原运行态挂单算出的应预留金额。
            long required = RuntimeOrderAdmission.requiredReservation(runtime, identities, userId,
                    resolved, openInterestSteps, runtime.admissionOrders(userId, identities.symbolId(resolved.instrumentId())));
            RuntimeOrderStateTransitions.place(runtime, identities, userId, resolved, commandId, required);
        });
    }

    /**
     * 直接调用原账户取消状态规则释放剩余冻结，用于业务状态测试，不作为 Matcher 撤单确认。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param userId 当前测试命令或风险查询使用的用户编号
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState cancelOrder(TradingCoreState before, long userId, CancelOrderCommand command) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) ->
                RuntimeOrderStateTransitions.cancel(runtime, userId, command.orderId()));
    }

    /**
     * 把父单视图交给生产算法身份及版本规则，测试沿用其新建、追加和终态限制。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param userId 当前测试命令或风险查询使用的用户编号
     * @param view 需要应用到算法父单的协议视图
     */
    public TradingCoreState upsertAlgoOrder(TradingCoreState before, long userId, CoreAlgoOrderView view) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) ->
                RuntimeAlgoOrderStateTransitions.upsert(runtime, identities, userId, view));
    }

    /**
     * 调用生产撤单倒计时状态机，只推进计时器状态而不真实调度撤单。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param userId 当前测试命令或风险查询使用的用户编号
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState updateCancelAllAfter(TradingCoreState before, long userId,
                                          CoreCancelAllAfterCommand command) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) ->
                RuntimeCancelAllAfterStateTransitions.update(runtime, userId, command));
    }

    /**
     * 按前状态产品选择现货或衍生品生产结算处理器，应用传入的确定 Matcher 事件，物化结算后状态。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param takerOrderId 当前匹配事件所归属的吃单订单编号
     * @param baseAsset 现货成交需要的基础资产代码
     * @param quoteAsset 现货成交需要的报价资产代码
     * @param matches 已经确定的 Matcher 事件列表，由生产结算器应用而不重新撮合
     */
    public TradingCoreState applyMatches(TradingCoreState before, long takerOrderId,
                                  String baseAsset, String quoteAsset, List<MatcherEvent> matches) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) -> {
            if (before.productLine() == com.surprising.product.api.ProductLine.SPOT) {
                RuntimeSpotMatchProcessor.apply(before, takerOrderId, baseAsset, quoteAsset,
                        matches, runtime, identities);
            } else {
                RuntimeDerivativeMatchProcessor.apply(before, takerOrderId, matches, runtime, identities);
            }
        });
    }

    /**
     * 使用生产资金费入口的默认用户集合及分块标识规则，返回后状态、支付事实和进度。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public FundingApplication applyFundingWithFacts(TradingCoreState before, ApplyFundingCommand command) {
        return applyFundingWithFacts(before, command, null, null);
    }

    /**
     * 投影一份冷运行时，按指定用户集合和分块 UUID 应用生产资金费规则，捕获实际支付与进度，再物化后状态并关闭运行时。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     * @param userIds 本次资金费或生命周期页选择的用户集合，null 交给生产入口采用其默认规则
     * @param chunkCommandId 当前分页命令的幂等 UUID，null 交给生产入口处理默认标识
     */
    public FundingApplication applyFundingWithFacts(TradingCoreState before, ApplyFundingCommand command,
                                              Iterable<Long> userIds, UUID chunkCommandId) {
        // 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities)) {
            // 生产资金费处理返回的实际支付事实和分页进度，与物化后状态一起交给测试。
            var result = RuntimePerpetualFundingProcessor.apply(before, command,
                    userIds, chunkCommandId, runtime, identities);
            return new FundingApplication(RuntimeStateMaterializer.materialize(runtime, identities),
                    result.payments(), result.progress());
        }
    }

    /**
     * 只取资金费完整测试结果中的业务后状态，支付及进度仍由同一生产入口计算。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState applyFunding(TradingCoreState before, ApplyFundingCommand command) {
        return applyFundingWithFacts(before, command).state();
    }

    /**
     * 调用不显式提供活动订单索引的生命周期辅助入口，返回后状态和分页进度。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     * @param userIds 本次资金费或生命周期页选择的用户集合，null 交给生产入口采用其默认规则
     * @param chunkCommandId 当前分页命令的幂等 UUID，null 交给生产入口处理默认标识
     */
    public SettlementApplication settleInstrumentWithProgress(TradingCoreState before,
                                                        SettleInstrumentCommand command,
                                                        Iterable<Long> userIds, UUID chunkCommandId) {
        return settleInstrumentWithProgress(before, command, userIds, chunkCommandId, null);
    }

    /**
     * 在冷投影中调用生产生命周期结算，传入用户、分块 UUID 和可选活动索引，物化后状态并保留实际进度。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     * @param userIds 本次资金费或生命周期页选择的用户集合，null 交给生产入口采用其默认规则
     * @param chunkCommandId 当前分页命令的幂等 UUID，null 交给生产入口处理默认标识
     * @param activeOrders 生命周期结算可选的活动订单索引，原生产入口负责解释 null
     */
    public SettlementApplication settleInstrumentWithProgress(TradingCoreState before,
                                                        SettleInstrumentCommand command,
                                                        Iterable<Long> userIds, UUID chunkCommandId,
                                                        ActiveOrderIndex activeOrders) {
        // 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities)) {
            // 生产生命周期处理返回的确定分页进度。
            var progress = RuntimeLifecycleSettlement.apply(before, command, userIds, chunkCommandId,
                    activeOrders, runtime, identities);
            return new SettlementApplication(RuntimeStateMaterializer.materialize(runtime, identities), progress);
        }
    }

    /**
     * 按生产入口默认分页输入处理生命周期结算，仅返回业务后状态。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState settleInstrument(TradingCoreState before, SettleInstrumentCommand command) {
        return settleInstrumentWithProgress(before, command, null, null, null).state();
    }

    /**
     * 把测试业务订单投影成订单、用户和永久币对组成的撤单路由列表，不在此执行撤单。
     * @param orders 需要转换为撤单路由的测试业务订单集合
     */
    public static java.util.List<com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter.CancellationOrder> cancellationRoutes(
            Collection<CoreOrderState> orders) {
        return orders.stream().map(/* 当前需要投影撤单路由、执行取消或按终态过滤的测试业务订单。 */ order -> new com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter.CancellationOrder(
                order.orderId(), order.userId(), order.instrumentId())).toList();
    }

    /**
     * 把前状态所有非终态订单转成候选撤单路由，连同强平命令交给生产同步执行入口，结果再物化。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState executeLiquidation(TradingCoreState before, ExecuteLiquidationCommand command) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) -> RuntimeDerivativeLiquidationProcessor.applyExecution(
                before, command, cancellationRoutes(openOrders(before)), runtime, identities));
    }

    /**
     * 在测试已完成撤单的场景传空撤单路由，调用同一生产强平执行规则。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState executeLiquidationAfterCancellation(TradingCoreState before,
                                                          ExecuteLiquidationCommand command) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) -> RuntimeDerivativeLiquidationProcessor.applyExecution(
                before, command, List.of(), runtime, identities));
    }

    /**
     * 把指定已撤订单转成路由，交给生产强平撤单分页推进规则并采用给定后续游标。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     * @param canceledOrders 本次生命周期或强平撤单阶段已经确定的订单集合
     * @param nextCursorOrderId 强平撤单推进后保留的下一订单游标
     */
    public TradingCoreState advanceLiquidationCancellation(TradingCoreState before,
                                                     ExecuteLiquidationCommand command,
                                                     Collection<CoreOrderState> canceledOrders,
                                                     long nextCursorOrderId) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) ->
                RuntimeDerivativeLiquidationProcessor.applyCancellationAdvance(before, command,
                        cancellationRoutes(canceledOrders), nextCursorOrderId, runtime, identities));
    }

    /**
     * 在冷运行时逐项按剩余冻结调用原账户取消入口，最后递增一次命令版本，供生命周期场景准备已撤状态。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param canceledOrders 本次生命周期或强平撤单阶段已经确定的订单集合
     */
    public TradingCoreState cancelLifecycleOrders(TradingCoreState before,
                                           Collection<CoreOrderState> canceledOrders) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) -> {
            for (CoreOrderState /* 当前需要投影撤单路由、执行取消或按终态过滤的测试业务订单。 */ order : canceledOrders) {
                // 夹具当前读取但后续未使用的运行态订单局部值，实际取消使用原订单身份和冻结余额。
                OrderRuntime current = runtime.order(order.orderId());
                // 当前待取消订单的运行态冻结记录，按其剩余额度调用原取消入口。
                ReservationRuntime reservation = runtime.reservation(order.orderId());
                runtime.cancelOrder(order.orderId(), order.userId(), reservation.reservedUnits());
            }
            runtime.incrementCommandRevision();
        });
    }

    /**
     * 调用生产强平结果确认规则，返回确认后的风险及财务业务状态。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState resolveLiquidation(TradingCoreState before, ResolveLiquidationCommand command) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) ->
                RuntimeLiquidationResolution.apply(before, command, runtime, identities));
    }

    /**
     * 调用生产保险基金调整规则及资产身份映射，物化调整后的财库状态。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState adjustInsuranceFund(TradingCoreState before, AdjustInsuranceFundCommand command) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) ->
                RuntimeInsuranceFundStateTransitions.adjust(runtime, identities, command));
    }

    /**
     * 用前状态投影运行时调用生产 ADL 执行规则，物化账户与风险变化。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState executeAdl(TradingCoreState before, ExecuteAdlCommand command) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) -> RuntimeAdlExecution.apply(before, command, runtime, identities));
    }

    /**
     * 在冷运行时按资产建立本次持仓候选索引，再用生产估值及排名规则取得有界 ADL 候选。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param asset ADL 候选查询的结算资产代码
     * @param limit ADL 最多返回的候选条数
     */
    public List<CoreAdlCandidateView> adlCandidates(TradingCoreState before, String asset, int limit) {
        // 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities)) {
            return RuntimeRiskQueryService.adlCandidates(runtime, identities, asset,
                    new AdlPositionIndex(before, identities).positions(asset), limit);
        }
    }

    /**
     * 在冷运行时调用生产风险查询规则，按传入用户条件重新计算风险视图。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param userId 当前测试命令或风险查询使用的用户编号
     */
    public List<CoreRiskSnapshotView> riskSnapshots(TradingCoreState before, long userId) {
        // 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities)) {
            return RuntimeRiskQueryService.snapshots(runtime, identities, userId);
        }
    }

    /**
     * 从业务快照选择所有非终态订单，供强平测试构造候选撤单路由。
     * @param state 需要选出非终态订单的业务状态快照
     */
    private static List<CoreOrderState> openOrders(TradingCoreState state) {
        return state.orders().values().stream().filter(/* 当前需要投影撤单路由、执行取消或按终态过滤的测试业务订单。 */ order -> !order.status().terminal()).toList();
    }

    /**
     * 每次创建独立身份表并投影前状态，在同一冷运行时执行同步动作，成功后物化后状态，正常和异常退出都关闭该运行时。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param mutation 本次冷运行时中要执行的同步状态变更动作
     */
    private static TradingCoreState mutate(TradingCoreState before, Mutation mutation) {
        // 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities)) {
            mutation.apply(runtime, identities);
            return RuntimeStateMaterializer.materialize(runtime, identities);
        }
    }

    /**
     * 夹具内一次同步状态变更动作，只在本次冷运行时及其身份表上执行，结束后由统一入口物化结果。
     */
    @FunctionalInterface
    private interface Mutation {
        /**
         * 在本次已投影运行时及身份表上执行一个测试变更，返回前由具体生产规则完成其同步状态处理。
         * @param runtime 只属于本次操作的冷可变运行时，动作结束后由统一入口物化并关闭
         * @param identities 与本次投影运行时配套的身份映射，不跨多次操作复用
         */
        public void apply(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities);
    }

    /**
     * 资金费测试结果，包含业务后状态、生产计算返回的支付事实及分页进度；列表沿用返回引用，不在此另做防御复制。
     * @param state 资金费应用后物化的业务后状态
     * @param payments 生产资金费计算返回的实际支付明细列表
     * @param progress 生产资金费入口返回的本页完成游标和支付进度
     */
    public record FundingApplication(TradingCoreState state, List<CoreFundingPaymentView> payments,
                              CoreFundingProgressView progress) {
    }

    /**
     * 交割或行权测试结果，包含已物化业务后状态和生产入口返回的生命周期进度。
     * @param state 生命周期应用后物化的业务后状态
     * @param progress 生产生命周期入口返回的本页到期结算进度
     */
    public record SettlementApplication(TradingCoreState state, CoreSettlementProgressView progress) {
    }
}
