package com.surprising.aeron.service.order;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.AmendOrderBatchCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreAlgoOrderCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.service.account.AccountCommandService;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.adl.AdlService;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.core.change.AccountPartitionCommit;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.fee.FeePolicyBook;
import com.surprising.aeron.service.funding.FundingSettlementService;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.liquidation.LiquidationService;
import com.surprising.aeron.service.matcher.OrderBookEngine.CancellationOrder;
import com.surprising.aeron.service.matcher.OrderMatchingResult;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.result.CommandResultBuilder;
import com.surprising.aeron.service.result.ResponseSlotPool;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.settlement.TradeSettlement;
import com.surprising.aeron.service.settlement.ContractExpirySettlementService;
import com.surprising.aeron.service.treasury.SystemFunds;
import com.surprising.aeron.service.trigger.command.TriggerOrderCommands;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;
import com.surprising.aeron.support.collection.PrimitiveLongChangeSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;


public final class DirectOrderCommand {
    /** 创建槽时绑定的唯一所属 Owner，跨命令复用。 */
    private final TradingCore owner;
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private CoreMessage command;
    /** 本轮完整命令指纹，供幂等及终态保留核对。 */
    private CommandFingerprint fingerprint;
    private TradingCore.SourceKey sourceKey;
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private long commitFenceTimestamp;
    private long commitFenceClusterPosition;

    /** 返回是否已有直接命令占用本槽，不能据此判断业务是否成功。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean active;
    /** 该工作开始前的运行时修订，用于变化及回滚判断。 */
    private long beforeRevision;
    /** 命令开始前的账户回滚检查点。返回本命令开始前的账户回滚检查点。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private long checkpoint;
    /** 命令开始前的持仓身份回滚检查点。 */
    private long identityCheckpoint;
    /** 当前业务或回滚续步，返回假时由 Owner 后续轮询推进。 */
    private BooleanSupplier controlWork;
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private ResponseStatus status;
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private CoreResultCode resultCode;
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    private com.surprising.aeron.service.core.change.AccountPartitionCommit commitEvent;
    /** 返回直接命令的收尾材料是否已准备，防止后续轮询重复准备。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean finalizationPrepared;

    private final FundingControlContinuation fundingControl = new FundingControlContinuation();
    private final RiskScanControlContinuation riskScanControl = new RiskScanControlContinuation();
    private final AdlControlContinuation adlControl = new AdlControlContinuation();
    private final LiquidationResolutionControlContinuation liquidationResolutionControl =
            new LiquidationResolutionControlContinuation();
    private final AccountCommandService accountControl = new AccountCommandService();
    private final BooleanSupplier accountControlWork;
    private final TriggerControlContinuation triggerControl = new TriggerControlContinuation();
    private static final BooleanSupplier COMPLETE_CONTROL = () -> true;

    @Accessors(fluent = true, chain = false)
    @Getter
    private FundingSettlementService.FundingWork reusableFundingWork;
    @Accessors(fluent = true, chain = false)
    @Getter
    private AdlService.AdlWork reusableAdlWork;
    private LiquidationService.ResolutionWork reusableResolutionWork;
    private RiskScanner reusableRiskCoordinator;

    public DirectOrderCommand(TradingCore owner) {
        this.owner = Objects.requireNonNull(owner);
        accountControlWork = () -> {
            if (!accountControl.getAsBoolean()) return false;
            if (accountControl.stateChanged()) this.owner.requestCommitPublication();
            return true;
        };
    }

    /** 拒绝覆盖仍占用的槽，保存非空命令、指纹及来源，再记下业务时间、日志位置与回滚检查点。 */
    public void initialize(CoreMessage command, CommandFingerprint fingerprint,
            TradingCore.SourceKey sourceKey, long timestamp, long position,
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

    /** 返回是否还有控制续步，也可能是拒单后的回滚续步。 */
    public boolean hasControlWork() { return controlWork != null; }
    /** 保存调用方已经决定的状态和结果码，不在此检查非空或完成发布。 */
    public void result(ResponseStatus status, CoreResultCode resultCode) {
        this.status = status;
        this.resultCode = resultCode;
    }
    /** 记下本条命令的收尾材料已经准备。 */
    public void markFinalizationPrepared() { finalizationPrepared = true; }
    /** 撤销收尾材料就绪标记，允许调用方按现有规则重新准备。 */
    public void clearFinalizationPrepared() { finalizationPrepared = false; }

    /** 只解除本槽对提交事件的引用，事件回收由原调用方负责。 */
    public void clearCommitEvent() { commitEvent = null; }

    /** 要求当前没有续步，再保存非空控制工作，供 Owner 后续轮询。 */
    public void deferControl(BooleanSupplier work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        controlWork = Objects.requireNonNull(work);
    }

    /** 要求没有旧续步，绑定资金费收集器并保留工作对象供后续命令复用。 */
    public void deferFundingControl(FundingSettlementService.FundingWork work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        reusableFundingWork = Objects.requireNonNull(work);
        controlWork = fundingControl;
    }

    /** 绑定风险扫描和后续触发扫描所需信息。同一槽不能并存第二条控制续步。 */
    public void deferRiskScanControl(RiskScanner risk, int symbolId,
            String instrumentId, int maxUsers, int pendingBefore, long startedAt, long beforeRevision) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        riskScanControl.prepare(risk, symbolId, instrumentId, maxUsers, pendingBefore, startedAt,
                beforeRevision);
        controlWork = riskScanControl;
    }

    /** 绑定自动减仓续步，并记住原工作对象供后续复用。 */
    public void deferAdlControl(AdlService.AdlWork work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        reusableAdlWork = Objects.requireNonNull(work);
        controlWork = adlControl;
    }

    /** 绑定强平结果确认续步，并保留原工作对象供后续命令复用。 */
    public void deferLiquidationResolutionControl(LiquidationService.ResolutionWork work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        reusableResolutionWork = Objects.requireNonNull(work);
        controlWork = liquidationResolutionControl;
    }

    /** 返回上次保存的强平结果确认工作对象。 */
    public LiquidationService.ResolutionWork reusableLiquidationResolutionWork() {
        return reusableResolutionWork;
    }

    /** 首次用所属 Owner 的索引、运行时和身份表创建扫描器，之后重置本轮预算。 */
    public RiskScanner reusableRiskScanCoordinator(int maxUsers) {
        if (reusableRiskCoordinator == null) {
            reusableRiskCoordinator = new RiskScanner(
                    maxUsers, owner.positionUserIndex(), owner.accountBook(), owner.identities());
        } else {
            reusableRiskCoordinator.resetForCommand(maxUsers);
        }
        return reusableRiskCoordinator;
    }

    /** 准备所属用户的余额调整账户任务，并安装固定轮询函数。 */
    public void deferBalanceAdjustment(long userId, String asset, long deltaUnits) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        accountControl.prepareBalanceAdjustment(owner.accountBook(), owner.identities(), userId, asset, deltaUnits);
        controlWork = accountControlWork;
    }

    /** 准备所属用户的转出账户任务，完成和变化判断沿用账户任务自身回执。 */
    public void deferTransferOut(long userId, TransferFundsCommand command) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        accountControl.prepareTransferOut(owner.accountBook(), owner.identities(), userId, command);
        controlWork = accountControlWork;
    }

    /** 准备所属用户的划转完成任务，后续轮询成功且有变化时请求发布。 */
    public void deferTransferComplete(long userId, long transferId) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        accountControl.prepareTransferCompletion(owner.accountBook(), userId, transferId);
        controlWork = accountControlWork;
    }

    /** 准备杠杆变更账户任务，是否发生变化由账户回执判断。 */
    public void deferLeverageChange(long userId, UpdateLeverageCommand command) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        accountControl.prepareLeverageChange(owner.accountBook(), owner.identities(), userId, command);
        controlWork = accountControlWork;
    }

    /** 准备持仓模式变更账户任务，是否发生变化由账户回执判断。 */
    public void deferPositionModeChange(long userId, UpdatePositionModeCommand command) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        accountControl.preparePositionModeChange(owner.accountBook(), userId, command);
        controlWork = accountControlWork;
    }

    /** 准备逐仓保证金调整账户任务，不在 Owner 直接改 Lane 状态。 */
    public void deferPositionMarginAdjustment(long userId,
                                       AdjustPositionMarginCommand command) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        accountControl.preparePositionMarginAdjustment(owner.accountBook(), owner.identities(), userId, command);
        controlWork = accountControlWork;
    }

    /** 把触发单变更参数放入固定槽，只派发给该用户所属 Lane，后续由同一对象收集完成。 */
    public void deferTriggerMutationControl(long userId,
            TriggerOrderCommands.Mutation mutation, long triggerOrderId, long arg1, long arg2,
            long arg3, boolean flag, String text) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        triggerControl.prepareMutation(userId, mutation, triggerOrderId, arg1, arg2, arg3,
                flag, text);
        owner.accountBook().dispatchControlLanes(
                1L << owner.accountBook().topology().accountLaneId(userId), triggerControl);
        controlWork = triggerControl;
    }

    /** 准备触发单写入任务并派发给用户所属 Lane，保留合约和持仓身份。 */
    public void deferTriggerUpsertControl(long userId,
            CoreTriggerOrderStateView trigger, int symbolId, long positionKey,
            boolean instrumentSettled) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        triggerControl.prepareTriggerUpsert(userId, trigger, symbolId, positionKey,
                instrumentSettled);
        owner.accountBook().dispatchControlLanes(
                1L << owner.accountBook().topology().accountLaneId(userId), triggerControl);
        controlWork = triggerControl;
    }

    /** 准备互斥触发单对，共用一次所属账户 Lane 派发，第二条保存在同一续步内。 */
    public void deferTriggerOcoPairControl(long userId,
            CoreTriggerOrderStateView first, CoreTriggerOrderStateView second,
            int symbolId, long positionKey, boolean instrumentSettled) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        triggerControl.prepareTriggerUpsert(userId, first, symbolId, positionKey, instrumentSettled);
        triggerControl.secondTrigger = second;
        owner.accountBook().dispatchControlLanes(
                1L << owner.accountBook().topology().accountLaneId(userId), triggerControl);
        controlWork = triggerControl;
    }

    /** 准备算法单写入任务并派发给用户所属 Lane。 */
    public void deferAlgoUpsertControl(long userId, CoreAlgoOrderCommand algo,
            int symbolId, long clusterTimestamp) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        triggerControl.prepareAlgoUpsert(userId, algo, symbolId, clusterTimestamp);
        owner.accountBook().dispatchControlLanes(
                1L << owner.accountBook().topology().accountLaneId(userId), triggerControl);
        controlWork = triggerControl;
    }

    /** 只允许 Owner 推进已占用槽。若拒绝前已有变化，先分轮完成账户及持仓身份回滚，再交运行时收尾。 */
    public CoreResponse poll() {
        owner.assertOwner();
        if (!active) throw new IllegalStateException("no pending direct command");
        if (status == null) {
            try {
                if (!controlWork.getAsBoolean()) return null;
                status = ResponseStatus.APPLIED;
                resultCode = CoreResultCode.NONE;
            } catch (CoreStateRejectedException failure) {
                status = ResponseStatus.REJECTED;
                resultCode = CoreResultCode.fromRejectionCode(failure.code());
            } catch (ArithmeticException failure) {
                status = ResponseStatus.REJECTED;
                resultCode = CoreResultCode.ARITHMETIC_OVERFLOW;
            } catch (IllegalArgumentException failure) {
                status = ResponseStatus.REJECTED;
                resultCode = CoreResultCode.INVALID_COMMAND;
            }
            controlWork = null;
        }
        if (status != ResponseStatus.APPLIED
                && (owner.commits.publication.dirty()
                || owner.accountBook().revision() != beforeRevision
                || owner.accountBook().hasUncommittedCommandChanges())) {
            if (controlWork == null) {
                owner.commits.publication.clearBatchState();
                controlWork = owner.accountBook().beginCommandRollback(
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

    /** 清除本条命令、各固定续步和提交标记，释放本轮引用。 */
    public void clear() {
        riskScanControl.clear();
        accountControl.clear();
        triggerControl.clear();
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
        commitFenceTimestamp = commitFenceClusterPosition = 0;
    }

    /** 资金费工作完成后的 Owner 收集步骤，整理进度和受影响用户。 */
    private final class FundingControlContinuation implements BooleanSupplier {
        @Override public boolean getAsBoolean() {
            var work = reusableFundingWork;
            if (!work.poll()) return false;
            var result = work.result();
            owner.requestCommitPublication();
            owner.resultBuilder.commandFundingProgress = result.progress();
            owner.resultBuilder.addChangedUsersFromFundingPayments(result.payments());
            owner.notifyReplayFundingObserver(result);
            return true;
        }
    }

    /** 在同一条风险命令内衔接风险扫描和剩余预算内的触发扫描，不建立新业务队列。 */
    private final class RiskScanControlContinuation implements BooleanSupplier {
        private RiskScanner risk;
        private int symbolId;
        private String instrumentId;
        private int maxUsers;
        private int pendingBefore;
        private long startedAt;
        /** 该工作开始前的运行时修订，用于变化及回滚判断。 */
        private long beforeRevision;
        private BooleanSupplier triggers;

        /** 保存风险续扫输入并清空上一轮触发扫描续步。 */
        void prepare(RiskScanner risk, int symbolId, String instrumentId,
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
        /** 清除当前风险及触发续步引用和统计输入，保留外层可复用扫描器。 */
        void clear() {
            risk = null; instrumentId = null; triggers = null;
            symbolId = maxUsers = pendingBefore = 0; startedAt = beforeRevision = 0;
        }

        /** 先收齐可空风险扫描工作，有修订变化则请求发布。 */
        @Override public boolean getAsBoolean() {
            if (triggers == null) {
                if (risk != null && !risk.poll()) return false;
                if (owner.accountBook().revision() != beforeRevision) owner.requestCommitPublication();
                int remaining = maxUsers - (risk == null ? 0 : risk.completedWork());
                var completedScan = owner.accountBook().riskScan(symbolId);
                triggers = remaining > 0 && completedScan != null
                        && !completedScan.triggerComplete()
                        ? owner.pendingTriggerScan(instrumentId, remaining) : COMPLETE_CONTROL;
            }
            if (!triggers.getAsBoolean()) return false;
            owner.logRiskScan("continuation", instrumentId, maxUsers, pendingBefore, startedAt);
            return true;
        }
    }

    /** 在 Owner 轮询自动减仓工作完成并请求发布的固定续步。 */
    private final class AdlControlContinuation implements BooleanSupplier {
        @Override public boolean getAsBoolean() {
            var work = reusableAdlWork;
            if (!work.getAsBoolean()) return false;
            owner.requestCommitPublication();
            return true;
        }
    }

    /** 在 Owner 收集强平结果确认工作并请求发布的固定续步。 */
    private final class LiquidationResolutionControlContinuation implements BooleanSupplier {
        @Override public boolean getAsBoolean() {
            var work = reusableResolutionWork;
            if (!work.getAsBoolean()) return false;
            owner.requestCommitPublication();
            return true;
        }
    }

    /** 触发单和算法单直接命令共用的固定槽：账户 Lane 执行变更，Owner 再轮询并收集结果。 */
    private final class TriggerControlContinuation
            implements BooleanSupplier, IntFunction<Object> {
        private static final byte MUTATION = 1;
        private static final byte TRIGGER_UPSERT = 2;
        private static final byte ALGO_UPSERT = 3;
        private byte kind;
        private long userId, triggerOrderId, arg1, arg2, arg3;
        private boolean flag;
        private String text;
        private TriggerOrderCommands.Mutation mutation;
        private CoreTriggerOrderStateView trigger, secondTrigger;
        private CoreAlgoOrderCommand algo;
        private int symbolId;
        private long positionKey;
        private boolean instrumentSettled;

        /** 清除旧参数，保存非空触发单变更种类与各原语输入。 */
        void prepareMutation(long userId,
                TriggerOrderCommands.Mutation mutation, long triggerOrderId, long arg1, long arg2,
                long arg3, boolean flag, String text) {
            clear(); kind = MUTATION; this.userId = userId;
            this.mutation = Objects.requireNonNull(mutation); this.triggerOrderId = triggerOrderId;
            this.arg1 = arg1; this.arg2 = arg2; this.arg3 = arg3; this.flag = flag; this.text = text;
        }
        /** 清除旧参数并保存非空触发单及其账户、合约、持仓身份。 */
        void prepareTriggerUpsert(long userId,
                CoreTriggerOrderStateView trigger, int symbolId, long positionKey,
                boolean instrumentSettled) {
            clear(); kind = TRIGGER_UPSERT;
            this.userId = userId; this.trigger = Objects.requireNonNull(trigger); this.symbolId = symbolId;
            this.positionKey = positionKey; this.instrumentSettled = instrumentSettled;
        }
        /** 清除旧参数并保存非空算法单及其账户、合约身份。 */
        void prepareAlgoUpsert(long userId, CoreAlgoOrderCommand algo,
                int symbolId, long clusterTimestamp) {
            clear(); kind = ALGO_UPSERT;
            this.userId = userId; this.algo = Objects.requireNonNull(algo); this.symbolId = symbolId; this.arg1 = clusterTimestamp;
        }
        /** 清除本轮操作类别、引用与原语参数，固定续步对象本身继续复用。 */
        void clear() {
            kind = 0; mutation = null; trigger = null; secondTrigger = null; algo = null; text = null;
            userId = triggerOrderId = arg1 = arg2 = arg3 = positionKey = 0;
            flag = instrumentSettled = false; symbolId = 0;
        }

        /** 在已选中的账户 Lane 执行触发单变更、单条或互斥对写入、算法单写入。 */
        @Override public Object apply(int ignoredLaneId) {
            var accountBook = owner.accountBook();
            return switch (kind) {
                case MUTATION -> switch (mutation) {
                    case CANCEL -> accountBook.triggerOrders().cancelTriggerOrder(userId, triggerOrderId);
                    case CLAIM -> accountBook.triggerOrders().claimTriggerOrder(triggerOrderId, arg1, arg2, arg3);
                    case COMPLETE -> accountBook.triggerOrders().completeTriggerOrder(triggerOrderId,
                            flag, arg1, text == null ? "" : text, arg2);
                    case TRAILING -> accountBook.triggerOrders().updateTrailingTriggerOrder(triggerOrderId,
                            arg1, arg2, arg3);
                    case EXPIRE -> accountBook.triggerOrders().expireTriggerOrder(triggerOrderId, arg1);
                    case RETRY -> accountBook.triggerOrders().retryTriggerOrder(triggerOrderId, arg1, arg2);
                };
                case TRIGGER_UPSERT -> {
                    if (secondTrigger == null) accountBook.triggerOrders().upsertTriggerOrder(userId, trigger, symbolId,
                            positionKey, instrumentSettled);
                    else accountBook.triggerOrders().placeOcoPair(userId, trigger, secondTrigger,
                            symbolId, positionKey, instrumentSettled);
                    yield secondTrigger == null ? accountBook.triggerOrder(trigger.triggerOrderId()).view()
                            : java.util.List.of(accountBook.triggerOrder(trigger.triggerOrderId()).view(),
                                    accountBook.triggerOrder(secondTrigger.triggerOrderId()).view());
                }
                case ALGO_UPSERT -> {
                    accountBook.upsertAlgoOrder(userId, algo, symbolId, arg1);
                    yield accountBook.algoOrder(algo.algoOrderId());
                }
                default -> throw new IllegalStateException("unknown trigger continuation");
            };
        }

        /** 等账户控制派发完成，读取该用户 Lane 回执。 */
        @Override public boolean getAsBoolean() {
            if (!owner.accountBook().pollControlLanes()) return false;
            Object result = owner.accountBook().controlLaneResult(
                    owner.accountBook().topology().accountLaneId(userId));
            if (kind == MUTATION) {
                if (Boolean.TRUE.equals(result)) owner.requestCommitPublication();
            } else if (kind == TRIGGER_UPSERT) {
                owner.requestCommitPublication();
                if (secondTrigger == null) owner.resultBuilder.commandTriggerOrderViews = java.util.List.of((CoreTriggerOrderStateView) result);
                else {
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

