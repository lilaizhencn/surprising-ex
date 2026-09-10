package com.surprising.aeron.service.execution;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.List;

/** Owner 管理批流程；账户 Lane 仅写结果槽，经完成回执交接后读取，终态提交后释放。 */
final class OrderBatchItem {
    /** 当前业务项的订单 ID。 */
    final long orderId;
    /** 改单前订单 ID；非改单时按协议使用零值。 */
    final long originalOrderId;
    /** 改单后订单 ID；非改单时按协议使用零值。 */
    final long replacementOrderId;
    /** 本项不可变输入命令，完成前由批量上下文持有。 */
    final Object command;
    /** 该命令或业务项的执行状态。 */
    ResponseStatus status;
    /** 该命令或业务项的确定性结果码。 */
    CoreResultCode resultCode;
    /** 本项撮合事件；完成交接后只读，终态时释放引用。 */
    List<MatcherEvent> executionEvents = List.of();
    /** 本项需要编码的成交数量。 */
    int executionCount;
    /** 成交编码所需的主动方用户 ID。 */
    long executionTakerUserId;
    /** 本项终态返回的订单视图。 */
    com.surprising.aeron.service.state.OrderRuntime resultOrder;
    /** 提交时已解析的币对名称，编码不再物化订单 DTO。 */
    String resultOrderSymbol;
    /** Lane 在最终元数据盖章后提供结果，Owner 不再逐笔查发布表。 */
    boolean laneResultPrepared;
    /** 成交推送的不可变主动单身份；结算可提前，推送只能在本批提交时消费。 */
    com.surprising.aeron.service.state.OrderRuntime realtimeTakerOrder;
    /** 本项发给 matcher 的准备结果。 */
    java.util.function.Supplier<CoreMatchingResult> matchingSubmission;

    OrderBatchItem(long orderId, long originalOrderId, long replacementOrderId, Object command) {
        this.orderId = orderId;
        this.originalOrderId = originalOrderId;
        this.replacementOrderId = replacementOrderId;
        this.command = command;
    }

    long orderId() { return orderId; }
    long originalOrderId() { return originalOrderId; }
    long replacementOrderId() { return replacementOrderId; }
}
