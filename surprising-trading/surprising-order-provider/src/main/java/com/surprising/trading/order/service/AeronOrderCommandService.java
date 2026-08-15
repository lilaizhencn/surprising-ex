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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AeronOrderCommandService {

    private final OrderAeronGateway aeron;
    private final AeronOrderIdGenerator orderIds;
    private final InstrumentRuleLookup instrumentRules;
    private final MarkPriceLookup markPrices;
    private final TradingOrderProperties properties;

    public AeronOrderCommandService(OrderAeronGateway aeron, AeronOrderIdGenerator orderIds,
                                    InstrumentRuleLookup instrumentRules, MarkPriceLookup markPrices,
                                    TradingOrderProperties properties) {
        this.aeron = aeron;
        this.orderIds = orderIds;
        this.instrumentRules = instrumentRules;
        this.markPrices = markPrices;
        this.properties = properties;
    }

    public OrderResponse place(
            com.surprising.trading.api.model.PlaceOrderRequest request,
            ValidationResult validation,
            OrderFeeSnapshot fee) {
        long orderId = orderIds.next();
        PlaceOrderCommand command = placeCommand(orderId, request, validation, fee);
        UUID commandId = placeCommandId(request, orderId);
        var response = aeron.command(CoreMessageType.PLACE_ORDER, commandId, request.userId(),
                TradingCommandCodec.encodePlaceOrder(command));
        CoreOrderStateView responseOrder = commandOrder(response, orderId);
        if (responseOrder != null) {
            return requireOrder(responseOrder, "placed order missing");
        }
        return requireOrder(fallbackOrder(aeron, request.userId(), orderId, request.clientOrderId()),
                "placed order missing");
    }

    public com.surprising.trading.api.model.AmendOrderResponse replace(
            OrderResponse original,
            com.surprising.trading.api.model.PlaceOrderRequest replacement,
            ValidationResult validation,
            OrderFeeSnapshot fee) {
        long replacementOrderId = orderIds.next();
        PlaceOrderCommand replacementCommand = placeCommand(replacementOrderId, replacement, validation, fee);
        UUID commandId = stableId("ORDER_REPLACE:" + replacement.userId() + ':' + original.orderId() + ':'
                + placeIntent(replacement, replacementOrderId));
        var response = aeron.command(CoreMessageType.REPLACE_ORDER, commandId, replacement.userId(),
                TradingCommandCodec.encodeReplaceOrder(new ReplaceOrderCommand(original.orderId(), replacementCommand)));
        CoreCommandResultView result = commandResult(response);
        if (result != null) {
            CoreOrderStateView canceledView = result.orders().stream()
                    .filter(value -> value.orderId() == original.orderId()).findFirst().orElse(null);
            CoreOrderStateView placedView = result.orders().stream()
                    .filter(value -> value.orderId() == replacementOrderId).findFirst().orElse(null);
            return new com.surprising.trading.api.model.AmendOrderResponse(
                    requireOrder(canceledView, "replaced original order missing"),
                    requireOrder(placedView, "replacement order missing"), true,
                    placedView.status().equals("REJECTED") ? "replacement rejected" : "order replaced");
        }
        OrderResponse canceled = requireOrder(aeron.order(original.userId(), original.orderId()),
                "replaced original order missing");
        OrderResponse placed = requireOrder(fallbackOrder(aeron, replacement.userId(), replacementOrderId,
                        replacement.clientOrderId()),
                "replacement order missing");
        return new com.surprising.trading.api.model.AmendOrderResponse(canceled, placed, true,
                placed.status() == OrderStatus.REJECTED ? "replacement rejected" : "order replaced");
    }

    public com.surprising.trading.api.model.AmendOrderResponse replace(
            com.surprising.trading.api.model.AmendOrderRequest request) {
        long replacementOrderId = orderIds.next();
        AmendOrderCommand command = new AmendOrderCommand(request.orderId(), replacementOrderId,
                request.newClientOrderId(), request.priceTicks(), request.quantitySteps(),
                request.timeInForce() == null ? null : timeInForce(request.timeInForce()), request.postOnly());
        UUID commandId = stableId("ORDER_AMEND:" + request.userId() + ':' + request.orderId() + ':'
                + replacementOrderId + ':' + amendIntent(request));
        var response = aeron.command(CoreMessageType.AMEND_ORDER, commandId, request.userId(),
                TradingCommandCodec.encodeAmendOrder(command));
        CoreCommandResultView result = commandResult(response);
        if (result != null) {
            CoreOrderStateView canceledView = result.orders().stream()
                    .filter(value -> value.orderId() == request.orderId()).findFirst().orElse(null);
            CoreOrderStateView placedView = result.orders().stream()
                    .filter(value -> value.orderId() == replacementOrderId).findFirst().orElse(null);
            return new com.surprising.trading.api.model.AmendOrderResponse(
                    requireOrder(canceledView, "amended original order missing"),
                    requireOrder(placedView, "amended replacement order missing"), true,
                    placedView.status().equals("REJECTED") ? "replacement rejected" : "order amended");
        }
        OrderResponse canceled = requireOrder(aeron.order(request.userId(), request.orderId()),
                "amended original order missing");
        OrderResponse placed = requireOrder(fallbackOrder(aeron, request.userId(), replacementOrderId,
                        request.newClientOrderId()), "amended replacement order missing");
        return new com.surprising.trading.api.model.AmendOrderResponse(canceled, placed, true,
                placed.status() == OrderStatus.REJECTED ? "replacement rejected" : "order amended");
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
                request.postOnly(), request.clientOrderId() == null ? "" : request.clientOrderId(),
                fee.makerFeeRatePpm(), fee.takerFeeRatePpm());
    }

    public OrderAeronGateway.PreflightResult preflight(
            com.surprising.trading.api.model.PlaceOrderRequest request,
            ValidationResult validation,
            OrderFeeSnapshot fee) {
        return aeron.preflight(request.userId(), placeCommand(orderIds.next(), request, validation, fee));
    }

    public OrderResponse cancel(long userId, long orderId) {
        UUID commandId = stableId("ORDER_CANCEL:" + userId + ':' + orderId);
        var response = aeron.command(CoreMessageType.CANCEL_ORDER, commandId, userId,
                TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(orderId)));
        CoreOrderStateView responseOrder = commandOrder(response, orderId);
        if (responseOrder != null) {
            return requireOrder(responseOrder, "canceled order missing");
        }
        return requireOrder(aeron.order(userId, orderId), "canceled order missing");
    }

    public OrderResponse get(long userId, long orderId) {
        return requireOrder(aeron.order(userId, orderId), "order not found: " + orderId);
    }

    public OrderResponse get(long userId, String clientOrderId) {
        return requireOrder(aeron.order(userId, clientOrderId), "order not found: " + clientOrderId);
    }

    public OrderResponse find(long userId, String clientOrderId) {
        CoreOrderStateView view = aeron.order(userId, clientOrderId);
        return view == null ? null : requireOrder(view, "order not found");
    }

    public List<OrderResponse> openOrders(long userId, String symbol, long beforeOrderId, int limit) {
        return aeron.openOrders(userId, symbol, beforeOrderId, limit).stream()
                .map(view -> requireOrder(view, "open order missing"))
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

    private static CoreOrderStateView commandOrder(com.surprising.aeron.protocol.CoreResponse response, long orderId) {
        CoreCommandResultView result = commandResult(response);
        if (result == null) {
            return null;
        }
        return result.orders().stream().filter(value -> value.orderId() == orderId).findFirst()
                .orElseThrow(() -> new IllegalStateException("command response is missing order: " + orderId));
    }

    private static CoreCommandResultView commandResult(com.surprising.aeron.protocol.CoreResponse response) {
        if (response == null || response.data().length == 0) {
            return null;
        }
        return CoreCommandResultCodec.decode(response.data());
    }

    private static OrderStatus status(CoreOrderStateView view) {
        if ("OPEN".equals(view.status())) {
            return view.executedQuantitySteps() == 0 ? OrderStatus.ACCEPTED : OrderStatus.PARTIALLY_FILLED;
        }
        return OrderStatus.valueOf(view.status());
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static UUID placeCommandId(
            com.surprising.trading.api.model.PlaceOrderRequest request, long orderId) {
        return stableId("ORDER_PLACE:" + request.userId() + ':' + placeIntent(request, orderId));
    }

    private static String placeIntent(
            com.surprising.trading.api.model.PlaceOrderRequest request, long orderId) {
        String clientOrderId = request.clientOrderId();
        return field(clientOrderId == null || clientOrderId.isBlank()
                        ? "ORDER:" + orderId : "CLIENT:" + clientOrderId)
                + field(request.symbol())
                + field(request.side())
                + field(request.orderType())
                + field(request.timeInForce())
                + field(request.priceTicks())
                + field(request.quantitySteps())
                + field(request.marginMode())
                + field(request.positionSide())
                + field(request.reduceOnly())
                + field(request.postOnly());
    }

    private static String amendIntent(com.surprising.trading.api.model.AmendOrderRequest request) {
        return field(request.newClientOrderId()) + field(request.priceTicks()) + field(request.quantitySteps())
                + field(request.timeInForce()) + field(request.postOnly());
    }

    private static String field(Object value) {
        String text = String.valueOf(value);
        return text.length() + ":" + text;
    }

    private static CoreOrderStateView fallbackOrder(
            OrderAeronGateway aeron, long userId, long orderId, String clientOrderId) {
        CoreOrderStateView order = aeron.order(userId, orderId);
        if (order != null || clientOrderId == null || clientOrderId.isBlank()) {
            return order;
        }
        return aeron.order(userId, clientOrderId);
    }

    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }
    private static CoreOrderSide side(OrderSide value) { return CoreOrderSide.valueOf(value.name()); }
    private static CoreMarginMode marginMode(MarginMode value) { return CoreMarginMode.valueOf(value.name()); }
    private static CorePositionSide positionSide(PositionSide value) { return CorePositionSide.valueOf(value.name()); }
    private static CoreOrderType orderType(OrderType value) { return CoreOrderType.valueOf(value.name()); }
    private static CoreTimeInForce timeInForce(TimeInForce value) { return CoreTimeInForce.valueOf(value.name()); }
}
