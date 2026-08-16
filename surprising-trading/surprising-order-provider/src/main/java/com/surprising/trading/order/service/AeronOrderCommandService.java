package com.surprising.trading.order.service;

import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreCommandResultCodec;
import com.surprising.aeron.protocol.CoreCommandResultView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
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
        String clientOrderId = requireClientKey(request.clientOrderId(), "clientOrderId");
        ProductLine productLine = requireProductLine(fee.productLine());
        long orderId = StableOrderIdentity.orderId(productLine, request.userId(), clientOrderId);
        PlaceOrderCommand command = placeCommand(orderId, request, validation, fee);
        UUID commandId = StableOrderIdentity.commandId(productLine, request.userId(), clientOrderId);
        var response = aeron.command(CoreMessageType.PLACE_ORDER, commandId, request.userId(),
                TradingCommandCodec.encodePlaceOrder(command));
        return requireOrder(commandOrder(response, orderId), "placed order missing");
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
        PlaceOrderCommand replacementCommand = placeCommand(replacementOrderId, replacement, validation, fee);
        UUID commandId = StableOrderIdentity.replacementCommandId(productLine, replacement.userId(), clientOrderId);
        var response = aeron.command(CoreMessageType.REPLACE_ORDER, commandId, replacement.userId(),
                TradingCommandCodec.encodeReplaceOrder(new ReplaceOrderCommand(original.orderId(), replacementCommand)));
        CoreCommandResultView result = requireCommandResult(response);
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
        String clientOrderId = requireClientKey(request.newClientOrderId(), "newClientOrderId");
        String clientRequestId = requireClientKey(request.clientRequestId(), "clientRequestId");
        ProductLine productLine = configuredProductLine();
        long replacementOrderId = StableOrderIdentity.replacementOrderId(productLine, request.userId(), clientOrderId);
        AmendOrderCommand command = new AmendOrderCommand(request.orderId(), replacementOrderId,
                clientOrderId, request.priceTicks(), request.quantitySteps(),
                request.timeInForce() == null ? null : timeInForce(request.timeInForce()), request.postOnly());
        UUID commandId = StableOrderIdentity.replacementCommandId(productLine, request.userId(), clientRequestId);
        var response = aeron.command(CoreMessageType.AMEND_ORDER, commandId, request.userId(),
                TradingCommandCodec.encodeAmendOrder(command));
        CoreCommandResultView result = requireCommandResult(response);
        CoreOrderStateView canceledView = result.orders().stream()
                .filter(value -> value.orderId() == request.orderId()).findFirst().orElse(null);
        CoreOrderStateView placedView = result.orders().stream()
                .filter(value -> value.orderId() == replacementOrderId).findFirst().orElse(null);
        return new com.surprising.trading.api.model.AmendOrderResponse(
                requireOrder(canceledView, "amended original order missing"),
                requireOrder(placedView, "amended replacement order missing"), true,
                placedView.status().equals("REJECTED") ? "replacement rejected" : "order amended");
    }

    private PlaceOrderCommand placeCommand(
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
        return aeron.preflight(request.userId(), placeCommand(orderId, request, validation, fee));
    }

    public OrderResponse cancel(long userId, long orderId) {
        UUID commandId = StableOrderIdentity.commandId(configuredProductLine(), userId, "cancel:" + orderId);
        var response = aeron.command(CoreMessageType.CANCEL_ORDER, commandId, userId,
                TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(orderId)));
        CoreOrderStateView responseOrder = commandOrder(response, orderId);
        return requireOrder(responseOrder, "canceled order missing");
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
            throw new IllegalStateException("order provider product line is required");
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
}
