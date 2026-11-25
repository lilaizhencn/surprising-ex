package com.surprising.marketmaker.provider.service;

import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.model.DesiredQuote;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.OrderCommandResult;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure order-book filtering, quote slot matching, and receipt helpers for MarketMakerService. */
final class MakerQuoteMatchingSupport {

    static final Set<OrderStatus> LIVE_STATUSES = EnumSet.of(
            OrderStatus.ACCEPTED, OrderStatus.PARTIALLY_FILLED, OrderStatus.PENDING_RESERVE);

    private MakerQuoteMatchingSupport() {
    }

    static OrderBookSnapshotResponse excludeOwnQuotes(OrderBookSnapshotResponse book, List<OrderResponse> orders) {
        var bids = new HashMap<Long, Long>();
        var asks = new HashMap<Long, Long>();
        for (var order : orders) {
            if (!isLive(order) || order.side() == null) continue;
            var quantities = order.side() == OrderSide.BUY ? bids : asks;
            quantities.merge(order.priceTicks(), order.remainingQuantitySteps(), Long::sum);
        }
        return new OrderBookSnapshotResponse(book.instrumentId(), book.sequence(), book.depth(),
                excludeOwnSide(book.bids(), bids), excludeOwnSide(book.asks(), asks), book.eventTime());
    }

    static List<OrderBookLevel> excludeOwnSide(List<OrderBookLevel> levels, Map<Long, Long> ownQuantities) {
        return levels.stream().filter(level -> level.quantitySteps()
                > ownQuantities.getOrDefault(level.priceTicks(), 0L)).toList();
    }

    static boolean shouldKeep(OrderResponse order,
                              List<DesiredQuote> desiredQuotes,
                              String accountPrefix,
                              MarketMakerProperties.Quoting quoting) {
        return desiredQuotes.stream().anyMatch(quote -> isFreshQuote(order, quote, accountPrefix, quoting));
    }

    static boolean isFreshQuote(OrderResponse order,
                                DesiredQuote quote,
                                String accountPrefix,
                                MarketMakerProperties.Quoting quoting) {
        return isLive(order) && matchesQuote(order, quote, accountPrefix, quoting);
    }

    static boolean hasLiveQuote(List<OrderResponse> orders,
                                DesiredQuote quote,
                                String accountPrefix,
                                MarketMakerProperties.Quoting quoting) {
        return orders.stream()
                .filter(MakerQuoteMatchingSupport::isLive)
                .anyMatch(order -> matchesQuote(order, quote, accountPrefix, quoting));
    }

    static boolean occupiedOrCrossingQuote(List<OrderResponse> orders, DesiredQuote quote, String accountPrefix) {
        for (OrderResponse order : orders) {
            if (order == null) continue;
            if (quoteLevel(order.clientOrderId(), accountPrefix, quote.side()) == quote.level()) return true;
            if (isLive(order) && order.side() != quote.side() && (quote.side() == OrderSide.BUY
                    ? quote.priceTicks() >= order.priceTicks() : quote.priceTicks() <= order.priceTicks())) return true;
        }
        return false;
    }

    static boolean matchesQuote(OrderResponse order,
                                DesiredQuote quote,
                                String accountPrefix,
                                MarketMakerProperties.Quoting quoting) {
        return order.side() == quote.side()
                && quoteLevel(order.clientOrderId(), accountPrefix, quote.side()) == quote.level()
                && Math.abs(order.priceTicks() - quote.priceTicks())
                <= Math.max(quoting.getRefreshThresholdTicks(), ppmFloor(quote.priceTicks(), quoting.getRefreshTolerancePpm()))
                && quantityWithinRefreshTolerance(order.remainingQuantitySteps(), quote.quantitySteps(), quoting);
    }

    static int quoteLevel(String clientOrderId, String accountPrefix, OrderSide side) {
        if (clientOrderId == null || side == null || !clientOrderId.startsWith(accountPrefix)) return -1;
        int offset = accountPrefix.length();
        if (offset + 2 >= clientOrderId.length()
                || clientOrderId.charAt(offset++) != (side == OrderSide.BUY ? 'b' : 's')) return -1;
        if (clientOrderId.charAt(offset) == '0' && clientOrderId.charAt(offset + 1) != '-') return -1;
        int level = 0;
        boolean digitSeen = false;
        for (; offset < clientOrderId.length(); offset++) {
            char digit = clientOrderId.charAt(offset);
            if (digit == '-') return digitSeen ? level : -1;
            if (digit < '0' || digit > '9' || level > (Integer.MAX_VALUE - (digit - '0')) / 10) return -1;
            level = level * 10 + (digit - '0');
            digitSeen = true;
        }
        return -1;
    }

    static boolean quantityWithinRefreshTolerance(long resting, long desired, MarketMakerProperties.Quoting quoting) {
        long ppm = quoting.getQuantityRefreshTolerancePpm();
        if (ppm == 0L) return resting == desired;
        if (resting < 0L || desired <= 0L) return false;
        long tolerance = ppmFloor(desired, ppm);
        long difference = resting >= desired ? resting - desired : desired - resting;
        return difference <= tolerance;
    }

    static long ppmFloor(long value, long ppm) {
        if (value < 0 || ppm < 0 || ppm > 1_000_000L)
            throw new IllegalArgumentException("positive value and ppm in [0, 1000000] required");
        return (value / 1_000_000L) * ppm + (value % 1_000_000L) * ppm / 1_000_000L;
    }

    static boolean isLive(OrderResponse order) {
        return order != null && LIVE_STATUSES.contains(order.status());
    }

    static <T> T receiptResult(OrderCommandReceipt receipt, Class<T> type) {
        OrderCommandResult result = receipt == null ? null : receipt.result();
        return result != null && type.isInstance(result) ? type.cast(result) : null;
    }

    static String receiptMessage(OrderCommandReceipt receipt) {
        if (receipt == null) {
            return "订单服务未返回命令回执";
        }
        return receipt.code() + ": " + receipt.message();
    }

    static String firstReason(String current, String candidate) {
        if (current != null && !current.isBlank()) {
            return current;
        }
        return candidate == null || candidate.isBlank() ? "报价被拒绝" : candidate;
    }
}
