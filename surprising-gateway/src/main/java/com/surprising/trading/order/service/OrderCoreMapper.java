package com.surprising.trading.order.service;

import com.surprising.aeron.client.CoreCommandOutcome;
import com.surprising.aeron.protocol.CoreCommandResultCodec;
import com.surprising.aeron.protocol.CoreCommandResultView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderBatchResult;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AmendOrderBatchItemResponse;
import com.surprising.trading.api.model.AmendOrderBatchResponse;
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.AmendOrderResponse;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderBatchItemResponse;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderCommandResult;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.exception.OrderCommandRejectedException;
import java.time.Instant;
import java.util.List;

/**
 * Pure protocol/view mapping and batch request validation helpers shared across order services.
 */
final class OrderCoreMapper {

    private OrderCoreMapper() {
    }

    static OrderResponse toOrder(CoreOrderStateView view) {
        return view == null ? null : requireOrder(view, "order query returned no order");
    }

    static OrderResponse requireOrder(CoreOrderStateView view, String message) {
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

    static OrderResponse requireLocalOrder(CoreOrderStateView view, ProductLine productLine) {
        if (view == null) {
            throw new IllegalStateException("lifecycle order query returned a missing order");
        }
        if (view.productLine() != productLine) {
            throw new IllegalStateException("lifecycle order product line does not match local core");
        }
        return requireOrder(view, "lifecycle order missing");
    }

    static CoreOrderStateView commandOrder(CoreResponse response, long orderId) {
        CoreCommandResultView result = requireCommandResult(response);
        return result.orders().stream().filter(value -> value.orderId() == orderId).findFirst()
                .orElseThrow(() -> new IllegalStateException("command response is missing order: " + orderId));
    }

    static CoreCommandResultView requireCommandResult(CoreResponse response) {
        if (response != null && response.status() == ResponseStatus.REJECTED) {
            throw new OrderCommandRejectedException(response.resultCode());
        }
        byte[] data = response == null ? null : response.data();
        if (data == null || data.length == 0) {
            throw new IllegalStateException("command response is missing result payload");
        }
        return CoreCommandResultCodec.decode(data);
    }

    static CoreResponse terminalResponse(OrderService.CommandExecution execution) {
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

    static OrderCommandResult decodeResult(OrderService.CommandKind kind, List<Long> prospectiveIds, byte[] data) {
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

    static OrderResponse optionalOrder(CoreOrderStateView view) {
        return view == null ? null : requireOrder(view, "order result is missing");
    }

    static void requireBatchKey(String batchKey) {
        requireClientKey(batchKey, "batchKey");
    }

    static void requireBatchListSize(List<?> requests, int maximum, String field) {
        if (requests == null || requests.isEmpty() || requests.size() > maximum) {
            throw new IllegalArgumentException(field + " size must be in [1, " + maximum + "]");
        }
    }

    static long requireSingleUser(List<?> requests) {
        long userId = userId(requests.getFirst());
        if (userId <= 0L || requests.stream().anyMatch(request -> userId(request) != userId)) {
            throw new IllegalArgumentException("batch orders must belong to one positive user");
        }
        return userId;
    }

    static long userId(Object request) {
        return switch (request) {
            case PlaceOrderRequest value -> value.userId();
            case AmendOrderRequest value -> value.userId();
            case CancelOrderRequest value -> value.userId();
            default -> throw new IllegalArgumentException("unsupported order batch request");
        };
    }

    static OrderStatus status(CoreOrderStateView view) {
        if ("OPEN".equals(view.status())) {
            return view.executedQuantitySteps() == 0 ? OrderStatus.ACCEPTED : OrderStatus.PARTIALLY_FILLED;
        }
        return OrderStatus.valueOf(view.status());
    }

    static String requireClientKey(String key, String name) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return key;
    }

    static String emptyToNullStatic(String value) { return value == null || value.isEmpty() ? null : value; }
    static CoreOrderSide side(OrderSide value) { return CoreOrderSide.valueOf(value.name()); }
    static CoreMarginMode marginMode(MarginMode value) { return CoreMarginMode.valueOf(value.name()); }
    static CorePositionSide positionSide(PositionSide value) { return CorePositionSide.valueOf(value.name()); }
    static CoreOrderType orderType(OrderType value) { return CoreOrderType.valueOf(value.name()); }
    static CoreTimeInForce timeInForce(TimeInForce value) { return CoreTimeInForce.valueOf(value.name()); }
}
