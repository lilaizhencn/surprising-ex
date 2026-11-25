package com.surprising.marketmaker.provider.service;

import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.model.ExternalTrade;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Pure order-building and book-sweep helpers for maker simulated/external-print trades.
 */
final class MakerSimulatedTradeSupport {

    private MakerSimulatedTradeSupport() {}

    static List<PlaceOrderRequest> externalPrintOrders(MarketMakerProperties.Trade trade,
                                                       MarketMakerProperties.Strategy strategy,
                                                       String instrumentId,
                                                       long accountId,
                                                       InstrumentResponse instrument,
                                                       OrderBookSnapshotResponse book,
                                                       long position,
                                                       List<ExternalTrade> prints,
                                                       Supplier<String> clientOrderIdSupplier) {
        if (!instrument.marketOrderEnabled() || prints == null || prints.isEmpty()) return List.of();
        RandomGenerator fullSweep = new RandomGenerator() {
            @Override
            public long nextLong() {
                return Long.MAX_VALUE;
            }

            @Override
            public int nextInt(int bound) {
                return Math.max(0, bound - 1);
            }
        };
        TradeTarget buyTarget = tradeTarget(OrderSide.BUY, book, trade.getSlippageTicks(),
                trade.getMaxSweepLevels(), fullSweep);
        TradeTarget sellTarget = tradeTarget(OrderSide.SELL, book, trade.getSlippageTicks(),
                trade.getMaxSweepLevels(), fullSweep);
        long buyAvailable = buyTarget.availableQuantitySteps();
        long sellAvailable = sellTarget.availableQuantitySteps();
        long threshold = trade.getInventoryThresholdSteps();
        if (threshold > 0) {
            buyAvailable = Math.min(buyAvailable, position < -threshold ? Math.negateExact(position)
                    : Math.max(0, Math.subtractExact(threshold, position)));
            sellAvailable = Math.min(sellAvailable, position > threshold ? position
                    : Math.max(0, Math.addExact(threshold, position)));
        }
        long minimum = Math.max(trade.getMinQuantitySteps(), instrument.minQuantitySteps());
        long maximum = Math.min(trade.getMaxQuantitySteps(), instrument.maxQuantitySteps());
        if (minimum <= 0 || maximum < minimum) return List.of();
        int buyCopies = 0;
        int sellCopies = 0;
        List<PlaceOrderRequest> requests = new ArrayList<>();
        for (ExternalTrade print : prints) {
            boolean buy = print.takerSide() == OrderSide.BUY;
            if (buy && buyCopies >= trade.getOrdersPerBatch()) continue;
            if (!buy && sellCopies >= trade.getOrdersPerBatch()) continue;
            long available = buy ? buyAvailable : sellAvailable;
            long quantity = Math.min(print.quantitySteps(), Math.min(maximum, available));
            if (quantity < minimum) continue;
            long priceTicks = strategy.getProductLine() == com.surprising.product.api.ProductLine.SPOT
                    ? (buy ? buyTarget.priceTicks() : sellTarget.priceTicks())
                    : 0L;
            requests.add(new PlaceOrderRequest(accountId, clientOrderIdSupplier.get(), instrumentId, print.takerSide(),
                    OrderType.MARKET, TimeInForce.IOC, priceTicks, quantity, strategy.getMarginMode(),
                    PositionSide.NET, false, false));
            if (buy) {
                buyAvailable -= quantity;
                buyCopies++;
            } else {
                sellAvailable -= quantity;
                sellCopies++;
            }
        }
        return requests;
    }

    static List<PlaceOrderRequest> simulatedOrders(MarketMakerProperties.Trade trade,
                                                   MarketMakerProperties.Strategy strategy,
                                                   String instrumentId,
                                                   long accountId,
                                                   InstrumentResponse instrument,
                                                   OrderBookSnapshotResponse book,
                                                   MarkPriceResponse mark,
                                                   long position,
                                                   RandomGenerator random,
                                                   Supplier<String> clientOrderIdSupplier) {
        if (!instrument.marketOrderEnabled()) return List.of();
        TradeTarget buyTarget = tradeTarget(OrderSide.BUY, book, trade.getSlippageTicks(),
                trade.getMaxSweepLevels(), random);
        TradeTarget sellTarget = tradeTarget(OrderSide.SELL, book, trade.getSlippageTicks(),
                trade.getMaxSweepLevels(), random);
        long buyAvailable = buyTarget.availableQuantitySteps();
        long sellAvailable = sellTarget.availableQuantitySteps();
        long threshold = trade.getInventoryThresholdSteps();
        if (threshold > 0) {
            buyAvailable = Math.min(buyAvailable, position < -threshold ? Math.negateExact(position)
                    : Math.max(0, Math.subtractExact(threshold, position)));
            sellAvailable = Math.min(sellAvailable, position > threshold ? position
                    : Math.max(0, Math.addExact(threshold, position)));
        }
        long minimum = Math.max(trade.getMinQuantitySteps(), instrument.minQuantitySteps());
        long maximum = Math.min(trade.getMaxQuantitySteps(), instrument.maxQuantitySteps());
        if (minimum <= 0 || maximum < minimum) return List.of();
        List<PlaceOrderRequest> requests = new ArrayList<>(trade.getOrdersPerBatch());
        for (int i = 0; i < trade.getOrdersPerBatch(); i++) {
            boolean canBuy = buyAvailable >= minimum;
            boolean canSell = sellAvailable >= minimum;
            if (!canBuy && !canSell) break;
            double buyProbability = 0.5;
            if (threshold > 0) buyProbability -= 0.35 * Math.clamp((double) position / threshold, -1, 1);
            long markTicks = markPriceTicks(instrument, mark);
            long midTicks = midPriceTicks(book);
            if (markTicks > 0 && midTicks > 0) buyProbability += 0.1 * Long.compare(markTicks, midTicks);
            OrderSide side = !canSell || (canBuy && random.nextDouble() < buyProbability)
                    ? OrderSide.BUY : OrderSide.SELL;
            long available = side == OrderSide.BUY ? buyAvailable : sellAvailable;
            long quantity = tradeQuantity(minimum, Math.min(maximum, available), random);
            long priceTicks = strategy.getProductLine() == com.surprising.product.api.ProductLine.SPOT
                    ? (side == OrderSide.BUY ? buyTarget.priceTicks() : sellTarget.priceTicks())
                    : 0L;
            requests.add(new PlaceOrderRequest(accountId, clientOrderIdSupplier.get(), instrumentId, side,
                    OrderType.MARKET, TimeInForce.IOC, priceTicks, quantity, strategy.getMarginMode(),
                    PositionSide.NET, false, false));
            if (side == OrderSide.BUY) buyAvailable -= quantity;
            else sellAvailable -= quantity;
        }
        return requests;
    }

    static long markPriceTicks(InstrumentResponse instrument, MarkPriceResponse markPrice) {
        if (instrument == null || markPrice == null || instrument.priceTickUnits() <= 0
                || markPrice.markPriceUnits() <= 0) {
            return 0L;
        }
        return (markPrice.markPriceUnits() + instrument.priceTickUnits() / 2L) / instrument.priceTickUnits();
    }

    static long bestBid(OrderBookSnapshotResponse orderBook) {
        return firstValidPrice(orderBook == null ? null : orderBook.bids());
    }

    static long bestAsk(OrderBookSnapshotResponse orderBook) {
        return firstValidPrice(orderBook == null ? null : orderBook.asks());
    }

    static long midPriceTicks(OrderBookSnapshotResponse orderBook) {
        long bestBid = bestBid(orderBook);
        long bestAsk = bestAsk(orderBook);
        if (bestBid > 0 && bestAsk > 0) return (bestBid + bestAsk) / 2L;
        return bestBid > 0 ? bestBid : bestAsk;
    }

    private static long firstValidPrice(List<OrderBookLevel> levels) {
        if (levels == null) return 0L;
        for (OrderBookLevel level : levels) {
            if (level != null && level.priceTicks() > 0 && level.quantitySteps() > 0) {
                return level.priceTicks();
            }
        }
        return 0L;
    }

    private static TradeTarget tradeTarget(OrderSide side,
                                          OrderBookSnapshotResponse orderBook,
                                          long slippageTicks,
                                          int maxSweepLevels,
                                          RandomGenerator random) {
        List<OrderBookLevel> levels = side == OrderSide.BUY
                ? orderBook == null ? List.of() : orderBook.asks()
                : orderBook == null ? List.of() : orderBook.bids();
        if (levels == null || levels.isEmpty()) {
            return new TradeTarget(0L, 0L);
        }
        int targetDistinctLevels = random.nextInt(Math.max(1, maxSweepLevels)) + 1;
        long cumulativeQuantity = 0L;
        long targetPriceTicks = 0L;
        long previousPriceTicks = 0L;
        int distinctLevels = 0;
        for (OrderBookLevel level : levels) {
            if (level == null || level.priceTicks() <= 0 || level.quantitySteps() <= 0) continue;
            if (level.priceTicks() != previousPriceTicks) {
                distinctLevels++;
                previousPriceTicks = level.priceTicks();
            }
            if (distinctLevels > targetDistinctLevels) break;
            cumulativeQuantity = Math.addExact(cumulativeQuantity, level.quantitySteps());
            targetPriceTicks = level.priceTicks();
        }
        if (targetPriceTicks <= 0 || cumulativeQuantity <= 0) {
            return new TradeTarget(0L, 0L);
        }
        long slippage = Math.max(0L, slippageTicks);
        return side == OrderSide.BUY
                ? new TradeTarget(Math.addExact(targetPriceTicks, slippage), cumulativeQuantity)
                : new TradeTarget(Math.max(1L, targetPriceTicks - slippage), cumulativeQuantity);
    }

    private static long tradeQuantity(long minimum, long maximum, RandomGenerator random) {
        long range = maximum - minimum + 1;
        return minimum + Math.min(random.nextLong(range), random.nextLong(range));
    }

    private record TradeTarget(long priceTicks, long availableQuantitySteps) {}
}
