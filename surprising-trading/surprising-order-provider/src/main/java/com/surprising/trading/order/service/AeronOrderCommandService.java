package com.surprising.trading.order.service;

import com.surprising.aeron.protocol.CancelOrderCommand;
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
        UUID commandId = stableId("ORDER_PLACE:" + request.userId() + ':'
                + (request.clientOrderId() == null ? orderId : request.clientOrderId()));
        aeron.command(CoreMessageType.PLACE_ORDER, commandId, request.userId(),
                TradingCommandCodec.encodePlaceOrder(command));
        return requireOrder(aeron.order(request.userId(), orderId), "placed order missing");
    }

    public com.surprising.trading.api.model.AmendOrderResponse replace(
            OrderResponse original,
            com.surprising.trading.api.model.PlaceOrderRequest replacement,
            ValidationResult validation,
            OrderFeeSnapshot fee) {
        long replacementOrderId = orderIds.next();
        PlaceOrderCommand replacementCommand = placeCommand(replacementOrderId, replacement, validation, fee);
        UUID commandId = stableId("ORDER_REPLACE:" + replacement.userId() + ':' + original.orderId() + ':'
                + (replacement.clientOrderId() == null ? replacementOrderId : replacement.clientOrderId()));
        aeron.command(CoreMessageType.REPLACE_ORDER, commandId, replacement.userId(),
                TradingCommandCodec.encodeReplaceOrder(new ReplaceOrderCommand(original.orderId(), replacementCommand)));
        OrderResponse canceled = requireOrder(aeron.order(original.userId(), original.orderId()),
                "replaced original order missing");
        OrderResponse placed = requireOrder(aeron.order(replacement.userId(), replacementOrderId),
                "replacement order missing");
        return new com.surprising.trading.api.model.AmendOrderResponse(canceled, placed, true,
                placed.status() == OrderStatus.REJECTED ? "replacement rejected" : "order replaced");
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
        aeron.command(CoreMessageType.CANCEL_ORDER, commandId, userId,
                TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(orderId)));
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

    private static OrderStatus status(CoreOrderStateView view) {
        if ("OPEN".equals(view.status())) {
            return view.executedQuantitySteps() == 0 ? OrderStatus.ACCEPTED : OrderStatus.PARTIALLY_FILLED;
        }
        return OrderStatus.valueOf(view.status());
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }
    private static CoreOrderSide side(OrderSide value) { return CoreOrderSide.valueOf(value.name()); }
    private static CoreMarginMode marginMode(MarginMode value) { return CoreMarginMode.valueOf(value.name()); }
    private static CorePositionSide positionSide(PositionSide value) { return CorePositionSide.valueOf(value.name()); }
    private static CoreOrderType orderType(OrderType value) { return CoreOrderType.valueOf(value.name()); }
    private static CoreTimeInForce timeInForce(TimeInForce value) { return CoreTimeInForce.valueOf(value.name()); }
}
