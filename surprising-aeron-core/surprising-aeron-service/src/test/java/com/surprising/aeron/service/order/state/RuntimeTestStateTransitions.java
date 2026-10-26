package com.surprising.aeron.service.order.state;

import com.surprising.aeron.service.core.LedgerControlTestSupport;
import com.surprising.aeron.service.account.AccountControlTask;
import com.surprising.aeron.service.core.RuntimeDerivativeLiquidationFixture;

import com.surprising.aeron.service.core.RuntimeLifecycleSettlementFixture;

import com.surprising.aeron.service.core.RuntimePerpetualFundingFixture;

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
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.retention.TerminalStateRetention;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.ActiveOrderIndex;
import com.surprising.aeron.service.funding.RuntimePerpetualFundingProcessor;
import com.surprising.aeron.service.order.admission.CoreOrderDecisionResolver;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.admission.RuntimeOrderAdmission;
import com.surprising.aeron.service.risk.RuntimeDerivativeRiskProcessor;
import com.surprising.aeron.service.settlement.RuntimeDerivativeMatchFixture;
import com.surprising.aeron.service.settlement.RuntimeSpotMatchFixture;
import com.surprising.aeron.service.snapshot.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.aeron.service.insurance.command.InsuranceFundCommands;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.service.instrument.CoreInstrument;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter.CancellationOrder;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 测试用状态推进器，把给定业务前值投影成冷可变运行时，调用已有生产规则，再在断言边界物化后状态。状态变更不经过真实 Cluster；风险和 ADL 查询通过现有协议及账本、账户续步执行。
 */
public final class RuntimeTestStateTransitions {

    /**
     * 创建无独立业务状态的测试推进器，每次操作另投影自己的运行时。
     */
    public RuntimeTestStateTransitions() {
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
                LedgerControlTestSupport.await(runtime, new AccountControlTask().prepareBalanceAdjustment(runtime, identities, userId, command.asset(), command.deltaUnits())));
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
                runtime.updateRiskScanControl(command, updatedAtEpochMillis));
    }

    /**
     * 调用生产持仓模式检查和更新规则，测试不另行模拟模式转换。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param userId 当前测试命令或风险查询使用的用户编号
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState updatePositionMode(TradingCoreState before, long userId, UpdatePositionModeCommand command) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) ->
                LedgerControlTestSupport.await(runtime, new AccountControlTask().preparePositionModeChange(runtime, userId, command)));
    }

    /**
     * 调用生产用户杠杆更新及保证金规则，使用本次投影身份表。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param userId 当前测试命令或风险查询使用的用户编号
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState updateLeverage(TradingCoreState before, long userId, UpdateLeverageCommand command) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) ->
                LedgerControlTestSupport.await(runtime, new AccountControlTask().prepareLeverageChange(runtime, identities, userId, command)));
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
                LedgerControlTestSupport.await(runtime, new AccountControlTask().preparePositionMarginAdjustment(runtime, identities, userId, command)));
    }

    /**
     * 从前状态重建持仓用户索引，在冷运行时调用生产标记价及风险续步，再物化风险后状态。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState applyMarkPrice(TradingCoreState before, ApplyMarkPriceCommand command) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) -> RuntimeTestStateTransitions.applyRiskMarkPrice(before,
                command,
                runtime,
                identities));
    }

    /**
     * 按给定工作预算和持仓用户索引调用生产续扫规则，返回其物化进度和状态。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param maxUsers 本次风控续扫的用户工作预算
     */
    public TradingCoreState continueRiskScan(TradingCoreState before, int maxUsers) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) -> RuntimeTestStateTransitions.applyRiskContinuation(before,
                maxUsers,
                runtime,
                identities));
    }

    /**
     * 把动作时间固定在夹具已有行情的生成时刻；无行情时使用固定测试时间1，缺价仍由正式规则拒绝。
     * 使用真实运行时决策、账户准入和冻结写入，不读取系统时钟，也不绕过到期、行情鲜度或费率规则。
     */
    public TradingCoreState placeOrder(TradingCoreState before, long userId, PlaceOrderCommand command) {
        var mark = before.riskState().markPrices().get(command.instrumentId());
        long decisionTimestamp = mark == null ? 1 : mark.generatedAtEpochMillis();
        UUID commandId = UUID.randomUUID();
        return mutate(before, (runtime, identities) -> {
            ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(
                    runtime, identities, userId, command, decisionTimestamp);
            long required = RuntimeOrderAdmission.requiredReservation(runtime, identities, userId,
                    resolved, 0, runtime.admissionOrders(userId, identities.symbolId(resolved.instrumentId())));
            runtime.placePreparedOrder(userId, resolved, commandId, required,
                    identities.clientKey(userId, resolved.clientOrderId()),
                    identities.symbolId(resolved.instrumentId()), identities.assetId(resolved.reservationAsset()));
        });
    }

    /**
     * 对已经决策的订单读取运行时挂单汇总，计算预留并调用原账户写入。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param userId 当前测试命令或风险查询使用的用户编号
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState placeOrder(TradingCoreState before, long userId, ResolvedPlaceOrder command) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) -> {
            // 生产账户准入根据本单和原运行态挂单算出的应预留金额。
            long required = RuntimeOrderAdmission.requiredReservation(runtime, identities, userId,
                    command, 0, runtime.admissionOrders(userId, identities.symbolId(command.instrumentId())));
            runtime.placePreparedOrder(userId, command, UUID.randomUUID(), required,
                    identities.clientKey(userId, command.clientOrderId()),
                    identities.symbolId(command.instrumentId()), identities.assetId(command.reservationAsset()));
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
                runtime.cancelOpenOrder(userId, command.orderId()));
    }

    /**
     * 把父单视图交给生产算法身份及版本规则，测试沿用其新建、追加和终态限制。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param userId 当前测试命令或风险查询使用的用户编号
     * @param view 需要应用到算法父单的协议视图
     */
    public TradingCoreState upsertAlgoOrder(TradingCoreState before, long userId, CoreAlgoOrderView view) {
        var current = before.algoOrders().get(view.algoOrderId());
        long appended = current != null && view.childOrderIds().size() > current.childOrderIds().size()
                ? view.childOrderIds().getLast() : 0;
        com.surprising.aeron.protocol.CoreAlgoOrderCommand command = view.revision() == 1
                ? new com.surprising.aeron.protocol.CoreAlgoOrderCommand.Create(
                view.algoOrderId(), view.clientAlgoOrderId(), view.instrumentId(), view.algoTypeCode(), view.side(),
                        view.priceTicks(), view.quantitySteps(), view.childQuantitySteps(), view.intervalSeconds(),
                        view.durationSeconds(), view.marginMode(), view.positionSide(), view.reduceOnly(), view.timeInForce(), view.startAtEpochMillis())
                : new com.surprising.aeron.protocol.CoreAlgoOrderCommand.Update(
                view.algoOrderId(), view.revision() - 1, view.statusCode(), view.currentOrderId(), view.rejectReason(),
                        view.nextSliceAtEpochMillis(), view.updatedAtEpochMillis(), appended);
        return mutate(before, (runtime, identities) -> runtime.upsertAlgoOrder(
                identities, userId, command, Math.max(1, view.createdAtEpochMillis())));
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
                runtime.updateCancelAllAfter(userId, command));
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
                RuntimeTestStateTransitions.applySpotMatches(before, takerOrderId, matches, runtime, identities);
            } else {
                RuntimeTestStateTransitions.applyDerivativeMatches(before, takerOrderId, matches, runtime, identities);
            }
        });
    }

    /**
     * 使用生产资金费入口的默认用户集合及分页规则，返回后状态、支付事实和进度。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public FundingApplication applyFundingWithFacts(TradingCoreState before, ApplyFundingCommand command) {
        return applyFundingWithFacts(before, command, null);
    }

    /**
     * 投影一份冷运行时，按指定用户集合和命令批量上限应用生产资金费规则，捕获实际支付与进度，再物化后状态并关闭运行时。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     * @param userIds 本次资金费或生命周期页选择的用户集合，null 交给生产入口采用其默认规则
     */
    public FundingApplication applyFundingWithFacts(TradingCoreState before, ApplyFundingCommand command,
            Iterable<Long> userIds) {
        // 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities)) {
            // 生产资金费处理返回的实际支付事实和分页进度，与物化后状态一起交给测试。
            var result = RuntimeTestStateTransitions.applyFundingRuntime(before, command, userIds, runtime, identities);
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
     */
    public SettlementApplication settleInstrumentWithProgress(TradingCoreState before,
                                                        SettleInstrumentCommand command,
                                                        Iterable<Long> userIds) {
        return settleInstrumentWithProgress(before, command, userIds, null);
    }

    /**
     * 在冷投影中调用生产生命周期结算，传入用户、当前页和可选活动索引，物化后状态并保留实际进度。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     * @param userIds 本次资金费或生命周期页选择的用户集合，null 交给生产入口采用其默认规则
     * @param activeOrders 生命周期结算可选的活动订单索引，原生产入口负责解释 null
     */
    public SettlementApplication settleInstrumentWithProgress(TradingCoreState before,
                                                        SettleInstrumentCommand command,
                                                        Iterable<Long> userIds,
                                                        ActiveOrderIndex activeOrders) {
        // 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities)) {
            // 生产生命周期处理返回的确定分页进度。
            var progress = RuntimeTestStateTransitions.applyLifecycleSettlement(before,
                    command,
                    userIds,
                    activeOrders,
                    runtime,
                    identities);
            return new SettlementApplication(RuntimeStateMaterializer.materialize(runtime, identities), progress);
        }
    }

    /**
     * 按生产入口默认分页输入处理生命周期结算，仅返回业务后状态。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState settleInstrument(TradingCoreState before, SettleInstrumentCommand command) {
        return settleInstrumentWithProgress(before, command, null, null).state();
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
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) -> RuntimeTestStateTransitions.applyLiquidationExecution(before,
                command,
                cancellationRoutes(openOrders(before)),
                runtime,
                identities));
    }

    /**
     * 在测试已完成撤单的场景传空撤单路由，调用同一生产强平执行规则。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState executeLiquidationAfterCancellation(TradingCoreState before,
                                                          ExecuteLiquidationCommand command) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) -> RuntimeTestStateTransitions.applyLiquidationExecution(before,
                command,
                List.of(),
                runtime,
                identities));
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
                RuntimeTestStateTransitions.applyLiquidationCancellationAdvance(before,
                        command,
                        cancellationRoutes(canceledOrders),
                        nextCursorOrderId,
                        runtime,
                        identities));
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
                RuntimeTestStateTransitions.applyLiquidationResolution(before, command, runtime, identities));
    }

    /**
     * 调用生产保险基金调整规则及资产身份映射，物化调整后的财库状态。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState adjustInsuranceFund(TradingCoreState before, AdjustInsuranceFundCommand command) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) ->
                com.surprising.aeron.service.order.CoreTestCompletion.completeControlWork(
                        InsuranceFundCommands.prepare(runtime, identities, command)));
    }

    /**
     * 用前状态投影运行时调用生产 ADL 执行规则，物化账户与风险变化。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param command 对应业务动作的已构造协议命令或已解析订单输入，交给同名生产规则处理
     */
    public TradingCoreState executeAdl(TradingCoreState before, ExecuteAdlCommand command) {
        return mutate(before, (/* 只用于当前测试动作的冷可变运行时，统一投影后调用生产规则。 */ runtime, /* 该冷运行时本次操作独立使用的身份表，查询及金额计算都使用相同映射。 */ identities) -> RuntimeTestStateTransitions.applyAdlExecution(before,
                command,
                runtime,
                identities));
    }

    /**
     * 从检查点走现有 ADL 查询协议，账本选路后由账户 Lane 估值并返回有界候选。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param asset ADL 候选查询的结算资产代码
     * @param limit ADL 最多返回的候选条数
     */
    public List<CoreAdlCandidateView> adlCandidates(TradingCoreState before, String asset, int limit) {
        return CoreAdlQueryCodec.decodeCandidates(queryState(before, CoreMessageType.ADL_CANDIDATE_QUERY,
                0, CoreAdlQueryCodec.encodeQuery(asset, limit)));
    }

    /**
     * 从检查点走现有风险查询协议，按用户条件由账本和账户 Lane 重新计算风险视图。
     * @param before 本次业务操作前的完整冷状态快照，不在它上面直接改账户实体
     * @param userId 当前测试命令或风险查询使用的用户编号
     */
    public List<CoreRiskSnapshotView> riskSnapshots(TradingCoreState before, long userId) {
        return CoreRiskQueryCodec.decode(queryState(before, CoreMessageType.RISK_STATE_QUERY, userId, new byte[0]));
    }

    /** 从给定检查点启动隔离 Core，用原账本选路及账户查询续步取得独立响应字节，再关闭线程。 */
    private static byte[] queryState(TradingCoreState before, CoreMessageType type, long userId, byte[] payload) {
        try (var core = new TradingCoreRuntime(before.productLine(),
                0, new LinkedHashMap<>(), new LinkedHashMap<>(), before,
                new TerminalStateRetention(), null, 0,
                Map.of(), Map.of(), null, null)) {
            var request = new CoreMessage(CoreMessageHeader.query(type, UUID.randomUUID(), before.productLine(),
                    CommandSource.OPERATIONS, 0, 0, userId, 0, 0), payload);
            var response = CoreTestCompletion.querySynchronously(core, request);
            if (response.status() != ResponseStatus.OK) throw new AssertionError("risk query failed: " + response.resultCode());
            return response.data();
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

    /** 冷状态测试入口：投影边界检查后调用现用运行时规则。 */
    public static void applySpotMatches(TradingCoreState before, long takerOrderId, List<MatcherEvent> matches, TradingAccountRuntime runtime,
                             RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid spot match apply");
        }
        RuntimeSpotMatchFixture.applyRuntime(takerOrderId, matches, runtime, identities);
    }

    /** 冷状态测试入口：投影边界检查后调用现用运行时规则。 */
    public static TradingAccountRuntime applyDerivativeMatches(TradingCoreState before, long takerOrderId,
                                            List<MatcherEvent> matches, TradingAccountRuntime runtime,
                                            RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid perpetual match apply");
        }
        return RuntimeDerivativeMatchFixture.applyRuntime(takerOrderId, matches, runtime, identities);
    }

    /** 冷状态测试入口：投影边界检查后调用现用运行时规则。 */
    public static CoreSettlementProgressView applyLifecycleSettlement(TradingCoreState before, SettleInstrumentCommand command,
                                                   Iterable<Long> indexedUserIds,
                                                   ActiveOrderIndex activeOrderIndex, TradingAccountRuntime runtime,
                                                   RuntimeIdentityRegistry identities) {
        if (before == null || command == null || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid runtime settlement");
        }
        // 本次使用的用户来源；未传索引时取旧状态用户集合。
        Iterable<Long> users = indexedUserIds == null ? before.users().keySet() : indexedUserIds;
        // 本次使用的活动订单索引；未传时从旧状态构建。
        ActiveOrderIndex orders = activeOrderIndex == null ? new ActiveOrderIndex(before) : activeOrderIndex;
        return RuntimeLifecycleSettlementFixture.applyRuntime(command, users, orders, runtime, identities);
    }

    /** 冷状态测试入口：投影边界检查后调用现用运行时规则。 */
    public static TradingAccountRuntime applyLiquidationResolution(TradingCoreState before,
                                            ResolveLiquidationCommand command,
                                            TradingAccountRuntime runtime,
                                            RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid liquidation resolution apply");
        }
        return RuntimeDerivativeLiquidationFixture.applyResolution(command, runtime, identities, before.riskState().liquidations().keySet());
    }

    /** 冷状态测试入口：投影边界检查后调用现用运行时规则。 */
    public static TradingAccountRuntime applyLiquidationExecution(TradingCoreState before,
                                                     ExecuteLiquidationCommand command,
                                                     Collection<CancellationOrder> canceledOrders,
                                                     TradingAccountRuntime runtime,
                                                     RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid perpetual liquidation apply");
        }
        return RuntimeDerivativeLiquidationFixture.applyExecutionRuntime(command, canceledOrders, runtime, identities);
    }

    /** 冷状态测试入口：投影边界检查后调用现用运行时规则。 */
    public static TradingAccountRuntime applyLiquidationCancellationAdvance(TradingCoreState before,
                                                               ExecuteLiquidationCommand command,
                                                               Collection<CancellationOrder> canceledOrders,
                                                               long nextCursorOrderId,
                                                               TradingAccountRuntime runtime,
                                                               RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid liquidation cancellation apply");
        }
        return RuntimeDerivativeLiquidationFixture.applyCancellationAdvanceRuntime(command, canceledOrders, nextCursorOrderId, runtime, identities);
    }

    /** 冷状态测试入口：投影边界检查后调用现用运行时规则。 */
    public static TradingAccountRuntime applyAdlExecution(TradingCoreState before, ExecuteAdlCommand command,
                                            TradingAccountRuntime runtime,
                                            RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid ADL apply");
        }
        return RuntimeDerivativeLiquidationFixture.applyAdl(command, runtime, identities);
    }

    /** 冷状态测试入口：投影边界检查后调用现用运行时规则。 */
    public static void applyRiskMarkPrice(TradingCoreState before, ApplyMarkPriceCommand command,
                                      TradingAccountRuntime runtime,
                                      RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid perpetual risk apply");
        }
        RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(command, runtime, identities);
        if (runtime.riskScanControl().enabled()) {
            com.surprising.aeron.service.core.RuntimeDerivativeRiskFixture.continueRiskBudget(
                    runtime.riskScanControl().scanBatchSize(),
                    new com.surprising.aeron.service.core.index.PositionUserIndex(before, runtime.topology()), runtime, identities);
        }
    }

    /** 冷状态测试入口：投影边界检查后调用现用运行时规则。 */
    public static void applyRiskContinuation(TradingCoreState before, int maxWork,
                                         TradingAccountRuntime runtime,
                                         RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid perpetual risk continuation apply");
        }
        com.surprising.aeron.service.core.RuntimeDerivativeRiskFixture.continueRiskBudget(maxWork,
                new com.surprising.aeron.service.core.index.PositionUserIndex(before, runtime.topology()), runtime, identities);
    }

    /** 冷状态测试入口：投影边界检查后调用现用运行时规则。 */
    public static RuntimePerpetualFundingProcessor.FundingResult applyFundingRuntime(TradingCoreState before, ApplyFundingCommand command,
                                      Iterable<Long> indexedUserIds,
                                      TradingAccountRuntime runtime, RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid perpetual funding apply");
        }
        // 未传用户索引时使用旧状态用户集合，否则沿用调用方的索引。
        Iterable<Long> users = indexedUserIds == null ? before.users().keySet() : indexedUserIds;
        return RuntimePerpetualFundingFixture.applyRuntime(command, users, runtime, identities);
    }

    /** 冷状态强平使用的同一业务比较顺序，不使用集合遍历或线程完成顺序。 */
    private static final Comparator<CoreLiquidationState> CORE_LIQUIDATION_ORDER = Comparator
            .comparingLong(CoreLiquidationState::triggerPriceSequence)
            .thenComparingLong(CoreLiquidationState::userId)
            .thenComparing(CoreLiquidationState::instrumentId)
            .thenComparingInt(/* 当前参与排序、筛选或比例计算的强平索赔记录，比较字段取业务事实。 */ claim -> claim.positionSide().ordinal())
            .thenComparingLong(CoreLiquidationState::liquidationId);

    public static long expectedInsuranceCoverage(TradingCoreState state, long liquidationId) {
        if (state == null || liquidationId <= 0) {
            throw new IllegalArgumentException("insurance allocation state is required");
        }
        // 本次查询的目标强平记录，必须仍适用保险分配。
        CoreLiquidationState target = state.riskState().liquidations().get(liquidationId);
        if (target == null || target.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED) return 0;
        // 目标强平对应的规范合约。
        CoreInstrument targetInstrument = state.instruments().get(target.instrumentId());
        if (targetInstrument == null) return 0;
        // 冷状态目标合约的结算资产名称。
        String asset = targetInstrument.settleAsset();
        // 当前资产需要分配的独占索赔列表，排序会改变列表顺序。
        ArrayList<CoreLiquidationState> claims = new ArrayList<>();
        for (CoreLiquidationState /* 当前运行时或冷状态读取到的强平记录。 */ liquidation : state.riskState().liquidations().values()) {
            if (liquidation.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED) continue;
            // 当前候选对应的规范合约对象，需与强平输入中的对象匹配。
            CoreInstrument instrument = state.instruments().get(liquidation.instrumentId());
            if (instrument != null && asset.equals(instrument.settleAsset())) {
                claims.add(liquidation);
            }
        }
        claims.sort(CORE_LIQUIDATION_ORDER);
        // 当前非负保险余额与同资产总缺口取小后的可分配预算。
        long available = state.treasuryState().insuranceBalances().getOrDefault(asset, 0L);
        return allocationForCore(Math.max(0, available), claims, liquidationId);
    }

    public static boolean isNextInsuranceClaim(TradingCoreState state, long liquidationId) {
        // 本次查询的目标强平记录，必须仍适用保险分配。
        CoreLiquidationState target = state.riskState().liquidations().get(liquidationId);
        if (target == null) return false;
        // 目标强平对应的规范合约。
        CoreInstrument targetInstrument = state.instruments().get(target.instrumentId());
        if (targetInstrument == null) return false;
        // 冷状态优先级查询的目标结算资产名称。
        String targetAsset = targetInstrument.settleAsset();
        // 当前找到的同资产待保险索赔固定顺序最小项。
        CoreLiquidationState first = null;
        for (CoreLiquidationState /* 当前运行时或冷状态读取到的强平记录。 */ liquidation : state.riskState().liquidations().values()) {
            if (liquidation.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED) continue;
            // 当前候选对应的规范合约对象，需与强平输入中的对象匹配。
            CoreInstrument instrument = state.instruments().get(liquidation.instrumentId());
            if (instrument == null || !targetAsset.equals(instrument.settleAsset())) continue;
            if (first == null || CORE_LIQUIDATION_ORDER.compare(liquidation, first) < 0) {
                first = liquidation;
            }
        }
        return first != null && first.liquidationId() == liquidationId;
    }

    private static long allocationForCore(long availableUnits, ArrayList<CoreLiquidationState> claims, long targetId) {
        // 使用 BigInteger 汇总的同资产总缺口，避免长整数总额溢出。
        BigInteger total = BigInteger.ZERO;
        for (CoreLiquidationState /* 当前运行时或冷状态读取到的强平记录。 */ liquidation : claims) total = total.add(BigInteger.valueOf(liquidation.deficitUnits()));
        // 当前非负保险余额与同资产总缺口取小后的可分配预算。
        BigInteger available = BigInteger.valueOf(availableUnits).min(total);
        // 已按比例整除分出的基础金额大整数合计。
        BigInteger allocated = BigInteger.ZERO;
        // 目标按比例整除得到的基础补偿，不含余数补一。
        long targetBase = 0;
        // 目标在固定排序中的下标，负一表示目标不在当前索赔列表。
        int targetIndex = -1;
        for (/* 当前候选、有效回执或已排序索赔列表下标。 */ int index = 0; index < claims.size(); index++) {
            // 当前运行时或冷状态读取到的强平记录。
            CoreLiquidationState liquidation = claims.get(index);
            // 当前索赔按预算乘缺口再除总缺口得到的基础份额。
            BigInteger share = total.signum() == 0 ? BigInteger.ZERO
                    : available.multiply(BigInteger.valueOf(liquidation.deficitUnits())).divide(total);
            if (liquidation.liquidationId() == targetId) {
                targetBase = share.longValueExact();
                targetIndex = index;
            }
            allocated = allocated.add(share);
        }
        // 预算减基础份额合计后的剩余单位数，按固定排序前缀逐项补一。
        int remainder = available.subtract(allocated).intValueExact();
        return targetIndex < 0 ? 0 : targetBase + (targetIndex < remainder ? 1 : 0);
    }
}
