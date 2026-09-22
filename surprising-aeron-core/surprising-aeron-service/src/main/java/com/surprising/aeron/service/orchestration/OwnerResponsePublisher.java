package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.WireMessageKind;
import io.aeron.cluster.service.ClientSession;

/**
 * Owner 的终态响应出口。
 *
 * <p>交易状态提交和网络发送是两个不同的完成条件：交易状态一旦提交，响应就不能因为客户端背压而回滚。
 * 本类只负责响应类型选择和出口交接，不读取或修改订单、余额和持仓状态。</p>
 */
final class OwnerResponsePublisher {
    private final TradingCoreOwner.ResponseSink sink;

    OwnerResponsePublisher(TradingCoreOwner.ResponseSink sink) {
        this.sink = java.util.Objects.requireNonNull(sink, "response sink");
    }

    void publish(ClientSession session, CoreMessage request, CoreResponse response,
                 TradingCoreRuntime state) {
        CoreMessageHeader header = request.header().response(responseType(request.header()));
        sink.offer(session, header, response, state.committedCoreSequence());
    }

    /** 按请求类型计算协议层的响应消息类型。 */
    private static CoreMessageType responseType(CoreMessageHeader requestHeader) {
        return switch (requestHeader.messageType()) {
            case USER_STATE_QUERY -> CoreMessageType.USER_STATE_RESULT;
            case ORDER_STATE_QUERY, CLIENT_ORDER_STATE_QUERY -> CoreMessageType.ORDER_STATE_RESULT;
            case BOOK_STATE_QUERY -> CoreMessageType.BOOK_STATE_RESULT;
            case ORDER_BOOK_BOOTSTRAP_QUERY -> CoreMessageType.ORDER_BOOK_BOOTSTRAP_RESULT;
            case LIQUIDATION_WORK_QUERY -> CoreMessageType.LIQUIDATION_WORK_RESULT;
            case USER_OPEN_ORDERS_QUERY -> CoreMessageType.USER_OPEN_ORDERS_RESULT;
            case TRIGGER_ORDER_QUERY -> CoreMessageType.TRIGGER_ORDER_RESULT;
            case USER_OPEN_TRIGGER_ORDERS_QUERY -> CoreMessageType.USER_OPEN_TRIGGER_ORDERS_RESULT;
            case FUNDING_PROGRESS_QUERY -> CoreMessageType.FUNDING_PROGRESS_RESULT;
            case SETTLEMENT_PROGRESS_QUERY -> CoreMessageType.SETTLEMENT_PROGRESS_RESULT;
            case COMMAND_RESULT_QUERY -> CoreMessageType.COMMAND_RESULT_RESULT;
            case RISK_SCAN_CONTROL_QUERY -> CoreMessageType.RISK_SCAN_CONTROL_RESULT;
            case INSTRUMENT_MAINTENANCE_QUERY -> CoreMessageType.INSTRUMENT_MAINTENANCE_RESULT;
            default -> requestHeader.kind() == WireMessageKind.QUERY
                    ? CoreMessageType.STATE_HASH_RESULT : CoreMessageType.COMMAND_RESULT;
        };
    }

}
