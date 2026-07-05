package com.surprising.marketmaker.provider.service;

import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.model.DesiredQuote;
import com.surprising.marketmaker.provider.model.QuotePlan;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookLevel;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookSnapshot;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.OrderSide;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class QuotePlanner {

    private static final long ONE_PPM = 1_000_000L;

    public QuotePlan plan(MarketMakerProperties.Strategy strategy,
                          MarketMakerProperties.Quoting quoting,
                          MarketMakerProperties.Risk risk,
                          InstrumentResponse instrument,
                          OrderBookSnapshotResponse orderBook,
                          MarkPriceResponse markPrice,
                          long signedPositionSteps) {
        return plan(strategy, quoting, risk, instrument, orderBook, markPrice, signedPositionSteps, null);
    }

    public QuotePlan plan(MarketMakerProperties.Strategy strategy,
                          MarketMakerProperties.Quoting quoting,
                          MarketMakerProperties.Risk risk,
                          InstrumentResponse instrument,
                          OrderBookSnapshotResponse orderBook,
                          MarkPriceResponse markPrice,
                          long signedPositionSteps,
                          ReferenceOrderBookSnapshot referenceOrderBook) {
        long anchor = anchorPriceTicks(instrument, orderBook, markPrice, referenceOrderBook);
        int levels = orderLevels(strategy, quoting);
        long halfSpread = Math.max(1L, spreadTicks(strategy, quoting) / 2L);
        long spacing = levelSpacingTicks(strategy, quoting);
        long maxDeviationTicks = Math.max(1L, multiplyDiv(anchor, quoting.getMaxPriceDeviationPpm(), ONE_PPM));
        long minPrice = Math.max(1L, anchor - maxDeviationTicks);
        long maxPrice = anchor + maxDeviationTicks;
        long bestBid = bestBid(orderBook);
        long bestAsk = bestAsk(orderBook);
        List<DesiredQuote> quotes = new ArrayList<>(levels * 2);
        for (int level = 0; level < levels; level++) {
            long bidDistance = referenceDistance(referenceOrderBook, OrderSide.BUY, level);
            long askDistance = referenceDistance(referenceOrderBook, OrderSide.SELL, level);
            long bidPrice = Math.max(minPrice, anchor - (bidDistance > 0 ? bidDistance : halfSpread + spacing * level));
            if (bestAsk > 0) {
                bidPrice = Math.min(bidPrice, bestAsk - 1L);
            }
            addQuoteIfAllowed(strategy, risk, quotes, OrderSide.BUY, level, bidPrice, signedPositionSteps,
                    referenceQuantity(referenceOrderBook, OrderSide.BUY, level));

            long askPrice = Math.min(maxPrice, anchor + (askDistance > 0 ? askDistance : halfSpread + spacing * level));
            if (bestBid > 0) {
                askPrice = Math.max(askPrice, bestBid + 1L);
            }
            addQuoteIfAllowed(strategy, risk, quotes, OrderSide.SELL, level, askPrice, signedPositionSteps,
                    referenceQuantity(referenceOrderBook, OrderSide.SELL, level));
        }
        return new QuotePlan(anchor, signedPositionSteps, List.copyOf(quotes));
    }

    private void addQuoteIfAllowed(MarketMakerProperties.Strategy strategy,
                                   MarketMakerProperties.Risk risk,
                                   List<DesiredQuote> quotes,
                                   OrderSide side,
                                   int level,
                                   long priceTicks,
                                   long signedPositionSteps,
                                   long referenceQuantitySteps) {
        if (priceTicks <= 0 || !sideAllowed(side, strategy, risk, signedPositionSteps)) {
            return;
        }
        long quantity = adjustedQuantity(strategy, risk, side, signedPositionSteps, referenceQuantitySteps);
        if (quantity > 0) {
            quotes.add(new DesiredQuote(side, level, priceTicks, quantity));
        }
    }

    private boolean sideAllowed(OrderSide side,
                                MarketMakerProperties.Strategy strategy,
                                MarketMakerProperties.Risk risk,
                                long signedPositionSteps) {
        long maxInventory = maxInventory(strategy, risk);
        if (side == OrderSide.BUY) {
            return signedPositionSteps < maxInventory;
        }
        return signedPositionSteps > -maxInventory;
    }

    private long adjustedQuantity(MarketMakerProperties.Strategy strategy,
                                  MarketMakerProperties.Risk risk,
                                  OrderSide side,
                                  long signedPositionSteps,
                                  long referenceQuantitySteps) {
        long configuredBase = Math.max(1L, strategy.getBaseQuantitySteps());
        long base = referenceQuantitySteps > 0 ? Math.min(referenceQuantitySteps, configuredBase) : configuredBase;
        long maxInventory = maxInventory(strategy, risk);
        long skewPpm = Math.min(maxSkewPpm(strategy, risk),
                multiplyDiv(Math.abs(signedPositionSteps), ONE_PPM, maxInventory));
        long scale = ONE_PPM;
        if ((side == OrderSide.BUY && signedPositionSteps > 0)
                || (side == OrderSide.SELL && signedPositionSteps < 0)) {
            scale = Math.max(0L, ONE_PPM - skewPpm);
        } else if (signedPositionSteps != 0) {
            scale = ONE_PPM + skewPpm / 2L;
        }
        return multiplyDiv(base, scale, ONE_PPM);
    }

    private long anchorPriceTicks(InstrumentResponse instrument,
                                  OrderBookSnapshotResponse orderBook,
                                  MarkPriceResponse markPrice,
                                  ReferenceOrderBookSnapshot referenceOrderBook) {
        long fromMark = markToTicks(instrument, markPrice);
        if (fromMark > 0) {
            return fromMark;
        }
        long fromReference = referenceMid(referenceOrderBook);
        if (fromReference > 0) {
            return fromReference;
        }
        long bestBid = bestBid(orderBook);
        long bestAsk = bestAsk(orderBook);
        if (bestBid > 0 && bestAsk > 0) {
            return (bestBid + bestAsk) / 2L;
        }
        if (bestBid > 0) {
            return bestBid;
        }
        if (bestAsk > 0) {
            return bestAsk;
        }
        throw new IllegalStateException("cannot resolve quote anchor price");
    }

    private long markToTicks(InstrumentResponse instrument, MarkPriceResponse markPrice) {
        if (instrument == null || markPrice == null || instrument.priceTickUnits() <= 0
                || markPrice.markPriceUnits() <= 0) {
            return 0L;
        }
        return (markPrice.markPriceUnits() + instrument.priceTickUnits() / 2L) / instrument.priceTickUnits();
    }

    private int orderLevels(MarketMakerProperties.Strategy strategy, MarketMakerProperties.Quoting quoting) {
        if (strategy.getOrderLevels() != null && strategy.getOrderLevels() > 0) {
            return Math.min(strategy.getOrderLevels(), 20);
        }
        return quoting.getOrderLevels();
    }

    private long spreadTicks(MarketMakerProperties.Strategy strategy, MarketMakerProperties.Quoting quoting) {
        return strategy.getSpreadTicks() > 0
                ? Math.max(strategy.getSpreadTicks(), quoting.getMinSpreadTicks())
                : quoting.getMinSpreadTicks();
    }

    private long levelSpacingTicks(MarketMakerProperties.Strategy strategy, MarketMakerProperties.Quoting quoting) {
        return strategy.getLevelSpacingTicks() > 0 ? strategy.getLevelSpacingTicks() : quoting.getLevelSpacingTicks();
    }

    private long maxInventory(MarketMakerProperties.Strategy strategy, MarketMakerProperties.Risk risk) {
        return strategy.getMaxInventorySteps() != null && strategy.getMaxInventorySteps() > 0
                ? strategy.getMaxInventorySteps()
                : risk.getMaxInventorySteps();
    }

    private long maxSkewPpm(MarketMakerProperties.Strategy strategy, MarketMakerProperties.Risk risk) {
        return strategy.getMaxInventorySkewPpm() != null
                ? Math.min(strategy.getMaxInventorySkewPpm(), ONE_PPM)
                : risk.getMaxInventorySkewPpm();
    }

    private long bestBid(OrderBookSnapshotResponse orderBook) {
        if (orderBook == null || orderBook.bids() == null || orderBook.bids().isEmpty()) {
            return 0L;
        }
        OrderBookLevel level = orderBook.bids().get(0);
        return level == null ? 0L : level.priceTicks();
    }

    private long bestAsk(OrderBookSnapshotResponse orderBook) {
        if (orderBook == null || orderBook.asks() == null || orderBook.asks().isEmpty()) {
            return 0L;
        }
        OrderBookLevel level = orderBook.asks().get(0);
        return level == null ? 0L : level.priceTicks();
    }

    private long referenceDistance(ReferenceOrderBookSnapshot referenceOrderBook, OrderSide side, int level) {
        long mid = referenceMid(referenceOrderBook);
        ReferenceOrderBookLevel referenceLevel = referenceLevel(referenceOrderBook, side, level);
        if (mid <= 0 || referenceLevel == null || referenceLevel.priceTicks() <= 0) {
            return 0L;
        }
        long distance = side == OrderSide.BUY
                ? mid - referenceLevel.priceTicks()
                : referenceLevel.priceTicks() - mid;
        return Math.max(1L, distance);
    }

    private long referenceQuantity(ReferenceOrderBookSnapshot referenceOrderBook, OrderSide side, int level) {
        ReferenceOrderBookLevel referenceLevel = referenceLevel(referenceOrderBook, side, level);
        return referenceLevel == null ? 0L : referenceLevel.quantitySteps();
    }

    private ReferenceOrderBookLevel referenceLevel(ReferenceOrderBookSnapshot referenceOrderBook,
                                                  OrderSide side,
                                                  int level) {
        if (referenceOrderBook == null || !referenceOrderBook.hasTwoSidedDepth() || level < 0) {
            return null;
        }
        List<ReferenceOrderBookLevel> levels = side == OrderSide.BUY
                ? referenceOrderBook.bids()
                : referenceOrderBook.asks();
        if (level >= levels.size()) {
            return null;
        }
        return levels.get(level);
    }

    private long referenceMid(ReferenceOrderBookSnapshot referenceOrderBook) {
        return referenceOrderBook != null && referenceOrderBook.hasTwoSidedDepth()
                ? referenceOrderBook.midPriceTicks()
                : 0L;
    }

    private long multiplyDiv(long value, long multiplier, long divisor) {
        if (divisor <= 0) {
            throw new IllegalArgumentException("divisor must be positive");
        }
        return Math.multiplyExact(value, multiplier) / divisor;
    }
}
