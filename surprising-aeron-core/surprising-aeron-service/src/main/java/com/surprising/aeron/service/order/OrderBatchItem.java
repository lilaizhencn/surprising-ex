package com.surprising.aeron.service.order;

import com.surprising.aeron.service.position.Position;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.exception.FatalMatchingDivergenceException;
import com.surprising.aeron.protocol.AmendOrderBatchCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountTransactionRollback;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.matcher.OrderCancelResult;
import com.surprising.aeron.service.matcher.OrderMatchingResult;
import com.surprising.aeron.service.result.OrderSource;
import com.surprising.aeron.service.result.ResponseSlotPool;
import com.surprising.aeron.service.settlement.TradeSettlement;
import com.surprising.aeron.support.collection.PrimitiveLongChangeSet;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;


final class OrderBatchItem {
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    long orderId;
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    long originalOrderId;
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    long replacementOrderId;
    Object command;
    ResponseStatus status;
    CoreResultCode resultCode;
    List<MatcherEvent> executionEvents = List.of();
    private exchange.core2.core.common.MatcherResult executionSlot;
    /** 本项持有的准入订单可复用副本，不能当作活跃账户订单修改。 */
    private com.surprising.aeron.service.order.Order orderCheckCopy;
    private com.surprising.aeron.service.order.Order resultCopy;

    /** 把准入订单复制到本项可复用槽，传空只解除当前结果引用。 */
    void captureAdmittedOrder(com.surprising.aeron.service.order.Order order) {
        admittedOrder = order == null ? null : (orderCheckCopy = order.copyIntoCommandSlot(orderCheckCopy));
    }

    /** 把结算后订单复制到本项结果槽，空输入表示响应没有这张订单视图。 */
    void captureResultOrder(com.surprising.aeron.service.order.Order order) {
        resultOrder = order == null ? null : (resultCopy = order.copyIntoCommandSlot(resultCopy));
    }

    /** 空事件表使用共用空表并返回零。否则复制到本项 Matcher 事件槽，保留全部事件供后续读取，并单独统计成交事件数量。 */
    int captureExecutions(List<MatcherEvent> events) {
        if (events.isEmpty()) {
            executionEvents = List.of();
            return 0;
        }
        if (executionSlot == null) executionSlot = exchange.core2.core.common.MatcherResult.commandSlot();
        executionSlot.captureEvents(events);
        executionEvents = executionSlot.events();
        int trades = 0;
        for (int index = 0; index < executionEvents.size(); index++) {
            if (executionEvents.get(index).eventType() == MatcherEventType.TRADE) trades++;
        }
        return trades;
    }
    int executionCount;
    long executionTakerUserId;
    com.surprising.aeron.service.order.Order resultOrder;
    String resultOrderSymbol;
    boolean laneResultPrepared;
    /** 提交前不能归入其他命令的实时范围。 */
    com.surprising.aeron.service.order.Order realtimeTakerOrder;
    com.surprising.aeron.service.order.Order admittedOrder;
    /** Owner 为当前撤单片段解析的品种名称，Matcher 借用后清理。 */
    String cancelSymbol;

    /** 保存本项订单身份及输入。 */
    OrderBatchItem(long orderId, long originalOrderId, long replacementOrderId, Object command) {
        initialize(orderId, originalOrderId, replacementOrderId, command);
    }

    /** 仅重新绑定订单身份与输入，不重置旧结果。 */
    void initialize(long orderId, long originalOrderId, long replacementOrderId, Object command) {
        this.orderId = orderId;
        this.originalOrderId = originalOrderId;
        this.replacementOrderId = replacementOrderId;
        this.command = command;
    }

    /** 清掉本项输入、状态、成交及订单结果引用，清理复用副本内容。 */
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
        if (orderCheckCopy != null) orderCheckCopy.clearCommandSlot();
        if (resultCopy != null) resultCopy.clearCommandSlot();
        cancelSymbol = null;
    }

}

