package com.surprising.trading.order.service;

import com.surprising.aeron.client.CoreCommandOutcome;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.utils.InstrumentIds;
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import java.util.Comparator;

/**
 * 订单下单、改单与撤单请求的纯参数校验及标准化。
 */
final class OrderRequestNormalizer {

    private OrderRequestNormalizer() {
    }

    static void requireBatchSize(int size, int max, String field) {
        if (size < 1 || size > max) {
            throw new IllegalArgumentException(field + " size must be in [1, " + max + "]");
        }
    }

    static <T> T terminalBatchResult(OrderCommandReceipt receipt, Class<T> type) {
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

    static void validateCancelRequest(CancelOrderRequest request) {
        if (request == null || request.userId() <= 0L || request.orderId() <= 0L) {
            throw new IllegalArgumentException("userId and orderId must be positive");
        }
    }

    static PlaceOrderRequest normalize(PlaceOrderRequest request,
                                       ProductLine currentProductLine,
                                       MatchingMarketDataService bboOrderBook) {
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
        long priceTicks = resolveLimitPrice(request, instrumentId, bboOrderBook);
        PositionSide positionSide = PositionSide.defaultIfNull(request.positionSide());
        if (currentProductLine == ProductLine.SPOT && positionSide.isHedgeSide()) {
            throw new IllegalArgumentException("现货订单不支持 LONG/SHORT 仓位方向");
        }
        MarginMode marginMode = MarginMode.defaultIfNull(request.marginMode());
        boolean reduceOnly = request.reduceOnly()
                || positionSide.isHedgeSide() && positionSide.isClosingSide(request.side());
        if (request.bboPriceMode() == null && clientOrderId.equals(request.clientOrderId())
                && instrumentId.equals(request.instrumentId()) && priceTicks == request.priceTicks()
                && marginMode == request.marginMode() && positionSide == request.positionSide()
                && reduceOnly == request.reduceOnly()) {
            return request;
        }
        return new PlaceOrderRequest(
                request.userId(),
                clientOrderId,
                instrumentId,
                request.side(),
                request.orderType(),
                request.timeInForce(),
                priceTicks,
                request.quantitySteps(),
                marginMode,
                positionSide,
                reduceOnly,
                request.postOnly(),
                null,
                request.takeProfitPriceTicks(),
                request.stopLossPriceTicks(),
                request.tpSlPriceSource(),
                request.stpMode());
    }

    private static long resolveLimitPrice(PlaceOrderRequest request,
                                        String instrumentId,
                                        MatchingMarketDataService bboOrderBook) {
        var mode = request.bboPriceMode();
        if (mode == null) {
            return request.priceTicks();
        }
        if (request.orderType() != OrderType.LIMIT || request.priceTicks() != 0) {
            throw new IllegalArgumentException("BBO requires a LIMIT order with priceTicks=0");
        }
        if (bboOrderBook == null) {
            throw new IllegalStateException("BBO order book is unavailable");
        }
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

    static AmendOrderRequest normalizeAmend(AmendOrderRequest request) {
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
        if (!InstrumentIds.valid(normalized)) {
            throw new IllegalArgumentException("invalid instrumentId: " + instrumentId);
        }
        return normalized;
    }

    static String normalizeClientOrderId(String clientOrderId) {
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

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
