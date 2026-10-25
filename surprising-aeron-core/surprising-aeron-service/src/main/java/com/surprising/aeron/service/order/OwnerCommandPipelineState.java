package com.surprising.aeron.service.order;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.support.ingress.OwnerIngressRing;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Owner 独占的命令调度状态：保存响应窗口、控制命令、当前提交队首、依赖阻塞引用和观测计数。这里决定下一步能推进什么，不拥有订单簿、余额或持仓事实，也不创建计时器或工作线程。
 */
public final class OwnerCommandPipelineState {
    /** Owner 每轮完成收集使用的批次上限六十四，实际循环由调用方控制。 */
    public static final int COMPLETION_BATCH_SIZE = 64;
    /** 异步命令截止点使用的三十秒纳秒间隔，本类只保存截止点。 */
    static final long COMMAND_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);

    /**  当前 Owner 的响应 FIFO。 返回当前响应 FIFO。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final ClusterCommandWindow commandWindow;

    /**
     * 创建使用默认窗口容量的 Owner 调度状态。
     */
    public OwnerCommandPipelineState() {
        this(com.surprising.aeron.config.CoreRuntimeConfiguration.standalone().ownerCommandWindow());
    }

    /**
     * 创建指定窗口容量的 Owner 调度状态，其他字段使用初始值。
     * @param commandWindowCapacity 命令响应窗口容量
     */
    public OwnerCommandPipelineState(int commandWindowCapacity) {
        commandWindow = new ClusterCommandWindow(commandWindowCapacity);
    }
    /**  正在等待直接业务或查询完成的入口条目。 返回当前控制命令的入口条目引用。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private OwnerIngressRing.Entry activeControl;
    /**
     *  当前控制命令暂存的可空响应候选。
     * 返回当前控制命令暂存的响应候选。
     * 保存可空响应候选，实际发送时机由 Owner 主流程决定。
     * @param controlResponse 需要暂存或匹配保存的可空/非空响应，具体要求见方法
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    private CoreResponse controlResponse;
    /** 控制命令等待的 Matcher 序号，零表示无等待。 */
    private long matchingResponseSequence;
    /**  已经匹配等待序号的控制撮合终态响应。 返回已经匹配保存的控制撮合终态响应。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private CoreResponse matchingResponse;

    /**  当前正在提交的窗口队首引用，退休前固定。 返回已绑定队首引用，其日志位置与时间保留在条目中。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private ClusterCommandWindow.Entry committingHead;
    /**  当前异步命令保存的 nanoTime 截止点。 返回上次保存的单调时钟截止点，未设置时为零。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long progressDeadline;

    /** 被现有依赖挡住的入口请求对象。 */
    private CoreMessage blockedIngress;
    /** 必须先退休的依赖前缀最后条目，引用既有窗口对象。 */
    private ClusterCommandWindow.Entry blockedThrough;

    /**  Owner 已记录的命令阶段推进次数。 返回 Owner 已记录的推进次数，不是已应用 Core 序号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long commandProgress;

    /**  准入记录时观测到的窗口最大占用。 返回记录准入时观测到的窗口峰值。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int commandWindowHighWaterMark;

    /**
     * 清当前窗口及全部控制、依赖和观测字段，供启动或测试复用；不会重置交易运行时的业务状态。
     */
    public void reset() {
        commandWindow.clear();
        activeControl = null;
        controlResponse = null;
        matchingResponseSequence = 0;
        matchingResponse = null;
        committingHead = null;
        progressDeadline = 0;
        blockedIngress = null;
        blockedThrough = null;
        commandProgress = 0;
        commandWindowHighWaterMark = 0;
    }

    /**
     * 终止时清窗口和活动引用，保留进展/峰值/边界计数，便于随后输出摘要。
     */
    public void clearCommandContainers() {
        commandWindow.clear();
        activeControl = null;
        controlResponse = null;
        matchingResponseSequence = 0;
        matchingResponse = null;
        committingHead = null;
        progressDeadline = 0;
        blockedIngress = null;
        blockedThrough = null;
    }

    /**
     * 返回是否已登记活动控制入口。
     */
    public boolean hasActiveControl() {
        return activeControl != null;
    }

    /**
     * 保存控制入口并清上一控制响应及等待 Matcher 水位，不校验 entry 非空。
     * @param entry 当前控制命令的入口条目引用
     */
    public void beginControl(OwnerIngressRing.Entry entry) {
        activeControl = entry;
        controlResponse = null;
        matchingResponseSequence = 0;
        matchingResponse = null;
    }

    /**
     * 记录当前控制命令等待的序号并清旧响应；本方法不检查序号正数。
     * @param sequence 控制命令等待或报告完成的 Matcher 序号
     */
    public void rememberMatchingResponseSequence(long sequence) {
        matchingResponseSequence = sequence;
        matchingResponse = null;
    }

    /**
     * 返回等待序号是否非零，不等于异步子任务已经完成。
     */
    public boolean hasMatchingResponseSequence() {
        return matchingResponseSequence != 0;
    }

    /**
     * 清当前控制入口、响应候选和等待序号，不移动普通命令窗口。
     */
    public void finishControl() {
        activeControl = null;
        controlResponse = null;
        matchingResponseSequence = 0;
        matchingResponse = null;
    }

    /**
     * 要求依赖前缀在当前窗口内，记录请求对象和该前缀最后一个现有条目；不复制一份依赖集合。
     * @param request 需要判定或记录依赖的入口请求对象
     * @param prefixSize 当前必须退休的正依赖前缀长度
     */
    public void rememberBlockedIngress(CoreMessage request, int prefixSize) {
        if (prefixSize <= 0 || prefixSize > commandWindow.size())
            throw new IllegalArgumentException("invalid ingress dependency prefix");
        blockedIngress = request;
        blockedThrough = commandWindow.get(prefixSize - 1);
    }

    /**
     * 清掉阻塞请求和依赖末项的引用，允许入口重新准备。
     */
    public void clearBlockedIngress() {
        blockedIngress = null;
        blockedThrough = null;
    }

    /**
     * 按请求对象身份及已记录末项引用判断是否仍阻塞，不在这里扫描窗口或自行退休依赖。
     * @param request 需要判定或记录依赖的入口请求对象
     */
    public boolean isIngressBlocked(CoreMessage request) {
        return request == blockedIngress && blockedThrough != null;
    }

    /**
     * 只为仍在入口队首的同一请求保留解码，换请求或队首为空时也清旧阻塞引用。
     * @param pending 当前仍在入口队首的请求，空表示没有待准备请求
     */
    public void retainPendingPreparation(CoreMessage pending) {
        commandWindow.retainDecoded(pending);
        if (pending != blockedIngress || pending == null) clearBlockedIngress();
    }

    /**
     * 要求窗口非空且还没有提交队首，保存当前首项并增加进入提交边界的次数；不在此判断其业务是否已完成。
     */
    public void beginHeadCommit() {
        if (commandWindow.size() == 0 || committingHead != null) {
            throw new IllegalStateException("invalid command drain boundary");
        }
        committingHead = commandWindow.get(0);
    }

    /**
     * 返回是否已绑定正在提交的窗口队首。
     */
    public boolean hasCommittingHead() {
        return committingHead != null;
    }

    /**
     * 保存当前 nanoTime 加三十秒的截止点，超时判断由调用方后续执行，不在这里启动计时任务。
     */
    public void startProgressDeadline() {
        progressDeadline = System.nanoTime() + COMMAND_TIMEOUT_NANOS;
    }

    /**
     * 调用方确认队首终态后退休一项；若它正是阻塞依赖末项先清引用，随后移窗口、清提交状态并增加进展计数。
     */
    public void finishHeadCommit() {
        if (committingHead == null) throw new IllegalStateException("no command commit boundary");
        if (committingHead == blockedThrough) clearBlockedIngress();
        commandWindow.removePrefix(1);
        committingHead = null;
        commandProgress++;
    }

    /**
     * 先要求普通窗口空和响应非空，再仅接收与等待序号相同的结果，不匹配的内部子命令结果忽略；同序号重复响应拒绝。本方法不另校验活动控制存在或序号为正。
     * @param sequence 控制命令等待或报告完成的 Matcher 序号
     * @param response 需要暂存或匹配保存的可空/非空响应，具体要求见方法
     */
    public void completeControlMatching(long sequence, CoreResponse response) {
        if (commandWindow.size() != 0 || response == null)
            throw new IllegalStateException("control completion requires an empty trading window and a response");
        if (sequence != matchingResponseSequence) return;
        if (matchingResponse != null) throw new IllegalStateException("duplicate control matching completion");
        matchingResponse = response;
    }

    /**
     * 记录一次入口准入进展，并按当前窗口大小更新观测峰值。
     */
    public void recordAdmission() {
        commandProgress++;
        commandWindowHighWaterMark = Math.max(commandWindowHighWaterMark, commandWindow.size());
    }

    /**
     * 记录一次控制命令推进。
     */
    public void recordControlProgress() {
        commandProgress++;
    }

    /**
     * 组装终止日志摘要：窗口峰值与剩余未完成项。
     */
    public String terminationSummary() {
        return "highWaterMark=" + commandWindowHighWaterMark
                + " pending=" + commandWindow.size();
    }
}
