package com.surprising.aeron.service.replay;

import com.surprising.aeron.client.RealtimeOutbox;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.realtime.state.RealtimeStateCapture.LiquidationChange;
import com.surprising.product.api.ProductLine;
import java.util.*;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 独立导出工作进程使用的确定性交易回放副本。接收已提交日志并按原时间、位置执行，等账户、撮合和账本完成后生成订单、成交、执行、触发及相关结果事实；不连接在线 Core，也不参与在线回调调度。
 */
public final class CommittedTradeReplay implements AutoCloseable {
    /** 当前工作进程独占的交易回放副本。 */
    private final TradingCoreRuntime state;
    /** 本副本的有界实时捕获队列，最多六万五千五百三十六帧及三十二 MiB 字节预算。 */
    private final RealtimeOutbox outbox = new RealtimeOutbox(65536, 32 * 1024 * 1024);
    /** 仅输出订单投影的本副本实时捕获器。 */
    private final com.surprising.aeron.service.realtime.state.RealtimeStateCapture capture;
    /** 资金费观察器为当前命令暂存的计算完成事实，生成分页后清引用。 */
    private com.surprising.aeron.service.funding.RuntimePerpetualFundingProcessor.FundingResult fundingResult;
    /**  最近一次 apply 的可空资金费分页。 读取最近一次命令生成的可空已提交资金费分页。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private CommittedFundingPage fundingPage;
    /**  最近一次 apply 的可空交割现金分页。 读取最近一次命令生成的可空交割现金分页。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private CommittedDeliveryPage deliveryPage;
    /**  最近一次正用户命令尝试的可空最终收据。 读取最近一次正用户命令尝试的最终收据；冲突重试有自己的拒绝收据，不覆盖原成功事实。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private CommittedCommandResult commandResult;
    /**  最近一次余额或转账命令的可空实际现金变化。 读取最近一次命令产生的可空实际现金差额，不把释放冻结当收入；零调账仅供审计确认，收益投影不记现金流水。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private CommittedBalanceFlow balanceFlow;
    // 这只是本独立回放工作进程的读模型路由；子单成交或撤单未改父修订时，仍可据子 ID 找到父单。
    /** 本导出副本的子订单 ID 到父算法单 ID 路由，不是第二份订单状态。 */
    private final Map<Long, Long> algoByChild = new HashMap<>();
    /**  当前命令造成进度变化的最终父单视图列表，每次 apply 开头清空。 读取最近一次 apply 收集的算法父单变化列表，下一次 apply 会先清空。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private List<CoreAlgoOrderView> algoChanges = List.of();
    private final ArrayList<LiquidationChange> liquidationChanges = new ArrayList<>();

    /** 当前 apply 已完成的强平变化；返回独立列表，下一条命令不会清掉调用方批次。 */
    public List<LiquidationChange> liquidationChanges() { return List.copyOf(liquidationChanges); }

    /**
     * 创建指定产品线空运行时或从快照恢复，挂载只采集订单投影的实时捕获器与资金费观察器，再遍历已有算法父单重建子单路由。
     * @param product 当前回放或结果事实所属的独立产品线
     * @param snapshot 可空已提交 Core 快照，空则从空副本开始
     */
    public CommittedTradeReplay(ProductLine product, byte[] snapshot) {
        state =
                snapshot == null
                        ? new TradingCoreRuntime(product)
                        : TradingCoreRuntime.fromSnapshot(product, snapshot);
        state.assertClusterCallbackComplete();
        capture = state.attachRealtime(outbox);
        capture.orderProjectionOnly(true);
        capture.liquidationObserver(liquidationChanges::add);
        state.funding().observeReplayPayments(/* 资金费观察器交来的本命令完成事实，暂存后统一生成分页 */ result -> fundingResult = result);
        forEachRetainedAlgo(/* 构造时遍历父单以重建路由的空消费者参数，不另外处理视图 */ ignored -> {});
    }

    /**
     * 清上次导出结果后解码，拒绝跨产品帧、跳过外部查询；保存所需余额，执行命令并等待直接控制及撮合完成，再生成最终收据、现金差额和相关分页。捕获丢失或未完成即报错，调用方不得前移检查点；finally 只中止捕获，不承诺回滚已执行副本。
     * @param bytes 一条已提交命令的完整协议编码
     * @param timestamp 该日志项原复制业务时间
     * @param logPosition 该日志项原复制位置
     */
    public List<RealtimeFrame> apply(byte[] bytes, long timestamp, long logPosition) {
        algoChanges = List.of();
        liquidationChanges.clear();
        fundingPage = null;
        deliveryPage = null;
        commandResult = null;
        balanceFlow = null;
        fundingResult = null;
        // 当前协议帧解码得到的实际命令
        CoreMessage command = CoreMessageCodec.decode(bytes);
        if (command.header().productLine() != state.productLine())
            throw new IllegalArgumentException("cross-product replay message");
        if (command.header().kind() == WireMessageKind.QUERY) return List.of();
        // 本次需比较的可空现金目标
        var balanceTarget = balanceFlowTarget(command);
        // 命令前可用加冻结的 BigInteger 总额
        var beforeBalance = balanceTarget == null ? null
                : balanceUnits(balanceTarget.userId(), balanceTarget.asset());
        // 可解析的交割结算命令，否则为空
        var delivery = deliveryCommand(command);
        // 交割本页用户执行前总额标量列表
        var deliveryBalances = delivery == null ? List.<BeforeDeliveryBalance>of() : deliveryBalances(delivery, timestamp, logPosition);
        // 捕获开始前 outbox 已丢批次数
        // 捕获开始前编码失败计数，完成后必须不增加
        long dropped = outbox.droppedBatches(), failures = capture.failures();
        capture.begin(logPosition, timestamp, 0, state.realtimeExportSequence());
        try {
            // 直接执行、完成轮询或内部查询返回的响应
            CoreResponse response = state.apply(command, timestamp, logPosition);
            // 当前直接控制槽最多等待五秒的截止时间
            long directDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (response == null && state.directCommand.active() && System.nanoTime() < directDeadline) {
                response = state.directCommand.poll();
                if (response == null) Thread.onSpinWait();
            }
            if (response == null && state.directCommand.active()) {
                throw new AssertionError("Direct command timed out in CommittedTradeReplay: " + command.header().messageType());
            }
            // 当前撮合或内部读取阶段单独计算的纳秒截止时间
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            // 只有独立回放在这里有界等待；在线 Owner 继续使用自己的调度，撮合和账户仍由原所有者结算。
            // 撮合无进展时逐步增加的休眠间隔，一微秒起、上限一百微秒
            long idleNanos = 1_000L;
            while (state.pendingMatching().firstSequence() != 0) {
                if (Thread.currentThread().isInterrupted())
                    throw new IllegalStateException("committed replay interrupted; checkpoint must not advance");
                if (state.commits.commitReadyMatching(
                                64, timestamp, logPosition, false, (/* 撮合完成回调的 Core 序号，本回放回调不另处理 */ sequence, /* 撮合完成回调结果，本回放等全部收尾后再读最终账本 */ matchingResponse) -> {}) == 0) {
                    java.util.concurrent.locks.LockSupport.parkNanos(idleNanos);
                    idleNanos = Math.min(idleNanos * 2, 100_000L);
                } else idleNanos = 1_000L;
                if (System.nanoTime() > deadline)
                    throw new IllegalStateException("committed replay matching timed out");
            }
            state.assertClusterCallbackComplete();
            if (command.header().userId() > 0) {
                // 撮合初始响应可能仍在等待，必须先完成账户与账本再读最终保留结果。
                // 冲突重试按本次拒绝统计，不借用原成功收据；这些读取只发生在独立导出副本。
                // 当前命令保留的终态记录，冲突重试刻意不借用原记录
                var retained = response != null && response.resultCode() == CoreResultCode.IDEMPOTENCY_CONFLICT
                        ? null : state.resultLedger().get(command.header().commandId());
                if (retained == null && response == null)
                    throw new IllegalStateException("committed replay has no command result");
                // 从最终记录或本次直接响应取得的命令终态
                var status = retained == null ? response.commandStatus() : retained.status();
                // 本次最终业务结果码，不能仍待撮合
                var code = retained == null ? response.resultCode() : retained.resultCode();
                // 最终记录或本次响应保存的原应用计数
                long applied = retained == null ? response.appliedCommandCount() : retained.appliedCommandCount();
                if (code == CoreResultCode.MATCHING_PENDING)
                    throw new IllegalStateException("committed replay did not finish the trading command");
                // 由合法下单载荷解析出的可空客户号
                // 由合法下单载荷解析出的可空币对 ID
                String clientOrderId = null, instrumentId = null;
                if (command.header().messageType() == CoreMessageType.PLACE_ORDER && code != CoreResultCode.IDEMPOTENCY_CONFLICT) {
                    try {
                        // 当前下单载荷解析出的业务命令，用于导出客户身份
                        var order = TradingCommandCodec.decodePlaceOrder(command.payloadUnsafe());
                        clientOrderId = order.clientOrderId();
                        instrumentId = order.instrumentId();
                    } catch (/* 下单载荷参数异常，只有原终态已明确 INVALID_COMMAND 才允许缺少下单身份 */ IllegalArgumentException invalidCommand) {
                        // 畸形帧仍有明确的已提交拒绝结果，但不能提供可信的订单客户号或币对查询身份。
                        if (code != CoreResultCode.INVALID_COMMAND) throw invalidCommand;
                    }
                }
                // 本次批撤最终要导出的不可变逐项结果
                List<CancelBatchItem> cancelResults = List.of();
                if (command.header().messageType() == CoreMessageType.CANCEL_ORDER_BATCH && code != CoreResultCode.IDEMPOTENCY_CONFLICT) {
                    // 原批撤请求项，载荷无效且已有对应拒绝时为空列表
                    List<CancelOrderCommand> requested;
                    try { requested = TradingOrderBatchCodec.decodeCancelOrderBatch(command.payloadUnsafe()).orders(); }
                    catch (/* 批撤载荷参数异常，其他终态与它不一致时继续抛出 */ IllegalArgumentException invalid) {
                        if (code != CoreResultCode.INVALID_COMMAND) throw invalid;
                        requested = List.of();
                    }
                    // 按原请求顺序建立的批撤结果列表
                    var items = new ArrayList<CancelBatchItem>(requested.size());
                    if (status == ResponseStatus.APPLIED && !requested.isEmpty()) {
                        // 解码后的批量终态响应，条数和订单身份须与请求匹配
                        var result = TradingOrderBatchCodec.decodeResult(retained == null ? response.data() : retained.responseData());
                        if (result.items().size() != requested.size()) throw new IllegalStateException("incomplete committed cancel batch");
                        for (var /* 当前解码批撤项，用其原下标定位请求 */ item : result.items()) {
                            // 当前批撤请求或订单帧的业务订单 ID
                            long orderId = requested.get(item.index()).orderId();
                            if (item.orderId() != orderId) throw new IllegalStateException("committed cancel batch identity mismatch");
                            items.add(new CancelBatchItem(item.index(), orderId, item.status() == ResponseStatus.APPLIED, item.resultCode().name()));
                        }
                    } else {
                        for (/* 当前按请求顺序生成或核对的批撤项下标 */ int i = 0; i < requested.size(); i++)
                            items.add(new CancelBatchItem(i, requested.get(i).orderId(), false, code.name()));
                    }
                    cancelResults = List.copyOf(items);
                }
                commandResult = new CommittedCommandResult(state.productLine(), command.header().userId(),
                        command.header().commandId(), command.header().messageType(), clientOrderId, instrumentId,
                        status, code, applied, logPosition, timestamp, cancelResults);
            }
            if (balanceTarget != null && commandResult != null
                    && commandResult.status() == ResponseStatus.APPLIED) {
                // 执行前后资产总额的 BigInteger 差值，零不生成现金流水
                var afterBalance = balanceUnits(balanceTarget.userId(), balanceTarget.asset());
                var delta = afterBalance.subtract(beforeBalance);
                // 重试不再次确认；首次零调账保留建账审计事实，现金投影跳过零差额。
                if (delta.signum() != 0 || command.header().messageType() == CoreMessageType.ADJUST_BALANCE
                        && response != null && response.status() == ResponseStatus.APPLIED)
                    balanceFlow = new CommittedBalanceFlow(state.productLine(), balanceTarget.userId(),
                            command.header().commandId(), balanceTarget.asset(), delta.toString(), afterBalance.toString(),
                            logPosition, timestamp, balanceTarget.transfer());
            }
            if (fundingResult != null) {
                if (response.commandStatus() != ResponseStatus.APPLIED)
                    throw new IllegalStateException("funding payments without an applied committed command");
                fundingPage = new CommittedFundingPage(state.productLine(), logPosition, timestamp,
                        TradingCommandCodec.decodeApplyFunding(command.payloadUnsafe()), fundingResult.progress(),
                        response.resultCode().name(), fundingResult.payments());
                fundingResult = null;
            }
            // 本交割命令在结果账本中的可空最终记录
            var retainedDelivery = delivery == null ? null : state.resultLedger().get(command.header().commandId());
            // 从交割保留结果或直接响应取得的最终状态
            var deliveryStatus = retainedDelivery == null ? response.commandStatus() : retainedDelivery.status();
            if (delivery != null && deliveryStatus == ResponseStatus.APPLIED) {
                // 本交割页实际非零现金变化列表
                var flows = new ArrayList<DeliveryCashFlow>();
                for (var /* 本页某用户执行前保存的结算资产及总额 */ before : deliveryBalances) {
                    // 本页该用户执行后的结算资产总额
                    long total = deliveryBalance(before.userId(), before.asset(), timestamp, logPosition);
                    // 精确相减得到的本页现金差额
                    long amount = Math.subtractExact(total, before.totalUnits());
                    if (amount != 0) flows.add(new DeliveryCashFlow(before.userId(), before.asset(), amount, total));
                }
                if (!flows.isEmpty()) deliveryPage = new CommittedDeliveryPage(state.productLine(), timestamp, delivery, flows);
            }
            capture.commit(state.realtimeExportSequence());
            if (outbox.droppedBatches() != dropped || capture.failures() != failures)
                throw new IllegalStateException(
                        "reliable replay event capture incomplete; checkpoint must not advance");
            // 当前出队并筛选的订单、成交、执行与触发帧列表
            var changes = new ArrayList<RealtimeFrame>();
            // 从本副本 outbox 取出的完整帧字节
            byte[] encoded;
            while ((encoded = outbox.poll()) != null) {
                // 当前筛选或分析的已提交事实帧
                var frame = RealtimeFrameCodec.decode(encoded);
                if (frame.kind() == RealtimeFrame.Kind.TRADE || frame.kind() == RealtimeFrame.Kind.ORDER
                        || frame.kind() == RealtimeFrame.Kind.EXECUTION || frame.kind() == RealtimeFrame.Kind.TRIGGER)
                    changes.add(frame);
            }
            collectAlgoChanges(command, changes);
            return List.copyOf(changes);
        } finally {
            capture.abort();
        }
    }

    /**
     * 读取回放副本当前记录的复制业务时间。
     */
    public long clusterTimestamp() { return state.currentClusterTimestamp(); }

    /**
     * 只为正用户的余额调整、划出和划入解析现金目标；转账端用户须与命令用户相同。参数解析失败不生成辅助目标，原命令仍交权威执行路径决定拒绝。
     * @param command 当前待解析或统计的命令，分页 record 中是对应的已解析业务命令
     */
    private BalanceFlowTarget balanceFlowTarget(CoreMessage command) {
        // 当前命令现金所属用户或内部查询目标
        long user = command.header().userId();
        if (user <= 0) return null;
        try {
            return switch (command.header().messageType()) {
                case ADJUST_BALANCE -> new BalanceFlowTarget(user,
                        com.surprising.aeron.model.AssetBalance.normalizeAsset(
                                TradingCommandCodec.decodeBalanceAdjustment(command.payloadUnsafe()).asset()), null);
                case TRANSFER_OUT, TRANSFER_IN -> {
                    // 当前解析的转账命令或待完成转账事实
                    var transfer = TradingCommandCodec.decodeTransferFunds(command.payloadUnsafe());
                    // 根据划入划出类型选出的实际转账端用户 ID
                    long owner = command.header().messageType() == CoreMessageType.TRANSFER_OUT
                            ? transfer.sourceUserId() : transfer.targetUserId();
                    yield owner == user ? new BalanceFlowTarget(user, transfer.asset(), transfer) : null;
                }
                default -> null;
            };
        } catch (/* 辅助解析中无法取得身份的异常；原命令或其他变化仍按现有路径处理 */ IllegalArgumentException malformed) {
            // 辅助身份解析失败不替代真实执行，原命令路径仍记录对应拒绝。
            return null;
        }
    }

    /**
     * 在既有 Owner 账户访问边界读单资产可用加冻结总额，以 BigInteger 求和；未注册资产或无余额为零，需要访问权时最多等待五秒，只有本次取得的访问权才在 finally 释放。
     * @param user 本次读取或查询的用户 ID
     * @param asset 需要比较的规范资产代码
     */
    private java.math.BigInteger balanceUnits(long user, String asset) {
        // 资产文本对应的可空稠密编号，未注册时本次总额按零处理
        Integer id = state.identities().findAssetId(asset);
        if (id == null) return java.math.BigInteger.ZERO;
        // 本回放副本持有的实际账户运行时
        var runtime = state.runtimeState();
        // 当前账户线程已启动且 Owner 尚无读取访问权
        boolean needsAccess = runtime.accountLanesStarted() && !runtime.ownerLaneAccess();
        // 本次读取是否亲自取得访问权，用于只归还自己的借用
        boolean acquired = false;
        try {
            if (needsAccess) {
                // 当前撮合或内部读取阶段单独计算的纳秒截止时间
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!runtime.tryAcquireOwnerLaneAccess()) {
                    if (System.nanoTime() >= deadline)
                        throw new IllegalStateException("committed balance read timeout; checkpoint must not advance");
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000L);
                }
                acquired = true;
            }
            // 沿已有 Owner 访问边界只读这一笔资产总额，避免用户全状态查询的四千零九十六实体上限挡住普通现金流水。
            // 当前资产的可空余额值或交割查询命中的结算余额
            var balance = runtime.balance(user, id);
            return balance == null ? java.math.BigInteger.ZERO
                    : java.math.BigInteger.valueOf(balance.availableUnits())
                            .add(java.math.BigInteger.valueOf(balance.lockedUnits()));
        } finally {
            if (acquired) runtime.releaseOwnerLaneAccess();
        }
    }

    /**
     * 本次余额或转账命令要比较的用户、资产与可空转账元数据，由辅助解析构造，不另存账户状态。
     * @param userId 当前事实所属正用户 ID
     * @param asset 需要比较的规范资产代码
     * @param transfer 可空转账元数据，用来区分转账方向与用户归属
     */
    private record BalanceFlowTarget(long userId, String asset, TransferFundsCommand transfer) {}

    /**
     * 本次已提交尝试的现金净差额及原始事后总额，使用十进制整数字符串；零调账保留原始完成事实，现金流水消费者跳过零差额。
     * @param product 当前回放或结果事实所属的独立产品线
     * @param userId 当前事实所属正用户 ID
     * @param commandId 本次已提交命令尝试 UUID
     * @param asset 需要比较的规范资产代码
     * @param amountUnits 本次实际现金净差额的十进制整数字符串
     * @param balanceAfterUnits 原命令完成后可用加冻结的精确总额
     * @param clusterPosition 事实所属的正复制日志位置
     * @param occurredAt 事实所属的正业务毫秒时间
     * @param transfer 可空转账元数据，用来区分转账方向与用户归属
     */
    public record CommittedBalanceFlow(ProductLine product, long userId, UUID commandId, String asset,
                                       String amountUnits, String balanceAfterUnits, long clusterPosition, long occurredAt,
                                       TransferFundsCommand transfer) {}

    /**
     * 显式冷查询本回放副本的组合财务状态，先收集待完成转账双方用户供快照判断；不从 apply 或在线 Core 回调自动触发。
     * @param asOf 组合快照要评估的毫秒时点
     * @param maximumMarkAgeMillis 组合快照允许使用的最大标记价年龄
     */
    public CommittedPortfolioSnapshot portfolioSnapshot(long asOf, long maximumMarkAgeMillis) {
        // 组合快照需标记的待完成转账双方用户集合
        var pendingUsers = new HashSet<Long>();
        state.pendingTransfers().values().forEach(/* 当前解析的转账命令或待完成转账事实 */ transfer -> {
            pendingUsers.add(transfer.sourceUserId());
            pendingUsers.add(transfer.targetUserId());
        });
        return CommittedPortfolioSnapshot.capture(state.tradingState(), state.currentClusterTimestamp(),
                asOf, maximumMarkAgeMillis, pendingUsers);
    }

    /**
     * 遍历本副本保留的算法路由，查询实际父单并更新子单索引，再交给消费者；路由存在而状态缺失时报错。
     * @param consumer 接收本副本保留父单视图的消费者
     */
    public void forEachRetainedAlgo(java.util.function.Consumer<CoreAlgoOrderView> consumer) {
        state.algoOrderIndex().forEachRoute(/* 当前保留父单的用户及身份路由 */ route -> {
            // 按保留路由查到的最终父单视图，用来恢复子单路由并交给消费者
            var value = readAlgo(route.userId(), route.algoOrderId());
            if (value == null) throw new IllegalStateException("retained algo route has no state");
            routeAlgoChildren(value);
            consumer.accept(value);
        });
    }

    /**
     * 对本副本执行单父单查询，解码首项视图，空页返回空；查询失败明确报错。
     * @param userId 当前事实所属正用户 ID
     * @param algoId 需要查询的父算法单 ID
     */
    private CoreAlgoOrderView readAlgo(long userId, long algoId) {
        // 直接执行、完成轮询或内部查询返回的响应
        var response = replayQuery(CoreMessageType.ALGO_ORDER_QUERY, userId,
                CoreAlgoOrderCodec.encodeQuery(userId, algoId, "", 0, 1), state.currentClusterTimestamp(), 1);
        if (response.status() != ResponseStatus.OK) throw new IllegalStateException("committed algo query failed");
        return CoreAlgoOrderCodec.decodeList(response.data()).stream().findFirst().orElse(null);
    }

    /**
     * 按父单当前状态更新视图中列出的子单映射，状态代码不大于二时挂路由，终态时移除这些子单映射，不复制订单状态。
     * @param value 用于更新子单路由的最终父单视图
     */
    private void routeAlgoChildren(CoreAlgoOrderView value) {
        // 父状态代码零到二仍需子单进度路由，终态父单对应的已列子单不再接收进度更新。
        for (long /* 当前父单视图列出的一个子订单 ID */ child : value.childOrderIds()) {
            if (value.statusCode() <= 2) algoByChild.put(child, value.algoOrderId());
            else algoByChild.remove(child);
        }
    }

    /**
     * 从成功父单更新、首个立即成交子单客户号及普通订单变化收集脏父单，按首次发现顺序查询最终父视图并更新路由；父修订不变但子单成交也能产生进度变化。
     * @param command 当前待解析或统计的命令，分页 record 中是对应的已解析业务命令
     * @param changes 当前命令已捕获的订单及成交等事实帧
     */
    private void collectAlgoChanges(CoreMessage command, List<RealtimeFrame> changes) {
        // 按首次发现顺序保存的脏父单 ID 到用户映射
        var dirty = new LinkedHashMap<Long, Long>();
        if (command.header().messageType() == CoreMessageType.UPSERT_ALGO_ORDER
                && commandResult != null && commandResult.status() == ResponseStatus.APPLIED) {
            // 成功算法更新载荷中的父单元数据
            var submitted = CoreAlgoOrderCodec.decodeCommand(command.payloadUnsafe());
            dirty.put(submitted.algoOrderId(), command.header().userId());
        }
        // 首子单可能尚未挂父路由就已成交，因此复用已解析的下单收据身份，避免每条普通订单帧都重复解析客户身份。
        // 最近下单收据提供的可空客户号，首子单可从算法格式识别父身份
        String client = commandResult == null ? null : commandResult.clientOrderId();
        if (client != null && client.startsWith("algo-") && commandResult.status() == ResponseStatus.APPLIED
                && changes.stream().anyMatch(/* 当前筛选或分析的已提交事实帧 */ frame -> {
                    if (frame.kind() != RealtimeFrame.Kind.ORDER || frame.userId() != commandResult.userId()) return false;
                    // 当前订单事实解码出的子单视图，核对客户号与非 OPEN 状态
                    var child = CoreStateQueryCodec.decodeOrderState(frame.payload());
                    return client.equals(child.clientOrderId()) && !"OPEN".equals(child.status());
                })) {
            // 算法客户号最后一个分隔符位置
            int separator = client.lastIndexOf('-');
            if (separator > 5) try {
                // 从算法客户号中解析出的父单 ID，随后核对路由用户
                long id = Long.parseLong(client.substring(5, separator));
                // 当前保留父单的用户及身份路由
                var route = state.algoOrderIndex().value(id);
                if (route != null && route.userId() == commandResult.userId()) dirty.put(id, route.userId());
            } catch (/* 辅助解析中无法取得身份的异常；原命令或其他变化仍按现有路径处理 */ NumberFormatException malformed) { /* 普通客户号不提供可识别的算法父身份，保持其他订单变化处理。 */ }
        }
        for (var /* 当前筛选或分析的已提交事实帧 */ frame : changes) {
            if (frame.kind() != RealtimeFrame.Kind.ORDER) continue;
            // 当前批撤请求或订单帧的业务订单 ID
            long orderId = Long.parseLong(frame.entityId());
            // 当前订单变化从子单索引查到的可空父算法 ID
            Long parent = algoByChild.get(orderId);
            if (parent != null) dirty.put(parent, frame.userId());
        }
        if (dirty.isEmpty()) return;
        // 重新查询并收集的最终脏父单视图
        var values = new ArrayList<CoreAlgoOrderView>(dirty.size());
        dirty.forEach((/* 本轮需要重新导出进度的父算法单 ID */ id, /* 该父单路由对应的用户 ID */ user) -> {
            // 重新查询的最终父单视图，收集前先同步它的子单路由
            var value = readAlgo(user, id);
            if (value != null) {
                routeAlgoChildren(value);
                values.add(value);
            }
        });
        algoChanges = List.copyOf(values);
    }

    /**
     * 启动重建时分页查询本副本保留的触发记录，校验游标和一至五百一十二的页大小；本次内部查询使用当前系统毫秒时间，不从在线 Core 取数据。
     * @param before 触发记录分页的非负上界游标
     * @param limit 本页条数，一至五百一十二
     */
    public List<CoreTriggerOrderStateView> retainedTriggersBefore(long before, int limit) {
        if (before < 0 || limit < 1 || limit > 512) throw new IllegalArgumentException("invalid retained trigger page");
        // 当前保留触发查询使用的系统毫秒时间
        long now = System.currentTimeMillis();
        return CoreTriggerOrderCodec.decodeList(replayQuery(CoreMessageType.TRIGGER_ORDER_QUERY, 0,
                CoreTriggerOrderCodec.encodeQuery(new CoreTriggerOrderQuery(0, "", before, limit)), now, 1).data());
    }

    /**
     * 仅为交割产品的 SETTLE_INSTRUMENT 尝试解析辅助交割输入，解析运行时异常返回空，原命令仍经实际回放入口处理。
     * @param message 当前已解码原命令
     */
    private SettleInstrumentCommand deliveryCommand(CoreMessage message) {
        if (!state.productLine().isDeliveryProduct() || message.header().messageType() != CoreMessageType.SETTLE_INSTRUMENT) return null;
        try { return TradingCommandCodec.decodeSettleInstrument(message.payloadUnsafe()); }
        catch (/* 辅助解析中无法取得身份的异常；原命令或其他变化仍按现有路径处理 */ RuntimeException malformed) { return null; } // 原命令仍由实际回放执行路径记录明确拒绝。
    }

    /**
     * 沿结算命令同一用户游标分批查询本页受影响用户，保存结算资产及可用加冻结总额；页大小最多三十二，拒绝不前进或有后续却为空的分页。
     * @param command 当前待解析或统计的命令，分页 record 中是对应的已解析业务命令
     * @param timestamp 该日志项原复制业务时间
     * @param position 本副本内部读取使用的日志位置上下文
     */
    private List<BeforeDeliveryBalance> deliveryBalances(SettleInstrumentCommand command, long timestamp, long position) {
        // 本回放副本持有的实际账户运行时
        var runtime = state.runtimeState();
        // 本副本中交割合约的可空注册定义
        var instrument = runtime.instrument(command.instrumentId());
        if (instrument == null) return List.of();
        // 本页结算前需保存的用户总额标量列表
        var balances = new ArrayList<BeforeDeliveryBalance>();
        // 与结算命令共用且逐页前进的用户游标
        long cursor = command.cursorUserId();
        while (balances.size() < command.maxUsers()) {
            // 本副本维护查询返回的用户分页
            var page = CoreMaintenanceCodec.decodePage(replayQuery(CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY, 0,
                    CoreMaintenanceCodec.encodeQuery(new CoreMaintenanceCodec.Query(command.instrumentId(), cursor,
                            Math.min(32, command.maxUsers() - balances.size()))), timestamp, position).data());
            for (long /* 当前命令现金所属用户或内部查询目标 */ user : page.userIds()) {
                if (user <= cursor) throw new IllegalStateException("nonadvancing committed delivery user page");
                balances.add(new BeforeDeliveryBalance(user, instrument.settleAsset(), deliveryBalance(user, instrument.settleAsset(), timestamp, position)));
                cursor = user;
            }
            if (!page.hasMore()) break;
            if (page.userIds().isEmpty()) throw new IllegalStateException("empty committed delivery user cursor");
        }
        return List.copyOf(balances);
    }

    /**
     * 通过本副本的用户状态查询读取结算资产总额，核对产品和用户身份并要求余额存在，再用精确加法合计可用与冻结。
     * @param user 本次读取或查询的用户 ID
     * @param asset 需要比较的规范资产代码
     * @param timestamp 该日志项原复制业务时间
     * @param position 本副本内部读取使用的日志位置上下文
     */
    private long deliveryBalance(long user, String asset, long timestamp, long position) {
        // 本副本用户状态查询返回的已解码视图
        var view = CoreStateQueryCodec.decodeUserState(replayQuery(CoreMessageType.USER_STATE_QUERY, user, new byte[0], timestamp, position).data());
        if (view.userId() != user || view.productLine() != state.productLine())
            throw new IllegalStateException("committed delivery account identity mismatch");
        // 当前资产的可空余额值或交割查询命中的结算余额
        var balance = view.balances().stream().filter(/* 当前被筛选的用户资产余额，要求是本页结算资产 */ value -> value.asset().equals(asset)).findFirst()
                .orElseThrow(() -> new IllegalStateException("delivery position has no settlement balance"));
        return Math.addExact(balance.availableUnits(), balance.lockedUnits());
    }

    /**
     * 只在本回放副本构造并执行查询；内部盘口异步结果最多等五秒并检查健康，最终必须 OK 且回调边界干净，不访问在线集群。
     * @param type 本副本内部只读查询类型
     * @param user 本次读取或查询的用户 ID
     * @param payload 与查询类型配对的已编码载荷
     * @param timestamp 该日志项原复制业务时间
     * @param position 本副本内部读取使用的日志位置上下文
     */
    private CoreResponse replayQuery(CoreMessageType type, long user, byte[] payload, long timestamp, long position) {
        // 只对本副本构造的内部查询消息
        var request = new CoreMessage(CoreMessageHeader.query(type, UUID.randomUUID(), state.productLine(),
                CommandSource.OPERATIONS, 0, timestamp, user, timestamp, timestamp), payload);
        // 直接执行、完成轮询或内部查询返回的响应
        var response = state.apply(request, timestamp, position);
        if (response.resultCode() == CoreResultCode.MATCHING_PENDING) {
            // 当前内部异步查询的序号，只从该序号接回查询结果
            var id = request.header().commandId();
            // 当前撮合或内部读取阶段单独计算的纳秒截止时间
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((response = state.bookQueries.takeQueryResult(id)) == null) {
                state.assertHealthy();
                if (System.nanoTime() >= deadline) throw new IllegalStateException("committed delivery read timeout; checkpoint must not advance");
                java.util.concurrent.locks.LockSupport.parkNanos(1_000L);
            }
        }
        if (response.status() != ResponseStatus.OK) throw new IllegalStateException("committed delivery read failed: " + response.resultCode());
        state.assertClusterCallbackComplete();
        return response;
    }

    /**
     * 本页交割执行前保存的用户、结算资产与总额三个标量，供同页完成后计算差额。
     * @param userId 当前事实所属正用户 ID
     * @param asset 需要比较的规范资产代码
     * @param totalUnits 当前资产可用加冻结的原总额
     */
    private record BeforeDeliveryBalance(long userId, String asset, long totalUnits) {}

    /**
     * 独立回放中当前有界交割页观察到的现金变化，列表复制后对外提供，不是在线账户状态副本。
     * @param product 当前回放或结果事实所属的独立产品线
     * @param occurredAt 事实所属的正业务毫秒时间
     * @param command 本页已执行的交割结算命令，用户游标与现金项共用它的预算
     * @param cashFlows 本交割页非空且用户不重复的现金变化列表
     */
    public record CommittedDeliveryPage(ProductLine product, long occurredAt,
                                         SettleInstrumentCommand command, List<DeliveryCashFlow> cashFlows) {
        /**
         * 复制现金项列表，校验交割产品、正时间、非空命令及受本页用户预算约束的非空项数，并拒绝重复用户；只校验已有事实，不执行结算。
         */
        public CommittedDeliveryPage {
            cashFlows = List.copyOf(cashFlows);
            if (product == null || !product.isDeliveryProduct() || occurredAt <= 0
                    || command == null || cashFlows.isEmpty() || cashFlows.size() > command.maxUsers())
                throw new IllegalArgumentException("invalid committed delivery cash page");
            // 本交割页已出现用户集合，拒绝重复现金项
            var users = new HashSet<Long>();
            for (var /* 当前校验用户唯一性的交割现金项 */ flow : cashFlows) if (!users.add(flow.userId()))
                throw new IllegalArgumentException("duplicate delivery cash user");
        }
    }

    /**
     * 一个交割用户在本页实际发生的非零结算现金差额及结算后总额。
     * @param userId 当前事实所属正用户 ID
     * @param asset 需要比较的规范资产代码
     * @param amountUnits 本页结算实际增减的非零最小单位数，负数表示支出
     * @param balanceAfterUnits 交割完成后的非负可用加冻结总额
     */
    public record DeliveryCashFlow(long userId, String asset, long amountUnits, long balanceAfterUnits) {
        /**
         * 要求正用户、非空资产、非零差额和非负结算后总额；负差额表示实际支出，不在这里归一化资产文本。
         */
        public DeliveryCashFlow {
            if (userId <= 0 || asset == null || asset.isBlank() || amountUnits == 0 || balanceAfterUnits < 0)
                throw new IllegalArgumentException("invalid delivery cash flow");
        }
    }

    /**
     * 读取回放副本当前实时导出序号。
     */
    public long exportSequence() {
        return state.realtimeExportSequence();
    }

    /**
     * 要求副本回调工作已结束，再生成当前交易活动摘要。
     * @param now 活动摘要的当前毫秒观测时点
     */
    public com.surprising.aeron.service.query.state.CommittedTradingActivity currentTradingActivity(long now) {
        state.assertClusterCallbackComplete();
        return com.surprising.aeron.service.query.state.CommittedTradingActivity.capture(state.runtimeState(), state.identities(), now);
    }

    /**
     * 要求本副本回调工作已结束，再交真实快照入口编码恢复状态。
     */
    public byte[] snapshot() {
        state.assertClusterCallbackComplete();
        return state.snapshot();
    }

    /**
     * 读取本副本用于快照配对的业务哈希。
     */
    public long businessHash() {
        return state.snapshotBusinessStateHash();
    }

    /**
     * 关闭独立回放运行时及其资源，不关闭或操纵在线交易 Core。
     */
    @Override
    public void close() {
        state.close();
    }

    /**
     * 当前已提交资金费命令对应的进度和支付事实，列表属于本页结果；不重新计算资金费。
     * @param product 当前回放或结果事实所属的独立产品线
     * @param clusterPosition 事实所属的正复制日志位置
     * @param occurredAt 事实所属的正业务毫秒时间
     * @param command 本页已执行的资金费命令，支付记录必须与其结算身份及费率一致
     * @param progress 与资金费命令结算 ID 相同的完成进度
     * @param resultCode 资金费命令当前终态码文本
     * @param payments 与当前资金费结算 ID、币对和费率匹配的支付列表
     */
    public record CommittedFundingPage(ProductLine product, long clusterPosition, long occurredAt,
                                       ApplyFundingCommand command, CoreFundingProgressView progress,
                                       String resultCode, List<CoreFundingPaymentView> payments) {
        /**
         * 校验支持资金费的产品、正日志元数据及命令和进度配对，复制支付列表后逐项核对结算 ID、币对及费率属于当前命令。
         */
        public CommittedFundingPage {
            if (product == null || !product.isFundingProduct() || clusterPosition <= 0 || occurredAt <= 0
                    || command == null || progress == null || progress.settlementId() != command.settlementId()
                    || resultCode == null || payments == null) throw new IllegalArgumentException("invalid committed funding page");
            payments = List.copyOf(payments);
            for (var /* 当前核对结算 ID、币对和费率归属的资金费支付事实 */ payment : payments) {
                if (payment.settlementId() != command.settlementId()
                        || !payment.instrumentId().equals(command.instrumentId())
                        || payment.fundingRatePpm() != command.fundingRatePpm())
                    throw new IllegalArgumentException("funding payment does not belong to committed command");
            }
        }
    }

    /**
     * 一次已提交命令尝试的最终导出收据，含可空下单身份及有界批撤逐项结果；冲突尝试被拒绝时不借用原成功收据。
     * @param product 当前回放或结果事实所属的独立产品线
     * @param userId 当前事实所属正用户 ID
     * @param commandId 本次已提交命令尝试 UUID
     * @param commandType 本次普通 COMMAND 类型
     * @param clientOrderId 可空下单客户号，拒单仍可由合法载荷提供
     * @param instrumentId 可空下单币对标识
     * @param status 本次尝试的 APPLIED 或 REJECTED 终态
     * @param code 本次命令或批撤项的终态结果码
     * @param appliedCommandCount 最终收据保存的应用命令计数，不是回放条数
     * @param clusterPosition 事实所属的正复制日志位置
     * @param occurredAt 事实所属的正业务毫秒时间
     * @param cancelResults 仅批撤携带的连续下标逐项结果列表
     */
    public record CommittedCommandResult(ProductLine product, long userId, UUID commandId,
                                         CoreMessageType commandType, String clientOrderId, String instrumentId,
                                         ResponseStatus status, CoreResultCode code, long appliedCommandCount,
                                         long clusterPosition, long occurredAt, List<CancelBatchItem> cancelResults) {
        /**
         * 复制批撤项并检查最多五十项、仅用于批撤且下标连续；命令必须为 COMMAND，收据只能是已应用或拒绝，不能仍为 MATCHING_PENDING，身份及日志元数据必须有效。
         */
        public CommittedCommandResult {
            cancelResults = List.copyOf(cancelResults);
            if (cancelResults.size() > 50 || (!cancelResults.isEmpty() && commandType != CoreMessageType.CANCEL_ORDER_BATCH))
                throw new IllegalArgumentException("invalid committed cancellation items");
            for (/* 当前按请求顺序生成或核对的批撤项下标 */ int i = 0; i < cancelResults.size(); i++)
                if (cancelResults.get(i).index() != i) throw new IllegalArgumentException("noncontiguous cancellation items");
            if (product == null || userId <= 0 || commandId == null || commandType == null
                    || commandType.kind() != WireMessageKind.COMMAND
                    || (status != ResponseStatus.APPLIED && status != ResponseStatus.REJECTED) || code == null
                    || code == CoreResultCode.MATCHING_PENDING || appliedCommandCount < 0
                    || clusterPosition <= 0 || occurredAt <= 0)
                throw new IllegalArgumentException("invalid committed command result");
        }
    }

    /**
     * 单个批撤请求的确定性结果，只有请求下标、订单身份、成功标记与结果码，不复制完整订单。
     * @param index 批撤请求中的非负逐项下标
     * @param orderId 该批撤项请求的正订单 ID
     * @param success 本项是否成功，必须与结果码 NONE 等价
     * @param code 本次命令或批撤项的终态结果码
     */
    public record CancelBatchItem(int index, long orderId, boolean success, String code) {
        /**
         * 校验非负项下标、正订单 ID 和可识别终态结果码；成功当且仅当码为 NONE，不允许保留 MATCHING_PENDING。
         */
        public CancelBatchItem {
            if (index < 0 || orderId <= 0 || code == null || code.isBlank()
                    || CoreResultCode.valueOf(code) == CoreResultCode.MATCHING_PENDING
                    || success != (CoreResultCode.valueOf(code) == CoreResultCode.NONE))
                throw new IllegalArgumentException("invalid cancellation result item");
        }
    }
}
