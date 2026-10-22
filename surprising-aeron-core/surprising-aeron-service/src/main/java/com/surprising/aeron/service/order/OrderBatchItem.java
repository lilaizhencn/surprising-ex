package com.surprising.aeron.service.order;

import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 一条批量命令中的一个订单项，保存输入、该项状态、准入订单副本、结算后订单副本及撮合事件。Owner 管理批内顺序；所属账户和 Matcher 只在各自交接边界写约定结果，Owner 等回执后再读。整批终态退出且无人借用后才能清理复用。
 */
final class OrderBatchItem {
    /**  本项结果使用的订单编号，改单对应替换后编号。 返回本项对外结果所用的订单编号，改单时是替换后编号。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    long orderId;
    /**  改单原订单编号，非改单按输入使用零。 返回改单原订单编号，其他批次项通常为零。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    long originalOrderId;
    /**  改单替换后编号，非改单按输入使用零。 返回改单替换后订单编号，其他批次项通常为零。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    long replacementOrderId;
    /** 本项已经解码的输入，随整批生命周期保留。 */
    Object command;
    /** 该项已决定的执行状态，整批接受不代表每项均成功。 */
    ResponseStatus status;
    /** 该项状态对应的业务结果码。 */
    CoreResultCode resultCode;
    /** 本项保留的全部 Matcher 事件视图，不仅包含成交；清槽后使用空表。 */
    List<MatcherEvent> executionEvents = List.of();
    /** 保存本项事件副本的可复用 Matcher 槽，容量跨批保留。 */
    private exchange.core2.core.common.MatcherResult executionSlot;
    /** 本项持有的准入订单可复用副本，不能当作活跃账户订单修改。 */
    private com.surprising.aeron.service.order.state.OrderRuntime admissionCopy;
    /** 本项持有的写后订单可复用副本，供结果编码。 */
    private com.surprising.aeron.service.order.state.OrderRuntime resultCopy;

    /**
     * 把准入订单复制到本项可复用槽，传空只解除当前结果引用；Matcher 和实时输出借同一份副本，不再次读取活跃账户订单。
     * @param order 当前要捕获的可空订单，非空时复制进本项槽
     */
    void captureAdmittedOrder(com.surprising.aeron.service.order.state.OrderRuntime order) {
        admittedOrder = order == null ? null : (admissionCopy = order.copyIntoCommandSlot(admissionCopy));
    }

    /**
     * 把结算后订单复制到本项结果槽，空输入表示响应没有这张订单视图；副本容量保留供后续批次复用。
     * @param order 当前要捕获的可空订单，非空时复制进本项槽
     */
    void captureResultOrder(com.surprising.aeron.service.order.state.OrderRuntime order) {
        resultOrder = order == null ? null : (resultCopy = order.copyIntoCommandSlot(resultCopy));
    }

    /**
     * 空事件表使用共用空表并返回零；否则复制到本项 Matcher 事件槽，保留全部事件供后续读取，并单独统计成交事件数量。
     * @param events 本次 Matcher 事件列表，调用方保证非空引用
     */
    int captureExecutions(List<MatcherEvent> events) {
        if (events.isEmpty()) {
            executionEvents = List.of();
            return 0;
        }
        if (executionSlot == null) executionSlot = exchange.core2.core.common.MatcherResult.commandSlot();
        executionSlot.captureEvents(events);
        executionEvents = executionSlot.events();
        // 复制事件中实际 TRADE 事件数量，不包含拒单或其他事件
        int trades = 0;
        for (/* 正在统计的本项事件下标 */ int index = 0; index < executionEvents.size(); index++) {
            if (executionEvents.get(index).eventType() == MatcherEventType.TRADE) trades++;
        }
        return trades;
    }
    /** 本项已累计的成交事件数量，供批量编码预估长度。 */
    int executionCount;
    /** 成交编码需要的主动方用户编号。 */
    long executionTakerUserId;
    /** 该项响应中的可空订单副本。 */
    com.surprising.aeron.service.order.state.OrderRuntime resultOrder;
    /** 对应结果订单的品种名称，编码时不再查订单 DTO。 */
    String resultOrderSymbol;
    /** 所属 Lane 是否已经提供本项写后或不变结果，空订单也可表示已准备。 */
    boolean laneResultPrepared;
    /** 本批提交时读取的主动单身份引用；提交前不能归入其他命令的实时范围。 */
    com.surprising.aeron.service.order.state.OrderRuntime realtimeTakerOrder;
    /** 由所属账户提供并保留到整批退休的准入订单副本。 */
    com.surprising.aeron.service.order.state.OrderRuntime admittedOrder;
    /** Owner 为当前撤单片段解析的品种名称，Matcher 借用后清理。 */
    String cancelSymbol;

    /**
     * 保存本项订单身份及输入；其他结果初始为空，后续复用前须先 clear。
     * @param orderId 本项结果订单编号
     * @param originalOrderId 改单原订单编号，非改单为零
     * @param replacementOrderId 改单替换后编号，非改单为零
     * @param command 本项已解码的业务输入
     */
    OrderBatchItem(long orderId, long originalOrderId, long replacementOrderId, Object command) {
        initialize(orderId, originalOrderId, replacementOrderId, command);
    }

    /**
     * 仅重新绑定订单身份与输入，不重置旧结果；调用方须在上一批所有借用结束并 clear 后使用。
     * @param orderId 本项结果订单编号
     * @param originalOrderId 改单原订单编号，非改单为零
     * @param replacementOrderId 改单替换后编号，非改单为零
     * @param command 本项已解码的业务输入
     */
    void initialize(long orderId, long originalOrderId, long replacementOrderId, Object command) {
        this.orderId = orderId;
        this.originalOrderId = originalOrderId;
        this.replacementOrderId = replacementOrderId;
        this.command = command;
    }

    /**
     * 清掉本项输入、状态、成交及订单结果引用，清理复用副本内容；保留事件槽和订单副本对象，供下一批重新填充。
     */
    void clear() {
        orderId = originalOrderId = replacementOrderId = 0;
        command = null;
        status = null;
        resultCode = null;
        executionEvents = List.of();
        if (executionSlot != null) executionSlot.clearCommandSlot();
        executionCount = 0;
        executionTakerUserId = 0;
        resultOrder = null;
        resultOrderSymbol = null;
        laneResultPrepared = false;
        realtimeTakerOrder = null;
        admittedOrder = null;
        if (admissionCopy != null) admissionCopy.clearCommandSlot();
        if (resultCopy != null) resultCopy.clearCommandSlot();
        cancelSymbol = null;
    }

}
