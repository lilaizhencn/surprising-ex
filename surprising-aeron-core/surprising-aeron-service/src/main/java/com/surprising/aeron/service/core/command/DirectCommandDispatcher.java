package com.surprising.aeron.service.core.command;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertFeePolicyCommand;
import com.surprising.aeron.service.adl.RuntimeAdlExecution;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.funding.command.PerpetualFundingCommands;
import com.surprising.aeron.service.instrument.command.InstrumentConfigurationCommands;
import com.surprising.aeron.service.insurance.command.InsuranceFundCommands;
import com.surprising.aeron.service.liquidation.RuntimeLiquidationResolution;
import com.surprising.aeron.service.risk.command.RiskCommands;
import com.surprising.aeron.service.trigger.command.TriggerOrderCommands;

/**
 * Owner 按协议消息类型寻找对应业务入口的分发表，覆盖余额、配置、风险和条件单等直接命令。部分入口仍需账户、Matcher 或账本异步续步；派发命中不等于业务已经成功或提交。
 */
public final class DirectCommandDispatcher
        implements java.util.function.IntFunction<Object>, java.util.function.BooleanSupplier {
    /** 本产品线唯一交易 Owner，提供命令上下文、权威状态、控制槽与提交入口。 */
    private final TradingCoreRuntime owner;
    /** 合约注册、配置及维护状态入口。 */
    private final InstrumentConfigurationCommands instruments;
    /** 永续资金费命令入口。 */
    private final PerpetualFundingCommands funding;
    /** 标记价、风控续扫与扫描控制入口。 */
    private final RiskCommands risk;
    /** 保险基金调整入口。 */
    private final InsuranceFundCommands insurance;
    /** 算法单、延时全撤及触发单生命周期入口。 */
    private final TriggerOrderCommands triggers;
    /** 当前唯一待应用费率政策，经控制邮箱交给 Lane 0，Owner 收集完成或明确失败后清除。 */
    private UpsertFeePolicyCommand pendingFeePolicy;

    /**
     * 接入交易 Owner 及既有业务处理入口并逐项要求非空，避免运行中遇到缺失分支。
     */
    public DirectCommandDispatcher(TradingCoreRuntime owner,
                                   InstrumentConfigurationCommands instruments,
                                   PerpetualFundingCommands funding,
                                   RiskCommands risk,
                                   InsuranceFundCommands insurance,
                                   TriggerOrderCommands triggers) {
        this.owner = java.util.Objects.requireNonNull(owner);
        this.instruments = java.util.Objects.requireNonNull(instruments);
        this.funding = java.util.Objects.requireNonNull(funding);
        this.risk = java.util.Objects.requireNonNull(risk);
        this.insurance = java.util.Objects.requireNonNull(insurance);
        this.triggers = java.util.Objects.requireNonNull(triggers);
    }

    /**
     * 命中支持的消息类型就调用相应入口，正常返回后给真；未匹配返回假，由调用方继续撮合或控制流程。入口抛出的异常直接传播，真值只表示已派发，不表示异步命令已经完成。
     * @param message 当前借用的原消息，其头部决定分支，载荷由业务入口解码
     * @param clusterTimestamp 该条复制命令的业务时间，传给需要它的业务入口
     */
    public boolean dispatch(CoreMessage message, long clusterTimestamp) {
        switch (message.header().messageType()) {
            case ADJUST_BALANCE -> executeAdjustBalance(message);
            case TRANSFER_OUT -> executeTransferOut(message);
            case TRANSFER_IN -> executeTransferIn(message);
            case COMPLETE_TRANSFER -> executeCompleteTransfer(message);
            case REGISTER_INSTRUMENT -> instruments.executeRegisterInstrument(message);
            case APPLY_MARK_PRICE -> risk.executeApplyMarkPrice(message);
            case APPLY_FUNDING -> funding.executeApplyFunding(message);
            case EXECUTE_ADL -> executeExecuteAdl(message);
            case RESOLVE_LIQUIDATION -> executeResolveLiquidation(message);
            case CONTINUE_RISK_SCAN -> risk.executeContinueRiskScan(message);
            case UPDATE_RISK_SCAN_CONTROL -> risk.executeUpdateRiskScanControl(message, clusterTimestamp);
            case UPDATE_INSTRUMENT_MAINTENANCE -> instruments.executeUpdateInstrumentMaintenance(message);
            case UPSERT_FEE_POLICY -> executeUpsertFeePolicy(message);
            case UPDATE_POSITION_MODE -> executeUpdatePositionMode(message);
            case ADJUST_POSITION_MARGIN -> executeAdjustPositionMargin(message);
            case ADJUST_INSURANCE_FUND -> insurance.executeAdjustInsuranceFund(message);
            case UPDATE_LEVERAGE -> executeUpdateLeverage(message);
            case UPSERT_ALGO_ORDER -> triggers.executeUpsertAlgoOrder(message, clusterTimestamp);
            case UPDATE_CANCEL_ALL_AFTER -> triggers.executeUpdateCancelAllAfter(message);
            case PLACE_TRIGGER_OCO_PAIR -> triggers.executePlaceTriggerOcoPair(message, clusterTimestamp);
            case PLACE_TRIGGER_ORDER -> triggers.executePlaceTriggerOrder(message, clusterTimestamp);
            case CANCEL_TRIGGER_ORDER -> triggers.executeCancelTriggerOrder(message);
            case CLAIM_TRIGGER_ORDER -> triggers.executeClaimTriggerOrder(message);
            case COMPLETE_TRIGGER_ORDER -> triggers.executeCompleteTriggerOrder(message);
            case UPDATE_TRIGGER_TRAILING -> triggers.executeUpdateTriggerTrailing(message);
            case EXPIRE_TRIGGER_ORDER -> triggers.executeExpireTriggerOrder(message);
            case RETRY_TRIGGER_ORDER -> triggers.executeRetryTriggerOrder(message);
            case EXECUTE_TRIGGER_ORDER -> triggers.executeTriggerOrder(message);
            default -> { return false; }
        }
        return true;
    }

    private void executeAdjustBalance(CoreMessage message) {
        long userId = message.header().userId();
        owner.resultBuilder.setSingleChangedUser(userId);
        var command = TradingCommandCodec.decodeBalanceAdjustment(message.payloadUnsafe());
        owner.directCommand.deferBalanceAdjustment(userId, command.asset(), command.deltaUnits());
    }

    private void executeTransferOut(CoreMessage message) {
        long userId = message.header().userId();
        owner.resultBuilder.setSingleChangedUser(userId);
        var command = TradingCommandCodec.decodeTransferFunds(message.payloadUnsafe());
        owner.directCommand.deferTransferOut(userId, command);
    }

    private void executeTransferIn(CoreMessage message) {
        long userId = message.header().userId();
        owner.resultBuilder.setSingleChangedUser(userId);
        var command = TradingCommandCodec.decodeTransferFunds(message.payloadUnsafe());
        if (userId != command.targetUserId())
            throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer recipient mismatch");
        if (owner.runtimeState().productLine() != command.targetProductLine())
            throw new CoreStateRejectedException("PRODUCT_LINE_MISMATCH", "transfer target product line mismatch");
        owner.directCommand.deferBalanceAdjustment(userId, command.asset(), command.amountUnits());
    }

    private void executeCompleteTransfer(CoreMessage message) {
        long id = TradingCommandCodec.decodeCompleteTransfer(message.payloadUnsafe()).transferId();
        owner.directCommand.deferTransferComplete(message.header().userId(), id);
    }

    private void executeExecuteAdl(CoreMessage message) {
        var command = TradingCommandCodec.decodeExecuteAdl(message.payloadUnsafe());
        owner.resultBuilder.setSingleChangedUser(command.targetUserId());
        var work = RuntimeAdlExecution.begin(owner.directCommand.reusableAdlWork(),
                command, owner.runtimeState(), owner.identities());
        owner.directCommand.deferAdlControl(work);
    }

    private void executeResolveLiquidation(CoreMessage message) {
        var command = TradingCommandCodec.decodeResolveLiquidation(message.payloadUnsafe());
        var work = RuntimeLiquidationResolution.begin(owner.directCommand.reusableLiquidationResolutionWork(), command,
                owner.runtimeState(), owner.identities(), owner.activeLiquidationIds());
        owner.directCommand.deferLiquidationResolutionControl(work);
    }

    private void executeUpsertFeePolicy(CoreMessage message) {
        var command = TradingCommandCodec.decodeUpsertFeePolicy(message.payloadUnsafe());
        if (pendingFeePolicy != null) throw new IllegalStateException("fee policy command is still pending");
        pendingFeePolicy = command;
        try { owner.runtimeState().dispatchControlLanes(1L, this); }
        catch (RuntimeException | Error failure) { pendingFeePolicy = null; throw failure; }
        owner.directCommand.deferControl(this);
    }

    private void executeUpdatePositionMode(CoreMessage message) {
        long userId = message.header().userId();
        owner.resultBuilder.setSingleChangedUser(userId);
        var command = TradingCommandCodec.decodeUpdatePositionMode(message.payloadUnsafe());
        owner.directCommand.deferPositionModeChange(userId, command);
    }

    private void executeAdjustPositionMargin(CoreMessage message) {
        long userId = message.header().userId();
        owner.resultBuilder.setSingleChangedUser(userId);
        var command = TradingCommandCodec.decodeAdjustPositionMargin(message.payloadUnsafe());
        owner.directCommand.deferPositionMarginAdjustment(userId, command);
    }

    private void executeUpdateLeverage(CoreMessage message) {
        long userId = message.header().userId();
        owner.resultBuilder.setSingleChangedUser(userId);
        var command = TradingCommandCodec.decodeUpdateLeverage(message.payloadUnsafe());
        owner.directCommand.deferLeverageChange(userId, command);
    }

    @Override
    public Object apply(int laneId) {
        if (laneId != 0 || pendingFeePolicy == null)
            throw new IllegalStateException("fee policy reached the wrong configuration Lane");
        owner.runtimeState().upsertFeePolicy(pendingFeePolicy);
        return null;
    }

    @Override
    public boolean getAsBoolean() {
        try {
            if (!owner.runtimeState().pollControlLanes()) return false;
        } catch (RuntimeException | Error failure) { pendingFeePolicy = null; throw failure; }
        pendingFeePolicy = null;
        owner.requestCommitPublication();
        return true;
    }
}
