package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.WireMessageKind;
import io.aeron.cluster.service.ClientSession;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Owner 的终态响应出口。
 *
 * <p>交易状态提交和网络发送是两个不同的完成条件：交易状态一旦提交，响应就不能因为客户端背压而回滚。
 * 本类只负责编码、交接和有限重试，不读取或修改订单、余额和持仓状态。</p>
 */
final class OwnerResponsePublisher {
    /** 兼容旧的同步测试入口或独立回放入口的响应交接函数。 */
    private final TradingCoreOwner.ResponseSink sink;
    /** 没有外部出口时使用的有限响应重试队列。 */
    private final DeferredSessionResponses deferredResponses = new DeferredSessionResponses();
    /** 可复用的响应编码缓冲区，所有者是当前 Owner 线程。 */
    private byte[] responseScratch = new byte[4 * 1024];
    /** 对 {@link #responseScratch} 的 Agrona 包装，避免每条响应重新创建对象。 */
    private final UnsafeBuffer responseBuffer = new UnsafeBuffer(responseScratch);

    /** 创建响应出口；sink 为空表示使用本类内置的兼容发送路径。 */
    OwnerResponsePublisher(TradingCoreOwner.ResponseSink sink) {
        this.sink = sink;
    }

    /** 清空尚未发送的响应；用于启动和角色切换边界。 */
    void clear() {
        deferredResponses.clear();
    }

    /** 移除已经关闭会话的待发送响应。 */
    void removeSession(long sessionId) {
        if (sink == null) deferredResponses.remove(sessionId);
    }

    /** 推进有限响应发送队列，返回本轮实际处理的响应数量。 */
    int poll(long nowNanos, int budget) {
        return deferredResponses.poll(nowNanos, budget);
    }

    /**
     * 发布已经提交的终态响应。
     *
     * <p>有生产环境出口时只交给 {@code ClusterServiceEgress}；没有出口时才在当前 Owner 内编码并进入有限重试队列。</p>
     */
    void publish(ClientSession session, CoreMessage request, CoreResponse response,
                 TradingCoreRuntime state) {
        CoreMessageHeader header = request.header().response(responseType(request.header()));
        if (sink != null) {
            sink.offer(session, header, response, state.committedCoreSequence());
            return;
        }
        if (session == null || session.isClosing()) {
            state.releaseResponse(response);
            return;
        }
        int length = CoreMessageCodec.encodedResponseLength(response);
        ensureScratchCapacity(length);
        try {
            CoreMessageCodec.encodeResponse(header, response, state.committedCoreSequence(), responseScratch);
            deferredResponses.offer(session, responseBuffer, length, System.nanoTime());
        } finally {
            state.releaseResponse(response);
        }
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

    /** 确保编码缓冲区足够容纳当前响应，并同步更新 Agrona 包装。 */
    private void ensureScratchCapacity(int length) {
        if (responseScratch.length >= length) return;
        responseScratch = new byte[length];
        responseBuffer.wrap(responseScratch);
    }
}
