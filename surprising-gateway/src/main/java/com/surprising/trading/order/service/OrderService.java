package com.surprising.trading.order.service;

import com.surprising.trading.order.model.StableOrderIdentity;

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
    private final OrderBatchService batchOps;

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
                        @Autowired(required = false) BracketOrderDispatcher bracketOrderDispatcher) {
        this(properties, orderValidator, aeron, bboOrderBook, bracketOrderDispatcher,
                new OrderQueryService(properties, aeron, aeronOrderProjection,
                        tradingMetrics, tradeProjection, realtimeQueries));
    }

    public OrderService(TradingOrderProperties properties, OrderValidator orderValidator,
                        OrderAeronGateway aeron, MatchingMarketDataService bboOrderBook,
                        BracketOrderDispatcher bracketOrderDispatcher,
                        OrderQueryService queries) {
        this.properties = properties;
        this.orderValidator = orderValidator;
        this.aeron = aeron;
        this.bboOrderBook = bboOrderBook;
        this.bracketOrderDispatcher = bracketOrderDispatcher;
        this.queries = queries;
        this.batchOps = new OrderBatchService(this, queries);
    }

    OrderQueryService queries() {
        return queries;
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
        return registerBracket(prepared, receipt(submitPlaceCommand(prepared)));
    }

    public CompletionStage<OrderCommandReceipt> placeCommandAsync(PlaceOrderRequest request) {
        requireAeron();
        PlaceOrderRequest prepared = prepareOrder(request);
        return submitPlaceCommandAsync(prepared)
                .thenApply(execution -> registerBracket(prepared, receipt(execution)));
    }

    private OrderCommandReceipt registerBracket(PlaceOrderRequest prepared, OrderCommandReceipt receipt) {
        if (bracketOrderDispatcher != null && prepared.hasBracketOrders() && receipt != null
                && receipt.result() instanceof OrderResponse order) {
            bracketOrderDispatcher.registerBracket(prepared, order);
        }
        return receipt;
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

    PlaceOrderRequest prepareOrder(PlaceOrderRequest request) {
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
        return batchOps.placeBatch(request);
    }

    public OrderCommandReceipt placeBatchCommand(BatchPlaceOrderRequest request) {
        return batchOps.placeBatchCommand(request);
    }

    public CompletionStage<OrderCommandReceipt> placeBatchCommandAsync(BatchPlaceOrderRequest request) {
        return batchOps.placeBatchCommandAsync(request);
    }

    CommandExecution submitPlaceBatchCommand(String batchKey, List<PlaceOrderRequest> requests) {
        return batchOps.submitPlaceBatchCommand(batchKey, requests);
    }

    CompletionStage<CommandExecution> submitPlaceBatchCommandAsync(String batchKey, List<PlaceOrderRequest> requests) {
        return batchOps.submitPlaceBatchCommandAsync(batchKey, requests);
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
        return batchOps.amendBatch(request);
    }

    public OrderCommandReceipt amendBatchCommand(BatchAmendOrdersRequest request) {
        return batchOps.amendBatchCommand(request);
    }

    public CompletionStage<OrderCommandReceipt> amendBatchCommandAsync(BatchAmendOrdersRequest request) {
        return batchOps.amendBatchCommandAsync(request);
    }

    CommandExecution submitAmendBatchCommand(String batchKey, List<AmendOrderRequest> requests) {
        return batchOps.submitAmendBatchCommand(batchKey, requests);
    }

    CompletionStage<CommandExecution> submitAmendBatchCommandAsync(String batchKey, List<AmendOrderRequest> requests) {
        return batchOps.submitAmendBatchCommandAsync(batchKey, requests);
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
        return batchOps.cancelBatch(request);
    }

    public OrderCommandReceipt cancelBatchCommand(BatchCancelOrdersRequest request) {
        return batchOps.cancelBatchCommand(request);
    }

    public CompletionStage<OrderCommandReceipt> cancelBatchCommandAsync(BatchCancelOrdersRequest request) {
        return batchOps.cancelBatchCommandAsync(request);
    }

    CommandExecution submitCancelBatchCommand(String batchKey, List<CancelOrderRequest> requests) {
        return batchOps.submitCancelBatchCommand(batchKey, requests);
    }

    CompletionStage<CommandExecution> submitCancelBatchCommandAsync(String batchKey, List<CancelOrderRequest> requests) {
        return batchOps.submitCancelBatchCommandAsync(batchKey, requests);
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
        return batchOps.cancelOpenOrders(request);
    }

    /** 定时撤单从所属Core读取本批目标，完成判断不依赖公开查询投影的进度。 */
    OrderBatchResponse cancelTimerOrders(long userId, String instrumentId, int limit) {
        return batchOps.cancelTimerOrders(userId, instrumentId, limit);
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

    PlaceOrderCommand buildPlaceCommand(long orderId, PlaceOrderRequest request) {
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

    ProductLine configuredProductLine() {
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

    void requireBatchSize(int size, int max, String field) {
        OrderRequestNormalizer.requireBatchSize(size, max, field);
    }

    List<OrderBatchItemResponse> cancelOrderChunks(List<CancelOrderRequest> requests) {
        return batchOps.cancelOrderChunks(requests);
    }

    <T> T terminalBatchResult(OrderCommandReceipt receipt, Class<T> type) {
        return OrderRequestNormalizer.terminalBatchResult(receipt, type);
    }

    void validateCancelRequest(CancelOrderRequest request) {
        OrderRequestNormalizer.validateCancelRequest(request);
    }

    private PlaceOrderRequest normalize(PlaceOrderRequest request) {
        return OrderRequestNormalizer.normalize(request, currentProductLine(), bboOrderBook);
    }

    AmendOrderRequest normalizeAmend(AmendOrderRequest request) {
        return OrderRequestNormalizer.normalizeAmend(request);
    }

    static String normalizeInstrumentId(String instrumentId) {
        return OrderRequestNormalizer.normalizeInstrumentId(instrumentId);
    }

    private String normalizeClientOrderId(String clientOrderId) {
        return OrderRequestNormalizer.normalizeClientOrderId(clientOrderId);
    }

    static void requireOrderId(long orderId) {
        OrderRequestNormalizer.requireOrderId(orderId);
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
