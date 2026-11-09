package com.surprising.trading.api.client;

import com.surprising.trading.api.model.AlgoOrderBatchResponse;
import com.surprising.trading.api.model.AlgoOrderQueryResponse;
import com.surprising.trading.api.model.AlgoOrderResponse;
import com.surprising.trading.api.model.BatchAmendOrdersRequest;
import com.surprising.trading.api.model.BatchCancelOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceOrderRequest;
import com.surprising.trading.api.model.CancelAlgoOrderRequest;
import com.surprising.trading.api.model.CancelAllAfterRequest;
import com.surprising.trading.api.model.CancelAllAfterResponse;
import com.surprising.trading.api.model.CancelOpenAlgoOrdersRequest;
import com.surprising.trading.api.model.CancelOpenOrdersRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.ClosePositionRequest;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.PlaceAlgoOrderRequest;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.TestOrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * 订单交易 RPC 客户端熔断与快速失败保护。
 */
@Component
public class OrderRpcApiFallbackFactory implements FallbackFactory<OrderRpcApi> {

    private static final Logger log = LoggerFactory.getLogger(OrderRpcApiFallbackFactory.class);

    @Override
    public OrderRpcApi create(Throwable cause) {
        return new OrderRpcApi() {
            private String causeMsg() {
                return cause != null ? cause.getMessage() : "circuit breaker triggered / timeout";
            }

            @Override
            public OrderCommandReceipt place(PlaceOrderRequest request) {
                log.warn("OrderRpcApi#place 熔断触发, 下单指令快速失败保护: {}", causeMsg());
                throw new IllegalStateException("交易网关下单接口熔断中: " + causeMsg(), cause);
            }

            @Override
            public OrderCommandReceipt placeBatch(BatchPlaceOrderRequest request) {
                log.warn("OrderRpcApi#placeBatch 熔断触发, 批量下单快速失败保护: {}", causeMsg());
                throw new IllegalStateException("交易网关批量下单接口熔断中: " + causeMsg(), cause);
            }

            @Override
            public TestOrderResponse test(PlaceOrderRequest request) {
                log.warn("OrderRpcApi#test 熔断触发: {}", causeMsg());
                throw new IllegalStateException("交易网关测试下单接口熔断中: " + causeMsg(), cause);
            }

            @Override
            public OrderCommandReceipt amend(com.surprising.trading.api.model.AmendOrderRequest request) {
                log.warn("OrderRpcApi#amend 熔断触发: {}", causeMsg());
                throw new IllegalStateException("交易网关改单接口熔断中: " + causeMsg(), cause);
            }

            @Override
            public OrderCommandReceipt amendBatch(BatchAmendOrdersRequest request) {
                log.warn("OrderRpcApi#amendBatch 熔断触发: {}", causeMsg());
                throw new IllegalStateException("交易网关批量改单接口熔断中: " + causeMsg(), cause);
            }

            @Override
            public OrderResponse closePosition(ClosePositionRequest request) {
                log.warn("OrderRpcApi#closePosition 熔断触发: {}", causeMsg());
                throw new IllegalStateException("交易网关平仓接口熔断中: " + causeMsg(), cause);
            }

            @Override
            public OrderCommandReceipt cancel(CancelOrderRequest request) {
                log.warn("OrderRpcApi#cancel 熔断触发: {}", causeMsg());
                throw new IllegalStateException("交易网关撤单接口熔断中: " + causeMsg(), cause);
            }

            @Override
            public OrderCommandReceipt cancelBatch(BatchCancelOrdersRequest request) {
                log.warn("OrderRpcApi#cancelBatch 熔断触发: {}", causeMsg());
                throw new IllegalStateException("交易网关批量撤单接口熔断中: " + causeMsg(), cause);
            }

            @Override
            public OrderBatchResponse cancelOpen(CancelOpenOrdersRequest request) {
                log.warn("OrderRpcApi#cancelOpen 熔断触发: {}", causeMsg());
                throw new IllegalStateException("交易网关一键撤单接口熔断中: " + causeMsg(), cause);
            }

            @Override
            public CancelAllAfterResponse cancelAllAfter(CancelAllAfterRequest request) {
                log.warn("OrderRpcApi#cancelAllAfter 熔断触发: {}", causeMsg());
                throw new IllegalStateException("交易网关倒计时撤单接口熔断中: " + causeMsg(), cause);
            }

            @Override
            public AlgoOrderResponse placeAlgo(PlaceAlgoOrderRequest request) {
                log.warn("OrderRpcApi#placeAlgo 熔断触发: {}", causeMsg());
                throw new IllegalStateException("交易网关策略单接口熔断中: " + causeMsg(), cause);
            }

            @Override
            public AlgoOrderResponse cancelAlgo(CancelAlgoOrderRequest request) {
                log.warn("OrderRpcApi#cancelAlgo 熔断触发: {}", causeMsg());
                throw new IllegalStateException("交易网关撤销策略单接口熔断中: " + causeMsg(), cause);
            }

            @Override
            public AlgoOrderBatchResponse cancelOpenAlgo(CancelOpenAlgoOrdersRequest request) {
                log.warn("OrderRpcApi#cancelOpenAlgo 熔断触发: {}", causeMsg());
                throw new IllegalStateException("交易网关全量撤销策略单接口熔断中: " + causeMsg(), cause);
            }

            @Override
            public AlgoOrderResponse getAlgo(long algoOrderId) {
                log.warn("OrderRpcApi#getAlgo 熔断触发: algoOrderId={}, 原因: {}", algoOrderId, causeMsg());
                return null;
            }

            @Override
            public AlgoOrderQueryResponse openAlgoOrders(long userId, String instrumentId, int limit) {
                log.warn("OrderRpcApi#openAlgoOrders 熔断触发: userId={}, 原因: {}", userId, causeMsg());
                return new AlgoOrderQueryResponse(0, Collections.emptyList());
            }

            @Override
            public OrderResponse get(long orderId) {
                log.warn("OrderRpcApi#get 熔断触发: orderId={}, 原因: {}", orderId, causeMsg());
                return null;
            }

            @Override
            public OrderResponse getByClientOrderId(long userId, String clientOrderId) {
                log.warn("OrderRpcApi#getByClientOrderId 熔断触发: clientOrderId={}, 原因: {}", clientOrderId, causeMsg());
                return null;
            }

            @Override
            public OrderQueryResponse openOrders(long userId, String instrumentId, int limit, String cursor) {
                log.warn("OrderRpcApi#openOrders 熔断触发: userId={}, 原因: {}", userId, causeMsg());
                return new OrderQueryResponse(0, Collections.emptyList());
            }
        };
    }
}
