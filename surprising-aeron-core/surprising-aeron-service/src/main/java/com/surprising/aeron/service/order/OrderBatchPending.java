package com.surprising.aeron.service.order;

import com.surprising.aeron.protocol.CoreOrderStateSource;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.service.matcher.CoreCancellationResult;
import com.surprising.aeron.service.matcher.MatchingResult;
import com.surprising.aeron.service.order.admission.PlaceBatchAdmissionEvent;
import com.surprising.aeron.service.order.admission.PlaceBatchIntentSource;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.command.DecodedMatchingCommand;
import com.surprising.aeron.service.order.command.OrderBatchKind;
import com.surprising.aeron.service.order.state.LaneOrderResultTarget;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.result.ResponseArena;
import com.surprising.aeron.support.collection.PrimitiveLongChangeSet;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * 一条批量命令从准入到终态的复用上下文。它把各项输入、准入回执、撮合与账户结果、资金差额和分页游标放在同一生命周期里；Owner 管理顺序，Matcher 和账户按交接约定使用固定槽。结果编码直接读取本批，不另建同内容的订单结果表，整批退休后才清理复用。
 */
public final class OrderBatchPending implements LaneOrderResultTarget, PlaceBatchIntentSource, TradingOrderBatchCodec.ResultSource, CoreOrderStateSource {
    /**
     * 按批内下标读取 PLACE 输入；仅在下单批次和有效下标范围使用。
     * @param index 本方法使用的有效批内下标，撤单 Matcher 回执入口使用当前片段局部下标
     */
    public com.surprising.aeron.protocol.PlaceOrderCommand intent(int index) {
        return (com.surprising.aeron.protocol.PlaceOrderCommand) items.get(index).command;
    }
    /**
     * 借用该项已经准备的产品判断输入，账户准入前由 Owner 填写。
     * @param index 本方法使用的有效批内下标，撤单 Matcher 回执入口使用当前片段局部下标
     */
    public PlaceBatchIntentSource.Decision decision(int index) { return preparedDecisions[index]; }
    /** 每项可复用的解析订单槽，借用时才创建，整批退休后清理内容。 */
    private final ResolvedPlaceOrder[] resolvedOrderSlots;
    /**
     * 按有效下标取得本项可复用的解析订单槽，首次使用才创建；调用方遵守当前阶段的独占写权限。
     * @param index 本方法使用的有效批内下标，撤单 Matcher 回执入口使用当前片段局部下标
     */
    public ResolvedPlaceOrder resolvedOrderSlot(int index) {
        // 当前下标可复用的解析订单槽，首次使用前为空
        var slot = resolvedOrderSlots[index];
        if (slot == null) resolvedOrderSlots[index] = slot = new ResolvedPlaceOrder();
        return slot;
    }
    /**
     * 把账户准入订单捕获到对应项的固定副本，返回该份可借用回执，整批退休前保留。
     * @param index 本方法使用的有效批内下标，撤单 Matcher 回执入口使用当前片段局部下标
     * @param order 当前由账户提供的可空准入或结果订单
     */
    public OrderRuntime captureAdmittedOrder(int index, OrderRuntime order) {
        // 该项持有准入副本直到整批退休；Matcher 与实时输出共用这份回执，不再重复捕获。
        // 当前被捕获、检查、编码或清理的批内项
        OrderBatchItem item = items.get(index);
        item.captureAdmittedOrder(order);
        return item.admittedOrder;
    }

    /** 每项引用的产品判断输入，可指向同品种共享上下文。 */
    final com.surprising.aeron.service.order.admission.PlaceBatchIntentSource.Decision[] preparedDecisions;
    /**
     * 返回需要生成 Lane 结果的批内项数。
     */
    public int resultCount() { return items.size(); }
    /**
     * 返回指定项的结果订单编号。
     * @param index 本方法使用的有效批内下标，撤单 Matcher 回执入口使用当前片段局部下标
     */
    public long resultOrderId(int index) { return items.get(index).orderId(); }
    /**
     * 返回指定项的改单原订单编号。
     * @param index 本方法使用的有效批内下标，撤单 Matcher 回执入口使用当前片段局部下标
     */
    public long resultOriginalOrderId(int index) { return items.get(index).originalOrderId(); }
    /**
     * 捕获本项可空的写后订单并保存品种名称，最后标记该项结果已准备；空订单同样是有效结果。
     * @param index 本方法使用的有效批内下标，撤单 Matcher 回执入口使用当前片段局部下标
     * @param order 当前由账户提供的可空准入或结果订单
     * @param instrumentId 结果订单对应的品种名称
     */
    public void resultOrder(int index, OrderRuntime order, String instrumentId) {
        // 当前被捕获、检查、编码或清理的批内项
        var item = items.get(index);
        item.captureResultOrder(order);
        item.resultOrderSymbol = instrumentId;
        item.laneResultPrepared = true;
    }
    /**
     * 要求所属 Lane 也收集未变化或拒绝项，避免 Owner 另查发布表；终态撤单和改单是否包含退休后像仍由结果目标既有规则决定。
     */
    @Override public boolean captureUnchangedResults() {
        // 各项由所属账户提供结果，未变化或拒绝项也如此，避免 Owner 再查发布表。
        // 终态撤单和改单仍按结果目标规则决定是否保留退休后像。
        return true;
    }
    /**
     * 返回指定项是否已经取得所属 Lane 的结果。
     * @param index 本方法使用的有效批内下标，撤单 Matcher 回执入口使用当前片段局部下标
     */
    @Override public boolean resultPrepared(int index) {
        return items.get(index).laneResultPrepared;
    }
    /**
     * 按 Matcher 接受与否填写单项状态；撤单片段的局部下标先加当前片段起点，映射到整批项。
     * @param index 本方法使用的有效批内下标，撤单 Matcher 回执入口使用当前片段局部下标
     * @param accepted Matcher 是否接受当前项
     */
    @Override public void matcherResult(int index, boolean accepted) {
        // 当前片段局部下标换算后的整批下标
        int itemIndex = kind == OrderBatchKind.CANCEL ? cancellationChunkStart + index : index;
        // 当前被捕获、检查、编码或清理的批内项
        OrderBatchItem item = items.get(itemIndex);
        item.status = accepted ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        item.resultCode = accepted ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
    }
    /**  完成交接后可读的响应字节缓冲，有效范围由长度限定。 借用已编码响应缓冲，完成交接后才可读，实际有效长度见 preparedResponseLength。 */
    @Accessors(fluent = true, chain = false)
    @Getter(onMethod_ = {@Override})
    byte[] preparedResponse;
    /**  响应缓冲已编码的有效长度。 返回缓冲中已经编码的有效字节长度，不等于缓冲容量。 */
    @Accessors(fluent = true, chain = false)
    @Getter(onMethod_ = {@Override})
    int preparedResponseLength;
    /** 创建本批时指定的响应槽分配器。 */
    private final ResponseArena responseArena;
    /** 本批尚未移交或归还的可空响应槽租约。 */
    private ResponseArena.Slot preparedResponseSlot;
    /**
     * previousBatch：仅由 Owner 访问的前一批排序链接，入池前由外层摘除。
     * nextBatch：仅由 Owner 访问的后一批排序链接，Matcher 和账户不读取。
     */
    OrderBatchPending previousBatch, nextBatch;

    /**
     * 只有全部项均有 Lane 结果和状态才重编码，未齐直接返回；先归还旧租约并清响应引用，再借响应槽编码。编码异常归还新槽并继续抛出，最终解除临时编码游标。
     */
    @Override public void prepareResponse() {
        // 部分拒单、顺序改单可能没有本次 Lane 结果，仍需提交点补齐其查询语义。
        for (OrderBatchItem item : items) if (!item.laneResultPrepared || item.status == null) return;
        if (preparedResponseSlot != null) {
            responseArena.release(preparedResponseSlot);
            preparedResponseSlot = null;
        }
        preparedResponse = null;
        preparedResponseLength = 0;
        try {
            // 编码全部结果需要的字节数
            int length = TradingOrderBatchCodec.encodedResultSourceLength(this);
            preparedResponseSlot = responseArena.acquireSlot(length);
            // 实际写入响应槽的有效字节数
            int encodedLength = TradingOrderBatchCodec.encodeResultSourceInto(
                    this, preparedResponseSlot.storage(), 0);
            preparedResponse = preparedResponseSlot.storage();
            preparedResponseLength = encodedLength;
        } catch (/* 重编码过程中抛出的运行异常或错误，释放本批新租约后继续抛出 */ RuntimeException | Error failure) {
            if (preparedResponseSlot != null) responseArena.release(preparedResponseSlot);
            preparedResponseSlot = null;
            throw failure;
        } finally {
            responseItem = null;
        }
    }

    /**
     * 解除本批对响应槽租约的持有，由终态响应接管；字节引用保留到清批，不能再由本批归还同一槽。
     */
    void transferPreparedResponseOwnership() {
        preparedResponseSlot = null;
    }

    /**
     * 返回本批是否还持有需要释放或移交的响应槽租约。
     */
    boolean hasPreparedResponseSlot() {
        return preparedResponseSlot != null;
    }

    /**
     * 返回批内实际项数，供结果编码器读取。
     */
    public int size() { return items.size(); }
    /**
     * 返回指定项的结果订单编号，改单为新编号。
     * @param index 本方法使用的有效批内下标，撤单 Matcher 回执入口使用当前片段局部下标
     */
    public long orderId(int index) { return items.get(index).orderId; }
    /**
     * 返回指定项的原单编号，非改单通常为零。
     * @param index 本方法使用的有效批内下标，撤单 Matcher 回执入口使用当前片段局部下标
     */
    public long originalOrderId(int index) { return items.get(index).originalOrderId; }
    /**
     * 返回指定项的替换后编号，非改单通常为零。
     * @param index 本方法使用的有效批内下标，撤单 Matcher 回执入口使用当前片段局部下标
     */
    public long replacementOrderId(int index) { return items.get(index).replacementOrderId; }
    /**
     * 返回指定项已决定的响应状态。
     * @param index 本方法使用的有效批内下标，撤单 Matcher 回执入口使用当前片段局部下标
     */
    public ResponseStatus status(int index) { return items.get(index).status; }
    /**
     * 返回指定项的确定性结果码。
     * @param index 本方法使用的有效批内下标，撤单 Matcher 回执入口使用当前片段局部下标
     */
    public CoreResultCode resultCode(int index) { return items.get(index).resultCode; }
    /** 编码器当前选中的临时项游标，不跨线程，不随响应保存。 */
    private OrderBatchItem responseItem;
    /**
     * 把临时编码游标移到指定项；有结果订单时返回本对象作为只读源，没有订单则返回空。该游标不能跨线程或逃逸到响应缓存。
     * @param index 本方法使用的有效批内下标，撤单 Matcher 回执入口使用当前片段局部下标
     */
    public com.surprising.aeron.protocol.CoreOrderStateSource order(int index) {
        responseItem = items.get(index);
        return responseItem.resultOrder == null ? null : this;
    }
    /**
     * 返回当前编码游标选中订单的订单编号；调用方须先选择有订单的项。
     */
    public long orderId() { return responseItem.resultOrder.orderId(); }
    /**
     * 返回当前编码游标选中订单的产品线；调用方须先选择有订单的项。
     */
    public com.surprising.product.api.ProductLine productLine() { return responseItem.resultOrder.productLine(); }
    /**
     * 返回当前编码游标选中订单的所属用户编号；调用方须先选择有订单的项。
     */
    public long userId() { return responseItem.resultOrder.userId(); }
    /**
     * 返回当前编码游标选中订单的已保存的品种名称；调用方须先选择有订单的项。
     */
    public String instrumentId() { return responseItem.resultOrderSymbol; }
    /**
     * 返回当前编码游标选中订单的买卖方向；调用方须先选择有订单的项。
     */
    public com.surprising.aeron.protocol.CoreOrderSide side() { return responseItem.resultOrder.side(); }
    /**
     * 返回当前编码游标选中订单的委托价格刻度；调用方须先选择有订单的项。
     */
    public long priceTicks() { return responseItem.resultOrder.priceTicks(); }
    /**
     * 返回当前编码游标选中订单的委托总数量步数；调用方须先选择有订单的项。
     */
    public long quantitySteps() { return responseItem.resultOrder.quantitySteps(); }
    /**
     * 返回当前编码游标选中订单的已成交数量步数；调用方须先选择有订单的项。
     */
    public long executedQuantitySteps() { return responseItem.resultOrder.executedQuantitySteps(); }
    /**
     * 返回当前编码游标选中订单的剩余数量步数；调用方须先选择有订单的项。
     */
    public long remainingQuantitySteps() { return responseItem.resultOrder.remainingQuantitySteps(); }
    /**
     * 返回当前编码游标选中订单的是否只允许减少持仓；调用方须先选择有订单的项。
     */
    public boolean reduceOnly() { return responseItem.resultOrder.reduceOnly(); }
    /**
     * 返回当前编码游标选中订单的全仓或逐仓模式；调用方须先选择有订单的项。
     */
    public com.surprising.aeron.protocol.CoreMarginMode marginMode() { return responseItem.resultOrder.marginMode(); }
    /**
     * 返回当前编码游标选中订单的净持仓、做多或做空方向；调用方须先选择有订单的项。
     */
    public com.surprising.aeron.protocol.CorePositionSide positionSide() { return responseItem.resultOrder.positionSide(); }
    /**
     * 返回当前编码游标选中订单的订单类别；调用方须先选择有订单的项。
     */
    public com.surprising.aeron.protocol.CoreOrderType orderType() { return responseItem.resultOrder.orderType(); }
    /**
     * 返回当前编码游标选中订单的订单有效期规则；调用方须先选择有订单的项。
     */
    public com.surprising.aeron.protocol.CoreTimeInForce timeInForce() { return responseItem.resultOrder.timeInForce(); }
    /**
     * 返回当前编码游标选中订单的是否只挂单；调用方须先选择有订单的项。
     */
    public boolean postOnly() { return responseItem.resultOrder.postOnly(); }
    /**
     * 返回当前编码游标选中订单的客户端订单号；调用方须先选择有订单的项。
     */
    public String clientOrderId() { return responseItem.resultOrder.clientOrderId(); }
    /**
     * 返回当前编码游标选中订单的原命令 UUID；调用方须先选择有订单的项。
     */
    public java.util.UUID commandId() { return responseItem.resultOrder.commandId(); }
    /**
     * 返回当前编码游标选中订单的挂单手续费率，单位为百万分之一；调用方须先选择有订单的项。
     */
    public long makerFeeRatePpm() { return responseItem.resultOrder.makerFeeRatePpm(); }
    /**
     * 返回当前编码游标选中订单的吃单手续费率，单位为百万分之一；调用方须先选择有订单的项。
     */
    public long takerFeeRatePpm() { return responseItem.resultOrder.takerFeeRatePpm(); }
    /**
     * 返回当前编码游标选中订单的累计已扣手续费最小单位数；调用方须先选择有订单的项。
     */
    public long cumulativeFeeUnits() { return responseItem.resultOrder.cumulativeFeeUnits(); }
        /**
         * 返回当前编码游标选中订单的累计成交价值的高位部分；调用方须先选择有订单的项。
         */
        public long executedValueHigh() { return responseItem.resultOrder.executedValueHigh(); }
        /**
         * 返回当前编码游标选中订单的累计成交价值的低位部分；调用方须先选择有订单的项。
         */
        public long executedValueLow() { return responseItem.resultOrder.executedValueLow(); }
    /**
     * 返回当前编码游标选中订单的创建业务时间，单位毫秒；调用方须先选择有订单的项。
     */
    public long createdAtEpochMillis() { return responseItem.resultOrder.createdAtEpochMillis(); }
    /**
     * 返回当前编码游标选中订单的最近更新业务时间，单位毫秒；调用方须先选择有订单的项。
     */
    public long updatedAtEpochMillis() { return responseItem.resultOrder.updatedAtEpochMillis(); }
    /**
     * 返回当前编码游标选中订单的最近更新的复制日志位置；调用方须先选择有订单的项。
     */
    public long clusterPosition() { return responseItem.resultOrder.clusterPosition(); }
    /**
     * 返回当前编码游标选中订单的订单状态名称；调用方须先选择有订单的项。
     */
    public String status() { return responseItem.resultOrder.status().name(); }
    /**
     * 返回当前编码游标选中订单的订单修订号；调用方须先选择有订单的项。
     */
    public long revision() { return responseItem.resultOrder.revision(); }

    /**
     * 返回指定项已统计的成交事件数，编码器用它计算所需长度。
     * @param index 本方法使用的有效批内下标，撤单 Matcher 回执入口使用当前片段局部下标
     */
    public int executionCount(int index) { return items.get(index).executionCount; }
    /**
     * 只编码指定项中的成交事件，按主动单、被动单、双方用户、价格和数量的固定顺序写入；其他 Matcher 事件跳过。
     * @param index 本方法使用的有效批内下标，撤单 Matcher 回执入口使用当前片段局部下标
     * @param output 已按批量结果长度准备好的目标字节缓冲
     */
    public void writeExecutions(int index, java.nio.ByteBuffer output) {
        // 当前被捕获、检查、编码或清理的批内项
        OrderBatchItem item = items.get(index);
        for (/* 当前编码的本项 Matcher 事件下标 */ int eventIndex = 0; eventIndex < item.executionEvents.size(); eventIndex++) {
            // 当前 Matcher 事件，只对成交写执行明细
            MatcherEvent event = item.executionEvents.get(eventIndex);
            if (event.eventType() != MatcherEventType.TRADE) continue;
            output.putLong(item.orderId).putLong(event.matchedOrderId())
                    .putLong(item.executionTakerUserId).putLong(event.matchedOrderUid())
                    .putLong(event.price()).putLong(event.size());
        }
    }

    /** 本批下单、撤单或改单类别。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    OrderBatchKind kind;
    /** 已解码的整批输入及稳定路由缓存。 */
    DecodedMatchingCommand decodedCommand;
    /**  本轮有效项列表，元素来自固定项槽。 借用本批项列表，不复制；各线程只能在约定交接边界访问对应输入或结果。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final ArrayList<OrderBatchItem> items;
    /** 跨批保留的固定项对象数组，所有借用结束后才允许复用。 */
    private final OrderBatchItem[] itemSlots;
    /** 整批原命令固定的 Cluster 业务时间。 */
    long clusterTimestamp;
    /** 整批原命令固定的复制日志位置。 */
    long clusterPosition;
    /** 本批共用的撮合操作类别。 */
    CommandSlot.Operation operation;
    /** 本批已发现的变化用户原语去重缓冲。 */
    final PrimitiveLongChangeSet changedUserIds;
    /** 本批实际变化订单原语去重缓冲，最终提交统一盖戳。 */
    final PrimitiveLongChangeSet runtimeChangedOrderIds;
    /** 每个预备客户身份对应的用户列，只读有效前缀。 */
    final long[] preparedClientUsers;
    /** 预备客户身份对应的客户端订单号引用列。 */
    final String[] preparedClientIds;
    /** 失败时需要退回的预备客户身份键列。 */
    final long[] preparedClientKeys;
    /** 预备客户身份三列中的有效前缀长度。 */
    int preparedClientKeyCount;
    /** 跨子项累积的财库差额对象，清批时清内容并保留容量。 */
    com.surprising.aeron.service.treasury.RuntimeTreasuryDelta treasuryDelta;
    /**
     *  下一条要准入或处理的批内项下标。
     * 保存调用方推进后的批内项游标，不在此验证范围或完成账户工作。
     * @param nextIndex 调用方推进后的批内下标
     */
    @Accessors(fluent = true, chain = false)
    @Setter
    int nextIndex;
    /** 本批已经统计的成交事件总数。 */
    long tradeCount;
    /** 当前撤单片段在整批中的起点，包含此项。 */
    int cancellationChunkStart;
    /** 当前撤单片段在整批中的终点，不包含此项。 */
    int cancellationChunkEnd;
    /** 整批共用的 Core 序号，各子项不另占 Core 序号。 */
    long sequence;
    /** 当前子项撮合前必须先完成的撤单编号列表。 */
    List<Long> currentPreMatchingCancellationOrderIds = List.of();
    /** 等待、已预派发或有序提交的独占阶段，防止重复派发。 */
    private byte commitState;
    /** 本批已收集、已应用等生命周期标记位图，实际事实仍在事件中。 */
    int lifecycleFlags;
    /** 已经登记整批准入身份分配的标记位。 */
    private static final int ADMISSION_COLLECTED = 1;
    /** 本批撮合输入全部应用后的标记位。 */
    private static final int MATCHING_APPLIED = 1 << 1;
    /** 已登记品种依赖的标记位。 */
    private static final int PIPELINE_REGISTERED = 1 << 2;
    /** 最终账户提交水位完成标记。 */
    private static final int LANE_COMMIT_COMPLETED = 1 << 3;
    /** 整批结算事件已收集标记。 */
    private static final int SETTLEMENTS_COLLECTED = 1 << 4;
    /** 已经进入最终收尾的标记位。 */
    private static final int FINISHING = 1 << 6;
    /** 最终账户完成位图已经建立的标记位。 */
    private static final int LANE_CONTEXT_INITIALIZED = 1 << 7;
    /** 已经取得本批执行上下文的标记位。 */
    private static final int ACTIVATED = 1 << 8;
    /**  本批是否已准备采用流水准入及撮合路径。 返回本批是否采用整批流水路径，不表示全部工作已完成。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    boolean pipelined;
    /** 本批是否必须按项串行准入，以保留资金及订单依赖。 */
    boolean sequentialAdmission;
    /**  Matcher 执行流水批次时保存的失败，交 Owner 决定恢复处理。 返回 Matcher 执行流水批次时保存的可空失败原因。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    Throwable pipelinedMatchingFailure;
    /**  整批流水结算事件，完成并收集后才可回收。 借用整批流水路径的结算事件。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    com.surprising.aeron.service.settlement.MatcherSettlementEvent settlementEvent;
    /**  整批下单账户准入事件，Owner 收集后回收。 借用整批账户准入事件，Owner 收集回收后为空。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    PlaceBatchAdmissionEvent placeBatchAdmissionEvent;
    /** 本批有效项的解析下单输入列，派发后不能提前清理。 */
    final ResolvedPlaceOrder[] preparedOrders;
    /** 本批有效项的客户身份键值列。 */
    final long[] preparedClientKeyValues;
    /** 由账户准入提供的本批订单副本引用列。 */
    final OrderRuntime[] preparedAdmittedOrders;
    /** 当前撤单片段的原语订单号缓冲，只读取当前片段长度。 */
    long[] preparedCancellationOrderIds;
    /** 本批准入已经准备的品种名称，和共享上下文列同下标。 */
    final ArrayList<String> preparedSymbols;
    /** 按品种共用的只读配置判断上下文，账户及 OI 判断仍逐项执行。 */
    final com.surprising.aeron.service.order.admission.PlaceBatchIntentSource.Decision[] preparedContextDecisions;

    /** 已收集单项或撤单片段累计的参与账户位图。 */
    long observedSettlementLaneMask;
    /** 最终由本批事实及变化用户汇总的账户位图，需与命令槽核对。 */
    long actualLaneMask;
    // 顺序路径同一时刻只有一个子项等待账户发布，直接持有其事件，不复制执行状态。
    /**  当前唯一等待账户发布的顺序子项或撤单片段事件，不另复制一份执行状态。 借用当前顺序子项或撤单片段的结算事件。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    com.surprising.aeron.service.settlement.MatcherSettlementEvent itemSettlementEvent;
    /**  当前顺序改单已经收集的解析准入。 借用当前顺序改单的可空解析准入。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    com.surprising.aeron.service.order.command.ResolvedMatchingAdmission replacementAdmission;
    /** 当前顺序改单仍需保留的账户准入事件。 */
    com.surprising.aeron.service.order.admission.ReplacementAdmissionEvent replacementAdmissionEvent;
    /**  当前顺序下单的账户预留续步。 借用当前顺序下单的可空账户预留续步。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    java.util.function.BooleanSupplier itemAdmission;
    /** 开始当前项准入前的修订，跨轮询保留。 */
    long itemAdmissionRevision;
    /**  整批最后的账户盖戳及响应准备事件。 借用本批最终账户提交事件，未派发或已回收时为空。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    com.surprising.aeron.service.core.change.LaneCommitEvent laneCommitEvent;
    /**
     *  当前最近已保存的撮合事实，供账户结算续步使用。
     * 借用最近已经保存的 Matcher 事实，可用于当前子项结算续步。
     * 保存或清除当前事实引用，不重复核验证据。
     * @param lastMatchingResult 要保存或清除的可空 Matcher 事实
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    MatchingResult lastMatchingResult;
    /** 整批无原生撮合动作时复用的成功证据占位，只绑定当前 Core 序号。 */
    final com.surprising.aeron.service.matcher.CoreMatchingResult noNativeCommandResult =
            new com.surprising.aeron.service.matcher.CoreMatchingResult(true, "NO_NATIVE_COMMAND");
    /** 顺序下单共用的账户预留工作，上一项完成后才能准备下一项。 */
    final com.surprising.aeron.service.order.admission.BatchPlaceReservation placeReservation =
            new com.surprising.aeron.service.order.admission.BatchPlaceReservation();
    /** 本批流水下单在 Matcher 执行的固定任务。 */
    final OrderBatchExecutor.PipelinedPlaceBatchSubmission pipelinedPlaceBatchSubmission =
            new OrderBatchExecutor.PipelinedPlaceBatchSubmission();
    /** 本批同分片撤单片段在 Matcher 执行的固定任务。 */
    final OrderBatchExecutor.CancelBatchChunkSubmission cancelBatchChunkSubmission =
            new OrderBatchExecutor.CancelBatchChunkSubmission();

    /** 尚未预派发或开始有序提交的阶段编码。 */
    private static final byte COMMIT_WAITING = 0;
    /** 已经取得账户工作预派发权的阶段编码。 */
    private static final byte COMMIT_PREDISPATCHED = 1;
    /** 已经进入整批有序提交的阶段编码。 */
    private static final byte COMMITTING = 2;

    /**
     * 仅在尚未取得预派发或有序提交权时返回真。
     */
    boolean canPredispatch() { return commitState == COMMIT_WAITING; }
    /**
     * 返回是否已经进入本批有序提交阶段。
     */
    public boolean commitStarted() { return commitState == COMMITTING; }

    /**
     * 要求仍在等待阶段，再标为已预派发，防止重复取得账户工作派发权。
     */
    void markPredispatched() {
        if (!canPredispatch()) throw new IllegalStateException("batch Lane work already owned");
        commitState = COMMIT_PREDISPATCHED;
    }

    /**
     * 拒绝重复开始有序提交，将等待或预派发阶段转为提交阶段。
     */
    void beginCommit() {
        if (commitStarted()) throw new IllegalStateException("batch commit already started");
        commitState = COMMITTING;
    }

    /**
     * 检查生命周期位图是否包含给定标记，不推进实际业务。
     * @param bit 要读取或更新的生命周期位
     */
    private boolean has(int bit) { return (lifecycleFlags & bit) != 0; }
    /**
     * 按布尔值设置或清除给定生命周期位，只操作本地位图。
     * @param bit 要读取或更新的生命周期位
     * @param value 调用方决定的生命周期条件是否成立
     */
    private void set(int bit, boolean value) {
        if (value) lifecycleFlags |= bit; else lifecycleFlags &= ~bit;
    }
    /**
     * 读取生命周期位，说明Owner 是否已经登记整批准入的身份分配。
     */
    boolean admissionCollected() { return has(ADMISSION_COLLECTED); }
    /**
     * 只更新生命周期位，记录Owner 是否已经登记整批准入的身份分配；实际工作由调用方推进。
     * @param value 调用方决定的生命周期条件是否成立
     */
    void admissionCollected(boolean value) { set(ADMISSION_COLLECTED, value); }
    /**
     * 读取生命周期位，说明当前批次全部撮合输入是否已经应用。
     */
    public boolean matchingApplied() { return has(MATCHING_APPLIED); }
    /**
     * 只更新生命周期位，记录当前批次全部撮合输入是否已经应用；实际工作由调用方推进。
     * @param value 调用方决定的生命周期条件是否成立
     */
    public void matchingApplied(boolean value) { set(MATCHING_APPLIED, value); }
    /**
     * 读取生命周期位，说明是否已在品种依赖表登记本批。
     */
    boolean pipelineRegistered() { return has(PIPELINE_REGISTERED); }
    /**
     * 只更新生命周期位，记录是否已在品种依赖表登记本批；实际工作由调用方推进。
     * @param value 调用方决定的生命周期条件是否成立
     */
    void pipelineRegistered(boolean value) { set(PIPELINE_REGISTERED, value); }
    /**
     * 读取生命周期位，说明本批最终账户提交水位是否已经完成。
     */
    boolean laneCommitCompleted() { return has(LANE_COMMIT_COMPLETED); }
    /**
     * 只更新生命周期位，记录本批最终账户提交水位是否已经完成；实际工作由调用方推进。
     * @param value 调用方决定的生命周期条件是否成立
     */
    void laneCommitCompleted(boolean value) { set(LANE_COMMIT_COMPLETED, value); }
    /**
     * 读取生命周期位，说明整批结算事件是否已经收集。
     */
    boolean settlementsCollected() { return has(SETTLEMENTS_COLLECTED); }
    /**
     * 只更新生命周期位，记录整批结算事件是否已经收集；实际工作由调用方推进。
     * @param value 调用方决定的生命周期条件是否成立
     */
    void settlementsCollected(boolean value) { set(SETTLEMENTS_COLLECTED, value); }
    /**
     * 读取生命周期位，说明是否已进入整批最终收尾。
     */
    boolean finishing() { return has(FINISHING); }
    /**
     * 只更新生命周期位，记录是否已进入整批最终收尾；实际工作由调用方推进。
     * @param value 调用方决定的生命周期条件是否成立
     */
    void finishing(boolean value) { set(FINISHING, value); }
    /**
     * 读取生命周期位，说明是否已经为最终提交建立完整账户完成位图。
     */
    boolean laneContextInitialized() { return has(LANE_CONTEXT_INITIALIZED); }
    /**
     * 只更新生命周期位，记录是否已经为最终提交建立完整账户完成位图；实际工作由调用方推进。
     * @param value 调用方决定的生命周期条件是否成立
     */
    void laneContextInitialized(boolean value) { set(LANE_CONTEXT_INITIALIZED, value); }
    /**
     * 读取生命周期位，说明是否已经取得本批执行上下文。
     */
    public boolean activated() { return has(ACTIVATED); }
    /**
     * 只更新生命周期位，记录是否已经取得本批执行上下文；实际工作由调用方推进。
     * @param value 调用方决定的生命周期条件是否成立
     */
    public void activated(boolean value) { set(ACTIVATED, value); }

    /**
     * 容量至少取一，要求显式响应分配器，分配本批各列和项槽；后续按有效项数复用，不自动扩展这些数组。
     * @param requestedCapacity 请求的项容量，至少分配一项
     * @param responseArena 非空响应槽分配器
     */
    public OrderBatchPending(int requestedCapacity, ResponseArena responseArena) {
        // 至少为一的本批预分配项容量
        int capacity = Math.max(1, requestedCapacity);
        this.responseArena = java.util.Objects.requireNonNull(responseArena, "response arena");
        items = new ArrayList<>(capacity);
        itemSlots = new OrderBatchItem[capacity];
        changedUserIds = new PrimitiveLongChangeSet(capacity * 2);
        runtimeChangedOrderIds = new PrimitiveLongChangeSet(capacity * 2);
        preparedClientUsers = new long[capacity];
        preparedClientIds = new String[capacity];
        preparedClientKeys = new long[capacity];
        preparedOrders = new ResolvedPlaceOrder[capacity];
        resolvedOrderSlots = new ResolvedPlaceOrder[capacity];
        preparedDecisions = new com.surprising.aeron.service.order.admission.PlaceBatchIntentSource.Decision[capacity];
        preparedClientKeyValues = new long[capacity];
        preparedAdmittedOrders = new OrderRuntime[capacity];
        preparedCancellationOrderIds = new long[preparedAdmittedOrders.length];
        preparedSymbols = new ArrayList<>(capacity);
        preparedContextDecisions =
                new com.surprising.aeron.service.order.admission.PlaceBatchIntentSource.Decision[capacity];
    }

    /**
     * 绑定非空批次种类和操作及原日志时间位置；只初始化这四项，其他旧结果须由上一轮 clear 清掉。
     * @param kind 本批业务种类
     * @param clusterTimestamp 固定业务时间
     * @param clusterPosition 固定复制日志位置
     * @param operation 本批共用的撮合操作
     */
    OrderBatchPending initialize(OrderBatchKind kind, long clusterTimestamp,
                                         long clusterPosition, CommandSlot.Operation operation) {
        this.kind = java.util.Objects.requireNonNull(kind, "batch kind");
        this.clusterTimestamp = clusterTimestamp;
        this.clusterPosition = clusterPosition;
        this.operation = java.util.Objects.requireNonNull(operation, "batch operation");
        return this;
    }

    /**
     * 返回当前预分配的项容量，不表示已经装入的项数。
     */
    int capacity() {
        return preparedOrders.length;
    }

    /**
     * 清空当前品种前缀的共享判断引用和品种列表；仅在准入交出前或整批退休后使用，不能清仍被账户借用的输入。
     */
    void clearPreparedDecisionCache() {
        java.util.Arrays.fill(preparedContextDecisions, 0, preparedSymbols.size(), null);
        preparedSymbols.clear();
    }

    /**
     * 使用当前项数作为下标，首次创建或复用该位置项槽，再追加到列表；容量由调用方预先保证，旧项必须已清理。
     * @param orderId 本项结果订单编号
     * @param originalOrderId 改单原订单编号，非改单为零
     * @param replacementOrderId 改单替换后编号，非改单为零
     * @param command 本项已解码业务输入
     */
    public void addItem(long orderId, long originalOrderId, long replacementOrderId, Object command) {
        // 当前项槽、预备身份或 Matcher 事实的遍历下标
        int index = items.size();
        // 当前被捕获、检查、编码或清理的批内项
        OrderBatchItem item = itemSlots[index];
        if (item == null) itemSlots[index] = item = new OrderBatchItem(orderId, originalOrderId, replacementOrderId, command);
        else item.initialize(orderId, originalOrderId, replacementOrderId, command);
        items.add(item);
    }

    /**
     * 在所有借用结束后清本批输入、结果、有效前缀和等待标记，归还仍由本批持有的响应槽，保留数组及工作对象容量。批次链链接由外层先摘除，部分原语列只靠有效长度隔离旧值。
     */
    void clear() {
        // 清批前记录的有效项数，只清对应数组前缀
        int preparedCount = items.size();
        for (OrderBatchItem /* 当前被捕获、检查、编码或清理的批内项 */ item : items) item.clear();
        items.clear();
        decodedCommand = null;
        changedUserIds.clear();
        runtimeChangedOrderIds.clear();
        java.util.Arrays.fill(preparedClientIds, 0, preparedClientKeyCount, null);
        java.util.Arrays.fill(preparedClientKeys, 0, preparedClientKeyCount, 0);
        preparedClientKeyCount = 0;
        if (treasuryDelta != null) treasuryDelta.clear();

        nextIndex = 0;
        tradeCount = 0;
        responseItem = null;
        if (preparedResponseSlot != null) responseArena.release(preparedResponseSlot);
        preparedResponseSlot = null;
        preparedResponse = null;
        preparedResponseLength = 0;
        cancellationChunkStart = cancellationChunkEnd = 0;
        sequence = 0;
        currentPreMatchingCancellationOrderIds = List.of();
        commitState = COMMIT_WAITING;
        pipelined = false;
        sequentialAdmission = false;
        pipelinedMatchingFailure = null;
        settlementEvent = null;
        placeBatchAdmissionEvent = null;
        java.util.Arrays.fill(preparedOrders, 0, preparedCount, null);
        java.util.Arrays.fill(preparedDecisions, 0, preparedCount, null);
        java.util.Arrays.fill(preparedClientKeyValues, 0, preparedCount, 0);
        java.util.Arrays.fill(preparedAdmittedOrders, 0, preparedCount, null);
        for (/* 当前项槽、预备身份或 Matcher 事实的遍历下标 */ int index = 0; index < preparedCount; index++) {
            if (resolvedOrderSlots[index] != null) resolvedOrderSlots[index].clear();
        }
        clearPreparedDecisionCache();
        lifecycleFlags = 0;
        observedSettlementLaneMask = 0;
        actualLaneMask = 0;
        lastMatchingResult = null;
        noNativeCommandResult.clearCoreSequence();
        itemSettlementEvent = null;
        replacementAdmission = null;
        replacementAdmissionEvent = null;
        itemAdmission = null;
        itemAdmissionRevision = 0;
        laneCommitEvent = null;
        placeReservation.clear();
        pipelinedPlaceBatchSubmission.clear();
        cancelBatchChunkSubmission.clear();
        kind = null;
        clusterTimestamp = 0;
        clusterPosition = 0;
        operation = null;
    }

    /**
     * 要求资金差额非空，首次才创建本批财库累加器，再合并本轮结果。
     * @param delta 已收集且非空的财库差额
     */
    void mergeTreasuryDelta(
            com.surprising.aeron.service.treasury.RuntimeTreasuryDelta delta) {
        if (delta == null) throw new IllegalArgumentException("order batch Treasury delta is required");
        if (treasuryDelta == null) {
            treasuryDelta = new com.surprising.aeron.service.treasury.RuntimeTreasuryDelta(
                    com.surprising.aeron.service.treasury.RuntimeTreasuryDelta.ORDER_BATCH_CAPACITY);
        }
        treasuryDelta.merge(delta);
    }

    /**
     * 只登记非零预备客户身份键，同时保存用户及客户端号供失败时逆序退回；容量耗尽直接抛错。
     * @param userId 预备客户身份所属用户
     * @param clientOrderId 预备客户身份对应的客户端订单号
     * @param preparedKey 预备客户身份键值，零不登记
     */
    void retainPreparedClientKey(
            long userId, String clientOrderId,
            long preparedKey) {
        if (preparedKey != 0) {
            if (preparedClientKeyCount >= preparedClientKeys.length) {
                throw new IllegalStateException("prepared client key capacity is exhausted");
            }
            preparedClientUsers[preparedClientKeyCount] = userId;
            preparedClientIds[preparedClientKeyCount] = clientOrderId;
            preparedClientKeys[preparedClientKeyCount++] = preparedKey;
        }
    }

    /**
     * 按登记的逆序退回客户身份预备键，成功走完后清引用及有效数量；本方法不回滚余额或订单。
     * @param identities 负责退回预备客户身份的运行时字典
     */
    void rollbackPreparedClientKeys(
            com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry identities) {
        for (/* 当前项槽、预备身份或 Matcher 事实的遍历下标 */ int index = preparedClientKeyCount - 1; index >= 0; index--) {
            identities.rollbackPreparedClientKey(preparedClientUsers[index], preparedClientIds[index],
                    preparedClientKeys[index]);
        }
        java.util.Arrays.fill(preparedClientIds, 0, preparedClientKeyCount, null);
        java.util.Arrays.fill(preparedClientKeys, 0, preparedClientKeyCount, 0);
        preparedClientKeyCount = 0;
    }

    /**
     * 只检查最终 Lane 提交事件或尚未收集的整批结算事件；当前单项事件另由调用方判断。
     */
    public boolean hasPendingLaneWork() {
        return laneCommitEvent != null
                || settlementEvent != null && !settlementsCollected();
    }

    /**
     * 检查已有单项、最终提交及未收集整批事件是否都完成；不检查准入续步，也不代表整批已经提交终态。
     */
    public boolean laneWorkComplete() {
        if (itemSettlementEvent != null && !itemSettlementEvent.complete()) return false;
        if (laneCommitEvent != null && !laneCommitEvent.complete()) return false;
        if (settlementEvent != null && !settlementsCollected() && !settlementEvent.complete()) return false;
        return true;
    }

    /**
     * 登记主动用户及成交 maker；PLACE 或成功项记录相关订单，成功的前置撤单始终记录。保持原语去重缓冲，留到最终提交统一盖戳。
     * @param item 当前完成的批内项
     * @param takerUserId 当前项主动方用户编号
     * @param matchingResult 该项已有 Matcher 事实
     */
    void collectChangedOrderIds(
            OrderBatchItem item,
            long takerUserId,
            MatchingResult matchingResult) {
        changedUserIds.add(takerUserId);
        // PLACE 即使被拒绝也保留订单记录，其他类型只有接受项才记录相关订单
        boolean recordOrders = kind == OrderBatchKind.PLACE || matchingResult.accepted();
        if (recordOrders) {
            runtimeChangedOrderIds.add(item.orderId());
            if (item.originalOrderId() > 0) runtimeChangedOrderIds.add(item.originalOrderId());
            if (item.replacementOrderId() > 0) runtimeChangedOrderIds.add(item.replacementOrderId());
        }
        // 当前结果的全部 Matcher 事件
        var matcherEvents = matchingResult.matcherEvents();
        for (/* 当前项槽、预备身份或 Matcher 事实的遍历下标 */ int index = 0; index < matcherEvents.size(); index++) {
            // 当前 Matcher 事件，只对成交写执行明细
            MatcherEvent event = matcherEvents.get(index);
            if (event.eventType() == MatcherEventType.TRADE) {
                changedUserIds.add(event.matchedOrderUid());
                if (recordOrders) runtimeChangedOrderIds.add(event.matchedOrderId());
            }
        }
        // 当前结果的前置撤单事实列表
        var cancellations = matchingResult.cancellations();
        for (/* 当前项槽、预备身份或 Matcher 事实的遍历下标 */ int index = 0; index < cancellations.size(); index++) {
            // 当前撤单事实，成功才登记其变化订单
            CoreCancellationResult cancellation = cancellations.get(index);
            if (cancellation.accepted()) {
                runtimeChangedOrderIds.add(cancellation.orderId());
            }
        }
    }

    /**
     * 返回实际批内项数，不是数组容量。
     */
    public int itemCount() { return items.size(); }
}
