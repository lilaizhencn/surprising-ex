package com.surprising.trading.order.service;

import com.surprising.trading.order.model.StableOrderIdentity;

import static com.surprising.trading.order.service.OrderCoreMapper.requireBatchKey;
import static com.surprising.trading.order.service.OrderCoreMapper.requireBatchListSize;
import static com.surprising.trading.order.service.OrderCoreMapper.requireClientKey;
import static com.surprising.trading.order.service.OrderCoreMapper.requireSingleUser;
import static com.surprising.trading.order.service.OrderCoreMapper.timeInForce;

import com.surprising.aeron.client.CoreCommandOutcome;
import com.surprising.aeron.protocol.AmendOrderBatchCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AmendOrderBatchResponse;
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.BatchAmendOrdersRequest;
import com.surprising.trading.api.model.BatchCancelOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceOrderRequest;
import com.surprising.trading.api.model.CancelOpenOrdersRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.OrderBatchItemResponse;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.PlaceOrderRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Batch order placement, amendment, and cancellation execution for {@link OrderService}.
 */
final class OrderBatchService {

    private static final int MAX_CANCEL_BATCH_SIZE = 50;

    private final OrderService orders;
    private final OrderQueryService queries;

    OrderBatchService(OrderService orders, OrderQueryService queries) {
        this.orders = orders;
        this.queries = queries;
    }

    OrderBatchResponse placeBatch(BatchPlaceOrderRequest request) {
        return orders.terminalBatchResult(placeBatchCommand(request), OrderBatchResponse.class);
    }

    OrderCommandReceipt placeBatchCommand(BatchPlaceOrderRequest request) {
        List<PlaceOrderRequest> prepared = prepareBatchOrders(request);
        orders.requireAeron();
        return orders.receipt(submitPlaceBatchCommand(request.batchKey(), prepared));
    }

    CompletionStage<OrderCommandReceipt> placeBatchCommandAsync(BatchPlaceOrderRequest request) {
        List<PlaceOrderRequest> prepared = prepareBatchOrders(request);
        orders.requireAeron();
        return submitPlaceBatchCommandAsync(request.batchKey(), prepared).thenApply(orders::receipt);
    }

    OrderService.CommandExecution submitPlaceBatchCommand(String batchKey, List<PlaceOrderRequest> requests) {
        PreparedBatch prepared = preparePlaceBatch(batchKey, requests);
        CoreCommandOutcome outcome = orders.requireAeron().commandOutcome(
                CoreMessageType.PLACE_ORDER_BATCH, prepared.commandId(), prepared.userId(), prepared.payload());
        return new OrderService.CommandExecution(
                prepared.commandId(), prepared.prospectiveIds(), outcome, OrderService.CommandKind.PLACE_BATCH);
    }

    CompletionStage<OrderService.CommandExecution> submitPlaceBatchCommandAsync(
            String batchKey, List<PlaceOrderRequest> requests) {
        PreparedBatch prepared = preparePlaceBatch(batchKey, requests);
        return orders.requireAeron().commandOutcomeAsync(
                        CoreMessageType.PLACE_ORDER_BATCH, prepared.commandId(), prepared.userId(), prepared.payload())
                .thenApply(outcome -> new OrderService.CommandExecution(
                        prepared.commandId(), prepared.prospectiveIds(), outcome, OrderService.CommandKind.PLACE_BATCH));
    }

    AmendOrderBatchResponse amendBatch(BatchAmendOrdersRequest request) {
        return orders.terminalBatchResult(amendBatchCommand(request), AmendOrderBatchResponse.class);
    }

    OrderCommandReceipt amendBatchCommand(BatchAmendOrdersRequest request) {
        List<AmendOrderRequest> normalized = prepareAmendOrders(request);
        orders.requireAeron();
        return orders.receipt(submitAmendBatchCommand(request.batchKey(), normalized));
    }

    CompletionStage<OrderCommandReceipt> amendBatchCommandAsync(BatchAmendOrdersRequest request) {
        List<AmendOrderRequest> normalized = prepareAmendOrders(request);
        orders.requireAeron();
        return submitAmendBatchCommandAsync(request.batchKey(), normalized).thenApply(orders::receipt);
    }

    OrderService.CommandExecution submitAmendBatchCommand(String batchKey, List<AmendOrderRequest> requests) {
        PreparedBatch prepared = prepareAmendBatch(batchKey, requests);
        CoreCommandOutcome outcome = orders.requireAeron().commandOutcome(
                CoreMessageType.AMEND_ORDER_BATCH, prepared.commandId(), prepared.userId(), prepared.payload());
        return new OrderService.CommandExecution(
                prepared.commandId(), prepared.prospectiveIds(), outcome, OrderService.CommandKind.AMEND_BATCH);
    }

    CompletionStage<OrderService.CommandExecution> submitAmendBatchCommandAsync(
            String batchKey, List<AmendOrderRequest> requests) {
        PreparedBatch prepared = prepareAmendBatch(batchKey, requests);
        return orders.requireAeron().commandOutcomeAsync(
                        CoreMessageType.AMEND_ORDER_BATCH, prepared.commandId(), prepared.userId(), prepared.payload())
                .thenApply(outcome -> new OrderService.CommandExecution(
                        prepared.commandId(), prepared.prospectiveIds(), outcome, OrderService.CommandKind.AMEND_BATCH));
    }

    OrderBatchResponse cancelBatch(BatchCancelOrdersRequest request) {
        return orders.terminalBatchResult(cancelBatchCommand(request), OrderBatchResponse.class);
    }

    OrderCommandReceipt cancelBatchCommand(BatchCancelOrdersRequest request) {
        validateCancelBatch(request);
        return orders.receipt(submitCancelBatchCommand(request.batchKey(), request.orders()));
    }

    CompletionStage<OrderCommandReceipt> cancelBatchCommandAsync(BatchCancelOrdersRequest request) {
        validateCancelBatch(request);
        return submitCancelBatchCommandAsync(request.batchKey(), request.orders()).thenApply(orders::receipt);
    }

    OrderService.CommandExecution submitCancelBatchCommand(String batchKey, List<CancelOrderRequest> requests) {
        PreparedBatch prepared = prepareCancelBatch(batchKey, requests);
        CoreCommandOutcome outcome = orders.requireAeron().commandOutcome(
                CoreMessageType.CANCEL_ORDER_BATCH, prepared.commandId(), prepared.userId(), prepared.payload());
        return new OrderService.CommandExecution(
                prepared.commandId(), prepared.prospectiveIds(), outcome, OrderService.CommandKind.CANCEL_BATCH);
    }

    CompletionStage<OrderService.CommandExecution> submitCancelBatchCommandAsync(
            String batchKey, List<CancelOrderRequest> requests) {
        PreparedBatch prepared = prepareCancelBatch(batchKey, requests);
        return orders.requireAeron().commandOutcomeAsync(
                        CoreMessageType.CANCEL_ORDER_BATCH, prepared.commandId(), prepared.userId(), prepared.payload())
                .thenApply(outcome -> new OrderService.CommandExecution(
                        prepared.commandId(), prepared.prospectiveIds(), outcome, OrderService.CommandKind.CANCEL_BATCH));
    }

    OrderBatchResponse cancelOpenOrders(CancelOpenOrdersRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("cancel open orders request is required");
        }
        if (request.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        int limit = request.limit() == null ? 1000 : request.limit();
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String instrumentId = request.instrumentId() == null || request.instrumentId().isBlank()
                ? null : orders.normalizeInstrumentId(request.instrumentId());
        List<OrderResponse> open = queries.activeOpenOrders(
                orders.currentProductLine(), request.userId(), instrumentId, limit);
        orders.requireAeron();
        List<CancelOrderRequest> requests = open.stream()
                .map(order -> new CancelOrderRequest(request.userId(), order.orderId()))
                .toList();
        return orderBatchResponse(cancelOrderChunks(requests));
    }

    OrderBatchResponse cancelTimerOrders(long userId, String instrumentId, int limit) {
        List<CancelOrderRequest> requests = queries.openOrdersFromCore(userId, instrumentId, 0, limit).stream()
                .map(order -> new CancelOrderRequest(userId, order.orderId()))
                .toList();
        return orderBatchResponse(cancelOrderChunks(requests));
    }

    List<OrderBatchItemResponse> cancelOrderChunks(List<CancelOrderRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        Map<Long, List<IndexedCancelRequest>> requestsByUser = new LinkedHashMap<>();
        for (int index = 0; index < requests.size(); index++) {
            CancelOrderRequest request = requests.get(index);
            orders.validateCancelRequest(request);
            requestsByUser.computeIfAbsent(request.userId(), ignored -> new ArrayList<>())
                    .add(new IndexedCancelRequest(index, request));
        }

        List<OrderBatchItemResponse> results = new ArrayList<>(requests.size());
        for (List<IndexedCancelRequest> userRequests : requestsByUser.values()) {
            for (int start = 0; start < userRequests.size(); start += MAX_CANCEL_BATCH_SIZE) {
                List<IndexedCancelRequest> chunk = userRequests.subList(start,
                        Math.min(start + MAX_CANCEL_BATCH_SIZE, userRequests.size()));
                List<CancelOrderRequest> chunkRequests = chunk.stream()
                        .map(IndexedCancelRequest::request)
                        .toList();
                OrderBatchResponse response;
                try {
                    response = cancelBatch(new BatchCancelOrdersRequest(chunkRequests));
                } catch (RuntimeException exception) {
                    response = failedCancelBatch(chunkRequests, exception.getMessage());
                }
                for (int localIndex = 0; localIndex < chunk.size(); localIndex++) {
                    int resultIndex = localIndex;
                    IndexedCancelRequest indexed = chunk.get(localIndex);
                    OrderBatchItemResponse item = response.results().stream()
                            .filter(value -> value != null && value.index() == resultIndex)
                            .findFirst()
                            .orElseGet(() -> new OrderBatchItemResponse(resultIndex, false,
                                    "batch cancellation result is missing", null));
                    results.add(new OrderBatchItemResponse(indexed.index(), item.success(), item.message(),
                            item.order()));
                }
            }
        }
        results.sort(Comparator.comparingInt(OrderBatchItemResponse::index));
        return List.copyOf(results);
    }

    private List<PlaceOrderRequest> prepareBatchOrders(BatchPlaceOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("place batch request is required");
        }
        orders.requireBatchSize(request.orders().size(), 20, "orders");
        List<PlaceOrderRequest> prepared = new ArrayList<>(request.orders().size());
        for (PlaceOrderRequest order : request.orders()) {
            prepared.add(orders.prepareOrder(order));
        }
        return prepared;
    }

    private List<AmendOrderRequest> prepareAmendOrders(BatchAmendOrdersRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("amend batch request is required");
        }
        orders.requireBatchSize(request.orders().size(), 20, "orders");
        return request.orders().stream().map(orders::normalizeAmend).toList();
    }

    private void validateCancelBatch(BatchCancelOrdersRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("cancel batch request is required");
        }
        orders.requireBatchSize(request.orders().size(), 50, "orders");
        request.orders().forEach(orders::validateCancelRequest);
        orders.requireAeron();
    }

    private PreparedBatch preparePlaceBatch(String batchKey, List<PlaceOrderRequest> requests) {
        requireBatchKey(batchKey);
        requireBatchListSize(requests, PlaceOrderBatchCommand.MAX_ORDERS, "orders");
        ProductLine productLine = orders.configuredProductLine();
        long userId = requireSingleUser(requests);
        List<PlaceOrderCommand> commands = new ArrayList<>(requests.size());
        List<Long> prospectiveIds = new ArrayList<>(requests.size());
        for (PlaceOrderRequest request : requests) {
            String clientOrderId = requireClientKey(request.clientOrderId(), "clientOrderId");
            long orderId = StableOrderIdentity.orderId(productLine, userId, clientOrderId);
            prospectiveIds.add(orderId);
            commands.add(orders.buildPlaceCommand(orderId, request));
        }
        UUID commandId = OrderService.batchCommandId(productLine, userId, "place", batchKey);
        byte[] payload = TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(commands));
        return new PreparedBatch(commandId, userId, prospectiveIds, payload);
    }

    private PreparedBatch prepareAmendBatch(String batchKey, List<AmendOrderRequest> requests) {
        requireBatchKey(batchKey);
        requireBatchListSize(requests, AmendOrderBatchCommand.MAX_ORDERS, "orders");
        ProductLine productLine = orders.configuredProductLine();
        long userId = requireSingleUser(requests);
        List<AmendOrderCommand> commands = new ArrayList<>(requests.size());
        List<Long> prospectiveIds = new ArrayList<>(requests.size());
        for (AmendOrderRequest request : requests) {
            String clientOrderId = requireClientKey(request.newClientOrderId(), "newClientOrderId");
            long replacementOrderId = StableOrderIdentity.replacementOrderId(productLine, userId, clientOrderId);
            prospectiveIds.add(replacementOrderId);
            commands.add(new AmendOrderCommand(request.orderId(), replacementOrderId, clientOrderId,
                    request.priceTicks(), request.quantitySteps(),
                    request.timeInForce() == null ? null : timeInForce(request.timeInForce()), request.postOnly()));
        }
        UUID commandId = OrderService.batchCommandId(productLine, userId, "amend", batchKey);
        byte[] payload = TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(commands));
        return new PreparedBatch(commandId, userId, prospectiveIds, payload);
    }

    private PreparedBatch prepareCancelBatch(String batchKey, List<CancelOrderRequest> requests) {
        requireBatchKey(batchKey);
        requireBatchListSize(requests, CancelOrderBatchCommand.MAX_ORDERS, "orders");
        ProductLine productLine = orders.configuredProductLine();
        long userId = requireSingleUser(requests);
        List<CancelOrderCommand> commands = requests.stream()
                .map(request -> new CancelOrderCommand(request.orderId())).toList();
        List<Long> prospectiveIds = requests.stream().map(CancelOrderRequest::orderId).toList();
        UUID commandId = OrderService.batchCommandId(productLine, userId, "cancel", batchKey);
        byte[] payload = TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(commands));
        return new PreparedBatch(commandId, userId, prospectiveIds, payload);
    }

    private OrderBatchResponse orderBatchResponse(List<OrderBatchItemResponse> results) {
        int completed = (int) results.stream().filter(OrderBatchItemResponse::success).count();
        return new OrderBatchResponse(results.size(), completed, results.size() - completed, results);
    }

    private OrderBatchResponse failedCancelBatch(List<CancelOrderRequest> requests, String message) {
        String reason = message == null || message.isBlank() ? "batch cancellation failed" : message;
        List<OrderBatchItemResponse> results = new ArrayList<>(requests.size());
        for (int index = 0; index < requests.size(); index++) {
            results.add(new OrderBatchItemResponse(index, false, reason, null));
        }
        return new OrderBatchResponse(results.size(), 0, results.size(), results);
    }

    private record PreparedBatch(UUID commandId, long userId, List<Long> prospectiveIds, byte[] payload) {
    }

    private record IndexedCancelRequest(int index, CancelOrderRequest request) {
    }
}
