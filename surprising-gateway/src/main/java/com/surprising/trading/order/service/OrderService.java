package com.surprising.trading.order.service;

import static com.surprising.trading.order.service.OrderCoreMapper.*;

import com.surprising.aeron.client.CoreCommandOutcome;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreCommandResultCodec;
import com.surprising.aeron.protocol.CoreCommandResultView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderBatchResult;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CorePositionView;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.ValkeyUserQueries;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;
import com.surprising.trading.api.model.AdminBatchCancelOrdersRequest;
import com.surprising.trading.api.model.AdminCancelByInstrumentRequest;
import com.surprising.trading.api.model.AdminCancelOrderResult;
import com.surprising.trading.api.model.AdminCancelOrdersPreviewResponse;
import com.surprising.trading.api.model.AdminCancelOrdersResponse;
import com.surprising.trading.api.model.AdminTradeQueryResponse;
import com.surprising.trading.api.model.AmendOrderBatchItemResponse;
import com.surprising.trading.api.model.AmendOrderBatchResponse;
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.AmendOrderResponse;
import com.surprising.trading.api.model.BatchAmendOrdersRequest;
import com.surprising.trading.api.model.BatchCancelOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceOrderRequest;
import com.surprising.trading.api.model.CancelOpenOrdersRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.CancellationPlanResponse;
import com.surprising.trading.api.model.ClosePositionRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.MarketPriceProtection;
import com.surprising.trading.api.model.OrderBatchItemResponse;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.OrderCommandResult;
import com.surprising.trading.api.model.OrderPriceProtectionResponse;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderSubmissionResponse;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TestOrderResponse;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.TradeFillQueryResponse;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.exception.OrderCommandRejectedException;
import com.surprising.trading.order.model.ActiveOrderPageQuery;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.TradeReadCursor;
import com.surprising.trading.order.model.ValidationResult;
import com.surprising.trading.order.repository.AeronOrderProjectionRepository;
import com.surprising.trading.order.repository.ProjectionReadResult;
import com.surprising.trading.order.repository.TradeProjectionRepository;
import com.surprising.trading.order.utils.ActiveOrderCursors;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;

@Service
public class OrderService {

    private static final int MAX_CANCEL_BATCH_SIZE = 50;

    private final TradingOrderProperties properties;
    private final OrderValidator orderValidator;
    private final OrderAeronGateway aeron;
    private final MatchingMarketDataService bboOrderBook;
    private final BracketOrderDispatcher bracketOrderDispatcher;
    private final OrderQueryService queries;
    private final OrderAdminService adminOps;

    public OrderService(TradingOrderProperties properties, OrderValidator orderValidator,
                        OrderAeronGateway aeron, AeronOrderProjectionRepository aeronOrderProjection,
                        MatchingMarketDataService bboOrderBook, TradingMetricsService tradingMetrics,
                        TradeProjectionRepository tradeProjection, ValkeyUserQueries realtimeQueries) {
        this(properties, orderValidator, aeron, aeronOrderProjection, bboOrderBook,
                tradingMetrics, tradeProjection, realtimeQueries, null);
    }

    public OrderService(TradingOrderProperties properties, OrderValidator orderValidator,
                        OrderAeronGateway aeron, AeronOrderProjectionRepository aeronOrderProjection,
                        MatchingMarketDataService bboOrderBook, TradingMetricsService tradingMetrics,
                        TradeProjectionRepository tradeProjection, ValkeyUserQueries realtimeQueries,
                        BracketOrderDispatcher bracketOrderDispatcher) {
        this(properties, orderValidator, aeron, bboOrderBook, bracketOrderDispatcher,
                new OrderQueryService(properties, aeron, aeronOrderProjection,
                        tradingMetrics, tradeProjection, realtimeQueries));
    }

    @Autowired
    public OrderService(TradingOrderProperties properties, OrderValidator orderValidator,
                        OrderAeronGateway aeron, MatchingMarketDataService bboOrderBook,
                        @Autowired(required = false) BracketOrderDispatcher bracketOrderDispatcher,
                        OrderQueryService queries) {
        this.properties = properties;
        this.orderValidator = orderValidator;
        this.aeron = aeron;
        this.bboOrderBook = bboOrderBook;
        this.bracketOrderDispatcher = bracketOrderDispatcher;
        this.queries = queries;
        this.adminOps = new OrderAdminService(this, queries);
    }

    public ObjectNode tradingMetrics(int window, int limit, ProductLine requestedProduct) {
        return queries.tradingMetrics(window, limit, requestedProduct);
    }

    public TradeFillQueryResponse ownFills(long userId, String instrumentId,
            Long orderId, Long startTime, Long endTime, int limit, TradeReadCursor.Fill cursor) {
        return queries.ownFills(userId, instrumentId, orderId, startTime, endTime, limit, cursor);
    }

    public AdminTradeQueryResponse adminTrades(Long userId, String instrumentId,
            Long orderId, int limit, TradeReadCursor.Execution cursor, boolean ascending, ProductLine requestedProduct) {
        return queries.adminTrades(userId, instrumentId, orderId, limit, cursor, ascending, requestedProduct);
    }

    public OrderPriceProtectionResponse priceProtection() {
        return queries.priceProtection();
    }

    public OrderResponse place(PlaceOrderRequest request) {
        requireAeron();
        PlaceOrderRequest prepared = prepareOrder(request);
        OrderResponse response = submitPlaceOrder(prepared);
        if (bracketOrderDispatcher != null && prepared.hasBracketOrders() && response != null) {
            bracketOrderDispatcher.registerBracket(prepared, response);
        }
        return response;
    }

    OrderResponse placeAlgoChild(PlaceOrderRequest request) {
        requireAeron();
        return submitPlaceOrder(prepareOrder(request, true));
    }

    public OrderCommandReceipt placeCommand(PlaceOrderRequest request) {
        requireAeron();
        PlaceOrderRequest prepared = prepareOrder(request);
        return receipt(submitPlaceCommand(prepared));
    }

    public CompletionStage<OrderCommandReceipt> placeCommandAsync(PlaceOrderRequest request) {
        requireAeron();
        PlaceOrderRequest prepared = prepareOrder(request);
        return submitPlaceCommandAsync(prepared).thenApply(this::receipt);
    }

    OrderResponse submitPlaceOrder(PlaceOrderRequest request) {
        CommandExecution execution = submitPlaceCommand(request);
        return requireOrder(commandOrder(terminalResponse(execution), execution.prospectiveOrderIds().getFirst()),
                "placed order missing");
    }

    CommandExecution submitPlaceCommand(PlaceOrderRequest request) {
        String clientOrderId = requireClientKey(request.clientOrderId(), "clientOrderId");
        ProductLine productLine = configuredProductLine();
        long orderId = StableOrderIdentity.orderId(productLine, request.userId(), clientOrderId);
        PlaceOrderCommand command = buildPlaceCommand(orderId, request);
        UUID commandId = StableOrderIdentity.commandId(productLine, request.userId(), clientOrderId);
        CoreCommandOutcome outcome = requireAeron().commandOutcome(CoreMessageType.PLACE_ORDER, commandId, request.userId(),
                TradingCommandCodec.encodePlaceOrder(command));
        return new CommandExecution(commandId, List.of(orderId), outcome, CommandKind.PLACE);
    }

    CompletionStage<CommandExecution> submitPlaceCommandAsync(PlaceOrderRequest request) {
        String clientOrderId = requireClientKey(request.clientOrderId(), "clientOrderId");
        ProductLine productLine = configuredProductLine();
        long orderId = StableOrderIdentity.orderId(productLine, request.userId(), clientOrderId);
        PlaceOrderCommand command = buildPlaceCommand(orderId, request);
        UUID commandId = StableOrderIdentity.commandId(productLine, request.userId(), clientOrderId);
        return requireAeron().commandOutcomeAsync(CoreMessageType.PLACE_ORDER, commandId, request.userId(),
                        TradingCommandCodec.encodePlaceOrder(command))
                .thenApply(outcome -> new CommandExecution(commandId, List.of(orderId), outcome, CommandKind.PLACE));
    }

    private PlaceOrderRequest prepareOrder(PlaceOrderRequest request) {
        return prepareOrder(request, false);
    }

    private PlaceOrderRequest prepareOrder(PlaceOrderRequest request, boolean algoChild) {
        PlaceOrderRequest normalized = normalize(request);
        if (normalized.clientOrderId().startsWith("algo-") != algoChild)
            throw new IllegalArgumentException("clientOrderId uses the reserved algo child namespace");
        if (orderValidator != null) {
            InstrumentRule instrument = orderValidator.currentRule(normalized.instrumentId()).orElse(null);
            ValidationResult validation = instrument == null
                    ? orderValidator.validate(normalized)
                    : orderValidator.validate(normalized, instrument);
            if (!validation.accepted()) throw new IllegalArgumentException(validation.rejectReason());
        }
        return normalized;
    }

    public OrderBatchResponse placeBatch(BatchPlaceOrderRequest request) {
        return terminalBatchResult(placeBatchCommand(request), OrderBatchResponse.class);
    }

    public OrderCommandReceipt placeBatchCommand(BatchPlaceOrderRequest request) {
        List<PlaceOrderRequest> orders = prepareBatchOrders(request);
        requireAeron();
        return receipt(submitPlaceBatchCommand(request.batchKey(), orders));
    }

    public CompletionStage<OrderCommandReceipt> placeBatchCommandAsync(BatchPlaceOrderRequest request) {
        List<PlaceOrderRequest> orders = prepareBatchOrders(request);
        requireAeron();
        return submitPlaceBatchCommandAsync(request.batchKey(), orders).thenApply(this::receipt);
    }

    CommandExecution submitPlaceBatchCommand(String batchKey, List<PlaceOrderRequest> requests) {
        requireBatchKey(batchKey);
        requireBatchListSize(requests, com.surprising.aeron.protocol.PlaceOrderBatchCommand.MAX_ORDERS, "orders");
        ProductLine productLine = configuredProductLine();
        long userId = requireSingleUser(requests);
        List<PlaceOrderCommand> commands = new ArrayList<>(requests.size());
        List<Long> prospectiveIds = new ArrayList<>(requests.size());
        for (PlaceOrderRequest request : requests) {
            String clientOrderId = requireClientKey(request.clientOrderId(), "clientOrderId");
            long orderId = StableOrderIdentity.orderId(productLine, userId, clientOrderId);
            prospectiveIds.add(orderId);
            commands.add(buildPlaceCommand(orderId, request));
        }
        UUID commandId = batchCommandId(productLine, userId, "place", batchKey);
        CoreCommandOutcome outcome = requireAeron().commandOutcome(CoreMessageType.PLACE_ORDER_BATCH, commandId, userId,
                TradingOrderBatchCodec.encodePlaceOrderBatch(new com.surprising.aeron.protocol.PlaceOrderBatchCommand(commands)));
        return new CommandExecution(commandId, prospectiveIds, outcome, CommandKind.PLACE_BATCH);
    }

    CompletionStage<CommandExecution> submitPlaceBatchCommandAsync(String batchKey, List<PlaceOrderRequest> requests) {
        requireBatchKey(batchKey);
        requireBatchListSize(requests, com.surprising.aeron.protocol.PlaceOrderBatchCommand.MAX_ORDERS, "orders");
        ProductLine productLine = configuredProductLine();
        long userId = requireSingleUser(requests);
        List<PlaceOrderCommand> commands = new ArrayList<>(requests.size());
        List<Long> prospectiveIds = new ArrayList<>(requests.size());
        for (PlaceOrderRequest request : requests) {
            long orderId = StableOrderIdentity.orderId(productLine, userId,
                    requireClientKey(request.clientOrderId(), "clientOrderId"));
            prospectiveIds.add(orderId);
            commands.add(buildPlaceCommand(orderId, request));
        }
        UUID commandId = batchCommandId(productLine, userId, "place", batchKey);
        byte[] payload = TradingOrderBatchCodec.encodePlaceOrderBatch(
                new com.surprising.aeron.protocol.PlaceOrderBatchCommand(commands));
        return requireAeron().commandOutcomeAsync(CoreMessageType.PLACE_ORDER_BATCH, commandId, userId, payload)
                .thenApply(outcome -> new CommandExecution(commandId, prospectiveIds, outcome, CommandKind.PLACE_BATCH));
    }

    private List<PlaceOrderRequest> prepareBatchOrders(BatchPlaceOrderRequest request) {
        if (request == null) throw new IllegalArgumentException("place batch request is required");
        requireBatchSize(request.orders().size(), 20, "orders");
        List<PlaceOrderRequest> orders = new ArrayList<>(request.orders().size());
        for (PlaceOrderRequest order : request.orders()) orders.add(prepareOrder(order));
        return orders;
    }

    public TestOrderResponse test(PlaceOrderRequest request) {
        PlaceOrderRequest normalized = normalize(request);
        ValidationResult validation = orderValidator.validate(normalized);
        if (!validation.accepted()) {
            return testRejected(validation, "ORDER_RULES");
        }
        return dryRunOpeningFunds(normalized, validation);
    }

    public OrderAeronGateway.PreflightResult preflight(PlaceOrderRequest request) {
        ProductLine productLine = configuredProductLine();
        long orderId = StableOrderIdentity.orderId(productLine, request.userId(),
                requireClientKey(request.clientOrderId(), "clientOrderId"));
        return requireAeron().preflight(request.userId(), buildPlaceCommand(orderId, request));
    }

    public AmendOrderResponse replace(OrderResponse original, PlaceOrderRequest replacement) {
        String clientOrderId = requireClientKey(replacement.clientOrderId(), "clientOrderId");
        ProductLine productLine = configuredProductLine();
        long replacementOrderId = StableOrderIdentity.replacementOrderId(
                productLine, replacement.userId(), clientOrderId);
        PlaceOrderCommand replacementCommand = buildPlaceCommand(replacementOrderId, replacement);
        UUID commandId = StableOrderIdentity.replacementCommandId(productLine, replacement.userId(), clientOrderId);
        var response = requireAeron().commandOutcome(CoreMessageType.REPLACE_ORDER, commandId, replacement.userId(),
                TradingCommandCodec.encodeReplaceOrder(new ReplaceOrderCommand(original.orderId(), replacementCommand)));
        CoreResponse responseValue = terminalResponse(new CommandExecution(commandId,
                List.of(original.orderId(), replacementOrderId), response, CommandKind.REPLACE));
        CoreCommandResultView result = requireCommandResult(responseValue);
        CoreOrderStateView canceledView = result.orders().stream()
                .filter(value -> value.orderId() == original.orderId()).findFirst().orElse(null);
        CoreOrderStateView placedView = result.orders().stream()
                .filter(value -> value.orderId() == replacementOrderId).findFirst().orElse(null);
        return new AmendOrderResponse(
                requireOrder(canceledView, "replaced original order missing"),
                requireOrder(placedView, "replacement order missing"), true,
                placedView.status().equals("REJECTED") ? "replacement rejected" : "order replaced");
    }

    public AmendOrderResponse amend(AmendOrderRequest request) {
        requireAeron();
        AmendOrderRequest normalized = normalizeAmend(request);
        CommandExecution execution = submitReplaceCommand(normalized);
        CoreCommandResultView result = requireCommandResult(terminalResponse(execution));
        CoreOrderStateView canceledView = result.orders().stream()
                .filter(value -> value.orderId() == normalized.orderId()).findFirst().orElse(null);
        CoreOrderStateView placedView = result.orders().stream()
                .filter(value -> value.orderId() == execution.prospectiveOrderIds().getLast()).findFirst().orElse(null);
        return new AmendOrderResponse(
                requireOrder(canceledView, "amended original order missing"),
                requireOrder(placedView, "amended replacement order missing"), true,
                placedView.status().equals("REJECTED") ? "replacement rejected" : "order amended");
    }

    public OrderCommandReceipt amendCommand(AmendOrderRequest request) {
        requireAeron();
        return receipt(submitReplaceCommand(normalizeAmend(request)));
    }

    public CompletionStage<OrderCommandReceipt> amendCommandAsync(AmendOrderRequest request) {
        requireAeron();
        return submitReplaceCommandAsync(normalizeAmend(request)).thenApply(this::receipt);
    }

    CommandExecution submitReplaceCommand(AmendOrderRequest request) {
        String clientOrderId = requireClientKey(request.newClientOrderId(), "newClientOrderId");
        String clientRequestId = requireClientKey(request.clientRequestId(), "clientRequestId");
        ProductLine productLine = configuredProductLine();
        long replacementOrderId = StableOrderIdentity.replacementOrderId(productLine, request.userId(), clientOrderId);
        AmendOrderCommand command = new AmendOrderCommand(request.orderId(), replacementOrderId,
                clientOrderId, request.priceTicks(), request.quantitySteps(),
                request.timeInForce() == null ? null : timeInForce(request.timeInForce()), request.postOnly());
        UUID commandId = StableOrderIdentity.replacementCommandId(productLine, request.userId(), clientRequestId);
        CoreCommandOutcome outcome = requireAeron().commandOutcome(CoreMessageType.AMEND_ORDER, commandId, request.userId(),
                TradingCommandCodec.encodeAmendOrder(command));
        return new CommandExecution(commandId, List.of(request.orderId(), replacementOrderId), outcome,
                CommandKind.AMEND);
    }

    CompletionStage<CommandExecution> submitReplaceCommandAsync(AmendOrderRequest request) {
        String clientOrderId = requireClientKey(request.newClientOrderId(), "newClientOrderId");
        String clientRequestId = requireClientKey(request.clientRequestId(), "clientRequestId");
        ProductLine productLine = configuredProductLine();
        long replacementOrderId = StableOrderIdentity.replacementOrderId(productLine, request.userId(), clientOrderId);
        AmendOrderCommand command = new AmendOrderCommand(request.orderId(), replacementOrderId,
                clientOrderId, request.priceTicks(), request.quantitySteps(),
                request.timeInForce() == null ? null : timeInForce(request.timeInForce()), request.postOnly());
        UUID commandId = StableOrderIdentity.replacementCommandId(productLine, request.userId(), clientRequestId);
        List<Long> orderIds = List.of(request.orderId(), replacementOrderId);
        return requireAeron().commandOutcomeAsync(CoreMessageType.AMEND_ORDER, commandId, request.userId(),
                        TradingCommandCodec.encodeAmendOrder(command))
                .thenApply(outcome -> new CommandExecution(commandId, orderIds, outcome, CommandKind.AMEND));
    }

    public AmendOrderBatchResponse amendBatch(BatchAmendOrdersRequest request) {
        return terminalBatchResult(amendBatchCommand(request), AmendOrderBatchResponse.class);
    }

    public OrderCommandReceipt amendBatchCommand(BatchAmendOrdersRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("amend batch request is required");
        }
        requireBatchSize(request.orders().size(), 20, "orders");
        List<AmendOrderRequest> normalized = request.orders().stream().map(this::normalizeAmend).toList();
        requireAeron();
        return receipt(submitAmendBatchCommand(request.batchKey(), normalized));
    }

    public CompletionStage<OrderCommandReceipt> amendBatchCommandAsync(BatchAmendOrdersRequest request) {
        if (request == null) throw new IllegalArgumentException("amend batch request is required");
        requireBatchSize(request.orders().size(), 20, "orders");
        List<AmendOrderRequest> normalized = request.orders().stream().map(this::normalizeAmend).toList();
        requireAeron();
        return submitAmendBatchCommandAsync(request.batchKey(), normalized).thenApply(this::receipt);
    }

    CommandExecution submitAmendBatchCommand(String batchKey, List<AmendOrderRequest> requests) {
        requireBatchKey(batchKey);
        requireBatchListSize(requests, com.surprising.aeron.protocol.AmendOrderBatchCommand.MAX_ORDERS, "orders");
        ProductLine productLine = configuredProductLine();
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
        UUID commandId = batchCommandId(productLine, userId, "amend", batchKey);
        CoreCommandOutcome outcome = requireAeron().commandOutcome(CoreMessageType.AMEND_ORDER_BATCH, commandId, userId,
                TradingOrderBatchCodec.encodeAmendOrderBatch(new com.surprising.aeron.protocol.AmendOrderBatchCommand(commands)));
        return new CommandExecution(commandId, prospectiveIds, outcome, CommandKind.AMEND_BATCH);
    }

    CompletionStage<CommandExecution> submitAmendBatchCommandAsync(String batchKey, List<AmendOrderRequest> requests) {
        requireBatchKey(batchKey);
        requireBatchListSize(requests, com.surprising.aeron.protocol.AmendOrderBatchCommand.MAX_ORDERS, "orders");
        ProductLine productLine = configuredProductLine();
        long userId = requireSingleUser(requests);
        List<AmendOrderCommand> commands = new ArrayList<>(requests.size());
        List<Long> prospectiveIds = new ArrayList<>(requests.size());
        for (var request : requests) {
            long replacementOrderId = StableOrderIdentity.replacementOrderId(productLine, userId,
                    requireClientKey(request.newClientOrderId(), "newClientOrderId"));
            prospectiveIds.add(replacementOrderId);
            commands.add(new AmendOrderCommand(request.orderId(), replacementOrderId, request.newClientOrderId(),
                    request.priceTicks(), request.quantitySteps(),
                    request.timeInForce() == null ? null : timeInForce(request.timeInForce()), request.postOnly()));
        }
        UUID commandId = batchCommandId(productLine, userId, "amend", batchKey);
        byte[] payload = TradingOrderBatchCodec.encodeAmendOrderBatch(
                new com.surprising.aeron.protocol.AmendOrderBatchCommand(commands));
        return requireAeron().commandOutcomeAsync(CoreMessageType.AMEND_ORDER_BATCH, commandId, userId, payload)
                .thenApply(outcome -> new CommandExecution(commandId, prospectiveIds, outcome, CommandKind.AMEND_BATCH));
    }

    public OrderResponse closePosition(ClosePositionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("close position request is required");
        }
        if (request.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        String clientOrderId = normalizeClientOrderId(request.clientOrderId());
        String instrumentId = normalizeInstrumentId(request.instrumentId());
        MarginMode marginMode = MarginMode.defaultIfNull(request.marginMode());
        PositionSide positionSide = PositionSide.defaultIfNull(request.positionSide());
        ProductLine productLine = currentProductLine();
        long quantity = requireClosePosition(
                productLine, request.userId(), instrumentId, marginMode, positionSide);
        if (quantity == 0L) {
            throw new IllegalStateException("open position not found");
        }
        OrderSide closeSide = quantity > 0L ? OrderSide.SELL : OrderSide.BUY;
        PlaceOrderRequest closeOrder = new PlaceOrderRequest(
                request.userId(),
                clientOrderId,
                instrumentId,
                closeSide,
                OrderType.MARKET,
                TimeInForce.IOC,
                0L,
                Math.absExact(quantity),
                marginMode,
                positionSide,
                true,
                false);
        return place(closeOrder);
    }

    long requireClosePosition(ProductLine line, long userId, String instrumentId,
                              MarginMode mode, PositionSide side) {
        if (line == null) throw new IllegalArgumentException("product line is required");
        if (line == ProductLine.SPOT) throw new IllegalStateException("open position not found");
        CoreUserStateView state = requireAeron().userState(userId);
        if (state == null) throw new IllegalStateException("Aeron user state not found: " + userId);
        if (state.productLine() != line) throw new IllegalStateException("Aeron user product line mismatch");
        if (state.positionMode() == com.surprising.aeron.protocol.CorePositionMode.HEDGE
                && !PositionSide.defaultIfNull(side).isHedgeSide()) {
            throw new IllegalArgumentException("positionSide LONG or SHORT is required in HEDGE position mode");
        }
        MarginMode normalizedMode = MarginMode.defaultIfNull(mode);
        PositionSide normalizedSide = PositionSide.defaultIfNull(side);
        for (CorePositionView position : state.positions()) {
            if (position.instrumentId().equalsIgnoreCase(instrumentId)
                    && position.marginMode().name().equals(normalizedMode.name())
                    && position.positionSide().name().equals(normalizedSide.name())
                    && position.signedQuantitySteps() != 0) return position.signedQuantitySteps();
        }
        throw new IllegalStateException("open position not found");
    }

    private TestOrderResponse dryRunOpeningFunds(PlaceOrderRequest request,
                                                 ValidationResult validation) {
        OrderAeronGateway.PreflightResult result = preflight(request);
        if (!result.accepted()) {
            return new TestOrderResponse(false, result.resultCode().name(), validation.instrumentChangeId(),
                    "CORE_PREFLIGHT", currentProductLine().accountTypeCode(), null, 0L);
        }
        return new TestOrderResponse(true, null, validation.instrumentChangeId(), "ACCEPTED",
                currentProductLine().accountTypeCode(), result.view().reservationAsset(),
                result.view().reservedUnits());
    }

    public OrderResponse cancel(CancelOrderRequest request) {
        if (request.userId() <= 0 || request.orderId() <= 0) {
            throw new IllegalArgumentException("userId and orderId must be positive");
        }
        return cancelById(request.userId(), request.orderId());
    }

    OrderResponse cancelById(long userId, long orderId) {
        CommandExecution execution = submitCancelCommand(userId, orderId);
        CoreOrderStateView responseOrder = commandOrder(terminalResponse(execution), orderId);
        return requireOrder(responseOrder, "canceled order missing");
    }

    public OrderCommandReceipt cancelCommand(CancelOrderRequest request) {
        if (request == null || request.userId() <= 0 || request.orderId() <= 0) {
            throw new IllegalArgumentException("userId and orderId must be positive");
        }
        requireAeron();
        return receipt(submitCancelCommand(request.userId(), request.orderId()));
    }

    public CompletionStage<OrderCommandReceipt> cancelCommandAsync(CancelOrderRequest request) {
        if (request == null || request.userId() <= 0 || request.orderId() <= 0) {
            throw new IllegalArgumentException("userId and orderId must be positive");
        }
        requireAeron();
        return submitCancelCommandAsync(request.userId(), request.orderId()).thenApply(this::receipt);
    }

    CommandExecution submitCancelCommand(long userId, long orderId) {
        UUID commandId = StableOrderIdentity.commandId(configuredProductLine(), userId, "cancel:" + orderId);
        CoreCommandOutcome outcome = requireAeron().commandOutcome(CoreMessageType.CANCEL_ORDER, commandId, userId,
                TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(orderId)));
        return new CommandExecution(commandId, List.of(orderId), outcome, CommandKind.CANCEL);
    }

    CompletionStage<CommandExecution> submitCancelCommandAsync(long userId, long orderId) {
        UUID commandId = StableOrderIdentity.commandId(configuredProductLine(), userId, "cancel:" + orderId);
        return requireAeron().commandOutcomeAsync(CoreMessageType.CANCEL_ORDER, commandId, userId,
                        TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(orderId)))
                .thenApply(outcome -> new CommandExecution(
                        commandId, List.of(orderId), outcome, CommandKind.CANCEL));
    }

    public OrderBatchResponse cancelBatch(BatchCancelOrdersRequest request) {
        return terminalBatchResult(cancelBatchCommand(request), OrderBatchResponse.class);
    }

    public OrderCommandReceipt cancelBatchCommand(BatchCancelOrdersRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("cancel batch request is required");
        }
        requireBatchSize(request.orders().size(), 50, "orders");
        request.orders().forEach(this::validateCancelRequest);
        requireAeron();
        return receipt(submitCancelBatchCommand(request.batchKey(), request.orders()));
    }

    public CompletionStage<OrderCommandReceipt> cancelBatchCommandAsync(BatchCancelOrdersRequest request) {
        if (request == null) throw new IllegalArgumentException("cancel batch request is required");
        requireBatchSize(request.orders().size(), 50, "orders");
        request.orders().forEach(this::validateCancelRequest);
        requireAeron();
        return submitCancelBatchCommandAsync(request.batchKey(), request.orders())
                .thenApply(this::receipt);
    }

    CommandExecution submitCancelBatchCommand(String batchKey, List<CancelOrderRequest> requests) {
        requireBatchKey(batchKey);
        requireBatchListSize(requests, com.surprising.aeron.protocol.CancelOrderBatchCommand.MAX_ORDERS, "orders");
        ProductLine productLine = configuredProductLine();
        long userId = requireSingleUser(requests);
        List<CancelOrderCommand> commands = requests.stream()
                .map(request -> new CancelOrderCommand(request.orderId())).toList();
        List<Long> prospectiveIds = requests.stream().map(CancelOrderRequest::orderId).toList();
        UUID commandId = batchCommandId(productLine, userId, "cancel", batchKey);
        CoreCommandOutcome outcome = requireAeron().commandOutcome(CoreMessageType.CANCEL_ORDER_BATCH, commandId, userId,
                TradingOrderBatchCodec.encodeCancelOrderBatch(new com.surprising.aeron.protocol.CancelOrderBatchCommand(commands)));
        return new CommandExecution(commandId, prospectiveIds, outcome, CommandKind.CANCEL_BATCH);
    }

    CompletionStage<CommandExecution> submitCancelBatchCommandAsync(String batchKey, List<CancelOrderRequest> requests) {
        requireBatchKey(batchKey);
        requireBatchListSize(requests, com.surprising.aeron.protocol.CancelOrderBatchCommand.MAX_ORDERS, "orders");
        ProductLine productLine = configuredProductLine();
        long userId = requireSingleUser(requests);
        List<CancelOrderCommand> commands = requests.stream()
                .map(request -> new CancelOrderCommand(request.orderId())).toList();
        List<Long> prospectiveIds = requests.stream()
                .map(CancelOrderRequest::orderId).toList();
        UUID commandId = batchCommandId(productLine, userId, "cancel", batchKey);
        byte[] payload = TradingOrderBatchCodec.encodeCancelOrderBatch(
                new com.surprising.aeron.protocol.CancelOrderBatchCommand(commands));
        return requireAeron().commandOutcomeAsync(CoreMessageType.CANCEL_ORDER_BATCH, commandId, userId, payload)
                .thenApply(outcome -> new CommandExecution(
                        commandId, prospectiveIds, outcome, CommandKind.CANCEL_BATCH));
    }

    public OrderCommandReceipt commandResult(long userId, UUID commandId) {
        return queries.commandResult(userId, commandId);
    }

    public OrderCommandReceipt commandResultFromCore(UUID commandId) {
        return queries.commandResultFromCore(commandId);
    }

    public OrderSubmissionResponse submission(long userId, String clientOrderId) {
        return queries.submission(userId, clientOrderId);
    }

    public CancellationPlanResponse cancellationPlan(long userId, String instrumentId) {
        return queries.cancellationPlan(userId, instrumentId);
    }

    public OrderCommandReceipt cancellationBatch(long userId, String batchKey) {
        return queries.cancellationBatch(userId, batchKey);
    }

    public OrderCommandReceipt amendment(long userId, String clientRequestId) {
        return queries.amendment(userId, clientRequestId);
    }

    public OrderCommandReceipt cancellation(long userId, long orderId) {
        return queries.cancellation(userId, orderId);
    }

    public OrderBatchResponse cancelOpenOrders(CancelOpenOrdersRequest request) {
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
                ? null : normalizeInstrumentId(request.instrumentId());
        List<OrderResponse> open = activeOpenOrders(currentProductLine(), request.userId(), instrumentId, limit);
        requireAeron();
        List<CancelOrderRequest> requests = open.stream()
                .map(order -> new CancelOrderRequest(request.userId(), order.orderId()))
                .toList();
        return orderBatchResponse(cancelOrderChunks(requests));
    }

    /** 定时撤单从所属Core读取本批目标，完成判断不依赖公开查询投影的进度。 */
    OrderBatchResponse cancelTimerOrders(long userId, String instrumentId, int limit) {
        List<CancelOrderRequest> requests = openOrdersFromCore(userId, instrumentId, 0, limit).stream()
                .map(order -> new CancelOrderRequest(userId, order.orderId()))
                .toList();
        return orderBatchResponse(cancelOrderChunks(requests));
    }

    public List<OrderResponse> openOrdersFromCore(long userId, String instrumentId, long beforeOrderId, int limit) {
        return queries.openOrdersFromCore(userId, instrumentId, beforeOrderId, limit);
    }

    public List<OrderResponse> lifecycleOpenOrders(String instrumentId, int limit) {
        return queries.lifecycleOpenOrders(instrumentId, limit);
    }

    public OrderResponse get(long userId, long orderId) {
        return get(userId, orderId, null);
    }

    public OrderResponse get(long userId, long orderId, Long minExportSequence) {
        return queries.get(userId, orderId, minExportSequence);
    }

    public OrderResponse getByClientOrderId(long userId, String clientOrderId) {
        return getByClientOrderId(userId, clientOrderId, null);
    }

    public OrderResponse getByClientOrderId(long userId, String clientOrderId, Long minExportSequence) {
        return queries.getByClientOrderId(userId, clientOrderId, minExportSequence);
    }

    public OrderQueryResponse openOrders(long userId, String instrumentId, ActiveOrderPageQuery query,
                                         Long minExportSequence) {
        return queries.openOrders(userId, instrumentId, query, minExportSequence);
    }

    private List<OrderResponse> activeOpenOrders(ProductLine productLine, Long userId, String instrumentId,
                                                 int limit) {
        return queries.activeOpenOrders(productLine, userId, instrumentId, limit);
    }

    public OrderQueryResponse historyOrders(long userId,
                                            String instrumentId,
                                            int limit,
                                            Long minimumOrderId,
                                            Long startTimeMillis,
                                            Long endTimeMillis) {
        return historyOrders(userId, instrumentId, limit, minimumOrderId, startTimeMillis, endTimeMillis, null, null, null);
    }

    public OrderQueryResponse historyOrders(long userId,
                                            String instrumentId,
                                            int limit,
                                            Long minimumOrderId,
                                            Long startTimeMillis,
                                            Long endTimeMillis,
                                            String cursor,
                                            Long minExportSequence, String status) {
        return queries.historyOrders(userId, instrumentId, limit, minimumOrderId,
                startTimeMillis, endTimeMillis, cursor, minExportSequence, status);
    }

    public OrderQueryResponse adminOrders(Long userId, String instrumentId, String status, Long orderId, int limit) {
        return adminOrders(userId, instrumentId, status, orderId, limit, null, null, null);
    }

    public OrderQueryResponse adminOrders(Long userId,
                                          String instrumentId,
                                          String status,
                                          Long orderId,
                                          int limit,
                                          String cursor,
                                          String sort) {
        return adminOrders(userId, instrumentId, status, orderId, limit, cursor, sort, null);
    }

    public OrderQueryResponse adminOrders(Long userId,
                                          String instrumentId,
                                          String status,
                                          Long orderId,
                                          int limit,
                                          String cursor,
                                          String sort,
                                          ProductLine productLine) {
        return queries.adminOrders(userId, instrumentId, status, orderId, limit, cursor, sort, productLine);
    }

    public AdminCancelOrderResult adminCancelOrder(long orderId, String reason) {
        return adminOps.adminCancelOrder(orderId, reason);
    }

    public AdminCancelOrderResult adminCancelOrder(long orderId, String reason, ProductLine productLine) {
        return adminOps.adminCancelOrder(orderId, reason, productLine);
    }

    public AdminCancelOrdersResponse adminCancelOrders(AdminBatchCancelOrdersRequest request) {
        return adminOps.adminCancelOrders(request);
    }

    public AdminCancelOrdersResponse adminCancelOrders(AdminBatchCancelOrdersRequest request, ProductLine productLine) {
        return adminOps.adminCancelOrders(request, productLine);
    }

    public AdminCancelOrdersPreviewResponse adminCancelPreview(Long userId, String instrumentId, int limit) {
        return adminOps.adminCancelPreview(userId, instrumentId, limit);
    }

    public AdminCancelOrdersPreviewResponse adminCancelPreview(
            Long userId, String instrumentId, int limit, ProductLine productLine) {
        return adminOps.adminCancelPreview(userId, instrumentId, limit, productLine);
    }

    public AdminCancelOrdersResponse adminCancelByInstrument(AdminCancelByInstrumentRequest request) {
        return adminOps.adminCancelByInstrument(request);
    }

    public AdminCancelOrdersResponse adminCancelByInstrument(AdminCancelByInstrumentRequest request, ProductLine productLine) {
        return adminOps.adminCancelByInstrument(request, productLine);
    }

    public int requestLifecycleCancellation(String instrumentId, int limit) {
        return adminOps.requestLifecycleCancellation(instrumentId, limit);
    }

    public boolean hasLifecycleActiveOrders(String instrumentId) {
        return adminOps.hasLifecycleActiveOrders(instrumentId);
    }

    private PlaceOrderCommand buildPlaceCommand(long orderId, PlaceOrderRequest request) {
        long limitPriceTicks = request.orderType() == OrderType.LIMIT ? request.priceTicks() : 0;
        if (request.orderType() == OrderType.MARKET && configuredProductLine() == ProductLine.SPOT) {
            if (bboOrderBook == null) throw new IllegalStateException("SPOT market order book is unavailable");
            var book = bboOrderBook.orderBookSnapshot(request.instrumentId(), 1);
            var side = request.side() == OrderSide.BUY ? book.asks() : book.bids();
            if (side.isEmpty()) throw new IllegalArgumentException("SPOT_MARKET_LIQUIDITY_UNAVAILABLE");
            limitPriceTicks = MarketPriceProtection.protectedPriceTicks(
                    request.side(), side.getFirst().priceTicks(), properties.getRisk().getMarketMaxSlippagePpm());
        }
        return new PlaceOrderCommand(orderId, request.instrumentId(),
                side(request.side()), limitPriceTicks, request.quantitySteps(), request.reduceOnly(),
                marginMode(request.marginMode()), positionSide(request.positionSide()),
                orderType(request.orderType()), timeInForce(request.timeInForce()), request.postOnly(),
                requireClientKey(request.clientOrderId(), "clientOrderId"));
    }

    public OrderCommandReceipt receipt(CommandExecution execution) {
        if (execution.outcome() instanceof CoreCommandOutcome.Terminal terminal) {
            CoreResponse response = terminal.response();
            if (response.resultCode() == CoreResultCode.MATCHING_PENDING) {
                return new OrderCommandReceipt(execution.commandId(), "MATCHING_PENDING",
                        CoreResultCode.MATCHING_PENDING.name(), "matching pending",
                        commandResultUrl(execution.commandId()),
                        execution.prospectiveOrderIds(), null, null);
            }
            OrderCommandResult result = decodeResult(execution.kind(), execution.prospectiveOrderIds(), response.data());
            return new OrderCommandReceipt(execution.commandId(), "TERMINAL", response.resultCode().name(),
                    response.resultCode() == CoreResultCode.NONE ? "completed" : response.resultCode().name(),
                    commandResultUrl(execution.commandId()), execution.prospectiveOrderIds(),
                    result, null);
        }
        if (execution.outcome() instanceof CoreCommandOutcome.ResultUnknown) {
            return new OrderCommandReceipt(execution.commandId(), "RESULT_UNKNOWN", "RESULT_UNKNOWN",
                    "command result is unknown", commandResultUrl(execution.commandId()),
                    execution.prospectiveOrderIds(), null, null);
        }
        CoreCommandOutcome.NotAccepted rejection = (CoreCommandOutcome.NotAccepted) execution.outcome();
        return new OrderCommandReceipt(execution.commandId(), "NOT_ACCEPTED", rejection.reason().name(),
                rejection.reason().name(), null, execution.prospectiveOrderIds(), null,
                rejection.rawOfferResult());
    }

    private String commandResultUrl(UUID commandId) {
        return commandResultUrl(configuredProductLine(), commandId);
    }

    static String commandResultUrl(ProductLine productLine, UUID commandId) {
        return OrderCommandReceipt.commandResultUrl(commandId) + "?productLine=" + productLine.name();
    }

    static UUID batchCommandId(ProductLine productLine, long userId, String kind, String batchKey) {
        return StableOrderIdentity.commandId(productLine, userId, "batch:" + kind + ':' + batchKey);
    }

    private ProductLine configuredProductLine() {
        return requireProductLine(properties.getProductLine());
    }

    private static ProductLine requireProductLine(ProductLine productLine) {
        if (productLine == null) {
            throw new IllegalStateException("trading provider product line is required");
        }
        return productLine;
    }

    OrderAeronGateway requireAeron() {
        if (aeron == null) throw new IllegalStateException("Aeron order gateway is required");
        return aeron;
    }

    ProductLine requireCurrentProductLine(ProductLine productLine) {
        if (productLine != currentProductLine()) {
            throw new IllegalArgumentException("product line does not match this order core");
        }
        return productLine;
    }

    private TestOrderResponse testRejected(ValidationResult validation, String stage) {
        return new TestOrderResponse(false, validation.rejectReason(), validation.instrumentChangeId(),
                stage, null, null, 0L);
    }

    private void requireBatchSize(int size, int max, String field) {
        if (size < 1 || size > max) {
            throw new IllegalArgumentException(field + " size must be in [1, " + max + "]");
        }
    }

    private OrderBatchResponse orderBatchResponse(List<OrderBatchItemResponse> results) {
        int completed = (int) results.stream().filter(OrderBatchItemResponse::success).count();
        return new OrderBatchResponse(results.size(), completed, results.size() - completed, results);
    }

    List<OrderBatchItemResponse> cancelOrderChunks(List<CancelOrderRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        Map<Long, List<IndexedCancelRequest>> requestsByUser = new LinkedHashMap<>();
        for (int index = 0; index < requests.size(); index++) {
            CancelOrderRequest request = requests.get(index);
            validateCancelRequest(request);
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

    private OrderBatchResponse failedCancelBatch(List<CancelOrderRequest> requests, String message) {
        String reason = message == null || message.isBlank() ? "batch cancellation failed" : message;
        List<OrderBatchItemResponse> results = new ArrayList<>(requests.size());
        for (int index = 0; index < requests.size(); index++) {
            results.add(new OrderBatchItemResponse(index, false, reason, null));
        }
        return new OrderBatchResponse(results.size(), 0, results.size(), results);
    }

    private <T> T terminalBatchResult(OrderCommandReceipt receipt, Class<T> type) {
        if (type.isInstance(receipt.result())) {
            return type.cast(receipt.result());
        }
        if ("RESULT_UNKNOWN".equals(receipt.code())) {
            throw new com.surprising.aeron.client.ResultUnknownException(receipt.commandId(), receipt.message());
        }
        if ("NOT_ACCEPTED".equals(receipt.outcome())) {
            throw new CoreCommandOutcome.NotAcceptedException(
                    new CoreCommandOutcome.NotAccepted(
                            CoreCommandOutcome.NotAcceptedReason.valueOf(receipt.code()),
                            receipt.rawOfferResult() == null ? 0L : receipt.rawOfferResult()));
        }
        throw new IllegalStateException(receipt.code() + ": batch result is unavailable");
    }

    private void validateCancelRequest(CancelOrderRequest request) {
        if (request == null || request.userId() <= 0L || request.orderId() <= 0L) {
            throw new IllegalArgumentException("userId and orderId must be positive");
        }
    }

    private record IndexedCancelRequest(int index, CancelOrderRequest request) {
    }

    private PlaceOrderRequest normalize(PlaceOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("order request is required");
        }
        if (request.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (request.side() == null || request.orderType() == null || request.timeInForce() == null) {
            throw new IllegalArgumentException("side, orderType and timeInForce are required");
        }
        if (request.priceTicks() < 0 || request.quantitySteps() <= 0) {
            throw new IllegalArgumentException("priceTicks must be non-negative and quantitySteps must be positive");
        }
        String clientOrderId = normalizeClientOrderId(request.clientOrderId());
        String instrumentId = normalizeInstrumentId(request.instrumentId());
        long priceTicks = resolveLimitPrice(request, instrumentId);
        PositionSide positionSide = PositionSide.defaultIfNull(request.positionSide());
        if (currentProductLine() == ProductLine.SPOT && positionSide.isHedgeSide()) {
            throw new IllegalArgumentException("现货订单不支持 LONG/SHORT 仓位方向");
        }
        boolean reduceOnly = request.reduceOnly()
                || positionSide.isHedgeSide() && positionSide.isClosingSide(request.side());
        if (request.bboPriceMode() == null && clientOrderId.equals(request.clientOrderId())
                && instrumentId.equals(request.instrumentId()) && priceTicks == request.priceTicks()
                && reduceOnly == request.reduceOnly()) return request;
        return new PlaceOrderRequest(
                request.userId(),
                clientOrderId,
                instrumentId,
                request.side(),
                request.orderType(),
                request.timeInForce(),
                priceTicks,
                request.quantitySteps(),
                MarginMode.defaultIfNull(request.marginMode()),
                positionSide,
                reduceOnly,
                request.postOnly());
    }

    private long resolveLimitPrice(PlaceOrderRequest request, String instrumentId) {
        var mode = request.bboPriceMode();
        if (mode == null) return request.priceTicks();
        if (request.orderType() != OrderType.LIMIT || request.priceTicks() != 0) {
            throw new IllegalArgumentException("BBO requires a LIMIT order with priceTicks=0");
        }
        if (bboOrderBook == null) throw new IllegalStateException("BBO order book is unavailable");
        boolean buyBook = (request.side() == OrderSide.BUY) == mode.sameSide();
        var bookSide = buyBook ? CoreOrderSide.BUY : CoreOrderSide.SELL;
        var book = bboOrderBook.orderBookSnapshot(instrumentId, mode.depth());
        var prices = (buyBook ? book.bids() : book.asks()).stream()
                .filter(level -> level.quantitySteps() > 0 && level.priceTicks() > 0)
                .map(OrderBookLevel::priceTicks).distinct()
                .sorted(buyBook ? Comparator.reverseOrder() : Comparator.naturalOrder()).toList();
        if (prices.size() < mode.depth()) {
            throw new IllegalArgumentException("BBO_DEPTH_UNAVAILABLE: requested " + mode.depth()
                    + " levels on " + bookSide + ", available " + prices.size());
        }
        return prices.get(mode.depth() - 1);
    }

    private AmendOrderRequest normalizeAmend(AmendOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("amend order request is required");
        }
        if (request.userId() <= 0 || request.orderId() <= 0) {
            throw new IllegalArgumentException("userId and orderId must be positive");
        }
        String newClientOrderId = normalizeClientOrderId(request.newClientOrderId());
        if (request.priceTicks() != null && request.priceTicks() <= 0) {
            throw new IllegalArgumentException("priceTicks must be positive for amend");
        }
        if (request.quantitySteps() != null && request.quantitySteps() <= 0) {
            throw new IllegalArgumentException("quantitySteps must be positive for amend");
        }
        if (request.priceTicks() == null && request.quantitySteps() == null
                && request.timeInForce() == null && request.postOnly() == null) {
            throw new IllegalArgumentException("amend request must change price, quantity, timeInForce or postOnly");
        }
        if (request.timeInForce() == TimeInForce.IOC || request.timeInForce() == TimeInForce.FOK) {
            throw new IllegalArgumentException("amended resting order requires GTC or GTX");
        }
        return new AmendOrderRequest(request.userId(), request.orderId(), newClientOrderId,
                request.priceTicks(), request.quantitySteps(), request.timeInForce(), request.postOnly(),
                request.clientRequestId());
    }

    static String normalizeInstrumentId(String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank()) {
            throw new IllegalArgumentException("instrumentId is required");
        }
        String normalized = instrumentId.trim().toUpperCase();
        if (!com.surprising.product.api.utils.InstrumentIds.valid(normalized)) {
            throw new IllegalArgumentException("invalid instrumentId: " + instrumentId);
        }
        return normalized;
    }

    private String normalizeClientOrderId(String clientOrderId) {
        String normalized = emptyToNull(clientOrderId);
        if (normalized == null) {
            throw new IllegalArgumentException("clientOrderId is required");
        }
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("clientOrderId length must be <= 64");
        }
        return normalized;
    }

    static void requireOrderId(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public ProductLine currentProductLine() {
        return properties.getProductLine();
    }

    public enum CommandKind {
        PLACE,
        CANCEL,
        REPLACE,
        AMEND,
        PLACE_BATCH,
        CANCEL_BATCH,
        AMEND_BATCH
    }

    public record CommandExecution(UUID commandId, List<Long> prospectiveOrderIds,
                                   CoreCommandOutcome outcome, CommandKind kind) {
        public CommandExecution {
            if (commandId == null || prospectiveOrderIds == null || prospectiveOrderIds.isEmpty()
                    || outcome == null || kind == null) {
                throw new IllegalArgumentException("invalid order command execution");
            }
            prospectiveOrderIds = List.copyOf(prospectiveOrderIds);
        }
    }
}
