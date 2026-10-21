package com.surprising.aeron.service.orchestration.pipeline;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import com.surprising.aeron.service.orchestration.ingress.OwnerIngressRing;

/**
 * Owner 命令流水线的唯一状态持有者。
 *
 * <p>这里保存的是“命令处于哪个执行阶段”的运行时状态，不保存订单、余额或持仓等业务事实。
 * 交易 Owner 只负责按日志顺序推进这些状态；Matcher、Account Lane 和业务运行时仍然拥有各自的业务状态。</p>
 */
public final class OwnerCommandPipelineState {
    /** 每轮最多处理的完成通知数量，限制单次 Owner 调度的执行时间。 */
    public static final int COMPLETION_BATCH_SIZE = 64;
    /** 单条复制命令等待异步执行完成的最长时间。 */
    static final long COMMAND_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);

    /** 已准入但尚未提交的撮合命令窗口，所有者是当前 Owner 线程。 */
    private final ClusterCommandWindow commandWindow;

    public OwnerCommandPipelineState() { this(true); }

    public OwnerCommandPipelineState(boolean pipelined) {
        commandWindow = pipelined ? new ClusterCommandWindow() : null;
    }
    /** 当前正在等待直接控制流程或查询结果的命令。 */
    private OwnerIngressRing.Entry activeControl;
    /** 当前控制命令已经产生、但异步子任务尚未全部完成的响应候选值。 */
    private CoreResponse controlResponse;
    /** 当前控制命令等待的 Matcher 序号；零表示没有等待 Matcher 回调。 */
    private long matchingResponseSequence;
    /** 当前控制命令收到的 Matcher 终态响应。 */
    private CoreResponse matchingResponse;

    /** 当前已建立捕获边界的 FIFO 队首；退休前不移动窗口头。 */
    private ClusterCommandWindow.Entry committingHead;
    /** 当前异步命令的超时截止时间。 */
    private long progressDeadline;

    /** 上一轮被依赖关系挡住的入站命令，用于避免重复准备。 */
    private CoreMessage blockedIngress;
    /** 必须退休的最后一个依赖项；引用窗口已有条目，不复制依赖状态。 */
    private ClusterCommandWindow.Entry blockedThrough;

    /** 上一轮等待 Matcher 通知时观察到的派发上限。 */
    private long waitingDispatchSequence;
    /** 上一轮等待 Matcher 通知时观察到的完成游标。 */
    private long waitingMatchingProgress;
    /** 上一轮没有本地进展且没有新通知时，避免无意义地重复扫描。 */
    private boolean waitingMatchingNotification;

    /** Owner 实际推进过的命令阶段数，用于唤醒和工作量判断，不代表业务序号。 */
    private long commandProgress;

    /** 命令窗口的历史峰值，不参与资金和订单状态判断。 */
    private int commandWindowHighWaterMark;
    /** 已经进入确定性提交边界的命令数量。 */
    private long drainedCommands;
    /** 因已有依赖而等待的次数。 */
    private long dependencyFences;
    /** 因控制命令而等待的次数。 */
    private long controlFences;
    /** 因相同命令重复提交而等待的次数。 */
    private long retryFences;

    /**
     * 清空本轮 Owner 的命令状态和观测数据。
     *
     * <p>启动和测试复用 Owner 实例时调用；不会清空 TradingCoreRuntime 的业务状态。</p>
     */
    public void reset() {
        if (commandWindow != null) commandWindow.clear();
        activeControl = null;
        controlResponse = null;
        matchingResponseSequence = 0;
        matchingResponse = null;
        committingHead = null;
        progressDeadline = 0;
        blockedIngress = null;
        blockedThrough = null;
        waitingDispatchSequence = 0;
        waitingMatchingProgress = 0;
        waitingMatchingNotification = false;
        commandProgress = 0;
        commandWindowHighWaterMark = 0;
        drainedCommands = 0;
        dependencyFences = 0;
        controlFences = 0;
        retryFences = 0;
    }

    /**
     * 清空命令容器并保留终止日志需要的计数值。
     *
     * <p>终止阶段不再接收新命令，但观测计数必须先输出，因此不复用 {@link #reset()}。</p>
     */
    public void clearCommandContainers() {
        if (commandWindow != null) commandWindow.clear();
        activeControl = null;
        controlResponse = null;
        matchingResponseSequence = 0;
        matchingResponse = null;
        committingHead = null;
        progressDeadline = 0;
        blockedIngress = null;
        blockedThrough = null;
        waitingMatchingNotification = false;
    }

    /** 返回 Owner 是否已经开始处理一个控制命令。 */
    public boolean hasActiveControl() {
        return activeControl != null;
    }

    /** 返回当前控制命令。 */
    public OwnerIngressRing.Entry activeControl() {
        return activeControl;
    }

    /** 设置当前控制命令，并清除上一条控制命令的响应候选值。 */
    public void beginControl(OwnerIngressRing.Entry entry) {
        activeControl = entry;
        controlResponse = null;
        matchingResponseSequence = 0;
        matchingResponse = null;
    }

    /** 返回控制命令当前累积的响应候选值。 */
    public CoreResponse controlResponse() {
        return controlResponse;
    }

    /** 保存控制命令的响应候选值，等待所有异步子任务完成后发送。 */
    public void controlResponse(CoreResponse response) {
        controlResponse = response;
    }

    /** 记录当前控制命令等待的 Matcher 序号，并清除旧的回调结果。 */
    public void rememberMatchingResponseSequence(long sequence) {
        matchingResponseSequence = sequence;
        matchingResponse = null;
    }

    /** 返回当前控制命令是否等待 Matcher 的异步结果。 */
    public boolean hasMatchingResponseSequence() {
        return matchingResponseSequence != 0;
    }

    /** 返回当前控制命令收到的 Matcher 终态响应。 */
    public CoreResponse matchingResponse() {
        return matchingResponse;
    }

    /** 设置控制命令结束后的清理状态。 */
    public void finishControl() {
        activeControl = null;
        controlResponse = null;
        matchingResponseSequence = 0;
        matchingResponse = null;
    }

    /** 返回撮合命令窗口。 */
    public ClusterCommandWindow commandWindow() {
        return commandWindow;
    }

    /** 记录实际依赖前缀的末项；无关队首退休不触发重新准备。 */
    public void rememberBlockedIngress(CoreMessage request, int prefixSize) {
        if (prefixSize <= 0 || prefixSize > commandWindow.size())
            throw new IllegalArgumentException("invalid ingress dependency prefix");
        blockedIngress = request;
        blockedThrough = commandWindow.get(prefixSize - 1);
    }

    /** 清除旧的依赖阻塞记录，允许重新准备当前队首命令。 */
    public void clearBlockedIngress() {
        blockedIngress = null;
        blockedThrough = null;
    }

    /** 依赖项退休时同步清除引用，避免环形槽位复用造成误判。 */
    public boolean isIngressBlocked(CoreMessage request) {
        return request == blockedIngress && blockedThrough != null;
    }

    /** 保留被阻塞命令的解码结果，并在命令离开队首时释放临时解码对象。 */
    public void retainPendingPreparation(CoreMessage pending) {
        commandWindow.retainDecoded(pending);
        if (pending != blockedIngress || pending == null) clearBlockedIngress();
    }

    /** 为当前 FIFO 队首建立独立的确定性提交边界。 */
    public void beginHeadCommit() {
        if (commandWindow.size() == 0 || committingHead != null) {
            throw new IllegalStateException("invalid command drain boundary");
        }
        committingHead = commandWindow.get(0);
        drainedCommands++;
    }

    /** 返回当前队首是否已经建立提交边界。 */
    public boolean hasCommittingHead() {
        return committingHead != null;
    }

    /** 返回正在提交的队首，其序号、时间和日志位置直接取自窗口。 */
    public ClusterCommandWindow.Entry committingHead() {
        return committingHead;
    }

    /** 设置当前异步命令的截止时间。 */
    public void startProgressDeadline() {
        progressDeadline = System.nanoTime() + COMMAND_TIMEOUT_NANOS;
    }

    /** 返回当前异步命令的截止时间。 */
    public long progressDeadline() {
        return progressDeadline;
    }

    /** 记录一次“等待完成通知但未提交”的状态，供下一轮廉价判断使用。 */
    void rememberMatchingWait(long dispatchThrough, long matchingProgress, long progressBefore,
                              boolean hasLocalMatchingWork) {
        waitingDispatchSequence = dispatchThrough;
        waitingMatchingProgress = matchingProgress;
        waitingMatchingNotification = matchingProgress == progressBefore && !hasLocalMatchingWork;
    }

    /** 返回本轮是否可以直接跳过未变化的 Matcher 完成扫描。 */
    boolean canSkipMatchingPoll(long dispatchThrough, long progressBefore,
                                boolean hasMatchingNotifications) {
        return waitingMatchingNotification
                && waitingDispatchSequence == dispatchThrough
                && waitingMatchingProgress == progressBefore
                && !hasMatchingNotifications;
    }

    /** 清除等待通知标记，开始一次新的完成收集。 */
    void beginMatchingPoll() {
        waitingMatchingNotification = false;
    }

    /** 返回 Owner 是否正在等待异步完成通知，且当前没有本地可推进工作。 */
    public boolean waitingForMatchingNotification() {
        return waitingMatchingNotification;
    }

    /** 完成当前命令；先释放依赖引用，再清空可复用槽位。 */
    public void finishHeadCommit() {
        if (committingHead == null) throw new IllegalStateException("no command commit boundary");
        if (committingHead == blockedThrough) clearBlockedIngress();
        commandWindow.removePrefix(1);
        committingHead = null;
        waitingMatchingNotification = false;
        commandProgress++;
    }

    /** 控制命令已排空撮合窗口；内部触发子单独立提交，仅保存客户端命令自身的响应。 */
    public void completeControlMatching(long sequence, CoreResponse response) {
        if (commandWindow != null && commandWindow.size() != 0 || response == null)
            throw new IllegalStateException("control completion requires an empty trading window and a response");
        if (sequence != matchingResponseSequence) return;
        if (matchingResponse != null) throw new IllegalStateException("duplicate control matching completion");
        matchingResponse = response;
    }

    /** 返回 Owner 进展计数。 */
    public long commandProgress() {
        return commandProgress;
    }

    /** 记录一条命令进入撮合窗口，并更新窗口峰值。 */
    public void recordAdmission() {
        commandProgress++;
        if (commandWindow != null)
            commandWindowHighWaterMark = Math.max(commandWindowHighWaterMark, commandWindow.size());
    }

    /** 记录一次控制命令推进。 */
    public void recordControlProgress() {
        commandProgress++;
    }

    /** 记录一次需要等待控制命令完成的边界。 */
    public void recordControlFence() {
        controlFences++;
    }

    /** 记录一次需要等待已有撮合命令的依赖边界。 */
    public void recordDependencyFence(boolean retry) {
        dependencyFences++;
        if (retry) retryFences++;
    }

    /** 返回窗口峰值，供兼容测试和运行诊断使用。 */
    public int commandWindowHighWaterMark() {
        return commandWindowHighWaterMark;
    }

    /** 返回终止日志使用的命令流水线摘要。 */
    public String terminationSummary() {
        return "highWaterMark=" + commandWindowHighWaterMark
                + " pending=" + (commandWindow == null ? 0 : commandWindow.size())
                + " commands=" + drainedCommands
                + " dependencyFences=" + dependencyFences
                + " controlFences=" + controlFences
                + " retryFences=" + retryFences;
    }
}
