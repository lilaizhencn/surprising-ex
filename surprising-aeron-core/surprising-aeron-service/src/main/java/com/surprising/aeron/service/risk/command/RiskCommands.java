package com.surprising.aeron.service.risk.command;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreRiskScanControlCodec;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.risk.RiskScanCoordinator;
import com.surprising.aeron.service.risk.RuntimeDerivativeRiskProcessor;
import com.surprising.aeron.service.risk.RuntimeRiskStateTransitions;

/**
 * 风控命令入口：更新标记价并建立触发扫描，后续复制命令按预算续扫风险和触发单。异步路径把账户估值交给原控制槽，冷路径直接推进；形成强平计划后，实际强平仍由后续命令执行。
 */
public final class RiskCommands {
    /** 本产品线交易 Owner，扫描状态和交易结果沿用其原有边界。 */
    private final TradingCoreRuntime owner;

    /**
     * 保存非空 Owner 引用，不创建扫描线程或独立状态。
     * @param owner 本产品线交易 Owner，提供权威运行时、身份表、控制槽和提交入口
     */
    public RiskCommands(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    /**
     * 解码价格并记录扫描积压及计时，应用原价格转换，再初始化触发扫描、请求发布并记录耗时；价格序列与产品规则由价格处理器核对。
     * @param message 当前借用的请求头与载荷，解码期间保持缓冲有效
     */
    public void executeApplyMarkPrice(CoreMessage message) {
        // 解码得到的标记价、价格序列及报价时间
        var command = TradingCommandCodec.decodeApplyMarkPrice(message.payloadUnsafe());
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
     * 先核对扫描已启用且请求预算未超控制上限；优先选风险未完扫描，否则选仍有触发工作的一项。触发未完时只给风险一半预算，整数折半可能为零。异步交接控制槽等待后续推进；冷路径实际续扫后将剩余预算交触发单，并记录本轮扫描。
     * @param message 当前借用的请求头与载荷，解码期间保持缓冲有效
     */
    public void executeContinueRiskScan(CoreMessage message) {
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
        // 本轮续扫前的业务修订，异步交给控制槽比较，冷路径用于判断是否请求发布
        long beforeRevision = owner.runtimeState().revision();
        // 忙碌账户可能让风险估值跨多次价格更新仍未完成，因此给止盈止损预留预算，避免一直等整轮风险扫完。
        // 分给风险估值的预算，触发尚未完成时折半以保留触发推进机会
        int riskBudget = activeScan.triggerComplete() ? maxWork : maxWork / 2;
        if (owner.asynchronousCommands()) {
            // 复用的异步风险协调任务；风险已完或分配预算为零时为空，由原控制槽继续处理剩余步骤
            RiskScanCoordinator risk = null;
            if (!activeScan.riskComplete() && riskBudget > 0) {
                risk = owner.directCommand.reusableRiskScanCoordinator(
                        riskBudget);
            }
            owner.directCommand.deferRiskScanControl(risk, activeScan.symbolId(), instrumentId, maxWork,
                    pendingBefore, startedAt, beforeRevision);
            return;
        }
        // 冷风险续扫实际消耗的工作量，用来扣除本轮总预算
        int completedRiskWork = 0;
        if (!activeScan.riskComplete() && riskBudget > 0) {
            completedRiskWork = RuntimeDerivativeRiskProcessor.continueRiskBudget(riskBudget,
                    owner.positionUserIndex(), owner.runtimeState(), owner.identities());
        }
        if (owner.runtimeState().revision() != beforeRevision) owner.requestCommitPublication();
        // 风险工作后还可用于触发单扫描的预算
        int remainingWork = maxWork - completedRiskWork;
        if (remainingWork > 0 && !owner.runtimeState().riskScan(activeScan.symbolId()).triggerComplete()) {
            owner.evaluatePendingTriggerScan(instrumentId, remainingWork);
        }
        owner.logRiskScan("continuation", instrumentId, maxWork, pendingBefore, startedAt);
    }

    /**
     * 解码版本化控制命令，用原业务时间更新规则；成功后请求发布，并把当前控制状态放入本命令响应。
     * @param message 当前借用的请求头与载荷，解码期间保持缓冲有效
     * @param clusterTimestamp 本次复制命令的业务时间，写入风险控制更新记录
     */
    public void executeUpdateRiskScanControl(CoreMessage message, long clusterTimestamp) {
        // 本次风险规则版本、启用状态、间隔及扫描批量配置
        var command = CoreRiskScanControlCodec.decodeCommand(message.payloadUnsafe());
        RuntimeRiskStateTransitions.updateScanControl(owner.runtimeState(), command, clusterTimestamp);
        owner.requestCommitPublication();
        owner.resultBuilder.commandRiskScanControl = owner.runtimeState().riskScanControl();
    }
}
