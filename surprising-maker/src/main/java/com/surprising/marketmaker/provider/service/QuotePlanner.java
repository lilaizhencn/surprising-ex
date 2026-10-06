package com.surprising.marketmaker.provider.service;

import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.model.DesiredQuote;
import com.surprising.marketmaker.provider.model.QuotePlan;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookLevel;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookSnapshot;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.product.api.ProductLine;
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
        return plan(strategy, quoting, risk, instrument, orderBook, markPrice, signedPositionSteps, 0L, null);
    }

    public QuotePlan plan(MarketMakerProperties.Strategy strategy,
                          MarketMakerProperties.Quoting quoting,
                          MarketMakerProperties.Risk risk,
                          InstrumentResponse instrument,
                          OrderBookSnapshotResponse orderBook,
                          MarkPriceResponse markPrice,
                          long signedPositionSteps,
                          ReferenceOrderBookSnapshot referenceOrderBook) {
        return plan(strategy, quoting, risk, instrument, orderBook, markPrice, signedPositionSteps, 0L,
                referenceOrderBook);
    }

    public QuotePlan plan(MarketMakerProperties.Strategy strategy,
                          MarketMakerProperties.Quoting quoting,
                          MarketMakerProperties.Risk risk,
                          InstrumentResponse instrument,
                          OrderBookSnapshotResponse orderBook,
                          MarkPriceResponse markPrice,
                          long signedPositionSteps,
                          long volatilityTicks,
                          ReferenceOrderBookSnapshot referenceOrderBook) {
        return plan(strategy, quoting, risk, instrument, orderBook, markPrice, signedPositionSteps,
                volatilityTicks, referenceOrderBook, instrument.makerFeeRatePpm());
    }

    public QuotePlan plan(MarketMakerProperties.Strategy strategy,
                          MarketMakerProperties.Quoting quoting, MarketMakerProperties.Risk risk,
                          InstrumentResponse instrument, OrderBookSnapshotResponse orderBook,
                          MarkPriceResponse markPrice, long signedPositionSteps, long volatilityTicks,
                          ReferenceOrderBookSnapshot referenceOrderBook, long effectiveMakerFeePpm) {
        if (effectiveMakerFeePpm < -ONE_PPM || effectiveMakerFeePpm > ONE_PPM)
            throw new IllegalArgumentException("invalid effective maker fee");
        long anchor = anchorPriceTicks(strategy, instrument, orderBook, markPrice, referenceOrderBook);
        int levels = orderLevels(strategy, quoting);
        long halfSpread = Math.max(spreadTicks(strategy, quoting) / 2L,
                volatilitySpreadTicks(quoting, volatilityTicks));
        halfSpread = Math.max(halfSpread, multiplyDiv(anchor, quoting.getHalfSpreadPpm(), ONE_PPM));
        boolean linear = instrument.contractType() == com.surprising.instrument.api.model.ContractType.LINEAR_PERPETUAL
                || instrument.contractType() == com.surprising.instrument.api.model.ContractType.LINEAR_DELIVERY;
        if (linear) {
            long feePpm = Math.max(0L, Math.max(effectiveMakerFeePpm, quoting.getMakerFeeReservePpm()));
            if (feePpm >= ONE_PPM) throw new IllegalStateException("maker fee leaves no executable quote margin");
            long requiredEdgePpm = Math.addExact(feePpm, quoting.getMinNetHalfSpreadPpm());
            if (requiredEdgePpm > 0) {
                var numerator = java.math.BigInteger.valueOf(anchor).multiply(java.math.BigInteger.valueOf(requiredEdgePpm));
                var denominator = java.math.BigInteger.valueOf(ONE_PPM - feePpm);
                long feeCoveredDistance = numerator.add(denominator).subtract(java.math.BigInteger.ONE)
                        .divide(denominator).longValueExact();
                halfSpread = Math.max(halfSpread, Math.addExact(feeCoveredDistance, 1L));
            }
        }
        long spacing = levelSpacingTicks(strategy, quoting);
        long maxDeviationTicks = Math.max(1L, multiplyDiv(anchor, quoting.getMaxPriceDeviationPpm(), ONE_PPM));
        if (linear && (Math.max(effectiveMakerFeePpm, quoting.getMakerFeeReservePpm()) > 0
                || quoting.getMinNetHalfSpreadPpm() > 0) && halfSpread > maxDeviationTicks) throw new IllegalStateException("quote price bound cannot cover required spread and maker fee");
        long priceSkew = linear ? priceSkewTicks(strategy, quoting, risk, anchor, signedPositionSteps,
                referenceOrderBook) : 0L;
        long skewLimit = Math.max(0L, maxDeviationTicks - halfSpread);
        priceSkew = Math.max(-skewLimit, Math.min(skewLimit, priceSkew));
        // Tilt quotes away from adverse flow without crossing the reference-based fee/edge floor.
        long bidHalfSpread = halfSpread + Math.max(0L, -priceSkew);
        long askHalfSpread = Math.max(halfSpread, (spreadTicks(strategy, quoting) + 1L) / 2L) + Math.max(0L, priceSkew);
        long minPrice = Math.max(1L, anchor - maxDeviationTicks);
        long maxPrice = anchor + maxDeviationTicks;
        long bestBid = bestBid(orderBook);
        long bestAsk = bestAsk(orderBook);
        List<DesiredQuote> quotes = new ArrayList<>(levels * 2);
        int suppressedDuplicateQuotes = 0;
        // 现货库存是基础资产余额，单位和衍生品持仓不同，不能拿它与合约的
        // maxInventorySteps 比较。现货是否有足够资产由账户预占原子校验，
        // 做市报价只负责生成双边价格；衍生品仍使用持仓库存风控。
        long riskPositionSteps = strategy.getProductLine() == ProductLine.SPOT ? 0L : signedPositionSteps;
        long previousBidDistance = 0L;
        long previousAskDistance = 0L;
        long previousBidPrice = Long.MAX_VALUE;
        long previousAskPrice = 0L;
        for (int level = 0; level < levels; level++) {
            long bidDistance = referenceDistance(referenceOrderBook, OrderSide.BUY, level);
            long askDistance = referenceDistance(referenceOrderBook, OrderSide.SELL, level);
            boolean referenceBid = referenceLevel(referenceOrderBook, OrderSide.BUY, level) != null;
            boolean referenceAsk = referenceLevel(referenceOrderBook, OrderSide.SELL, level) != null;
            bidDistance = referenceBid ? Math.max(bidHalfSpread, bidDistance) : bidHalfSpread + spacing * level;
            askDistance = referenceAsk ? Math.max(askHalfSpread, askDistance) : askHalfSpread + spacing * level;
            if (level > 0) {
                // Preserve the source's adjacent gap; configured spacing only extends missing depth.
                bidDistance = Math.max(bidDistance, previousBidDistance
                        + (referenceBid ? 1L : spacing));
                askDistance = Math.max(askDistance, previousAskDistance
                        + (referenceAsk ? 1L : spacing));
            }
            long bidGap = level == 0 ? 0 : bidDistance - previousBidDistance;
            long askGap = level == 0 ? 0 : askDistance - previousAskDistance;
            previousBidDistance = bidDistance;
            previousAskDistance = askDistance;
            long bidPrice = Math.max(minPrice, anchor - bidDistance);
            if (bestAsk > 0) {
                bidPrice = Math.min(bidPrice, bestAsk - 1L);
            }
            // When old opposite quotes cap the best price, move the whole ladder with it.
            // Clamping every level to the same top price collapses fifty levels into one.
            if (level > 0) bidPrice = Math.min(bidPrice, previousBidPrice - bidGap);
            previousBidPrice = bidPrice;
            if (bidPrice >= minPrice) suppressedDuplicateQuotes += addQuoteIfAllowed(strategy, risk, quotes, OrderSide.BUY, level, bidPrice,
                    riskPositionSteps, levelQuantity(strategy, quoting, referenceOrderBook, OrderSide.BUY, level));
            else suppressedDuplicateQuotes++;

            long askPrice = Math.min(maxPrice, anchor + askDistance);
            if (bestBid > 0) {
                askPrice = Math.max(askPrice, bestBid + 1L);
            }
            if (level > 0) askPrice = Math.max(askPrice, previousAskPrice + askGap);
            previousAskPrice = askPrice;
            if (askPrice <= maxPrice) suppressedDuplicateQuotes += addQuoteIfAllowed(strategy, risk, quotes, OrderSide.SELL, level, askPrice,
                    riskPositionSteps, levelQuantity(strategy, quoting, referenceOrderBook, OrderSide.SELL, level));
            else suppressedDuplicateQuotes++;
        }
        return new QuotePlan(anchor, signedPositionSteps,
                applyQuoteBudget(strategy, risk, instrument, markPrice, signedPositionSteps,
                        sizeLinearLiquidityBand(strategy, quoting, risk, instrument, signedPositionSteps, quotes, referenceOrderBook)),
                suppressedDuplicateQuotes);
    }

    private long priceSkewTicks(MarketMakerProperties.Strategy strategy, MarketMakerProperties.Quoting quoting,
            MarketMakerProperties.Risk risk, long anchor, long position, ReferenceOrderBookSnapshot reference) {
        long pressure = 0L;
        if (quoting.getReferencePressureSkewPpm() > 0 && reference != null && reference.hasTwoSidedDepth()) {
            var bid = nearDepth(reference.bids());
            var ask = nearDepth(reference.asks());
            var total = bid.add(ask);
            if (total.signum() > 0) {
                pressure = boundedSkew(anchor, quoting.getReferencePressureSkewPpm(), bid.subtract(ask), total);
            }
        }
        long maxInventory = maxInventory(strategy, risk);
        long inventory = maxInventory <= 0 ? 0L : boundedSkew(anchor, quoting.getInventoryPriceSkewPpm(),
                java.math.BigInteger.valueOf(Math.max(-maxInventory, Math.min(maxInventory, position))),
                java.math.BigInteger.valueOf(maxInventory));
        return Math.subtractExact(pressure, inventory);
    }

    private java.math.BigInteger nearDepth(List<ReferenceOrderBookLevel> levels) {
        var total = java.math.BigInteger.ZERO;
        for (int i = 0; i < Math.min(5, levels.size()); i++) {
            total = total.add(java.math.BigInteger.valueOf(Math.max(0L, levels.get(i).quantitySteps())));
        }
        return total;
    }

    private long boundedSkew(long anchor, long ppm, java.math.BigInteger imbalance,
            java.math.BigInteger total) {
        if (ppm == 0) return 0L;
        // Round the configured maximum up to an executable tick, then the signal to nearest tick.
        var million = java.math.BigInteger.valueOf(ONE_PPM);
        var maximum = java.math.BigInteger.valueOf(anchor).multiply(java.math.BigInteger.valueOf(ppm))
                .add(million).subtract(java.math.BigInteger.ONE).divide(million);
        long magnitude = maximum.multiply(imbalance.abs()).add(total.divide(java.math.BigInteger.TWO))
                .divide(total).min(maximum).longValueExact();
        return imbalance.signum() < 0 ? -magnitude : magnitude;
    }

    /** Make the configured executable band deep enough before enforcing the existing inventory/Core limits. */
    private List<DesiredQuote> sizeLinearLiquidityBand(MarketMakerProperties.Strategy strategy,
            MarketMakerProperties.Quoting quoting, MarketMakerProperties.Risk risk,
            InstrumentResponse instrument, long position, List<DesiredQuote> quotes,
            ReferenceOrderBookSnapshot reference) {
        long target = quoting.getLinearLiquidityTargetNotionalUnits();
        if (target == 0 || (instrument.contractType()
                != com.surprising.instrument.api.model.ContractType.LINEAR_PERPETUAL
                && instrument.contractType() != com.surprising.instrument.api.model.ContractType.LINEAR_DELIVERY))
            return quotes;
        int accounts = Math.max(1, strategy.getAccountIds().size());
        long accountTarget = target / accounts + (target % accounts == 0 ? 0 : 1);
        List<DesiredQuote> sized = new ArrayList<>(quotes);
        for (OrderSide side : OrderSide.values()) {
            long best = quotes.stream().filter(q -> q.side() == side).mapToLong(DesiredQuote::priceTicks)
                    .reduce(side == OrderSide.BUY ? Math::max : Math::min).orElse(0);
            if (best == 0) continue;
            long perStep = Math.multiplyExact(best, instrument.notionalMultiplierUnits());
            long targetSteps = accountTarget / perStep + (accountTarget % perStep == 0 ? 0 : 1);
            targetSteps = inventoryAdjustedQuantity(strategy, risk, side, position, targetSteps);
            List<Integer> band = new ArrayList<>();
            var distanceLimit = java.math.BigInteger.valueOf(best)
                    .multiply(java.math.BigInteger.valueOf(quoting.getLiquiditySlippagePpm()));
            for (int index = 0; index < sized.size(); index++) {
                DesiredQuote quote = sized.get(index);
                if (quote.side() != side) continue;
                long distance = side == OrderSide.BUY ? best - quote.priceTicks() : quote.priceTicks() - best;
                if (java.math.BigInteger.valueOf(distance).multiply(java.math.BigInteger.valueOf(ONE_PPM))
                        .compareTo(distanceLimit) > 0) continue;
                band.add(index);
            }
            band.sort((left, right) -> side == OrderSide.BUY
                    ? Long.compare(sized.get(right).priceTicks(), sized.get(left).priceTicks())
                    : Long.compare(sized.get(left).priceTicks(), sized.get(right).priceTicks()));
            // Spread funded depth across executable ticks. Outer slots carry up to twice
            // the weight of the best slot; never pour a capped slot's shortfall into best.
            // Reference depth changes redistribute the funded total, even at an unchanged mid.
            // Bound each multiplier to 0.8..1.2 so one external wall cannot absorb our ladder.
            long[] weights = new long[band.size()];
            long weightSum = 0;
            for (int slot = 0; slot < band.size(); slot++) {
                weights[slot] = Math.multiplyExact(band.size() + slot,
                        referenceDepthWeight(reference, side, sized.get(band.get(slot)).level()));
                weightSum = Math.addExact(weightSum, weights[slot]);
            }
            for (int slot = 0; slot < band.size(); slot++) {
                int index = band.get(slot);
                DesiredQuote quote = sized.get(index);
                long maximum = Math.min(instrument.maxQuantitySteps(), instrument.maxNotionalUnits()
                        / Math.multiplyExact(quote.priceTicks(), instrument.notionalMultiplierUnits()));
                var numerator = java.math.BigInteger.valueOf(targetSteps)
                        .multiply(java.math.BigInteger.valueOf(weights[slot]));
                var denominator = java.math.BigInteger.valueOf(weightSum);
                long share = numerator.add(denominator).subtract(java.math.BigInteger.ONE)
                        .divide(denominator).longValueExact();
                long quantity = Math.min(maximum, Math.max(instrument.minQuantitySteps(), share));
                sized.set(index, new DesiredQuote(side, quote.level(), quote.priceTicks(), quantity));
            }
        }
        return sized;
    }

    private long referenceDepthWeight(ReferenceOrderBookSnapshot reference, OrderSide side, int level) {
        if (reference == null || !reference.hasTwoSidedDepth()) return ONE_PPM;
        var levels = side == OrderSide.BUY ? reference.bids() : reference.asks();
        if (level >= levels.size()) return ONE_PPM;
        var total = java.math.BigInteger.ZERO;
        for (var entry : levels) {
            total = total.add(java.math.BigInteger.valueOf(Math.max(0L, entry.quantitySteps())));
        }
        if (total.signum() == 0) return ONE_PPM;
        var observed = java.math.BigInteger.valueOf(Math.max(0L, levels.get(level).quantitySteps()))
                .multiply(java.math.BigInteger.valueOf(levels.size()));
        return 800_000L + observed.multiply(java.math.BigInteger.valueOf(400_000L))
                .divide(observed.add(total)).longValueExact();
    }

    /** PMM budget sizing: scale each side proportionally; keep external depth proportions and core limits. */
    private List<DesiredQuote> applyQuoteBudget(MarketMakerProperties.Strategy strategy, MarketMakerProperties.Risk risk,
                                                     InstrumentResponse instrument, MarkPriceResponse mark,
                                                     long position, List<DesiredQuote> quotes) {
        if (strategy.getProductLine() == ProductLine.SPOT) return List.copyOf(quotes);
        long maxSteps = maxInventory(strategy, risk);
        if (instrument.contractType() == com.surprising.instrument.api.model.ContractType.LINEAR_PERPETUAL
                || instrument.contractType() == com.surprising.instrument.api.model.ContractType.LINEAR_DELIVERY) {
            long markTicks = markToTicks(instrument, mark);
            if (markTicks <= 0) throw new IllegalStateException("mark price required for linear quote budget");
            long budgetTicks = Math.max(markTicks, quotes.stream().mapToLong(DesiredQuote::priceTicks).max().orElse(markTicks));
            long perStep = Math.multiplyExact(budgetTicks, instrument.notionalMultiplierUnits());
            long limit = Math.min(instrument.maxPositionNotionalUnits(), instrument.userOpenInterestLimitFloorUnits());
            long oiSteps = limit / perStep;
            maxSteps = Math.min(maxSteps, oiSteps / 10 * 9);
        }
        List<DesiredQuote> sized = new ArrayList<>(quotes.size());
        for (OrderSide side : OrderSide.values()) {
            long capacity = side == OrderSide.BUY ? Math.max(0, Math.subtractExact(maxSteps, position))
                    : Math.max(0, Math.addExact(maxSteps, position));
            long total = quotes.stream().filter(q -> q.side() == side).mapToLong(DesiredQuote::quantitySteps).sum();
            long minimum = instrument.minQuantitySteps();
            long count = quotes.stream().filter(q -> q.side() == side).count();
            long minimumLadder = Math.multiplyExact(count, minimum);
            long availableExtra = Math.max(0, capacity - minimumLadder);
            long requestedExtra = Math.max(0, total - minimumLadder);
            for (DesiredQuote quote : quotes) {
                if (quote.side() != side) continue;
                long quantity;
                if (total <= capacity) quantity = quote.quantitySteps();
                else if (capacity < minimumLadder) quantity = Math.min(minimum, capacity);
                else quantity = minimum + (requestedExtra == 0 ? 0
                        : java.math.BigInteger.valueOf(Math.max(0, quote.quantitySteps() - minimum))
                                .multiply(java.math.BigInteger.valueOf(availableExtra))
                                .divide(java.math.BigInteger.valueOf(requestedExtra)).longValueExact());
                if (quantity >= minimum) {
                    sized.add(new DesiredQuote(side, quote.level(), quote.priceTicks(), quantity));
                    if (capacity < minimumLadder) capacity -= quantity;
                }
            }
        }
        // Preserve the interleaved bid/ask submission order used by reconciliation.
        sized.sort(java.util.Comparator.comparingInt(DesiredQuote::level).thenComparing(DesiredQuote::side));
        return List.copyOf(sized);
    }

    private int addQuoteIfAllowed(MarketMakerProperties.Strategy strategy,
                                  MarketMakerProperties.Risk risk,
                                  List<DesiredQuote> quotes,
                                  OrderSide side,
                                  int level,
                                  long priceTicks,
                                  long signedPositionSteps,
                                  long referenceQuantitySteps) {
        if (priceTicks <= 0 || !sideAllowed(side, strategy, risk, signedPositionSteps)) {
            return 0;
        }
        long quantity = adjustedQuantity(strategy, risk, side, signedPositionSteps, referenceQuantitySteps);
        if (quantity > 0) {
            boolean duplicatePrice = quotes.stream()
                    .anyMatch(quote -> quote.side() == side && quote.priceTicks() == priceTicks);
            if (duplicatePrice) {
                return 1;
            }
            quotes.add(new DesiredQuote(side, level, priceTicks, quantity));
        }
        return 0;
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
        return inventoryAdjustedQuantity(strategy, risk, side, signedPositionSteps, base);
    }

    private long inventoryAdjustedQuantity(MarketMakerProperties.Strategy strategy,
                                           MarketMakerProperties.Risk risk,
                                           OrderSide side, long signedPositionSteps, long base) {
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
        return scale == 0L ? 0L : Math.max(1L, multiplyDiv(base, scale, ONE_PPM));
    }

    private long anchorPriceTicks(MarketMakerProperties.Strategy strategy,
                                  InstrumentResponse instrument,
                                  OrderBookSnapshotResponse orderBook,
                                  MarkPriceResponse markPrice,
                                  ReferenceOrderBookSnapshot referenceOrderBook) {
        // Hummingbot PMM external price source: quote around the source market rather than our own fills.
        long fromReference = referenceMid(referenceOrderBook);
        if (fromReference > 0) return fromReference;
        long fromMark = markToTicks(instrument, markPrice);
        if (fromMark > 0) {
            return fromMark;
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
        // 启动时没有任何盘口时，只接受策略显式提供的锚点，禁止凭空生成价格。
        if (strategy != null && strategy.getInitialAnchorPriceTicks() > 0) {
            return strategy.getInitialAnchorPriceTicks();
        }
        throw new IllegalStateException("cannot resolve quote anchor price; configuredInitialAnchor="
                + (strategy == null ? "null" : strategy.getInitialAnchorPriceTicks()));
    }

    private long markToTicks(InstrumentResponse instrument, MarkPriceResponse markPrice) {
        if (instrument == null || markPrice == null || instrument.priceTickUnits() <= 0
                || markPrice.markPriceUnits() <= 0) {
            return 0L;
        }
        return (markPrice.markPriceUnits() + instrument.priceTickUnits() / 2L) / instrument.priceTickUnits();
    }

    private int orderLevels(MarketMakerProperties.Strategy strategy, MarketMakerProperties.Quoting quoting) {
        return strategy.getOrderLevels() != null && strategy.getOrderLevels() > 0
                ? Math.min(strategy.getOrderLevels(), 50) : quoting.getOrderLevels();
    }

    private long spreadTicks(MarketMakerProperties.Strategy strategy, MarketMakerProperties.Quoting quoting) {
        return strategy.getSpreadTicks() > 0
                ? Math.max(strategy.getSpreadTicks(), quoting.getMinSpreadTicks())
                : quoting.getMinSpreadTicks();
    }

    private long levelSpacingTicks(MarketMakerProperties.Strategy strategy, MarketMakerProperties.Quoting quoting) {
        return strategy.getLevelSpacingTicks() > 0 ? strategy.getLevelSpacingTicks() : quoting.getLevelSpacingTicks();
    }

    private long volatilitySpreadTicks(MarketMakerProperties.Quoting quoting, long volatilityTicks) {
        if (volatilityTicks <= 0 || quoting.getVolatilitySpreadMultiplierPpm() <= 0) {
            return 0L;
        }
        long spread = multiplyDiv(volatilityTicks, quoting.getVolatilitySpreadMultiplierPpm(), ONE_PPM);
        return Math.min(Math.max(1L, spread), quoting.getMaxVolatilitySpreadTicks());
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
        return Math.max(0L, distance);
    }

    /** Keep a stable size profile between refreshes; external liquidity and inventory still shape it. */
    private long levelQuantity(MarketMakerProperties.Strategy strategy, MarketMakerProperties.Quoting quoting,
                               ReferenceOrderBookSnapshot reference, OrderSide side, int level) {
        long external = referenceQuantity(reference, side, level);
        long variation = quoting.getQuantityVariationPpm();
        long base = Math.max(1L, strategy.getBaseQuantitySteps());
        boolean outerLevel = external <= 0;
        // Partial external books can contain fewer usable levels than the configured ladder.
        // Reuse observed sizes for outer levels while price spacing extends the reference shape.
        if (external <= 0 && reference != null && reference.hasTwoSidedDepth()) {
            var source = side == OrderSide.BUY ? reference.bids() : reference.asks();
            external = source.get(level % source.size()).quantitySteps();
        }
        if (external > 0 && (!outerLevel || variation == 0))
            return Math.min(base, Math.max(Math.max(1L, base / 200L), external));
        if (variation == 0) return base;
        long seed = ((long) strategy.getStrategyId().hashCode() << 32)
                ^ (side == OrderSide.BUY ? 0x1234abcdL : 0x5678ef01L) ^ level;
        var random = new java.util.SplittableRandom(seed);
        long scale = ONE_PPM - variation + random.nextLong(variation + 1);
        long profile = Math.max(1L, multiplyDiv(base, scale, ONE_PPM));
        // Half stable resting liquidity, half observed external liquidity; Core caps the total below.
        long observed = external > 0 ? Math.min(base, external) : profile;
        return Math.max(1L, profile / 2 + observed / 2);
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
