package com.surprising.aeron.service.order;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreAlgoOrderCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.service.account.AccountControlTask;
import com.surprising.aeron.service.adl.RuntimeAdlExecution;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.funding.RuntimePerpetualFundingProcessor;
import com.surprising.aeron.service.liquidation.RuntimeLiquidationResolution;
import com.surprising.aeron.service.order.state.RuntimeTriggerOrderStateTransitions;
import com.surprising.aeron.service.retention.TerminalStateRetention;
import com.surprising.aeron.service.risk.RiskScanCoordinator;
import com.surprising.aeron.service.trigger.command.TriggerOrderCommands;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * 直接控制命令的单个可复用槽。余额、划转、风控、资金费、触发单等命令不走订单簿完成队列，但可能需要等待账户 Lane、账本或回滚。这里保存当前命令和续步，业务状态、发布和回滚仍由运行时负责；当前命令结束后先 clear，再接下一条。
 */
public final class DirectCommandSlot {
    /** 创建槽时绑定的唯一所属 Owner，跨命令复用。 */
    private final TradingCoreRuntime owner;
    /**  本轮原始直接命令，清槽后为空。 借用当前原始直接命令，未占用或清理后为空。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private CoreMessage command;
    /** 本轮完整命令指纹，供幂等及终态保留核对。 */
    private CommandFingerprint fingerprint;
    /** 命令来源类型与实例标识。 */
    private TradingCoreRuntime.SourceKey sourceKey;
    /**  固定使用的 Cluster 业务时间。 返回本命令固定的 Cluster 业务时间。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private long commitFenceTimestamp;
    /** 固定使用的复制日志位置。 */
    private long commitFenceClusterPosition;

    /**  本槽是否被当前命令占用。 返回是否已有直接命令占用本槽，不能据此判断业务是否成功。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean active;
    /** 该工作开始前的运行时修订，用于变化及回滚判断。 */
    private long beforeRevision;
    /**  命令开始前的账户回滚检查点。 返回本命令开始前的账户回滚检查点。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private long checkpoint;
    /** 命令开始前的持仓身份回滚检查点。 */
    private long identityCheckpoint;
    /**  当前业务或回滚续步，返回假时由 Owner 之后再轮询。 借用当前可空续步，不调用或复制它。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private BooleanSupplier controlWork;
    /**  业务已决定的响应状态，未决定时为空。 返回已决定的响应状态，业务续步尚未结束时可为空。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private ResponseStatus status;
    /**  与响应状态配套的业务结果码。 返回已决定的结果码，不在此推进任务。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private CoreResultCode resultCode;
    /**
     *  本条直接命令待收集的账户提交事件。
     * 借用当前待收集的账户提交事件。
     * 保存调用方派发的可空账户提交事件，不等待 Lane。
     * @param commitEvent 当前账户提交事件引用
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    private com.surprising.aeron.service.core.change.LaneCommitEvent commitEvent;
    /**  本条命令的收尾材料是否已准备。 返回直接命令的收尾材料是否已准备，防止后续轮询重复准备。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean finalizationPrepared;
    /**  本条命令的发布是否已完成。 返回本条命令的发布工作是否已标记完成。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean publicationComplete;
    /**
     * fundsPreflight：当前占槽是否仅用于资金身份前置查重。
     * fundsRetainPrepared：本条资金身份写入是否已准备，避免轮询时重复准备。
     */
    private boolean fundsPreflight, fundsRetainPrepared;
    /** 查重通过后用于校验的来源已接收序号。 */
    private long fundsLastSourceSequence;
    /** 资金终态身份查询或写入的固定可复用工作槽。 */
    private final TerminalStateRetention.FundsIndexWork fundsIndexWork = new TerminalStateRetention.FundsIndexWork();

    /** 资金费完成收集续步。 */
    private final FundingControlContinuation fundingControl = new FundingControlContinuation();
    /** 风险扫描及触发扫描收集续步。 */
    private final RiskScanControlContinuation riskScanControl = new RiskScanControlContinuation();
    /** 自动减仓完成收集续步。 */
    private final AdlControlContinuation adlControl = new AdlControlContinuation();
    /** 强平结果确认收集续步。 */
    private final LiquidationResolutionControlContinuation liquidationResolutionControl =
            new LiquidationResolutionControlContinuation();
    /** 余额、划转及持仓设置共用的账户控制任务。 */
    private final AccountControlTask accountControl = new AccountControlTask();
    /** 只轮询固定账户任务并在有状态变化时请求发布的函数。 */
    private final BooleanSupplier accountControlWork;
    /** 触发单或算法单的固定账户操作和完成收集槽。 */
    private final TriggerControlContinuation triggerControl = new TriggerControlContinuation();
    /** 没有触发续扫时使用的已完成哨兵。 */
    private static final BooleanSupplier COMPLETE_CONTROL = () -> true;

    /**  跨直接命令保留的资金费工作对象。 返回上次保存的资金费工作对象；初次使用可能为空。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private RuntimePerpetualFundingProcessor.FundingWork reusableFundingWork;
    /**  跨命令保留的自动减仓工作对象。 返回上次保存的自动减仓工作对象。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private RuntimeAdlExecution.AdlWork reusableAdlWork;
    /** 跨命令保留的强平确认工作对象。 */
    private RuntimeLiquidationResolution.ResolutionWork reusableResolutionWork;
    /** 首次绑定当前运行时后只重置预算的风险扫描器。 */
    private RiskScanCoordinator reusableRiskCoordinator;

    /** @param owner 本槽及其所有续步共同服务的交易运行时 */
    public DirectCommandSlot(TradingCoreRuntime owner) {
        this.owner = Objects.requireNonNull(owner);
        accountControlWork = () -> {
            if (!accountControl.poll()) return false;
            if (accountControl.stateChanged()) this.owner.requestCommitPublication();
            return true;
        };
    }

    /**
     * 拒绝覆盖仍占用的槽，保存非空命令、指纹及来源，再记下业务时间、日志位置与回滚检查点；其余旧标记由前一轮 clear 清理。
     * @param command 本方法对应的原始或已解码业务命令，类型见声明
     * @param fingerprint 完整原命令的指纹
     * @param sourceKey 已确认的来源类型与实例键
     * @param timestamp 固定的 Cluster 业务时间
     * @param position 固定的复制日志位置
     * @param beforeRevision 工作开始前的权威修订号
     * @param checkpoint 开始前的账户回滚检查点
     * @param identityCheckpoint 开始前的持仓身份检查点
     */
    public void initialize(CoreMessage command, CommandFingerprint fingerprint,
            TradingCoreRuntime.SourceKey sourceKey, long timestamp, long position,
            long beforeRevision,
            long checkpoint, long identityCheckpoint) {
        if (active) throw new IllegalStateException("direct command slot is occupied");
        this.command = Objects.requireNonNull(command);
        this.fingerprint = Objects.requireNonNull(fingerprint);
        this.sourceKey = Objects.requireNonNull(sourceKey);
        this.beforeRevision = beforeRevision;
        this.checkpoint = checkpoint;
        this.identityCheckpoint = identityCheckpoint;
        commitFenceTimestamp = timestamp;
        commitFenceClusterPosition = position;
        active = true;
    }

    /**
     * 返回是否还有控制续步，也可能是拒单后的回滚续步。
     */
    public boolean hasControlWork() { return controlWork != null; }
    /**
     * 把当前续步换成非空的新续步，供原控制工作转入后续阶段。
     * @param work 当前要保存、替换或轮询的业务续步
     */
    public void replaceControlWork(BooleanSupplier work) { controlWork = Objects.requireNonNull(work); }
    /**
     * 保存调用方已经决定的状态和结果码，不在此检查非空或完成发布。
     * @param status 已决定的响应状态
     * @param resultCode 已决定的业务结果码
     */
    public void result(ResponseStatus status, CoreResultCode resultCode) {
        this.status = status;
        this.resultCode = resultCode;
    }
    /**
     * 记下发布已经完成，后续轮询继续其他收尾。
     */
    public void markPublicationComplete() { publicationComplete = true; }
    /**
     * 记下本条命令的收尾材料已经准备。
     */
    public void markFinalizationPrepared() { finalizationPrepared = true; }
    /**
     * 撤销收尾材料就绪标记，允许调用方按现有规则重新准备。
     */
    public void clearFinalizationPrepared() { finalizationPrepared = false; }

    /**
     * 先以零业务检查点占槽，再按命令 UUID 查询资金终态身份；精确查重通过前不取得业务作用域或账户前值。
     * @param message 资金查重所针对的原命令
     * @param fingerprint 完整原命令的指纹
     * @param sourceKey 已确认的来源类型与实例键
     * @param timestamp 固定的 Cluster 业务时间
     * @param position 固定的复制日志位置
     * @param lastSourceSequence 该来源最近已接收的命令序号
     * @param retention 资金终态身份保留入口
     */
    public void beginFundsCheck(CoreMessage message, CommandFingerprint fingerprint,
            TradingCoreRuntime.SourceKey sourceKey, long timestamp, long position, long lastSourceSequence,
            TerminalStateRetention retention) {
        // 精确资金身份查重通过前，不取得业务作用域或账户前值。
        initialize(message, fingerprint, sourceKey, timestamp, position, 0, 0, 0);
        fundsPreflight = true;
        fundsLastSourceSequence = lastSourceSequence;
        fundsIndexWork.prepareLookup(retention, message.header().commandIdMsb(), message.header().commandIdLsb());
    }

    /**
     * 第一次准备资金命令身份写入，之后只轮询账本工作；返回真表示此项写入完成，不在此再次修改余额。
     */
    public boolean retainFundsOnLedger() {
        if (!fundsRetainPrepared) {
            fundsIndexWork.prepareWrite(owner.terminalRetention(), command.header().commandIdMsb(),
                    command.header().commandIdLsb(), fingerprint);
            fundsRetainPrepared = true;
        }
        return fundsIndexWork.poll(owner);
    }

    /**
     * 等资金身份查询完成，优先判断历史重放或指纹冲突，再检查来源序号；先保存当前输入并清槽，随后返回已有响应或用原输入真正执行业务。
     */
    private CoreResponse pollFundsCheck() {
        if (!fundsIndexWork.poll(owner)) return null;
        // 资金身份查询或来源序号检查给出的可空既有响应
        CoreResponse replay = owner.fundsReplayResponse(fingerprint, fundsIndexWork.retained());
        if (replay == null) replay = owner.checkSourceSequence(command, fundsLastSourceSequence);
        // 清槽前保存的原命令，查重通过后继续执行
        CoreMessage checkedCommand = command;
        // 清槽前保存的完整指纹
        CommandFingerprint checkedFingerprint = fingerprint;
        // 清槽前保存的来源键
        TradingCoreRuntime.SourceKey checkedSource = sourceKey;
        // 清槽前保存的业务时间
        // 清槽前保存的日志位置
        long timestamp = commitFenceTimestamp, position = commitFenceClusterPosition;
        clear();
        return replay != null ? replay : owner.applyDirectCommand(
                checkedCommand, timestamp, position, checkedSource, checkedFingerprint);
    }
    /**
     * 只解除本槽对提交事件的引用，事件回收由原调用方负责。
     */
    public void clearCommitEvent() { commitEvent = null; }

    /**
     * 要求当前没有续步，再保存非空控制工作，供 Owner 后续轮询。
     * @param work 当前要保存、替换或轮询的业务续步
     */
    public void deferControl(BooleanSupplier work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        controlWork = Objects.requireNonNull(work);
    }

    /**
     * 要求没有旧续步，绑定资金费收集器并保留工作对象供后续命令复用。
     * @param work 当前要保存、替换或轮询的业务续步
     */
    public void deferFundingControl(RuntimePerpetualFundingProcessor.FundingWork work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        reusableFundingWork = Objects.requireNonNull(work);
        controlWork = fundingControl;
    }

    /**
     * 绑定风险扫描和后续触发扫描所需信息；同一槽不能并存第二条控制续步。
     * @param risk 本轮可空风险扫描器
     * @param symbolId 已解析的永久品种编号
     * @param instrumentId 本轮扫描的品种名称
     * @param maxUsers 本轮风险与触发扫描共用的用户预算
     * @param pendingBefore 开始前待处理数量，供观测日志使用
     * @param startedAt 本轮扫描开始的观测时间
     * @param beforeRevision 工作开始前的权威修订号
     */
    public void deferRiskScanControl(RiskScanCoordinator risk, int symbolId,
            String instrumentId, int maxUsers, int pendingBefore, long startedAt, long beforeRevision) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        riskScanControl.prepare(risk, symbolId, instrumentId, maxUsers, pendingBefore, startedAt,
                beforeRevision);
        controlWork = riskScanControl;
    }

    /**
     * 绑定自动减仓续步，并记住原工作对象供后续复用。
     * @param work 当前要保存、替换或轮询的业务续步
     */
    public void deferAdlControl(RuntimeAdlExecution.AdlWork work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        reusableAdlWork = Objects.requireNonNull(work);
        controlWork = adlControl;
    }

    /**
     * 绑定强平结果确认续步，并保留原工作对象供后续命令复用。
     * @param work 当前要保存、替换或轮询的业务续步
     */
    public void deferLiquidationResolutionControl(RuntimeLiquidationResolution.ResolutionWork work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        reusableResolutionWork = Objects.requireNonNull(work);
        controlWork = liquidationResolutionControl;
    }

    /**
     * 返回上次保存的强平结果确认工作对象。
     */
    public RuntimeLiquidationResolution.ResolutionWork reusableLiquidationResolutionWork() {
        return reusableResolutionWork;
    }

    /** 首次用所属 Owner 的索引、运行时和身份表创建扫描器，之后重置本轮预算。 */
    public RiskScanCoordinator reusableRiskScanCoordinator(int maxUsers) {
        if (reusableRiskCoordinator == null) {
            reusableRiskCoordinator = new RiskScanCoordinator(
                    maxUsers, owner.positionUserIndex(), owner.runtimeState(), owner.identities());
        } else {
            reusableRiskCoordinator.resetForCommand(maxUsers);
        }
        return reusableRiskCoordinator;
    }

    /**
     * 准备所属用户的余额调整账户任务，并安装固定轮询函数；账户确有变化才请求发布。
     * @param userId 操作所属账户用户
     * @param asset 要调整的资产
     * @param deltaUnits 有符号可用余额增减金额
     */
    public void deferBalanceAdjustment(long userId, String asset, long deltaUnits) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        accountControl.prepareBalanceAdjustment(owner.runtimeState(), owner.identities(), userId, asset, deltaUnits);
        controlWork = accountControlWork;
    }

    /**
     * 准备所属用户的转出账户任务，完成和变化判断沿用账户任务自身回执。
     * @param userId 操作所属账户用户
     * @param command 本方法对应的原始或已解码业务命令，类型见声明
     */
    public void deferTransferOut(long userId, TransferFundsCommand command) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        accountControl.prepareTransferOut(owner.runtimeState(), owner.identities(), userId, command);
        controlWork = accountControlWork;
    }

    /**
     * 准备所属用户的划转完成任务，后续轮询成功且有变化时请求发布。
     * @param userId 操作所属账户用户
     * @param transferId 当前要完成的划转编号
     */
    public void deferTransferComplete(long userId, long transferId) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        accountControl.prepareTransferCompletion(owner.runtimeState(), userId, transferId);
        controlWork = accountControlWork;
    }

    /**
     * 准备杠杆变更账户任务，是否发生变化由账户回执判断。
     * @param userId 操作所属账户用户
     * @param command 本方法对应的原始或已解码业务命令，类型见声明
     */
    public void deferLeverageChange(long userId, UpdateLeverageCommand command) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        accountControl.prepareLeverageChange(owner.runtimeState(), owner.identities(), userId, command);
        controlWork = accountControlWork;
    }

    /**
     * 准备持仓模式变更账户任务，是否发生变化由账户回执判断。
     * @param userId 操作所属账户用户
     * @param command 本方法对应的原始或已解码业务命令，类型见声明
     */
    public void deferPositionModeChange(long userId, UpdatePositionModeCommand command) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        accountControl.preparePositionModeChange(owner.runtimeState(), userId, command);
        controlWork = accountControlWork;
    }

    /**
     * 准备逐仓保证金调整账户任务，不在 Owner 直接改 Lane 状态。
     * @param userId 操作所属账户用户
     * @param command 本方法对应的原始或已解码业务命令，类型见声明
     */
    public void deferPositionMarginAdjustment(long userId,
                                       AdjustPositionMarginCommand command) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        accountControl.preparePositionMarginAdjustment(owner.runtimeState(), owner.identities(), userId, command);
        controlWork = accountControlWork;
    }

    /**
     * 把触发单变更参数放入固定槽，只派发给该用户所属 Lane，后续由同一对象收集完成。
     * @param userId 操作所属账户用户
     * @param mutation 触发单具体变更类别
     * @param triggerOrderId 待变更触发单编号
     * @param arg1 认领序号、原生订单号、追踪最高价、失效时间或重试边界，按操作类别解释
     * @param arg2 触发价、完成时间、追踪最低价或重试时间，按操作类别解释
     * @param arg3 触发时间或追踪激活时间，按操作类别解释
     * @param flag 完成操作是否成功
     * @param text 完成操作的拒绝原因，空引用按空串处理
     */
    public void deferTriggerMutationControl(long userId,
            TriggerOrderCommands.Mutation mutation, long triggerOrderId, long arg1, long arg2,
            long arg3, boolean flag, String text) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        triggerControl.prepareMutation(userId, mutation, triggerOrderId, arg1, arg2, arg3,
                flag, text);
        owner.runtimeState().dispatchControlLanes(
                1L << owner.runtimeState().topology().accountLaneId(userId), triggerControl);
        controlWork = triggerControl;
    }

    /**
     * 准备触发单写入任务并派发给用户所属 Lane，保留合约和持仓身份。
     * @param userId 操作所属账户用户
     * @param trigger 待写入的触发单视图
     * @param symbolId 已解析的永久品种编号
     * @param positionKey 触发单稳定持仓身份
     * @param instrumentSettled 当前合约是否已结束结算
     */
    public void deferTriggerUpsertControl(long userId,
            CoreTriggerOrderStateView trigger, int symbolId, long positionKey,
            boolean instrumentSettled) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        triggerControl.prepareTriggerUpsert(userId, trigger, symbolId, positionKey,
                instrumentSettled);
        owner.runtimeState().dispatchControlLanes(
                1L << owner.runtimeState().topology().accountLaneId(userId), triggerControl);
        controlWork = triggerControl;
    }

    /**
     * 准备互斥触发单对，共用一次所属账户 Lane 派发，第二条保存在同一续步内。
     * @param userId 操作所属账户用户
     * @param first 互斥对第一条触发单视图
     * @param second 互斥对第二条触发单视图
     * @param symbolId 已解析的永久品种编号
     * @param positionKey 触发单稳定持仓身份
     * @param instrumentSettled 当前合约是否已结束结算
     */
    public void deferTriggerOcoPairControl(long userId,
            CoreTriggerOrderStateView first, CoreTriggerOrderStateView second,
            int symbolId, long positionKey, boolean instrumentSettled) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        triggerControl.prepareTriggerUpsert(userId, first, symbolId, positionKey, instrumentSettled);
        triggerControl.secondTrigger = second;
        owner.runtimeState().dispatchControlLanes(
                1L << owner.runtimeState().topology().accountLaneId(userId), triggerControl);
        controlWork = triggerControl;
    }

    /**
     * 准备算法单写入任务并派发给用户所属 Lane。
     * @param userId 操作所属账户用户
     * @param algo 算法单的新建意图或生命周期增量
     * @param symbolId 已解析的永久品种编号
     */
    public void deferAlgoUpsertControl(long userId, CoreAlgoOrderCommand algo,
            int symbolId, long clusterTimestamp) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        triggerControl.prepareAlgoUpsert(userId, algo, symbolId, clusterTimestamp);
        owner.runtimeState().dispatchControlLanes(
                1L << owner.runtimeState().topology().accountLaneId(userId), triggerControl);
        controlWork = triggerControl;
    }

    /**
     * 只允许 Owner 推进已占用槽。资金查重走独立前置路径；业务续步未完成就返回空，已知业务、算术和参数错误记成拒绝。若拒绝前已有变化，先分轮完成账户及持仓身份回滚，再交运行时收尾；其他异常继续传播。
     */
    public CoreResponse poll() {
        owner.assertOwner();
        if (!active) throw new IllegalStateException("no pending direct command");
        if (fundsPreflight) return pollFundsCheck();
        if (status == null) {
            try {
                if (!controlWork.getAsBoolean()) return null;
                status = ResponseStatus.APPLIED;
                resultCode = CoreResultCode.NONE;
            } catch (/* 已知业务、算术或参数错误，映射成相应拒绝码 */ CoreStateRejectedException failure) {
                status = ResponseStatus.REJECTED;
                resultCode = CoreResultCode.fromRejectionCode(failure.code());
            } catch (/* 已知业务、算术或参数错误，映射成相应拒绝码 */ ArithmeticException failure) {
                status = ResponseStatus.REJECTED;
                resultCode = CoreResultCode.ARITHMETIC_OVERFLOW;
            } catch (/* 已知业务、算术或参数错误，映射成相应拒绝码 */ IllegalArgumentException failure) {
                status = ResponseStatus.REJECTED;
                resultCode = CoreResultCode.INVALID_COMMAND;
            }
            controlWork = null;
        }
        if (status != ResponseStatus.APPLIED
                && (owner.commits.publication.dirty()
                || owner.runtimeState().revision() != beforeRevision
                || owner.runtimeState().hasUncommittedCommandChanges())) {
            if (controlWork == null) {
                owner.commits.publication.abort();
                controlWork = owner.runtimeState().beginCommandRollback(
                        checkpoint, Math.incrementExact(owner.appliedCommandCount()));
            }
            if (!controlWork.getAsBoolean()) return null;
            owner.identities().rollbackPositionKeys(identityCheckpoint);
            controlWork = null;
        }
        return owner.finishDirectCommand(command, commitFenceTimestamp, commitFenceClusterPosition,
                sourceKey, fingerprint, beforeRevision, checkpoint,
                identityCheckpoint, status, resultCode);
    }

    /**
     * 清除本条命令、各固定续步和提交标记，释放本轮引用；资金费、风险、自动减仓、强平确认及保险调整的可复用工作引用继续保留。
     */
    public void clear() {
        riskScanControl.clear();
        accountControl.clear();
        triggerControl.clear();
        fundsIndexWork.clear();
        active = false;
        command = null;
        fingerprint = null;
        sourceKey = null;
        beforeRevision = checkpoint = identityCheckpoint = 0;
        controlWork = null;
        status = null;
        resultCode = null;
        commitEvent = null;
        finalizationPrepared = false;
        publicationComplete = fundsPreflight = fundsRetainPrepared = false;
        fundsLastSourceSequence = 0;
        commitFenceTimestamp = commitFenceClusterPosition = 0;
    }

    /**
     * 资金费工作完成后的 Owner 收集步骤，整理进度和受影响用户。
     */
    private final class FundingControlContinuation implements BooleanSupplier {
        @Override public boolean getAsBoolean() {
            var work = reusableFundingWork;
            if (!work.poll()) return false;
            // 当前业务工作完成结果或所属 Lane 回执，具体类型见声明
            var result = work.result();
            owner.requestCommitPublication();
            owner.resultBuilder.commandFundingProgress = result.progress();
            owner.resultBuilder.addChangedUsersFromFundingPayments(result.payments());
            owner.funding().notifyReplayObserver(result);
            return true;
        }
    }

    /**
     * 在同一条风险命令内衔接风险扫描和剩余预算内的触发扫描，不建立新业务队列。
     */
    private final class RiskScanControlContinuation implements BooleanSupplier {
        /** 当前可空的风险扫描工作；为空时可直接接触发扫描。 */
        private RiskScanCoordinator risk;
        /** 当前品种的永久编号。 */
        private int symbolId;
        /** 当前品种名称，用于接续触发扫描和日志。 */
        private String instrumentId;
        /** 本轮风险加触发扫描可处理的用户总预算。 */
        private int maxUsers;
        /** 本轮开始前待处理数量，供扫描日志比较。 */
        private int pendingBefore;
        /** 本轮开始的观测时间。 */
        private long startedAt;
        /** 该工作开始前的运行时修订，用于变化及回滚判断。 */
        private long beforeRevision;
        /** 风险结束后才确定的触发扫描续步，或已完成哨兵。 */
        private BooleanSupplier triggers;

        /**
         * 保存风险续扫输入并清空上一轮触发扫描续步。
         * @param risk 本轮可空风险扫描器
         * @param symbolId 已解析的永久品种编号
         * @param instrumentId 本轮扫描的品种名称
         * @param maxUsers 本轮风险与触发扫描共用的用户预算
         * @param pendingBefore 开始前待处理数量，供观测日志使用
         * @param startedAt 本轮扫描开始的观测时间
         * @param beforeRevision 工作开始前的权威修订号
         */
        void prepare(RiskScanCoordinator risk, int symbolId, String instrumentId,
                int maxUsers, int pendingBefore, long startedAt, long beforeRevision) {
            this.risk = risk;
            this.symbolId = symbolId;
            this.instrumentId = Objects.requireNonNull(instrumentId);
            this.maxUsers = maxUsers;
            this.pendingBefore = pendingBefore;
            this.startedAt = startedAt;
            this.beforeRevision = beforeRevision;
            this.triggers = null;
        }
        /**
         * 清除当前风险及触发续步引用和统计输入，保留外层可复用扫描器。
         */
        void clear() {
            risk = null; instrumentId = null; triggers = null;
            symbolId = maxUsers = pendingBefore = 0; startedAt = beforeRevision = 0;
        }

        /**
         * 先收齐可空风险扫描工作，有修订变化则请求发布；把剩余用户预算交给尚未结束的触发扫描，最后记录本轮推进情况。返回真仍需外层完成命令收尾。
         */
        @Override public boolean getAsBoolean() {
            if (triggers == null) {
                if (risk != null && !risk.poll()) return false;
                if (owner.runtimeState().revision() != beforeRevision) owner.requestCommitPublication();
                // 扣除风险已完成工作后留给触发扫描的用户预算
                int remaining = maxUsers - (risk == null ? 0 : risk.completedWork());
                // 当前品种已经推进的风险扫描记录，用于判断是否还有触发工作
                var completedScan = owner.runtimeState().riskScan(symbolId);
                triggers = remaining > 0 && completedScan != null
                        && !completedScan.triggerComplete()
                        ? owner.pendingTriggerScan(instrumentId, remaining) : COMPLETE_CONTROL;
            }
            if (!triggers.getAsBoolean()) return false;
            owner.logRiskScan("continuation", instrumentId, maxUsers, pendingBefore, startedAt);
            return true;
        }
    }

    /**
     * 在 Owner 轮询自动减仓工作完成并请求发布的固定续步。
     */
    private final class AdlControlContinuation implements BooleanSupplier {
        @Override public boolean getAsBoolean() {
            var work = reusableAdlWork;
            if (!work.getAsBoolean()) return false;
            owner.requestCommitPublication();
            return true;
        }
    }

    /**
     * 在 Owner 收集强平结果确认工作并请求发布的固定续步。
     */
    private final class LiquidationResolutionControlContinuation implements BooleanSupplier {
        @Override public boolean getAsBoolean() {
            var work = reusableResolutionWork;
            if (!work.getAsBoolean()) return false;
            owner.requestCommitPublication();
            return true;
        }
    }

    /**
     * 触发单和算法单直接命令共用的固定槽：账户 Lane 执行变更，Owner 再轮询并收集结果。
     */
    private final class TriggerControlContinuation
            implements BooleanSupplier, IntFunction<Object> {
        /** 触发单取消、认领、完成等变更操作类别。 */
        private static final byte MUTATION = 1;
        /** 触发单单条或互斥对写入类别。 */
        private static final byte TRIGGER_UPSERT = 2;
        /** 算法单写入类别。 */
        private static final byte ALGO_UPSERT = 3;
        /** 当前固定槽使用的操作类别，零表示尚未准备。 */
        private byte kind;
        /**
         * userId：账户归属用户，派发及回执均按其 Lane 路由。
         * triggerOrderId：当前变更的触发单编号。
         * arg1：首个变更参数：认领序号、完成后的原生订单号、追踪最高价、失效时间或重试过期边界。
         * arg2：第二个变更参数：触发价、完成时间、追踪最低价或重试时间。
         * arg3：第三个变更参数：触发时间或追踪激活时间。
         */
        private long userId, triggerOrderId, arg1, arg2, arg3;
        /** 完成操作的成功标记，其他变更不读取。 */
        private boolean flag;
        /** 完成操作的拒绝原因，空引用按空串处理。 */
        private String text;
        /** 触发单具体变更操作。 */
        private TriggerOrderCommands.Mutation mutation;
        /**
         * trigger：待写入的第一条触发单。
         * secondTrigger：互斥对的第二条触发单，单条写入为空。
         */
        private CoreTriggerOrderStateView trigger, secondTrigger;
        /** 当前待写入的算法单。 */
        private CoreAlgoOrderCommand algo;
        /** 当前品种的永久编号。 */
        private int symbolId;
        /** 触发单对应的稳定持仓身份。 */
        private long positionKey;
        /** 准备写入时该合约是否已经结束结算。 */
        private boolean instrumentSettled;

        /**
         * 清除旧参数，保存非空触发单变更种类与各原语输入。
         * @param userId 操作所属账户用户
         * @param mutation 触发单具体变更类别
         * @param triggerOrderId 待变更触发单编号
         * @param arg1 认领序号、原生订单号、追踪最高价、失效时间或重试边界，按操作类别解释
         * @param arg2 触发价、完成时间、追踪最低价或重试时间，按操作类别解释
         * @param arg3 触发时间或追踪激活时间，按操作类别解释
         * @param flag 完成操作是否成功
         * @param text 完成操作的拒绝原因，空引用按空串处理
         */
        void prepareMutation(long userId,
                TriggerOrderCommands.Mutation mutation, long triggerOrderId, long arg1, long arg2,
                long arg3, boolean flag, String text) {
            clear(); kind = MUTATION; this.userId = userId;
            this.mutation = Objects.requireNonNull(mutation); this.triggerOrderId = triggerOrderId;
            this.arg1 = arg1; this.arg2 = arg2; this.arg3 = arg3; this.flag = flag; this.text = text;
        }
        /**
         * 清除旧参数并保存非空触发单及其账户、合约、持仓身份。
         * @param userId 操作所属账户用户
         * @param trigger 待写入的触发单视图
         * @param symbolId 已解析的永久品种编号
         * @param positionKey 触发单稳定持仓身份
         * @param instrumentSettled 当前合约是否已结束结算
         */
        void prepareTriggerUpsert(long userId,
                CoreTriggerOrderStateView trigger, int symbolId, long positionKey,
                boolean instrumentSettled) {
            clear(); kind = TRIGGER_UPSERT;
            this.userId = userId; this.trigger = Objects.requireNonNull(trigger); this.symbolId = symbolId;
            this.positionKey = positionKey; this.instrumentSettled = instrumentSettled;
        }
        /**
         * 清除旧参数并保存非空算法单及其账户、合约身份。
         * @param userId 操作所属账户用户
         * @param algo 算法单的新建意图或生命周期增量
         * @param symbolId 已解析的永久品种编号
         */
        void prepareAlgoUpsert(long userId, CoreAlgoOrderCommand algo,
                int symbolId, long clusterTimestamp) {
            clear(); kind = ALGO_UPSERT;
            this.userId = userId; this.algo = Objects.requireNonNull(algo); this.symbolId = symbolId; this.arg1 = clusterTimestamp;
        }
        /**
         * 清除本轮操作类别、引用与原语参数，固定续步对象本身继续复用。
         */
        void clear() {
            kind = 0; mutation = null; trigger = null; secondTrigger = null; algo = null; text = null;
            userId = triggerOrderId = arg1 = arg2 = arg3 = positionKey = 0;
            flag = instrumentSettled = false; symbolId = 0;
        }

        /**
         * 在已选中的账户 Lane 执行触发单变更、单条或互斥对写入、算法单写入；返回变化布尔值或写后视图。Lane 参数不参与再次路由。
         * @param ignoredLaneId 派发器传来的 Lane 编号；本操作已按用户选定唯一 Lane，不读取此参数
         */
        @Override public Object apply(int ignoredLaneId) {
            // 当前账户 Lane 操作使用的同一权威运行时
            var runtime = owner.runtimeState();
            return switch (kind) {
                case MUTATION -> switch (mutation) {
                    case CANCEL -> RuntimeTriggerOrderStateTransitions.cancel(runtime, userId, triggerOrderId);
                    case CLAIM -> RuntimeTriggerOrderStateTransitions.claim(runtime, triggerOrderId, arg1, arg2, arg3);
                    case COMPLETE -> RuntimeTriggerOrderStateTransitions.complete(runtime, triggerOrderId,
                            flag, arg1, text == null ? "" : text, arg2);
                    case TRAILING -> RuntimeTriggerOrderStateTransitions.updateTrailing(runtime, triggerOrderId,
                            arg1, arg2, arg3);
                    case EXPIRE -> RuntimeTriggerOrderStateTransitions.expire(runtime, triggerOrderId, arg1);
                    case RETRY -> RuntimeTriggerOrderStateTransitions.retry(runtime, triggerOrderId, arg1, arg2);
                };
                case TRIGGER_UPSERT -> {
                    if (secondTrigger == null) RuntimeTriggerOrderStateTransitions.upsert(runtime, userId, trigger, symbolId,
                            positionKey, instrumentSettled);
                    else RuntimeTriggerOrderStateTransitions.placeOcoPair(runtime, userId, trigger, secondTrigger,
                            symbolId, positionKey, instrumentSettled);
                    yield secondTrigger == null ? runtime.triggerOrder(trigger.triggerOrderId()).view()
                            : java.util.List.of(runtime.triggerOrder(trigger.triggerOrderId()).view(),
                                    runtime.triggerOrder(secondTrigger.triggerOrderId()).view());
                }
                case ALGO_UPSERT -> {
                    runtime.upsertAlgoOrder(userId, algo, symbolId, arg1);
                    yield runtime.algoOrder(algo.algoOrderId());
                }
                default -> throw new IllegalStateException("unknown trigger continuation");
            };
        }

        /**
         * 等账户控制派发完成，读取该用户 Lane 回执；变更仅在返回真时请求发布，触发单写入同时保留一条或两条响应视图。
         */
        @Override public boolean getAsBoolean() {
            if (!owner.runtimeState().pollControlLanes()) return false;
            // 当前业务工作完成结果或所属 Lane 回执，具体类型见声明
            Object result = owner.runtimeState().controlLaneResult(
                    owner.runtimeState().topology().accountLaneId(userId));
            if (kind == MUTATION) {
                if (Boolean.TRUE.equals(result)) owner.requestCommitPublication();
            } else if (kind == TRIGGER_UPSERT) {
                owner.requestCommitPublication();
                if (secondTrigger == null) owner.resultBuilder.commandTriggerOrderViews = java.util.List.of((CoreTriggerOrderStateView) result);
                else {
                    // Lane 返回的两条互斥触发单视图，保留到本命令响应
                    @SuppressWarnings("unchecked") var views = (java.util.List<CoreTriggerOrderStateView>) result;
                    owner.resultBuilder.commandTriggerOrderViews = views;
                }
            } else {
                owner.requestCommitPublication();
            }
            return true;
        }
    }
}
