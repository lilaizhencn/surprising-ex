package com.surprising.trading.order.service;

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
    private final AeronOrderProjectionRepository aeronOrderProjection;
    private final MatchingMarketDataService bboOrderBook;
    private final TradingMetricsService tradingMetrics;
    private final TradeProjectionRepository tradeProjection;
    private final ValkeyUserQueries realtimeQueries;
    private final BracketOrderCoordinator bracketOrderCoordinator;

    public OrderService(TradingOrderProperties properties, OrderValidator orderValidator,
                        OrderAeronGateway aeron, AeronOrderProjectionRepository aeronOrderProjection,
                        MatchingMarketDataService bboOrderBook, TradingMetricsService tradingMetrics,
                        TradeProjectionRepository tradeProjection, ValkeyUserQueries realtimeQueries) {
        this(properties, orderValidator, aeron, aeronOrderProjection, bboOrderBook,
                tradingMetrics, tradeProjection, realtimeQueries, null);
    }

    @Autowired
    public OrderService(TradingOrderProperties properties, OrderValidator orderValidator,
                        OrderAeronGateway aeron, AeronOrderProjectionRepository aeronOrderProjection,
                        MatchingMarketDataService bboOrderBook, TradingMetricsService tradingMetrics,
                        TradeProjectionRepository tradeProjection, ValkeyUserQueries realtimeQueries,
                        @Autowired(required = false) BracketOrderCoordinator bracketOrderCoordinator) {
        this.properties = properties;
        this.orderValidator = orderValidator;
        this.aeron = aeron;
        this.aeronOrderProjection = aeronOrderProjection;
        this.bboOrderBook = bboOrderBook;
        this.tradingMetrics = tradingMetrics;
        this.tradeProjection = tradeProjection;
        this.realtimeQueries = realtimeQueries;
        this.bracketOrderCoordinator = bracketOrderCoordinator;
    }

    public ObjectNode tradingMetrics(int window, int limit, ProductLine requestedProduct) {
        var product = currentProductLine();
        if (requestedProduct != null && requestedProduct != product)
            throw new IllegalArgumentException("product line does not match provider");
        if (tradingMetrics == null) throw new ReadViewUnavailableException("TRADING_METRICS_UNAVAILABLE");
        return tradingMetrics.require(product, window, limit);
    }

    public TradeFillQueryResponse ownFills(long userId, String instrumentId,
            Long orderId, Long startTime, Long endTime, int limit, TradeReadCursor.Fill cursor) {
        if (tradeProjection == null) throw new ReadViewUnavailableException("TRADE_READ_VIEW_UNAVAILABLE");
        String instrument = instrumentId == null || instrumentId.isBlank() ? null : normalizeInstrumentId(instrumentId);
        return tradeProjection.ownFills(currentProductLine(), userId, instrument, orderId, startTime, endTime, limit, cursor);
    }

    public AdminTradeQueryResponse adminTrades(Long userId, String instrumentId,
            Long orderId, int limit, TradeReadCursor.Execution cursor, boolean ascending, ProductLine requestedProduct) {
        ProductLine product = currentProductLine();
        if (requestedProduct != null && requestedProduct != product)
            throw new IllegalArgumentException("product line does not match provider");
        if (tradeProjection == null) throw new ReadViewUnavailableException("TRADE_READ_VIEW_UNAVAILABLE");
        String instrument = instrumentId == null || instrumentId.isBlank() ? null : normalizeInstrumentId(instrumentId);
        return tradeProjection.search(product, userId, instrument, orderId, limit, cursor, ascending);
    }

    public OrderPriceProtectionResponse priceProtection() {
        return new OrderPriceProtectionResponse(
                currentProductLine(), properties.getRisk().getMarketMaxSlippagePpm());
    }

    public OrderResponse place(PlaceOrderRequest request) {
        requireAeron();
        PlaceOrderRequest prepared = prepareOrder(request);
        OrderResponse response = submitPlaceOrder(prepared);
        if (bracketOrderCoordinator != null && prepared.hasBracketOrders() && response != null) {
            bracketOrderCoordinator.registerBracket(prepared, response);
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
        if (commandId == null) {
            throw new IllegalArgumentException("commandId is required");
        }
        return requireProjection().commandResult(currentProductLine(), userId, commandId);
    }

    public OrderCommandReceipt commandResultFromCore(UUID commandId) {
        if (commandId == null) throw new IllegalArgumentException("commandId is required");
        try {
            CoreResponse response = requireAeron().commandResult(commandId);
            if (response.resultCode() == CoreResultCode.RESULT_UNKNOWN_OUTSIDE_RETENTION) {
                return new OrderCommandReceipt(commandId, "OUTSIDE_RETENTION",
                        CoreResultCode.RESULT_UNKNOWN_OUTSIDE_RETENTION.name(),
                        CoreResultCode.RESULT_UNKNOWN_OUTSIDE_RETENTION.name(), null, List.of(),
                        null, null);
            }
            if (response.resultCode() == CoreResultCode.MATCHING_PENDING) {
                return new OrderCommandReceipt(commandId, "MATCHING_PENDING",
                        CoreResultCode.MATCHING_PENDING.name(), "matching pending",
                        commandResultUrl(commandId), List.of(),
                        null, null);
            }
            return new OrderCommandReceipt(commandId, "TERMINAL", response.resultCode().name(),
                    response.resultCode() == CoreResultCode.NONE ? "completed" : response.resultCode().name(),
                    commandResultUrl(commandId), List.of(), null, null);
        } catch (com.surprising.aeron.client.ResultUnknownException exception) {
            return new OrderCommandReceipt(commandId, "RESULT_UNKNOWN", "RESULT_UNKNOWN",
                    "command result is unknown", commandResultUrl(commandId), List.of(),
                    null, null);
        } catch (CoreCommandOutcome.NotAcceptedException exception) {
            CoreCommandOutcome.NotAccepted rejection = exception.rejection();
            return new OrderCommandReceipt(commandId, "NOT_ACCEPTED", rejection.reason().name(),
                    rejection.reason().name(), null, List.of(), null, rejection.rawOfferResult());
        }
    }

    public OrderSubmissionResponse submission(long userId, String clientOrderId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        String key = normalizeClientOrderId(clientOrderId);
        var product = currentProductLine();
        return requireProjection().submission(product, userId, key, StableOrderIdentity.commandId(product, userId, key));
    }

    public CancellationPlanResponse cancellationPlan(long userId, String instrumentId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        var product = currentProductLine();
        String instrument = instrumentId == null ? null : normalizeInstrumentId(instrumentId);
        var view = requireRealtimeQueries().require(product, userId, null);
        var ids = view.openOrders().stream().filter(v -> instrument == null || instrument.equals(v.instrumentId()))
                .map(CoreOrderStateView::orderId).distinct().sorted().toList();
        return new CancellationPlanResponse(userId, product, instrument, ids);
    }

    public OrderCommandReceipt cancellationBatch(long userId, String batchKey) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        var product = currentProductLine();
        return requireProjection().cancellationBatch(product, userId,
                batchCommandId(product, userId, "cancel", normalizeClientOrderId(batchKey)));
    }

    public OrderCommandReceipt amendment(long userId, String clientRequestId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        var product = currentProductLine();
        return requireProjection().amendment(product, userId,
                StableOrderIdentity.replacementCommandId(product, userId, normalizeClientOrderId(clientRequestId)));
    }

    public OrderCommandReceipt cancellation(long userId, long orderId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        requireOrderId(orderId);
        var product = currentProductLine();
        return requireProjection().cancellation(product, userId, orderId,
                StableOrderIdentity.commandId(product, userId, "cancel:" + orderId));
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
        return requireAeron().openOrders(userId, instrumentId, beforeOrderId, limit).stream()
                .map(OrderService::toOrder)
                .toList();
    }

    public List<OrderResponse> lifecycleOpenOrders(String instrumentId, int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        ProductLine productLine = properties.getKafka().getProductLine();
        return requireAeron().lifecycleOpenOrders(instrumentId, limit).stream()
                .map(view -> requireLocalOrder(view, productLine))
                .toList();
    }

    public OrderResponse get(long userId, long orderId) {
        return get(userId, orderId, null);
    }

    public OrderResponse get(long userId, long orderId, Long minExportSequence) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        requireOrderId(orderId);
        var order = requireRealtimeQueries().order(currentProductLine(), userId, orderId, minExportSequence);
        OrderResponse current = order == null
                ? terminalOrder(requireProjection().byOrder(currentProductLine(), userId, orderId, minExportSequence))
                : toOrder(order);
        if (current == null) throw new IllegalStateException("order not found: " + orderId);
        return current;
    }

    public OrderResponse getByClientOrderId(long userId, String clientOrderId) {
        return getByClientOrderId(userId, clientOrderId, null);
    }

    public OrderResponse getByClientOrderId(long userId, String clientOrderId, Long minExportSequence) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        String normalized = normalizeClientOrderId(clientOrderId);
        OrderResponse current = requireRealtimeQueries().require(currentProductLine(), userId, minExportSequence).openOrders().stream()
                .filter(v -> normalized.equals(v.clientOrderId())).map(OrderService::toOrder).findFirst().orElse(null);
        if (current == null) current = terminalOrder(requireProjection().byClientOrderId(
                currentProductLine(), userId, normalized, minExportSequence));
        if (current == null) throw new IllegalStateException("order not found: " + normalized);
        return current;
    }

    public OrderQueryResponse openOrders(long userId, String instrumentId, ActiveOrderPageQuery query,
                                         Long minExportSequence) {
        int limit = query.limit();
        String normalizedInstrumentId = instrumentId;
        long beforeOrderId = query.beforeId();
        List<OrderResponse> orders = requireRealtimeQueries().require(currentProductLine(), userId, minExportSequence).openOrders().stream()
                .filter(v -> normalizedInstrumentId == null || normalizedInstrumentId.equals(v.instrumentId()))
                .filter(v -> beforeOrderId == 0 || v.orderId() < beforeOrderId)
                .sorted(Comparator.comparingLong(CoreOrderStateView::orderId).reversed())
                .limit(limit + 1).map(OrderService::toOrder).toList();
        boolean hasMore = orders.size() > limit;
        if (hasMore) orders = new ArrayList<>(orders.subList(0, limit));
        String nextCursor = hasMore && !orders.isEmpty()
                ? ActiveOrderCursors.encode(orders.getLast().orderId()) : null;
        return new OrderQueryResponse(orders.size(), orders, nextCursor, hasMore, "createdAt.desc", limit);
    }

    private List<OrderResponse> activeOpenOrders(ProductLine productLine, Long userId, String instrumentId,
                                                 int limit) {
        if (productLine != currentProductLine()) {
            throw new IllegalArgumentException("product line does not match this order core");
        }
        if (userId != null) return openOrders(userId, instrumentId, new ActiveOrderPageQuery(limit, 0L), null).orders();
        return readyProjection(requireProjection().openOrders(productLine, null, instrumentId, null, limit, null));
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
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        if (minimumOrderId != null && minimumOrderId <= 0L) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        Instant startTime = epochMillis(startTimeMillis, "startTime");
        Instant endTime = epochMillis(endTimeMillis, "endTime");
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw new IllegalArgumentException("startTime must not be after endTime");
        }
        OrderStatus historyStatus = status == null || status.isBlank() ? null : OrderStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        if (historyStatus != null && !Set.of(OrderStatus.FILLED, OrderStatus.CANCELED, OrderStatus.REJECTED).contains(historyStatus))
            throw new IllegalArgumentException("history status must be FILLED, CANCELED or REJECTED");
        String normalizedInstrumentId = instrumentId == null || instrumentId.isBlank() ? null : normalizeInstrumentId(instrumentId);
        return toQueryResponse(requireProjection().historyOrders(currentProductLine(), userId, normalizedInstrumentId,
                limit, minimumOrderId, startTimeMillis, endTimeMillis, cursor, minExportSequence, historyStatus),
                "updatedAt.desc", limit);
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
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (orderId != null && orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String normalizedInstrumentId = instrumentId == null || instrumentId.isBlank() ? null : normalizeInstrumentId(instrumentId);
        OrderStatus normalizedStatus = status == null || status.isBlank()
                ? null
                : OrderStatus.valueOf(status.trim().toUpperCase());
        ProductLine resolvedProductLine = productLine == null ? currentProductLine() : productLine;
        boolean ascending = "createdAt.asc".equalsIgnoreCase(sort);
        return toQueryResponse(requireProjection().search(resolvedProductLine, userId, normalizedInstrumentId,
                normalizedStatus, orderId, cursor, ascending, limit, null),
                ascending ? "createdAt.asc" : "createdAt.desc", limit);
    }

    public AdminCancelOrderResult adminCancelOrder(long orderId, String reason) {
        return adminCancelOrder(orderId, reason, null);
    }

    public AdminCancelOrderResult adminCancelOrder(long orderId, String reason, ProductLine productLine) {
        requireOrderId(orderId);
        ProductLine resolved = productLine == null ? currentProductLine() : productLine;
        requireCurrentProductLine(resolved);
        OrderResponse selected = readyProjection(requireProjection().byOrder(resolved, null, orderId, null))
                .stream().findFirst().orElse(null);
        if (selected == null) throw new IllegalStateException("order not found: " + orderId);
        requireAeron();
        OrderResponse canceled = cancelById(selected.userId(), selected.orderId());
        boolean requested = cancelSucceeded(canceled.status());
        return new AdminCancelOrderResult(canceled.orderId(), canceled.userId(), canceled.instrumentId(),
                canceled.status(), requested, requested ? "cancel requested" : "order is already "
                + canceled.status().name(), canceled);
    }

    public AdminCancelOrdersResponse adminCancelOrders(AdminBatchCancelOrdersRequest request) {
        return adminCancelOrders(request, null);
    }

    public AdminCancelOrdersResponse adminCancelOrders(AdminBatchCancelOrdersRequest request, ProductLine productLine) {
        Long userId = request == null ? null : request.userId();
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        String instrumentId = request == null || request.instrumentId() == null || request.instrumentId().isBlank()
                ? null
                : normalizeInstrumentId(request.instrumentId());
        int limit = request == null || request.limit() == null ? 100 : request.limit();
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        adminCancelReason(request == null ? null : request.reason());
        ProductLine resolved = productLine == null ? currentProductLine() : productLine;
        requireCurrentProductLine(resolved);
        List<OrderResponse> selected = activeOpenOrders(resolved, userId, instrumentId, limit);
        requireAeron();
        List<CancelOrderRequest> requests = selected.stream()
                .map(order -> new CancelOrderRequest(order.userId(), order.orderId()))
                .toList();
        List<OrderBatchItemResponse> canceled = cancelOrderChunks(requests);
        List<AdminCancelOrderResult> results = canceled.stream()
                .map(item -> {
                    OrderResponse selectedOrder = selected.get(item.index());
                    OrderResponse order = item.order() == null ? selectedOrder : item.order();
                    boolean requested = item.success() && cancelSucceeded(order.status());
                    String message = requested ? "cancel completed"
                            : item.success() ? "order is already " + order.status().name() : item.message();
                    return new AdminCancelOrderResult(order.orderId(), order.userId(), order.instrumentId(),
                            order.status(), requested, message, order);
                })
                .toList();
        int canceledCount = (int) results.stream().filter(AdminCancelOrderResult::cancelRequested).count();
        return new AdminCancelOrdersResponse(selected.size(), canceledCount, selected.size() - canceledCount, results);
    }

    public AdminCancelOrdersPreviewResponse adminCancelPreview(Long userId, String instrumentId, int limit) {
        return adminCancelPreview(userId, instrumentId, limit, null);
    }

    public AdminCancelOrdersPreviewResponse adminCancelPreview(
            Long userId, String instrumentId, int limit, ProductLine productLine) {
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String normalizedInstrumentId = instrumentId == null || instrumentId.isBlank() ? null : normalizeInstrumentId(instrumentId);
        ProductLine resolvedProductLine = productLine == null ? currentProductLine() : productLine;
        List<OrderResponse> orders = activeOpenOrders(resolvedProductLine, userId, normalizedInstrumentId, limit);
        long quantity = orders.stream().mapToLong(OrderResponse::remainingQuantitySteps).sum();
        int buys = (int) orders.stream().filter(order -> order.side() == OrderSide.BUY).count();
        return new AdminCancelOrdersPreviewResponse(userId, normalizedInstrumentId, orders.size(), orders.size(),
                quantity, buys, orders.size() - buys, orders);
    }

    public AdminCancelOrdersResponse adminCancelByInstrument(AdminCancelByInstrumentRequest request) {
        return adminCancelByInstrument(request, null);
    }

    public AdminCancelOrdersResponse adminCancelByInstrument(AdminCancelByInstrumentRequest request, ProductLine productLine) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        String instrumentId = normalizeInstrumentId(request.instrumentId());
        return adminCancelOrders(new AdminBatchCancelOrdersRequest(null, instrumentId, request.limit(), request.reason()),
                productLine);
    }

    /**
     * 到期生命周期只发起撤单，最终状态仍由撮合结果驱动。
     */
    public int requestLifecycleCancellation(String instrumentId, int limit) {
        String normalizedInstrumentId = normalizeInstrumentId(instrumentId);
        List<OrderResponse> selected = lifecycleOpenOrders(normalizedInstrumentId, limit);
        List<CancelOrderRequest> requests = selected.stream()
                .map(order -> new CancelOrderRequest(order.userId(), order.orderId()))
                .toList();
        return (int) cancelOrderChunks(requests).stream()
                .filter(item -> item.success() && item.order() != null && cancelSucceeded(item.order().status()))
                .count();
    }

    public boolean hasLifecycleActiveOrders(String instrumentId) {
        return !lifecycleOpenOrders(normalizeInstrumentId(instrumentId), 1).isEmpty();
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
        return OrderCommandReceipt.commandResultUrl(commandId) + "?productLine=" + configuredProductLine().name();
    }

    static OrderResponse toOrder(CoreOrderStateView view) {
        return view == null ? null : requireOrder(view, "order query returned no order");
    }

    private static OrderResponse requireOrder(CoreOrderStateView view, String message) {
        if (view == null) throw new IllegalStateException(message);
        return new OrderResponse(view.orderId(), view.userId(), emptyToNullStatic(view.clientOrderId()), view.instrumentId(),
                OrderSide.valueOf(view.side().name()), OrderType.valueOf(view.orderType().name()),
                TimeInForce.valueOf(view.timeInForce().name()), view.priceTicks(), view.quantitySteps(),
                view.executedQuantitySteps(), view.remainingQuantitySteps(), MarginMode.valueOf(view.marginMode().name()),
                PositionSide.valueOf(view.positionSide().name()), view.makerFeeRatePpm(), view.takerFeeRatePpm(),
                view.reduceOnly(), view.postOnly(), status(view), null,
                Instant.ofEpochMilli(view.createdAtEpochMillis()), Instant.ofEpochMilli(view.updatedAtEpochMillis()),
                view.cumulativeFeeUnits(), view.getExecutedValueTicks(), view.getAveragePriceTicks());
    }

    private static OrderResponse requireLocalOrder(CoreOrderStateView view, ProductLine productLine) {
        if (view == null) {
            throw new IllegalStateException("lifecycle order query returned a missing order");
        }
        if (view.productLine() != productLine) {
            throw new IllegalStateException("lifecycle order product line does not match local core");
        }
        return requireOrder(view, "lifecycle order missing");
    }

    private static CoreOrderStateView commandOrder(CoreResponse response, long orderId) {
        CoreCommandResultView result = requireCommandResult(response);
        return result.orders().stream().filter(value -> value.orderId() == orderId).findFirst()
                .orElseThrow(() -> new IllegalStateException("command response is missing order: " + orderId));
    }

    private static CoreCommandResultView requireCommandResult(CoreResponse response) {
        if (response != null && response.status() == ResponseStatus.REJECTED) {
            throw new OrderCommandRejectedException(response.resultCode());
        }
        byte[] data = response == null ? null : response.data();
        if (data == null || data.length == 0) {
            throw new IllegalStateException("command response is missing result payload");
        }
        return CoreCommandResultCodec.decode(data);
    }

    private static CoreResponse terminalResponse(CommandExecution execution) {
        if (execution.outcome() instanceof CoreCommandOutcome.Terminal terminal) {
            return terminal.response();
        }
        if (execution.outcome() instanceof CoreCommandOutcome.ResultUnknown unknown) {
            throw new com.surprising.aeron.client.ResultUnknownException(unknown.originalCommandId(),
                    "Aeron command result is unknown");
        }
        throw new CoreCommandOutcome.NotAcceptedException(
                (CoreCommandOutcome.NotAccepted) execution.outcome());
    }

    private OrderCommandResult decodeResult(CommandKind kind, List<Long> prospectiveIds, byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        return switch (kind) {
            case PLACE, CANCEL -> {
                CoreCommandResultView result = CoreCommandResultCodec.decode(data);
                CoreOrderStateView order = result.orders().stream()
                        .filter(value -> value.orderId() == prospectiveIds.getFirst())
                        .findFirst().orElse(null);
                yield order == null && !result.orders().isEmpty() ? optionalOrder(result.orders().getFirst())
                        : optionalOrder(order);
            }
            case REPLACE, AMEND -> {
                CoreCommandResultView result = CoreCommandResultCodec.decode(data);
                CoreOrderStateView original = result.orders().stream()
                        .filter(value -> value.orderId() == prospectiveIds.getFirst()).findFirst().orElse(null);
                CoreOrderStateView replacement = result.orders().stream()
                        .filter(value -> value.orderId() == prospectiveIds.getLast()).findFirst().orElse(null);
                yield new AmendOrderResponse(optionalOrder(original),
                        optionalOrder(replacement), true, "order amended");
            }
            case PLACE_BATCH, CANCEL_BATCH -> {
                CoreOrderBatchResult batch = TradingOrderBatchCodec.decodeResult(data);
                List<OrderBatchItemResponse> items = batch.items().stream()
                        .map(item -> new OrderBatchItemResponse(item.index(), item.status() == ResponseStatus.APPLIED,
                                item.resultCode().name(), optionalOrder(item.order())))
                        .toList();
                int completed = (int) items.stream().filter(OrderBatchItemResponse::success).count();
                yield new OrderBatchResponse(items.size(), completed, items.size() - completed, items);
            }
            case AMEND_BATCH -> {
                CoreOrderBatchResult batch = TradingOrderBatchCodec.decodeResult(data);
                List<AmendOrderBatchItemResponse> items = batch.items().stream()
                        .map(item -> new AmendOrderBatchItemResponse(item.index(),
                                item.status() == ResponseStatus.APPLIED, item.resultCode().name(),
                                item.order() == null ? null : new AmendOrderResponse(
                                        null, optionalOrder(item.order()), true, item.resultCode().name())))
                        .toList();
                int completed = (int) items.stream().filter(AmendOrderBatchItemResponse::success).count();
                yield new AmendOrderBatchResponse(items.size(), completed, items.size() - completed, items);
            }
        };
    }

    private static OrderResponse optionalOrder(CoreOrderStateView view) {
        return view == null ? null : requireOrder(view, "order result is missing");
    }

    static UUID batchCommandId(ProductLine productLine, long userId, String kind, String batchKey) {
        return StableOrderIdentity.commandId(productLine, userId, "batch:" + kind + ':' + batchKey);
    }

    private static void requireBatchKey(String batchKey) {
        requireClientKey(batchKey, "batchKey");
    }

    private static void requireBatchListSize(List<?> requests, int maximum, String field) {
        if (requests == null || requests.isEmpty() || requests.size() > maximum) {
            throw new IllegalArgumentException(field + " size must be in [1, " + maximum + "]");
        }
    }

    private static long requireSingleUser(List<?> requests) {
        long userId = userId(requests.getFirst());
        if (userId <= 0L || requests.stream().anyMatch(request -> userId(request) != userId)) {
            throw new IllegalArgumentException("batch orders must belong to one positive user");
        }
        return userId;
    }

    private static long userId(Object request) {
        return switch (request) {
            case PlaceOrderRequest value -> value.userId();
            case AmendOrderRequest value -> value.userId();
            case CancelOrderRequest value -> value.userId();
            default -> throw new IllegalArgumentException("unsupported order batch request");
        };
    }

    private static OrderStatus status(CoreOrderStateView view) {
        if ("OPEN".equals(view.status())) {
            return view.executedQuantitySteps() == 0 ? OrderStatus.ACCEPTED : OrderStatus.PARTIALLY_FILLED;
        }
        return OrderStatus.valueOf(view.status());
    }

    private ProductLine configuredProductLine() {
        return requireProductLine(properties.getKafka().getProductLine());
    }

    private static ProductLine requireProductLine(ProductLine productLine) {
        if (productLine == null) {
            throw new IllegalStateException("trading provider product line is required");
        }
        return productLine;
    }

    private static String requireClientKey(String key, String name) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return key;
    }

    private static String emptyToNullStatic(String value) { return value == null || value.isEmpty() ? null : value; }
    private static CoreOrderSide side(OrderSide value) { return CoreOrderSide.valueOf(value.name()); }
    private static CoreMarginMode marginMode(MarginMode value) { return CoreMarginMode.valueOf(value.name()); }
    private static CorePositionSide positionSide(PositionSide value) { return CorePositionSide.valueOf(value.name()); }
    private static CoreOrderType orderType(OrderType value) { return CoreOrderType.valueOf(value.name()); }
    private static CoreTimeInForce timeInForce(TimeInForce value) { return CoreTimeInForce.valueOf(value.name()); }

    private AeronOrderProjectionRepository requireProjection() {
        if (aeronOrderProjection == null) {
            throw new IllegalStateException("order projection repository is required");
        }
        return aeronOrderProjection;
    }

    private ValkeyUserQueries requireRealtimeQueries() {
        if (realtimeQueries == null) throw new ReadViewUnavailableException("READ_VIEW_UNAVAILABLE");
        return realtimeQueries;
    }

    private OrderResponse terminalOrder(ProjectionReadResult result) {
        return readyProjection(result).stream().filter(order -> switch (order.status()) {
            case FILLED, CANCELED, REJECTED -> true;
            default -> false;
        }).findFirst().orElse(null);
    }

    private List<OrderResponse> readyProjection(ProjectionReadResult result) {
        if (result.status() == ProjectionReadResult.Status.PROJECTION_LAG) {
            throw new ProjectionReadResult.ProjectionLagException(result.observedExportSequence(),
                    result.requiredExportSequence());
        }
        if (result.status() == ProjectionReadResult.Status.RESPONSE_TOO_LARGE) {
            throw new ProjectionReadResult.ResponseTooLargeException(result.observedExportSequence(),
                    result.requiredExportSequence(), result.nextCursor());
        }
        return result.orders();
    }

    private OrderQueryResponse toQueryResponse(ProjectionReadResult result, String sort, int limit) {
        List<OrderResponse> orders = readyProjection(result);
        return new OrderQueryResponse(orders.size(), orders, result.nextCursor(), result.hasMore(), sort, limit);
    }

    private OrderAeronGateway requireAeron() {
        if (aeron == null) throw new IllegalStateException("Aeron order gateway is required");
        return aeron;
    }

    private ProductLine requireCurrentProductLine(ProductLine productLine) {
        if (productLine != currentProductLine()) {
            throw new IllegalArgumentException("product line does not match this order core");
        }
        return productLine;
    }

    private String adminCancelReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "admin cancel";
        }
        String normalized = reason.trim();
        if (normalized.length() > 500) {
            normalized = normalized.substring(0, 500);
        }
        return "admin cancel: " + normalized;
    }

    private static boolean cancelSucceeded(OrderStatus status) {
        return status == OrderStatus.CANCEL_REQUESTED || status == OrderStatus.CANCELED;
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

    private List<OrderBatchItemResponse> cancelOrderChunks(List<CancelOrderRequest> requests) {
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

    private String normalizeInstrumentId(String instrumentId) {
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

    private void requireOrderId(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Instant epochMillis(Long value, String field) {
        if (value == null) {
            return null;
        }
        if (value < 0L) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        try {
            return Instant.ofEpochMilli(value);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(field + " is invalid", ex);
        }
    }

    public ProductLine currentProductLine() {
        return properties.getKafka().getProductLine();
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
