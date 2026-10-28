package com.surprising.aeron.service.core.command;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMaintenanceCodec;
import com.surprising.aeron.protocol.CoreRiskScanControlCodec;
import com.surprising.aeron.service.risk.RiskScanCoordinator;
import com.surprising.aeron.service.risk.RuntimeDerivativeRiskProcessor;
import com.surprising.aeron.service.funding.RuntimePerpetualFundingProcessor;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertFeePolicyCommand;
import com.surprising.aeron.service.adl.RuntimeAdlExecution;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.instrument.command.InstrumentConfigurationCommands;
import com.surprising.aeron.service.insurance.command.InsuranceFundCommands;
import com.surprising.aeron.service.liquidation.RuntimeLiquidationResolution;
import com.surprising.aeron.service.trigger.command.TriggerOrderCommands;

/**
 * Owner 按协议消息类型寻找对应业务入口的分发表，覆盖余额、配置、风险和条件单等直接命令。部分入口仍需账户、Matcher 或账本异步续步；派发命中不等于业务已经成功或提交。
 */
public final class DirectCommandDispatcher
        implements java.util.function.IntFunction<Object>, java.util.function.BooleanSupplier {
    /** 本产品线唯一交易 Owner，提供命令上下文、权威状态、控制槽与提交入口。 */
    private final TradingCoreRuntime owner;
    /** 算法单、延时全撤及触发单生命周期入口。 */
    private final TriggerOrderCommands triggers;
    /** 当前唯一待应用费率政策，经控制邮箱交给 Lane 0，Owner 收集完成或明确失败后清除。 */
    private UpsertFeePolicyCommand pendingFeePolicy;

    /**
     * 接入交易 Owner 及既有业务处理入口并逐项要求非空，避免运行中遇到缺失分支。
     */
    public DirectCommandDispatcher(TradingCoreRuntime owner, TriggerOrderCommands triggers) {
        this.owner = java.util.Objects.requireNonNull(owner);
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

    /**
     * 解码标的参数并应用注册或更新，转换正常返回后请求发布；相同配置是否需要增加修订由转换方法决定。
     * @param message 当前借用的请求头和载荷，解码完成前保持缓冲有效
     */
    private void executeRegisterInstrument(CoreMessage message) {
        var command = TradingCommandCodec.decodeRegisterInstrument(message.payloadUnsafe(), owner.runtimeState().productLine());
        InstrumentConfigurationCommands.applyConfiguration(owner.runtimeState(), owner.identities(), command);
        owner.requestCommitPublication();
    }

    /**
     * 解码带任务归属的维护命令，交给状态转换检查并修改维护状态，成功后请求发布。
     * @param message 当前借用的请求头和载荷，解码完成前保持缓冲有效
     */
    private void executeUpdateInstrumentMaintenance(CoreMessage message) {
        InstrumentConfigurationCommands.updateMaintenance(owner.runtimeState(), owner.identities(),
                CoreMaintenanceCodec.decodeCommand(message.payloadUnsafe()));
        owner.requestCommitPublication();
    }

    /**
     * 准备可复用保险基金调整并登记控制续步，请求发布，最终成功等待控制完成。
     * @param message 本次借用的原命令及载荷，调用期间保持缓冲有效
     */
    private void executeAdjustInsuranceFund(CoreMessage message) {
        var command = TradingCommandCodec.decodeAdjustInsuranceFund(message.payloadUnsafe());
        owner.directCommand.deferControl(InsuranceFundCommands.prepare(
                owner.runtimeState(), owner.identities(), command));
        owner.requestCommitPublication();
    }

    /**
     * 解码资金费参数并取得全局游标之后的持仓用户索引；复用工作对象后交给 directCommand 轮询；在线与独立回放都在实际完成后收集结果。
     * @param message 已路由到本产品线的资金费命令及其 UUID
     */
    private void executeApplyFunding(CoreMessage message) {
        // 从协议载荷解出的资金费编号、费率与用户分页参数。
        var command = TradingCommandCodec.decodeApplyFunding(message.payloadUnsafe());
        // 本币对全局用户游标之后的持仓用户来源，具体页由账本阶段选择。
        Iterable<Long> indexedUserIds = owner.positionUserIndex().usersAfter(command.instrumentId(), command.cursorUserId());
        // 命令槽持有的一页资金费工作，Owner 后续轮询收齐账户和账本回执。
        var work = RuntimePerpetualFundingProcessor.prepare(owner.directCommand.reusableFundingWork(), command,
                indexedUserIds, owner.runtimeState(), owner.identities());
        owner.directCommand.deferFundingControl(work);
    }

    /**
     * 解码价格并记录扫描积压及计时，应用原价格转换，再初始化触发扫描、请求发布并记录耗时；价格序列与产品规则由价格处理器核对。
     * @param message 当前借用的请求头与载荷，解码期间保持缓冲有效
     */
    private void executeApplyMarkPrice(CoreMessage message) {
        // 解码得到的标记价、价格序列及报价时间
        var command = TradingCommandCodec.decodeApplyMarkPrice(message.payloadUnsafe(), message.header().productLine());
        // 更新标记价前记录的未完成扫描数量，用于本次诊断日志
        int pendingBefore = owner.pendingRiskScanCount();
        // 本轮扫描开始的单调时钟时间，仅用于耗时观测
        long startedAt = System.nanoTime();
        RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(command, owner.runtimeState(), owner.identities());
        owner.initializeTriggerScan(command);
        owner.requestCommitPublication();
        owner.logRiskScan("mark-price", command.instrumentId(), owner.runtimeState().riskScanControl().scanBatchSize(),
                pendingBefore, startedAt);
    }

    /**
     * 先核对扫描已启用且请求预算未超控制上限；优先选风险未完扫描，否则选仍有触发工作的一项。触发未完时只给风险一半预算，整数折半可能为零。交接控制槽推进风险，再将剩余预算交触发单，并记录本轮扫描。
     * @param message 当前借用的请求头与载荷，解码期间保持缓冲有效
     */
    private void executeContinueRiskScan(CoreMessage message) {
        // 本次续扫请求允许处理的最大工作预算
        var command = TradingCommandCodec.decodeContinueRiskScan(message.payloadUnsafe());
        // 当前生效的扫描开关和批量上限，决定是否接受续扫请求
        var control = owner.runtimeState().riskScanControl();
        if (!control.enabled() || command.maxUsers() > control.scanBatchSize()) {
            throw new CoreStateRejectedException("INVALID_COMMAND",
                    "risk scan continuation exceeds current control");
        }
        // 优先取得的风险估值尚未完成扫描，没有时再寻找触发未完扫描
        var activeRiskScan = owner.runtimeState().firstRiskIncompleteScan();
        // 本轮选中的扫描状态，风险未完优先；没有任何待办则直接返回
        var activeScan = activeRiskScan == null
                ? owner.runtimeState().firstIncompleteRiskScan() : activeRiskScan;
        if (activeScan == null) return;
        // 由扫描内部标的索引还原的协议标的文本
        String instrumentId = owner.identities().instrumentId(activeScan.symbolId());
        // 本轮开始前未完成扫描数量，用于预算调整和诊断日志
        int pendingBefore = owner.pendingRiskScanCount();
        // 结合请求上限和当前积压计算的本轮总预算
        int maxWork = RiskScanCoordinator.adaptiveBudget(command.maxUsers(), pendingBefore);
        // 本轮扫描开始的单调时钟时间，仅用于耗时观测
        long startedAt = System.nanoTime();
        // 本轮续扫前的业务修订，交给控制槽比较，用于判断是否请求发布
        long beforeRevision = owner.runtimeState().revision();
        // 忙碌账户可能让风险估值跨多次价格更新仍未完成，因此给止盈止损预留预算，避免一直等整轮风险扫完。
        // 分给风险估值的预算，触发尚未完成时折半以保留触发推进机会
        int riskBudget = activeScan.triggerComplete() ? maxWork : maxWork / 2;
        // 复用的异步风险协调任务；风险已完或分配预算为零时为空，由原控制槽继续处理剩余步骤
        RiskScanCoordinator risk = null;
        if (!activeScan.riskComplete() && riskBudget > 0) {
            risk = owner.directCommand.reusableRiskScanCoordinator(
                    riskBudget);
        }
        owner.directCommand.deferRiskScanControl(risk, activeScan.symbolId(), instrumentId, maxWork,
                pendingBefore, startedAt, beforeRevision);
    }

    /**
     * 解码版本化控制命令，用原业务时间更新规则；成功后请求发布，并把当前控制状态放入本命令响应。
     * @param message 当前借用的请求头与载荷，解码期间保持缓冲有效
     * @param clusterTimestamp 本次复制命令的业务时间，写入风险控制更新记录
     */
    private void executeUpdateRiskScanControl(CoreMessage message, long clusterTimestamp) {
        // 本次风险规则版本、启用状态、间隔及扫描批量配置
        var command = CoreRiskScanControlCodec.decodeCommand(message.payloadUnsafe());
        owner.runtimeState().updateRiskScanControl(command, clusterTimestamp);
        owner.requestCommitPublication();
        owner.resultBuilder.commandRiskScanControl = owner.runtimeState().riskScanControl();
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
