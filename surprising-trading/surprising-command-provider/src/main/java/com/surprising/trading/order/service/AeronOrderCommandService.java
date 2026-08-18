package com.surprising.trading.order.service;

import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.client.CoreCommandOutcome;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreCommandResultCodec;
import com.surprising.aeron.protocol.CoreCommandResultView;
import com.surprising.aeron.protocol.CoreOrderBatchResult;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AmendOrderBatchItemResponse;
import com.surprising.trading.api.model.AmendOrderBatchResponse;
import com.surprising.trading.api.model.BatchAmendOrdersRequest;
import com.surprising.trading.api.model.BatchCancelOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceOrderRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderBatchItemResponse;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.OrderCommandResult;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.MarketPriceProtection;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.InstrumentRuleLookup;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import com.surprising.trading.order.model.ValidationResult;
import com.surprising.trading.order.model.MarkPriceLookup;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AeronOrderCommandService {

    private final OrderAeronGateway aeron;
    private final InstrumentRuleLookup instrumentRules;
    private final MarkPriceLookup markPrices;
    private final TradingOrderProperties properties;

    public AeronOrderCommandService(OrderAeronGateway aeron, AeronOrderIdGenerator orderIds,
                                    InstrumentRuleLookup instrumentRules, MarkPriceLookup markPrices,
                                    TradingOrderProperties properties) {
        this.aeron = aeron;
        this.instrumentRules = instrumentRules;
        this.markPrices = markPrices;
        this.properties = properties;
    }

    public OrderResponse place(
            com.surprising.trading.api.model.PlaceOrderRequest request,
            ValidationResult validation,
            OrderFeeSnapshot fee) {
        CommandExecution execution = placeCommand(request, validation, fee);
        return requireOrder(commandOrder(terminalResponse(execution), execution.prospectiveOrderIds().getFirst()),
                "placed order missing");
    }

    public com.surprising.trading.api.model.AmendOrderResponse replace(
            OrderResponse original,
            com.surprising.trading.api.model.PlaceOrderRequest replacement,
            ValidationResult validation,
            OrderFeeSnapshot fee) {
        String clientOrderId = requireClientKey(replacement.clientOrderId(), "clientOrderId");
        ProductLine productLine = requireProductLine(fee.productLine());
        long replacementOrderId = StableOrderIdentity.replacementOrderId(
                productLine, replacement.userId(), clientOrderId);
        PlaceOrderCommand replacementCommand = buildPlaceCommand(replacementOrderId, replacement, validation, fee);
        UUID commandId = StableOrderIdentity.replacementCommandId(productLine, replacement.userId(), clientOrderId);
        var response = aeron.commandOutcome(CoreMessageType.REPLACE_ORDER, commandId, replacement.userId(),
                TradingCommandCodec.encodeReplaceOrder(new ReplaceOrderCommand(original.orderId(), replacementCommand)));
        CoreResponse responseValue = terminalResponse(new CommandExecution(commandId,
                List.of(original.orderId(), replacementOrderId), response, CommandKind.REPLACE));
        CoreCommandResultView result = requireCommandResult(responseValue);
        CoreOrderStateView canceledView = result.orders().stream()
                .filter(value -> value.orderId() == original.orderId()).findFirst().orElse(null);
        CoreOrderStateView placedView = result.orders().stream()
                .filter(value -> value.orderId() == replacementOrderId).findFirst().orElse(null);
        return new com.surprising.trading.api.model.AmendOrderResponse(
                requireOrder(canceledView, "replaced original order missing"),
                requireOrder(placedView, "replacement order missing"), true,
                placedView.status().equals("REJECTED") ? "replacement rejected" : "order replaced");
    }

    public com.surprising.trading.api.model.AmendOrderResponse replace(
            com.surprising.trading.api.model.AmendOrderRequest request) {
        CommandExecution execution = replaceCommand(request);
        CoreCommandResultView result = requireCommandResult(terminalResponse(execution));
        CoreOrderStateView canceledView = result.orders().stream()
                .filter(value -> value.orderId() == request.orderId()).findFirst().orElse(null);
        CoreOrderStateView placedView = result.orders().stream()
                .filter(value -> value.orderId() == execution.prospectiveOrderIds().getLast()).findFirst().orElse(null);
        return new com.surprising.trading.api.model.AmendOrderResponse(
                requireOrder(canceledView, "amended original order missing"),
                requireOrder(placedView, "amended replacement order missing"), true,
                placedView.status().equals("REJECTED") ? "replacement rejected" : "order amended");
    }

    public CommandExecution replaceCommand(com.surprising.trading.api.model.AmendOrderRequest request) {
        String clientOrderId = requireClientKey(request.newClientOrderId(), "newClientOrderId");
        String clientRequestId = requireClientKey(request.clientRequestId(), "clientRequestId");
        ProductLine productLine = configuredProductLine();
        long replacementOrderId = StableOrderIdentity.replacementOrderId(productLine, request.userId(), clientOrderId);
        AmendOrderCommand command = new AmendOrderCommand(request.orderId(), replacementOrderId,
                clientOrderId, request.priceTicks(), request.quantitySteps(),
                request.timeInForce() == null ? null : timeInForce(request.timeInForce()), request.postOnly());
        UUID commandId = StableOrderIdentity.replacementCommandId(productLine, request.userId(), clientRequestId);
        CoreCommandOutcome outcome = aeron.commandOutcome(CoreMessageType.AMEND_ORDER, commandId, request.userId(),
                TradingCommandCodec.encodeAmendOrder(command));
        return new CommandExecution(commandId, List.of(request.orderId(), replacementOrderId), outcome,
                CommandKind.AMEND);
    }

    private PlaceOrderCommand buildPlaceCommand(
            long orderId,
            com.surprising.trading.api.model.PlaceOrderRequest request,
            ValidationResult validation,
            OrderFeeSnapshot fee) {
        InstrumentRule instrument = instrumentRules.currentRule(request.symbol())
                .filter(value -> value.version() == validation.instrumentVersion())
                .orElseThrow(() -> new IllegalStateException("instrument snapshot changed before Aeron submit"));
        long matchingPriceTicks = matchingPriceTicks(request, validation.instrumentVersion());
        String reservationAsset = instrument.spot()
                ? (request.side() == OrderSide.BUY ? instrument.quoteAsset() : instrument.baseAsset())
                : instrument.settleAsset();
        return new PlaceOrderCommand(orderId, request.symbol(), validation.instrumentVersion(),
                instrument.baseAsset(), instrument.quoteAsset(), instrument.settleAsset(), side(request.side()),
                request.priceTicks(), request.quantitySteps(), request.reduceOnly(), marginMode(request.marginMode()),
                positionSide(request.positionSide()), instrument.spot() ? ReservationKind.SPOT_ASSET
                : ReservationKind.DERIVATIVE_MARGIN, reservationAsset, 0,
                orderType(request.orderType()), timeInForce(request.timeInForce()), matchingPriceTicks,
                request.postOnly(), requireClientKey(request.clientOrderId(), "clientOrderId"),
                fee.makerFeeRatePpm(), fee.takerFeeRatePpm());
    }

    public OrderAeronGateway.PreflightResult preflight(
            com.surprising.trading.api.model.PlaceOrderRequest request,
            ValidationResult validation,
            OrderFeeSnapshot fee) {
        ProductLine productLine = requireProductLine(fee.productLine());
        long orderId = StableOrderIdentity.orderId(productLine, request.userId(),
                requireClientKey(request.clientOrderId(), "clientOrderId"));
        return aeron.preflight(request.userId(), buildPlaceCommand(orderId, request, validation, fee));
    }

    public OrderResponse cancel(long userId, long orderId) {
        CommandExecution execution = cancelCommand(userId, orderId);
        CoreOrderStateView responseOrder = commandOrder(terminalResponse(execution), orderId);
        return requireOrder(responseOrder, "canceled order missing");
    }

    public CommandExecution cancelCommand(long userId, long orderId) {
        UUID commandId = StableOrderIdentity.commandId(configuredProductLine(), userId, "cancel:" + orderId);
        CoreCommandOutcome outcome = aeron.commandOutcome(CoreMessageType.CANCEL_ORDER, commandId, userId,
                TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(orderId)));
        return new CommandExecution(commandId, List.of(orderId), outcome, CommandKind.CANCEL);
    }

    public CommandExecution placeCommand(
            com.surprising.trading.api.model.PlaceOrderRequest request,
            ValidationResult validation,
            OrderFeeSnapshot fee) {
        String clientOrderId = requireClientKey(request.clientOrderId(), "clientOrderId");
        ProductLine productLine = requireProductLine(fee.productLine());
        long orderId = StableOrderIdentity.orderId(productLine, request.userId(), clientOrderId);
        PlaceOrderCommand command = buildPlaceCommand(orderId, request, validation, fee);
        UUID commandId = StableOrderIdentity.commandId(productLine, request.userId(), clientOrderId);
        CoreCommandOutcome outcome = aeron.commandOutcome(CoreMessageType.PLACE_ORDER, commandId, request.userId(),
                TradingCommandCodec.encodePlaceOrder(command));
        return new CommandExecution(commandId, List.of(orderId), outcome, CommandKind.PLACE);
    }

    public CommandExecution placeBatchCommand(String batchKey,
                                               List<com.surprising.trading.api.model.PlaceOrderRequest> requests,
                                               List<ValidationResult> validations,
                                               List<OrderFeeSnapshot> fees) {
        requireBatchKey(batchKey);
        requireBatchSize(requests, com.surprising.aeron.protocol.PlaceOrderBatchCommand.MAX_ORDERS, "orders");
        if (validations == null || fees == null || validations.size() != requests.size()
                || fees.size() != requests.size()) {
            throw new IllegalArgumentException("batch validation and fee counts must match orders");
        }
        ProductLine productLine = configuredProductLine();
        long userId = requireSingleUser(requests);
        List<PlaceOrderCommand> commands = new java.util.ArrayList<>(requests.size());
        List<Long> prospectiveIds = new java.util.ArrayList<>(requests.size());
        for (int index = 0; index < requests.size(); index++) {
            com.surprising.trading.api.model.PlaceOrderRequest request = requests.get(index);
            OrderFeeSnapshot fee = fees.get(index);
            if (requireProductLine(fee.productLine()) != productLine) {
                throw new IllegalArgumentException("batch fee product line does not match command provider");
            }
            String clientOrderId = requireClientKey(request.clientOrderId(), "clientOrderId");
            long orderId = StableOrderIdentity.orderId(productLine, userId, clientOrderId);
            prospectiveIds.add(orderId);
            commands.add(buildPlaceCommand(orderId, request, validations.get(index), fee));
        }
        UUID commandId = batchCommandId(productLine, userId, "place", batchKey);
        CoreCommandOutcome outcome = aeron.commandOutcome(CoreMessageType.PLACE_ORDER_BATCH, commandId, userId,
                TradingOrderBatchCodec.encodePlaceOrderBatch(new com.surprising.aeron.protocol.PlaceOrderBatchCommand(commands)));
        return new CommandExecution(commandId, prospectiveIds, outcome, CommandKind.PLACE_BATCH);
    }

    public CommandExecution amendBatchCommand(String batchKey,
                                               List<com.surprising.trading.api.model.AmendOrderRequest> requests) {
        requireBatchKey(batchKey);
        requireBatchSize(requests, com.surprising.aeron.protocol.AmendOrderBatchCommand.MAX_ORDERS, "orders");
        ProductLine productLine = configuredProductLine();
        long userId = requireSingleUser(requests);
        List<AmendOrderCommand> commands = new java.util.ArrayList<>(requests.size());
        List<Long> prospectiveIds = new java.util.ArrayList<>(requests.size());
        for (com.surprising.trading.api.model.AmendOrderRequest request : requests) {
            String clientOrderId = requireClientKey(request.newClientOrderId(), "newClientOrderId");
            long replacementOrderId = StableOrderIdentity.replacementOrderId(productLine, userId, clientOrderId);
            prospectiveIds.add(replacementOrderId);
            commands.add(new AmendOrderCommand(request.orderId(), replacementOrderId, clientOrderId,
                    request.priceTicks(), request.quantitySteps(),
                    request.timeInForce() == null ? null : timeInForce(request.timeInForce()), request.postOnly()));
        }
        UUID commandId = batchCommandId(productLine, userId, "amend", batchKey);
        CoreCommandOutcome outcome = aeron.commandOutcome(CoreMessageType.AMEND_ORDER_BATCH, commandId, userId,
                TradingOrderBatchCodec.encodeAmendOrderBatch(new com.surprising.aeron.protocol.AmendOrderBatchCommand(commands)));
        return new CommandExecution(commandId, prospectiveIds, outcome, CommandKind.AMEND_BATCH);
    }

    public CommandExecution cancelBatchCommand(String batchKey,
                                                List<com.surprising.trading.api.model.CancelOrderRequest> requests) {
        requireBatchKey(batchKey);
        requireBatchSize(requests, com.surprising.aeron.protocol.CancelOrderBatchCommand.MAX_ORDERS, "orders");
        ProductLine productLine = configuredProductLine();
        long userId = requireSingleUser(requests);
        List<com.surprising.aeron.protocol.CancelOrderCommand> commands = requests.stream()
                .map(request -> new com.surprising.aeron.protocol.CancelOrderCommand(request.orderId())).toList();
        List<Long> prospectiveIds = requests.stream().map(com.surprising.trading.api.model.CancelOrderRequest::orderId).toList();
        UUID commandId = batchCommandId(productLine, userId, "cancel", batchKey);
        CoreCommandOutcome outcome = aeron.commandOutcome(CoreMessageType.CANCEL_ORDER_BATCH, commandId, userId,
                TradingOrderBatchCodec.encodeCancelOrderBatch(new com.surprising.aeron.protocol.CancelOrderBatchCommand(commands)));
        return new CommandExecution(commandId, prospectiveIds, outcome, CommandKind.CANCEL_BATCH);
    }

    public OrderCommandReceipt receipt(CommandExecution execution) {
        if (execution.outcome() instanceof CoreCommandOutcome.Terminal terminal) {
            com.surprising.aeron.protocol.CoreResponse response = terminal.response();
            if (response.resultCode() == CoreResultCode.MATCHING_PENDING) {
                return new OrderCommandReceipt(execution.commandId(), "MATCHING_PENDING",
                        CoreResultCode.MATCHING_PENDING.name(), "matching pending",
                        OrderCommandReceipt.commandResultUrl(execution.commandId()),
                        execution.prospectiveOrderIds(), knownExportSequence(response), null, null);
            }
            OrderCommandResult result = decodeResult(execution.kind(), execution.prospectiveOrderIds(), response.data());
            return new OrderCommandReceipt(execution.commandId(), "TERMINAL", response.resultCode().name(),
                    response.resultCode() == CoreResultCode.NONE ? "completed" : response.resultCode().name(),
                    OrderCommandReceipt.commandResultUrl(execution.commandId()), execution.prospectiveOrderIds(),
                    knownExportSequence(response), result, null);
        }
        if (execution.outcome() instanceof CoreCommandOutcome.ResultUnknown) {
            return new OrderCommandReceipt(execution.commandId(), "RESULT_UNKNOWN", "RESULT_UNKNOWN",
                    "command result is unknown", OrderCommandReceipt.commandResultUrl(execution.commandId()),
                    execution.prospectiveOrderIds(), null, null, null);
        }
        CoreCommandOutcome.NotAccepted rejection = (CoreCommandOutcome.NotAccepted) execution.outcome();
        return new OrderCommandReceipt(execution.commandId(), "NOT_ACCEPTED", rejection.reason().name(),
                rejection.reason().name(), null, execution.prospectiveOrderIds(), null, null,
                rejection.rawOfferResult());
    }

    public OrderCommandReceipt commandResult(UUID commandId) {
        if (commandId == null) throw new IllegalArgumentException("commandId is required");
        try {
            CoreResponse response = aeron.commandResult(commandId);
            if (response.resultCode() == CoreResultCode.RESULT_UNKNOWN_OUTSIDE_RETENTION) {
                return new OrderCommandReceipt(commandId, "OUTSIDE_RETENTION",
                        CoreResultCode.RESULT_UNKNOWN_OUTSIDE_RETENTION.name(),
                        CoreResultCode.RESULT_UNKNOWN_OUTSIDE_RETENTION.name(), null, List.of(),
                        null, null, null);
            }
            if (response.resultCode() == CoreResultCode.MATCHING_PENDING) {
                return new OrderCommandReceipt(commandId, "MATCHING_PENDING",
                        CoreResultCode.MATCHING_PENDING.name(), "matching pending",
                        OrderCommandReceipt.commandResultUrl(commandId), List.of(),
                        knownExportSequence(response), null, null);
            }
            return new OrderCommandReceipt(commandId, "TERMINAL", response.resultCode().name(),
                    response.resultCode() == CoreResultCode.NONE ? "completed" : response.resultCode().name(),
                    OrderCommandReceipt.commandResultUrl(commandId), List.of(), knownExportSequence(response),
                    null, null);
        } catch (com.surprising.aeron.client.ResultUnknownException exception) {
            return new OrderCommandReceipt(commandId, "RESULT_UNKNOWN", "RESULT_UNKNOWN",
                    "command result is unknown", OrderCommandReceipt.commandResultUrl(commandId), List.of(),
                    null, null, null);
        } catch (CoreCommandOutcome.NotAcceptedException exception) {
            CoreCommandOutcome.NotAccepted rejection = exception.rejection();
            return new OrderCommandReceipt(commandId, "NOT_ACCEPTED", rejection.reason().name(),
                    rejection.reason().name(), null, List.of(), null, null, rejection.rawOfferResult());
        }
    }

    public List<OrderResponse> lifecycleOpenOrders(String symbol, int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        ProductLine productLine = properties.getKafka().getProductLine();
        return aeron.lifecycleOpenOrders(symbol, limit).stream()
                .map(view -> requireLocalOrder(view, productLine))
                .toList();
    }

    public OrderResponse orderState(long userId, long orderId) {
        return toOrder(aeron.orderState(userId, orderId));
    }

    public OrderResponse orderStateByClientOrderId(long userId, String clientOrderId) {
        return toOrder(aeron.orderStateByClientOrderId(userId, clientOrderId));
    }

    public List<OrderResponse> openOrders(long userId, String symbol, long beforeOrderId, int limit) {
        return aeron.openOrders(userId, symbol, beforeOrderId, limit).stream()
                .map(AeronOrderCommandService::toOrder)
                .toList();
    }

    private static OrderResponse toOrder(CoreOrderStateView view) {
        return view == null ? null : requireOrder(view, "order query returned no order");
    }

    private long matchingPriceTicks(com.surprising.trading.api.model.PlaceOrderRequest request, long version) {
        if (request.orderType() == OrderType.LIMIT) return request.priceTicks();
        long mark = markPrices.latestMarkPriceTicks(request.symbol(), version,
                        properties.getRisk().getMarketMaxMarkAgeMs())
                .orElseThrow(() -> new IllegalStateException("mark price unavailable"));
        return MarketPriceProtection.protectedPriceTicks(request.side(), mark,
                properties.getRisk().getMarketMaxSlippagePpm());
    }

    private static OrderResponse requireOrder(CoreOrderStateView view, String message) {
        if (view == null) throw new IllegalStateException(message);
        return new OrderResponse(view.orderId(), view.userId(), emptyToNull(view.clientOrderId()), view.symbol(),
                view.instrumentVersion(), OrderSide.valueOf(view.side().name()), OrderType.valueOf(view.orderType().name()),
                TimeInForce.valueOf(view.timeInForce().name()), view.priceTicks(), view.quantitySteps(),
                view.executedQuantitySteps(), view.remainingQuantitySteps(), MarginMode.valueOf(view.marginMode().name()),
                PositionSide.valueOf(view.positionSide().name()), view.makerFeeRatePpm(), view.takerFeeRatePpm(),
                view.reduceOnly(), view.postOnly(), status(view), null,
                Instant.ofEpochMilli(view.createdAtEpochMillis()), Instant.ofEpochMilli(view.updatedAtEpochMillis()));
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

    private static CoreOrderStateView commandOrder(com.surprising.aeron.protocol.CoreResponse response, long orderId) {
        CoreCommandResultView result = requireCommandResult(response);
        return result.orders().stream().filter(value -> value.orderId() == orderId).findFirst()
                .orElseThrow(() -> new IllegalStateException("command response is missing order: " + orderId));
    }

    private static CoreCommandResultView requireCommandResult(
            com.surprising.aeron.protocol.CoreResponse response) {
        if (response == null || response.data().length == 0) {
            throw new IllegalStateException("command response is missing result payload");
        }
        return CoreCommandResultCodec.decode(response.data());
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

    private static Long knownExportSequence(CoreResponse response) {
        return response.requiredExportSequence() == 0L ? null : response.requiredExportSequence();
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
                yield new com.surprising.trading.api.model.AmendOrderResponse(optionalOrder(original),
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
                                item.order() == null ? null : new com.surprising.trading.api.model.AmendOrderResponse(
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

    private static UUID batchCommandId(ProductLine productLine, long userId, String kind, String batchKey) {
        return StableOrderIdentity.commandId(productLine, userId, "batch:" + kind + ':' + batchKey);
    }

    private static void requireBatchKey(String batchKey) {
        requireClientKey(batchKey, "batchKey");
    }

    private static void requireBatchSize(List<?> requests, int maximum, String field) {
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
            case com.surprising.trading.api.model.PlaceOrderRequest value -> value.userId();
            case com.surprising.trading.api.model.AmendOrderRequest value -> value.userId();
            case com.surprising.trading.api.model.CancelOrderRequest value -> value.userId();
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
            throw new IllegalStateException("command provider product line is required");
        }
        return productLine;
    }

    private static String requireClientKey(String key, String name) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return key;
    }

    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }
    private static CoreOrderSide side(OrderSide value) { return CoreOrderSide.valueOf(value.name()); }
    private static CoreMarginMode marginMode(MarginMode value) { return CoreMarginMode.valueOf(value.name()); }
    private static CorePositionSide positionSide(PositionSide value) { return CorePositionSide.valueOf(value.name()); }
    private static CoreOrderType orderType(OrderType value) { return CoreOrderType.valueOf(value.name()); }
    private static CoreTimeInForce timeInForce(TimeInForce value) { return CoreTimeInForce.valueOf(value.name()); }

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
