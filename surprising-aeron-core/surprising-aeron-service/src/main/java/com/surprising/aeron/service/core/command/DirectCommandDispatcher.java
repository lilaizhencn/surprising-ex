package com.surprising.aeron.service.core.command;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMaintenanceCodec;
import com.surprising.aeron.protocol.CoreRiskScanControlCodec;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.risk.RiskMarginCalculator;
import com.surprising.aeron.service.funding.FundingSettlementService;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertFeePolicyCommand;
import com.surprising.aeron.service.adl.AdlService;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.instrument.command.InstrumentConfigurationCommands;
import com.surprising.aeron.service.insurance.command.InsuranceFundCommands;
import com.surprising.aeron.service.liquidation.LiquidationService;
import com.surprising.aeron.service.trigger.command.TriggerOrderCommands;

/** Owner 按协议消息类型寻找对应业务入口的分发表，覆盖余额、配置、风险和条件单等直接命令。 */
public final class DirectCommandDispatcher
        implements java.util.function.IntFunction<Object>, java.util.function.BooleanSupplier {
    /** 本产品线唯一交易 Owner，提供命令上下文、权威状态、控制槽与提交入口。 */
    private final TradingCore owner;
    private final TriggerOrderCommands triggers;
    /** 当前唯一待应用费率政策，经控制邮箱交给 Lane 0，Owner 收集完成或明确失败后清除。 */
    private UpsertFeePolicyCommand pendingFeePolicy;

    /** 接入交易 Owner 及既有业务处理入口并逐项要求非空，避免运行中遇到缺失分支。 */
    public DirectCommandDispatcher(TradingCore owner, TriggerOrderCommands triggers) {
        this.owner = java.util.Objects.requireNonNull(owner);
        this.triggers = java.util.Objects.requireNonNull(triggers);
    }

    /** 命中支持的消息类型就调用相应入口，正常返回后给真。 */
    public boolean dispatch(CoreMessage message, long clusterTimestamp) {
        switch (message.header().messageType()) {
            case ADJUST_BALANCE -> executeAdjustBalance(message);
            case TRANSFER_OUT -> executeTransferOut(message);
            case TRANSFER_IN -> executeTransferIn(message);
            case COMPLETE_TRANSFER -> executeCompleteTransfer(message);
            case REGISTER_INSTRUMENT -> executeRegisterInstrument(message);
            case APPLY_MARK_PRICE -> executeApplyMarkPrice(message);
            case APPLY_FUNDING -> executeApplyFunding(message);
            case EXECUTE_ADL -> executeExecuteAdl(message);
            case RESOLVE_LIQUIDATION -> executeResolveLiquidation(message);
            case CONTINUE_RISK_SCAN -> executeContinueRiskScan(message);
            case UPDATE_RISK_SCAN_CONTROL -> executeUpdateRiskScanControl(message, clusterTimestamp);
            case UPDATE_INSTRUMENT_MAINTENANCE -> executeUpdateInstrumentMaintenance(message);
            case UPSERT_FEE_POLICY -> executeUpsertFeePolicy(message);
            case UPDATE_POSITION_MODE -> executeUpdatePositionMode(message);
            case ADJUST_POSITION_MARGIN -> executeAdjustPositionMargin(message);
            case ADJUST_INSURANCE_FUND -> executeAdjustInsuranceFund(message);
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

    private void executeRegisterInstrument(CoreMessage message) {
        var command = TradingCommandCodec.decodeRegisterInstrument(message.payloadUnsafe(), owner.accountBook().productLine());
        InstrumentConfigurationCommands.applyConfiguration(owner.accountBook(), owner.identities(), command);
        owner.requestCommitPublication();
    }

    private void executeUpdateInstrumentMaintenance(CoreMessage message) {
        InstrumentConfigurationCommands.updateMaintenance(owner.accountBook(), owner.identities(),
                CoreMaintenanceCodec.decodeCommand(message.payloadUnsafe()));
        owner.requestCommitPublication();
    }

    private void executeAdjustInsuranceFund(CoreMessage message) {
        var command = TradingCommandCodec.decodeAdjustInsuranceFund(message.payloadUnsafe());
        owner.directCommand.deferControl(InsuranceFundCommands.prepare(
                owner.accountBook(), owner.identities(), command));
        owner.requestCommitPublication();
    }

    private void executeApplyFunding(CoreMessage message) {
        var command = TradingCommandCodec.decodeApplyFunding(message.payloadUnsafe());
        Iterable<Long> indexedUserIds = owner.positionUserIndex().usersAfter(command.instrumentId(), command.cursorUserId());
        var work = FundingSettlementService.prepare(owner.directCommand.reusableFundingWork(), command,
                indexedUserIds, owner.accountBook(), owner.identities());
        owner.directCommand.deferFundingControl(work);
    }

    private void executeApplyMarkPrice(CoreMessage message) {
        var command = TradingCommandCodec.decodeApplyMarkPrice(message.payloadUnsafe(), message.header().productLine());
        int pendingBefore = owner.pendingRiskScanCount();
        long startedAt = System.nanoTime();
        RiskMarginCalculator.applyMarkPriceRuntime(command, owner.accountBook(), owner.identities());
        owner.initializeTriggerScan(command);
        owner.requestCommitPublication();
        owner.logRiskScan("mark-price", command.instrumentId(), owner.accountBook().riskScanControl().scanBatchSize(),
                pendingBefore, startedAt);
    }

    /** 优先选风险未完扫描，否则选仍有触发工作的一项。 */
    private void executeContinueRiskScan(CoreMessage message) {
        var command = TradingCommandCodec.decodeContinueRiskScan(message.payloadUnsafe());
        var control = owner.accountBook().riskScanControl();
        if (!control.enabled() || command.maxUsers() > control.scanBatchSize()) {
            throw new CoreStateRejectedException("INVALID_COMMAND",
                    "risk scan continuation exceeds current control");
        }
        var activeRiskScan = owner.accountBook().firstRiskIncompleteScan();
        var activeScan = activeRiskScan == null
                ? owner.accountBook().firstIncompleteRiskScan() : activeRiskScan;
        if (activeScan == null) return;
        String instrumentId = owner.identities().instrumentId(activeScan.symbolId());
        int pendingBefore = owner.pendingRiskScanCount();
        int maxWork = RiskScanner.adaptiveBudget(command.maxUsers(), pendingBefore);
        long startedAt = System.nanoTime();
        long beforeRevision = owner.accountBook().revision();
        int riskBudget = activeScan.triggerComplete() ? maxWork : maxWork / 2;
        RiskScanner risk = null;
        if (!activeScan.riskComplete() && riskBudget > 0) {
            risk = owner.directCommand.reusableRiskScanCoordinator(
                    riskBudget);
        }
        owner.directCommand.deferRiskScanControl(risk, activeScan.symbolId(), instrumentId, maxWork,
                pendingBefore, startedAt, beforeRevision);
    }

    private void executeUpdateRiskScanControl(CoreMessage message, long clusterTimestamp) {
        var command = CoreRiskScanControlCodec.decodeCommand(message.payloadUnsafe());
        owner.accountBook().updateRiskScanControl(command, clusterTimestamp);
        owner.requestCommitPublication();
        owner.resultBuilder.commandRiskScanControl = owner.accountBook().riskScanControl();
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
        if (owner.accountBook().productLine() != command.targetProductLine())
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
        var work = AdlService.begin(owner.directCommand.reusableAdlWork(),
                command, owner.accountBook(), owner.identities());
        owner.directCommand.deferAdlControl(work);
    }

    private void executeResolveLiquidation(CoreMessage message) {
        var command = TradingCommandCodec.decodeResolveLiquidation(message.payloadUnsafe());
        var work = LiquidationService.beginResolution(owner.directCommand.reusableLiquidationResolutionWork(), command,
                owner.accountBook(), owner.identities(), owner.activeLiquidationIds());
        owner.directCommand.deferLiquidationResolutionControl(work);
    }

    private void executeUpsertFeePolicy(CoreMessage message) {
        var command = TradingCommandCodec.decodeUpsertFeePolicy(message.payloadUnsafe());
        if (pendingFeePolicy != null) throw new IllegalStateException("fee policy command is still pending");
        pendingFeePolicy = command;
        try { owner.accountBook().dispatchControlLanes(1L, this); }
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
        owner.accountBook().upsertFeePolicy(pendingFeePolicy);
        return null;
    }

    @Override
    public boolean getAsBoolean() {
        try {
            if (!owner.accountBook().pollControlLanes()) return false;
        } catch (RuntimeException | Error failure) { pendingFeePolicy = null; throw failure; }
        pendingFeePolicy = null;
        owner.requestCommitPublication();
        return true;
    }

/** 说明一项账户 Lane 任务属于哪种工作，便于派发、统计和检查任务的执行边界。 */
public enum OperationType {
    COMMAND,
    SETTLEMENT,
    /** 在账户所属 Lane 内读取数据，不以查询名义绕过线程所有权。 */
    QUERY,
    RISK
}
}
