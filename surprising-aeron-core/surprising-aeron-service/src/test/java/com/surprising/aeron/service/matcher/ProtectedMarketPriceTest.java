package com.surprising.aeron.service.matcher;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.service.settlement.TradeSettlement;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import static com.surprising.aeron.service.core.DirectMatcherTestInputs.order;
import static com.surprising.aeron.service.core.DirectMatcherTestInputs.placeDirect;
import static org.assertj.core.api.Assertions.assertThat;

/** 滑点保护价：市价单只能吃到对手一档价格加减万分之一以内的档位。 */
class ProtectedMarketPriceTest {

    private static final long BEST = 10_000_000L;
    private static final long SLIPPAGE_PPM = 100L;
    // 与生产公式无关地直接算出：10_000_000 * 100 / 1_000_000 = 1_000。
    private static final long DISTANCE = BEST / 1_000_000L * SLIPPAGE_PPM;
    private static final long MAKER = 11;
    private static final long TAKER = 22;

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "LINEAR_DELIVERY"})
    void marketBuyStopsAtBestAskPlusDistance(ProductLine product) {
        try (var adapter = new OrderBookEngine()) {
            rest(adapter, product, "31", CoreOrderSide.SELL, BEST, BEST + DISTANCE / 2, BEST + DISTANCE * 2);
            var result = placeDirect(adapter, TAKER, order(product, 100, "31", CoreOrderSide.BUY,
                    CoreOrderType.MARKET, CoreTimeInForce.IOC, 20_000_000L, 3));
            long bound = Math.min(20_000_000L, BEST + DISTANCE);
            assertThat(tradePrices(result)).containsExactly(BEST, BEST + DISTANCE / 2)
                    .allMatch(price -> price <= bound);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "LINEAR_DELIVERY"})
    void marketSellStopsAtBestBidMinusDistance(ProductLine product) {
        try (var adapter = new OrderBookEngine()) {
            rest(adapter, product, "32", CoreOrderSide.BUY, BEST, BEST - DISTANCE / 2, BEST - DISTANCE * 2);
            var result = placeDirect(adapter, TAKER, order(product, 100, "32", CoreOrderSide.SELL,
                    CoreOrderType.MARKET, CoreTimeInForce.IOC, 1L, 3));
            long bound = Math.max(1L, BEST - DISTANCE);
            assertThat(tradePrices(result)).containsExactly(BEST, BEST - DISTANCE / 2)
                    .allMatch(price -> price >= bound);
        }
    }

    @Test
    void marketOrderAgainstEmptySideKeepsOriginalBoundAndTradesNothing() {
        try (var adapter = new OrderBookEngine()) {
            rest(adapter, ProductLine.LINEAR_PERPETUAL, "33", CoreOrderSide.BUY, BEST);
            var result = placeDirect(adapter, TAKER, order(ProductLine.LINEAR_PERPETUAL, 100, "33",
                    CoreOrderSide.BUY, CoreOrderType.MARKET, CoreTimeInForce.IOC, 20_000_000L, 1));
            assertThat(tradePrices(result)).isEmpty();
        }
    }

    @Test
    void limitOrderIsNotProtected() {
        try (var adapter = new OrderBookEngine()) {
            rest(adapter, ProductLine.LINEAR_PERPETUAL, "34", CoreOrderSide.SELL, BEST, BEST + DISTANCE * 2);
            var result = placeDirect(adapter, TAKER, order(ProductLine.LINEAR_PERPETUAL, 100, "34",
                    CoreOrderSide.BUY, CoreOrderType.LIMIT, CoreTimeInForce.IOC, BEST + DISTANCE * 2, 2));
            assertThat(tradePrices(result)).containsExactly(BEST, BEST + DISTANCE * 2);
        }
    }

    @Test
    void spotMarketOrderUsesOriginalBound() {
        try (var adapter = new OrderBookEngine()) {
            rest(adapter, ProductLine.SPOT, "35", CoreOrderSide.SELL, BEST, BEST + DISTANCE * 2);
            var result = placeDirect(adapter, TAKER, order(ProductLine.SPOT, 100, "35", CoreOrderSide.BUY,
                    CoreOrderType.MARKET, CoreTimeInForce.IOC, 20_000_000L, 2));
            assertThat(tradePrices(result)).containsExactly(BEST, BEST + DISTANCE * 2);
        }
    }

    private static void rest(OrderBookEngine adapter, ProductLine product, String instrumentId,
                             CoreOrderSide side, long... prices) {
        for (int index = 0; index < prices.length; index++) {
            assertThat(placeDirect(adapter, MAKER, order(product, 1 + index, instrumentId, side,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, prices[index], 1)).accepted()).isTrue();
        }
    }

    private static List<Long> tradePrices(TradeSettlement result) {
        return result.matcherEvents().stream()
                .filter(event -> event.eventType() == MatcherEventType.TRADE)
                .map(MatcherResult.MatcherEvent::price)
                .toList();
    }
}
