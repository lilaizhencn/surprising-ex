package com.surprising.marketmaker.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookLevel;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookSnapshot;
import com.surprising.marketmaker.provider.model.QuotePlan;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuotePlannerTest {

    private final QuotePlanner quotePlanner = new QuotePlanner();

    @Test
    void fundedLinearPlanMeetsTargetInsideOneBasisPointWithoutInflatingOuterLevels() {
        var strategy = strategy(); strategy.setOrderLevels(50);
        var quoting = quoting(); quoting.setLevelSpacingTicks(1); quoting.setLinearLiquidityTargetNotionalUnits(500_000_000L);
        var risk = risk(); risk.setMaxInventorySteps(20_000);
        var spec = org.mockito.Mockito.spy(instrument());
        org.mockito.Mockito.doReturn(2_000_000_000L).when(spec).userOpenInterestLimitFloorUnits();
        org.mockito.Mockito.doReturn(2_000_000_000L).when(spec).maxPositionNotionalUnits();
        var plan = quotePlanner.plan(strategy, quoting, risk, spec, orderBook(49900, 50100), mark(5_000_000), 0);
        assertBandCapacity(plan, 500_000_000L);
        for (OrderSide side : OrderSide.values()) {
            var best = plan.quotes().stream().filter(q -> q.side() == side)
                    .min((a, b) -> side == OrderSide.BUY ? Long.compare(b.priceTicks(), a.priceTicks())
                            : Long.compare(a.priceTicks(), b.priceTicks())).orElseThrow();
            assertThat(best.priceTicks() * best.quantitySteps() * spec.notionalMultiplierUnits())
                    .as("best quote carries only a share of the funded band")
                    .isLessThan(150_000_000L);
        }
        assertThat(plan.quotes().stream().filter(q -> q.level() == 49).mapToLong(q -> q.quantitySteps()))
                .containsExactly(10L, 10L);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {1, 40, 100})
    void distributionUsesOnlyExecutableTicksAndKeepsNearLevelsSmaller(long slippage) {
        var strategy = strategy(); strategy.setOrderLevels(50);
        var quoting = quoting(); quoting.setLevelSpacingTicks(1);
        quoting.setLiquiditySlippagePpm(slippage);
        quoting.setLinearLiquidityTargetNotionalUnits(500_000_000L);
        var risk = risk(); risk.setMaxInventorySteps(20_000);
        var spec = org.mockito.Mockito.spy(instrument());
        org.mockito.Mockito.doReturn(2_000_000_000L).when(spec).userOpenInterestLimitFloorUnits();
        org.mockito.Mockito.doReturn(2_000_000_000L).when(spec).maxPositionNotionalUnits();
        var plan = quotePlanner.plan(strategy, quoting, risk, spec, orderBook(49900, 50100), mark(5_000_000), 0);
        for (OrderSide side : OrderSide.values()) {
            var sorted = plan.quotes().stream().filter(q -> q.side() == side)
                    .sorted(java.util.Comparator.comparingInt(q -> q.level())).toList();
            long best = sorted.getFirst().priceTicks();
            var band = sorted.stream().filter(q -> Math.abs(q.priceTicks() - best) * 1_000_000 <= best * slippage).toList();
            long total = band.stream().mapToLong(q -> q.quantitySteps()).sum();
            assertThat(total * best).isGreaterThanOrEqualTo(500_000_000L);
            for (int i = 1; i < band.size(); i++) {
                assertThat(band.get(i).quantitySteps()).isGreaterThanOrEqualTo(band.get(i - 1).quantitySteps());
                assertThat(band.get(i).quantitySteps()).isLessThanOrEqualTo(band.getFirst().quantitySteps() * 2);
            }
            if (band.size() > 1) assertThat(band.getFirst().quantitySteps()).isLessThan(total / 2);
        }
    }

    @Test
    void fundedDepthRespondsToReferenceQuantitiesWithoutChangingPriceOrCapacity() {
        var strategy = strategy(); strategy.setOrderLevels(50);
        var quoting = quoting(); quoting.setLevelSpacingTicks(1);
        quoting.setLinearLiquidityTargetNotionalUnits(500_000_000L);
        var risk = risk(); risk.setMaxInventorySteps(20_000);
        var spec = org.mockito.Mockito.spy(instrument());
        org.mockito.Mockito.doReturn(2_000_000_000L).when(spec).userOpenInterestLimitFloorUnits();
        org.mockito.Mockito.doReturn(2_000_000_000L).when(spec).maxPositionNotionalUnits();
        var first = depthReference(100, Instant.EPOCH);
        var changed = depthReference(10_000, Instant.EPOCH.plusSeconds(1));
        var before = quotePlanner.plan(strategy, quoting, risk, spec, orderBook(49900, 50100),
                mark(5_000_000), 0, first);
        var after = quotePlanner.plan(strategy, quoting, risk, spec, orderBook(49900, 50100),
                mark(5_000_000), 0, changed);
        assertThat(after.quotes().stream().map(q -> q.priceTicks()).toList())
                .isEqualTo(before.quotes().stream().map(q -> q.priceTicks()).toList());
        assertThat(after.quotes().getFirst().quantitySteps()).isGreaterThan(before.quotes().getFirst().quantitySteps());
        assertThat(after.quotes().getFirst().quantitySteps()).isLessThan(before.quotes().getFirst().quantitySteps() * 2);
        assertThat(after.quotes().stream().filter(q -> q.side() == OrderSide.SELL).toList())
                .isEqualTo(before.quotes().stream().filter(q -> q.side() == OrderSide.SELL).toList());
        assertBandCapacity(before, 500_000_000L);
        assertBandCapacity(after, 500_000_000L);
        var heartbeat = quotePlanner.plan(strategy, quoting, risk, spec, orderBook(49900, 50100),
                mark(5_000_000), 0, depthReference(10_000, Instant.EPOCH.plusSeconds(2)));
        assertThat(heartbeat.quotes()).isEqualTo(after.quotes());
        var extreme = quotePlanner.plan(strategy, quoting, risk, spec, orderBook(49900, 50100),
                mark(5_000_000), 0, depthReference(Long.MAX_VALUE, Instant.EPOCH));
        assertBandCapacity(extreme, 500_000_000L);
        assertThat(extreme.quotes().getFirst().quantitySteps()).isLessThan(before.quotes().getFirst().quantitySteps() * 2);
    }

    private ReferenceOrderBookSnapshot depthReference(long firstQuantity, Instant time) {
        return new ReferenceOrderBookSnapshot("test", "604",
                List.of(new ReferenceOrderBookLevel(49999, firstQuantity),
                        new ReferenceOrderBookLevel(49998, 100), new ReferenceOrderBookLevel(49997, 100)),
                List.of(new ReferenceOrderBookLevel(50001, 100),
                        new ReferenceOrderBookLevel(50002, 100), new ReferenceOrderBookLevel(50003, 100)), time);
    }

    @Test
    void depthTargetIsSharedAcrossConfiguredMakerAccounts() {
        var strategy = strategy(); strategy.setAccountIds(List.of(900001L, 900002L));
        var quoting = quoting(); quoting.setLinearLiquidityTargetNotionalUnits(50_000_000L);
        var plan = quotePlanner.plan(strategy, quoting, risk(), instrument(), orderBook(49900, 50100), mark(5_000_000), 0);
        assertBandCapacity(plan, 25_000_000L);
        assertThat(plan.quotes().stream().mapToLong(q -> q.quantitySteps()).max().orElseThrow()).isLessThan(600);
    }

    @Test
    void liquidityTargetCannotOverrideInventoryOrIndividualOrderLimits() {
        var quoting = quoting(); quoting.setLinearLiquidityTargetNotionalUnits(500_000_000L);
        var spec = org.mockito.Mockito.spy(instrument());
        org.mockito.Mockito.doReturn(100L).when(spec).maxQuantitySteps();
        var plan = quotePlanner.plan(strategy(), quoting, risk(), spec, orderBook(49900, 50100), mark(5_000_000), 950);
        assertThat(plan.quotes()).allSatisfy(q -> assertThat(q.quantitySteps()).isLessThanOrEqualTo(100));
        assertThat(plan.quotes().stream().filter(q -> q.side() == OrderSide.BUY).mapToLong(q -> q.quantitySteps()).sum())
                .isLessThanOrEqualTo(50);
    }

    private static void assertBandCapacity(QuotePlan plan, long target) {
        for (OrderSide side : OrderSide.values()) {
            var quotes = plan.quotes().stream().filter(q -> q.side() == side).toList();
            long best = quotes.stream().mapToLong(q -> q.priceTicks())
                    .reduce(side == OrderSide.BUY ? Math::max : Math::min).orElseThrow();
            long steps = quotes.stream().filter(q -> Math.abs(q.priceTicks() - best) * 1_000_000 <= best * 100)
                    .mapToLong(q -> q.quantitySteps()).sum();
            // All requested contracts must fit in the band, including ceil rounding of market-order size.
            assertThat(steps).isGreaterThanOrEqualTo(target / best + (target % best == 0 ? 0 : 1));
        }
    }

    @Test
    void linearQuotesCoverPositiveMakerFeeAndConfiguredNetEdge() {
        var spec = org.mockito.Mockito.spy(instrument());
        org.mockito.Mockito.doReturn(200L).when(spec).makerFeeRatePpm();
        var quoting = quoting();
        quoting.setMinNetHalfSpreadPpm(100);
        var plan = quotePlanner.plan(strategy(), quoting, risk(), spec,
                orderBook(49_900, 50_100), mark(5_000_000), 0);
        long bestBid = plan.quotes().stream().filter(q -> q.side() == OrderSide.BUY)
                .mapToLong(q -> q.priceTicks()).max().orElseThrow();
        long bestAsk = plan.quotes().stream().filter(q -> q.side() == OrderSide.SELL)
                .mapToLong(q -> q.priceTicks()).min().orElseThrow();
        assertThat(bestBid).isLessThanOrEqualTo(49_984);
        assertThat(bestAsk).isGreaterThanOrEqualTo(50_016);
        assertThat((bestAsk - bestBid) * 1_000_000L - (bestAsk + bestBid) * 200L).isPositive();
    }

    @Test
    void entireRestingLadderCannotExceedInventoryAfterOneSidedFills() {
        var strategy = strategy();
        strategy.setOrderLevels(50);
        strategy.setBaseQuantitySteps(1000);
        for (long position : new long[]{0, 990, -990}) {
            var plan = quotePlanner.plan(strategy, quoting(), risk(), instrument(),
                    orderBook(49_990, 50_010), mark(5_000_000), position);
            long bids = plan.quotes().stream().filter(q -> q.side() == OrderSide.BUY).mapToLong(q -> q.quantitySteps()).sum();
            long asks = plan.quotes().stream().filter(q -> q.side() == OrderSide.SELL).mapToLong(q -> q.quantitySteps()).sum();
            assertThat(position + bids).isLessThanOrEqualTo(1000);
            assertThat(position - asks).isGreaterThanOrEqualTo(-1000);
        }
    }

    @Test
    void followsAdjacentReferencePriceTicks() {
        var strategy = strategy();
        strategy.setOrderLevels(5);
        var quoting = quoting();
        quoting.setMinSpreadTicks(2L);
        var bids = new java.util.ArrayList<ReferenceOrderBookLevel>();
        var asks = new java.util.ArrayList<ReferenceOrderBookLevel>();
        for (int level = 1; level <= 5; level++) {
            bids.add(new ReferenceOrderBookLevel(50_000L - level, 10L));
            asks.add(new ReferenceOrderBookLevel(50_000L + level, 10L));
        }
        var reference = new ReferenceOrderBookSnapshot("source", "1", bids, asks, Instant.now());
        var plan = quotePlanner.plan(strategy, quoting, risk(), instrument(),
                orderBook(49_900L, 50_100L), mark(5_000_000L), 0L, reference);
        assertThat(plan.quotes().stream().filter(q -> q.side() == OrderSide.BUY)
                .map(q -> q.priceTicks()).toList()).containsExactly(49_999L, 49_998L,
                        49_997L, 49_996L, 49_995L);
    }

    @Test
    void externalTwentyLevelsExtendToConfiguredFiftyAndKeepDepthShape() {
        var strategy = strategy();
        strategy.setOrderLevels(50);
        strategy.setBaseQuantitySteps(1_000L);
        var bids = new java.util.ArrayList<ReferenceOrderBookLevel>();
        var asks = new java.util.ArrayList<ReferenceOrderBookLevel>();
        for (int level = 0; level < 20; level++) {
            bids.add(new ReferenceOrderBookLevel(49_990L - level * 10L, level == 0 ? 90L : 1L));
            asks.add(new ReferenceOrderBookLevel(50_010L + level * 10L, level == 0 ? 80L : 2L));
        }
        var reference = new ReferenceOrderBookSnapshot("source", "1", bids, asks, Instant.now());
        var spec = instrument();
        var plan = quotePlanner.plan(strategy, quoting(), risk(), spec,
                orderBook(49_990L, 50_010L), mark(5_000_000L), 0L, reference);
        assertThat(plan.quotes()).hasSize(100);
        assertThat(plan.quotes().stream().filter(q -> q.side() == OrderSide.BUY)
                .mapToLong(q -> q.quantitySteps()).distinct().count()).isGreaterThan(1);
        for (OrderSide side : OrderSide.values()) {
            long worstPrice = plan.quotes().stream().mapToLong(q -> q.priceTicks()).max().orElseThrow();
            long notional = plan.quotes().stream().filter(q -> q.side() == side)
                    .mapToLong(q -> q.quantitySteps()).sum() * worstPrice * spec.notionalMultiplierUnits();
            assertThat(notional).isLessThanOrEqualTo(spec.userOpenInterestLimitFloorUnits());
        }
    }

    @Test
    void variedFiftyLevelLadderKeepsDistinctSizesAndConservativeBudget() {
        var strategy = strategy();
        strategy.setOrderLevels(50);
        strategy.setBaseQuantitySteps(1000L);
        var quoting = quoting();
        quoting.setQuantityVariationPpm(800_000L);
        var reference = new ReferenceOrderBookSnapshot("source", "1",
                List.of(new ReferenceOrderBookLevel(49_990L, 1000L)),
                List.of(new ReferenceOrderBookLevel(50_010L, 1000L)), Instant.now());
        var spec = instrument();
        var plan = quotePlanner.plan(strategy, quoting, risk(), spec,
                orderBook(49_990L, 50_010L), mark(5_000_000L), 0L, reference);
        assertThat(plan.quotes()).hasSize(100);
        assertThat(quotePlanner.plan(strategy, quoting, risk(), spec,
                orderBook(49_990L, 50_010L), mark(5_000_000L), 0L, reference)).isEqualTo(plan);
        for (var side : OrderSide.values()) {
            var quotes = plan.quotes().stream().filter(q -> q.side() == side).toList();
            assertThat(quotes).hasSize(50);
            assertThat(quotes.stream().map(q -> q.quantitySteps()).distinct().count()).isGreaterThan(5);
            long worstPrice = plan.quotes().stream().mapToLong(q -> q.priceTicks()).max().orElseThrow();
            long notional = quotes.stream().mapToLong(q -> q.quantitySteps()).sum()
                    * worstPrice * spec.notionalMultiplierUnits();
            assertThat(notional).isLessThanOrEqualTo(spec.userOpenInterestLimitFloorUnits());
        }
    }

    @Test
    void movingReferenceKeepsFiftyDistinctLevelsWhileOldOppositeQuotesRemain() {
        var strategy = strategy();
        strategy.setOrderLevels(50);
        var quoting = quoting();
        quoting.setMaxPriceDeviationPpm(100_000);
        for (long markUnits : new long[] {5_100_000L, 4_900_000L}) {
            var plan = quotePlanner.plan(strategy, quoting, risk(), instrument(),
                    orderBook(49_990L, 50_010L), mark(markUnits), 0L);
            var bids = plan.quotes().stream().filter(q -> q.side() == OrderSide.BUY).toList();
            var asks = plan.quotes().stream().filter(q -> q.side() == OrderSide.SELL).toList();
            assertThat(bids).hasSize(50);
            assertThat(asks).hasSize(50);
            assertThat(bids).extracting(q -> q.priceTicks()).doesNotHaveDuplicates();
            assertThat(asks).extracting(q -> q.priceTicks()).doesNotHaveDuplicates();
            assertThat(bids).allSatisfy(q -> assertThat(q.priceTicks()).isLessThan(50_010L));
            assertThat(asks).allSatisfy(q -> assertThat(q.priceTicks()).isGreaterThan(49_990L));
            for (int i = 1; i < 50; i++) {
                assertThat(bids.get(i - 1).priceTicks() - bids.get(i).priceTicks()).isGreaterThanOrEqualTo(10);
                assertThat(asks.get(i).priceTicks() - asks.get(i - 1).priceTicks()).isGreaterThanOrEqualTo(10);
            }
        }
    }

    @Test
    void plansSymmetricLevelsAroundFreshMarkPrice() {
        MarketMakerProperties.Strategy strategy = strategy();
        MarketMakerProperties.Quoting quoting = quoting();
        MarketMakerProperties.Risk risk = risk();

        QuotePlan plan = quotePlanner.plan(strategy, quoting, risk, instrument(),
                orderBook(49_990L, 50_010L), mark(5_000_000L), 0L);

        assertThat(plan.anchorPriceTicks()).isEqualTo(50_000L);
        assertThat(plan.quotes()).hasSize(4);
        assertThat(plan.quotes().get(0).side()).isEqualTo(OrderSide.BUY);
        assertThat(plan.quotes().get(0).priceTicks()).isEqualTo(49_995L);
        assertThat(plan.quotes().get(1).side()).isEqualTo(OrderSide.SELL);
        assertThat(plan.quotes().get(1).priceTicks()).isEqualTo(50_005L);
        assertThat(plan.quotes().get(2).priceTicks()).isEqualTo(49_985L);
        assertThat(plan.quotes().get(3).priceTicks()).isEqualTo(50_015L);
    }

    @Test
    void stopsExposureIncreasingSideAtInventoryCap() {
        MarketMakerProperties.Strategy strategy = strategy();
        strategy.setMaxInventorySteps(100L);

        QuotePlan plan = quotePlanner.plan(strategy, quoting(), risk(), instrument(),
                orderBook(49_990L, 50_010L), mark(5_000_000L), 100L);

        assertThat(plan.quotes()).extracting(quote -> quote.side()).containsOnly(OrderSide.SELL);
    }

    @Test
    void oneStepQuoteKeepsBothSidesUntilInventoryCap() {
        MarketMakerProperties.Strategy strategy = strategy();
        strategy.setBaseQuantitySteps(1L);
        strategy.setMaxInventorySteps(10_000L);
        strategy.setOrderLevels(50);
        MarketMakerProperties.Quoting quoting = quoting();
        quoting.setMaxPriceDeviationPpm(5_000L);

        QuotePlan plan = quotePlanner.plan(strategy, quoting, risk(), instrument(10_000_000L),
                orderBook(780_820L, 780_842L), mark(7_808_312_833_333L), 1L);

        assertThat(plan.quotes().stream().filter(quote -> quote.side() == OrderSide.BUY)).hasSize(50);
        assertThat(plan.quotes().stream().filter(quote -> quote.side() == OrderSide.SELL)).hasSize(50);
        assertThat(plan.quotes()).allSatisfy(quote -> assertThat(quote.quantitySteps()).isEqualTo(1L));
    }

    @Test
    void spotAssetBalanceDoesNotDisableBuyQuotes() {
        MarketMakerProperties.Strategy strategy = strategy();
        strategy.setProductLine(ProductLine.SPOT);
        strategy.setMaxInventorySteps(100L);

        // 现货基础资产余额可能远大于衍生品库存档位，但仍应生成双边报价。
        QuotePlan plan = quotePlanner.plan(strategy, quoting(), risk(), spotInstrument(),
                orderBook(49_990L, 50_010L), null, 100_000_000_000L);

        assertThat(plan.quotes()).extracting(quote -> quote.side())
                .containsExactly(OrderSide.BUY, OrderSide.SELL, OrderSide.BUY, OrderSide.SELL);
    }

    @Test
    void linearQuotesRequireMarkPriceEvenWithAnExplicitInitialAnchor() {
        var strategy = strategy();
        strategy.setInitialAnchorPriceTicks(50_000L);
        assertThatThrownBy(() -> quotePlanner.plan(strategy, quoting(), risk(), instrument(),
                orderBook(49_900L, 50_100L), null, 0L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mark price required");
    }

    @Test
    void relativeSpreadScalesWithPrice() {
        var quoting = quoting();
        quoting.setHalfSpreadPpm(1_000L);
        var plan = quotePlanner.plan(strategy(), quoting, risk(), instrument(),
                orderBook(49_000L, 51_000L), mark(5_000_000L), 0L);
        assertThat(plan.quotes().getFirst().priceTicks()).isEqualTo(49_950L);
        assertThat(plan.quotes().get(1).priceTicks()).isEqualTo(50_050L);
    }

    @Test
    void sizesWholeLadderWithinConservativeOpenInterestBudget() {
        var strategy = strategy();
        strategy.setOrderLevels(50);
        strategy.setBaseQuantitySteps(1_000_000L);
        var spec = instrument();
        var plan = quotePlanner.plan(strategy, quoting(), risk(), spec,
                orderBook(49_000L, 51_000L), mark(5_000_000L), 0L);
        for (var side : OrderSide.values()) {
            long steps = plan.quotes().stream().filter(q -> q.side() == side)
                    .mapToLong(q -> q.quantitySteps()).sum();
            assertThat(Math.multiplyExact(steps, 50_000L * spec.notionalMultiplierUnits()))
                    .isLessThanOrEqualTo(spec.userOpenInterestLimitFloorUnits());
        }
    }

    @Test
    void mirrorsReferenceMarketDistancesAndQuantitiesWhenAvailable() {
        ReferenceOrderBookSnapshot reference = new ReferenceOrderBookSnapshot("BINANCE", "1",
                List.of(new ReferenceOrderBookLevel(49_990L, 3L),
                        new ReferenceOrderBookLevel(49_970L, 4L)),
                List.of(new ReferenceOrderBookLevel(50_020L, 7L),
                        new ReferenceOrderBookLevel(50_040L, 8L)),
                Instant.parse("2026-01-01T00:00:00Z"));

        QuotePlan plan = quotePlanner.plan(strategy(), quoting(), risk(), instrument(),
                orderBook(49_900L, 50_100L), mark(5_000_000L), 0L, reference);

        assertThat(plan.anchorPriceTicks()).isEqualTo(50_005L);
        assertThat(plan.quotes()).hasSize(4);
        assertThat(plan.quotes().get(0).side()).isEqualTo(OrderSide.BUY);
        assertThat(plan.quotes().get(0).priceTicks()).isEqualTo(49_990L);
        assertThat(plan.quotes().get(0).quantitySteps()).isEqualTo(3L);
        assertThat(plan.quotes().get(1).side()).isEqualTo(OrderSide.SELL);
        assertThat(plan.quotes().get(1).priceTicks()).isEqualTo(50_020L);
        assertThat(plan.quotes().get(1).quantitySteps()).isEqualTo(7L);
        assertThat(plan.quotes().get(2).priceTicks()).isEqualTo(49_970L);
        assertThat(plan.quotes().get(2).quantitySteps()).isEqualTo(4L);
        assertThat(plan.quotes().get(3).priceTicks()).isEqualTo(50_040L);
        assertThat(plan.quotes().get(3).quantitySteps()).isEqualTo(8L);
    }

    @Test
    void widensHalfSpreadFromObservedVolatilityWithoutChangingAnchor() {
        MarketMakerProperties.Quoting quoting = quoting();
        quoting.setVolatilitySpreadMultiplierPpm(500_000L);
        quoting.setMaxVolatilitySpreadTicks(100L);

        QuotePlan plan = quotePlanner.plan(strategy(), quoting, risk(), instrument(),
                orderBook(49_990L, 50_010L), mark(5_000_000L), 0L, 100L, null);

        assertThat(plan.anchorPriceTicks()).isEqualTo(50_000L);
        assertThat(plan.quotes().get(0).priceTicks()).isEqualTo(49_950L);
        assertThat(plan.quotes().get(1).priceTicks()).isEqualTo(50_050L);
    }

    @Test
    void capsReferenceMarketQuantityAtStrategyBaseQuantity() {
        ReferenceOrderBookSnapshot reference = new ReferenceOrderBookSnapshot("BINANCE", "WEBSOCKET", "1",
                List.of(new ReferenceOrderBookLevel(49_990L, 40L)),
                List.of(new ReferenceOrderBookLevel(50_020L, 70L)),
                Instant.parse("2026-01-01T00:00:00Z"));

        QuotePlan plan = quotePlanner.plan(strategy(), quoting(), risk(), instrument(),
                orderBook(49_900L, 50_100L), mark(5_000_000L), 0L, reference);

        assertThat(plan.quotes().get(0).quantitySteps()).isEqualTo(10L);
        assertThat(plan.quotes().get(1).quantitySteps()).isEqualTo(10L);
    }

    @Test
    void extendsShortReferenceBookToFiftyDistinctLevelsPerSide() {
        MarketMakerProperties.Strategy strategy = strategy();
        strategy.setOrderLevels(50);
        MarketMakerProperties.Quoting quoting = quoting();
        quoting.setLevelSpacingTicks(1L);
        quoting.setMaxPriceDeviationPpm(50_000L);
        ReferenceOrderBookSnapshot reference = new ReferenceOrderBookSnapshot("BINANCE", "WEBSOCKET", "1",
                List.of(new ReferenceOrderBookLevel(49_995L, 1L), new ReferenceOrderBookLevel(49_990L, 1L)),
                List.of(new ReferenceOrderBookLevel(50_005L, 1L), new ReferenceOrderBookLevel(50_010L, 1L)),
                Instant.parse("2026-01-01T00:00:00Z"));

        QuotePlan plan = quotePlanner.plan(strategy, quoting, risk(), instrument(),
                orderBook(49_990L, 50_010L), mark(5_000_000L), 0L, reference);

        assertThat(plan.quotes().stream().filter(quote -> quote.side() == OrderSide.BUY)
                .map(quote -> quote.priceTicks()).distinct()).hasSize(50);
        assertThat(plan.quotes().stream().filter(quote -> quote.side() == OrderSide.SELL)
                .map(quote -> quote.priceTicks()).distinct()).hasSize(50);
    }

    @Test
    void extendsSixUsableReferenceLevelsWithObservedQuantities() {
        MarketMakerProperties.Strategy strategy = strategy();
        strategy.setOrderLevels(50);
        MarketMakerProperties.Quoting quoting = quoting();
        quoting.setMaxPriceDeviationPpm(50_000L);
        var bids = new java.util.ArrayList<ReferenceOrderBookLevel>();
        var asks = new java.util.ArrayList<ReferenceOrderBookLevel>();
        for (int level = 0; level < 6; level++) {
            bids.add(new ReferenceOrderBookLevel(49_995L - 5L * level, level + 1L));
            asks.add(new ReferenceOrderBookLevel(50_005L + 5L * level, level + 1L));
        }
        var reference = new ReferenceOrderBookSnapshot("BINANCE", "WEBSOCKET", "1", bids, asks,
                Instant.parse("2026-01-01T00:00:00Z"));

        QuotePlan plan = quotePlanner.plan(strategy, quoting, risk(), instrument(),
                orderBook(49_900L, 50_100L), mark(5_000_000L), 0L, reference);

        var bidQuotes = plan.quotes().stream().filter(quote -> quote.side() == OrderSide.BUY).toList();
        assertThat(bidQuotes).hasSize(50);
        assertThat(bidQuotes.get(0).quantitySteps()).isEqualTo(1L);
        assertThat(bidQuotes.get(5).quantitySteps()).isEqualTo(6L);
        assertThat(bidQuotes.get(6).quantitySteps()).isEqualTo(1L);
        assertThat(bidQuotes.get(7).quantitySteps()).isEqualTo(2L);
    }

    @Test
    void plansTwentyDistinctExecutableLevelsWithinDeviationForLinearPerpetual() {
        MarketMakerProperties.Strategy strategy = strategy();
        strategy.setOrderLevels(20);
        MarketMakerProperties.Quoting quoting = quoting();
        quoting.setMinSpreadTicks(20L);
        quoting.setLevelSpacingTicks(8L);
        quoting.setMaxPriceDeviationPpm(5_000L);
        long anchor = 780_831L;

        QuotePlan plan = quotePlanner.plan(strategy, quoting, risk(), instrument(10_000_000L),
                orderBook(780_820L, 780_842L), mark(7_808_312_833_333L), 0L);

        assertThat(plan.anchorPriceTicks()).isEqualTo(anchor);
        assertThat(plan.quotes()).hasSize(40)
                .allSatisfy(quote -> {
                    assertThat(quote.quantitySteps()).isPositive();
                    assertThat(Math.abs(quote.priceTicks() - anchor)).isLessThanOrEqualTo(3_904L);
                });
        assertThat(plan.quotes().stream().filter(quote -> quote.side() == OrderSide.BUY)
                .map(quote -> quote.priceTicks()).distinct()).hasSize(20).allMatch(price -> price < anchor);
        assertThat(plan.quotes().stream().filter(quote -> quote.side() == OrderSide.SELL)
                .map(quote -> quote.priceTicks()).distinct()).hasSize(20).allMatch(price -> price > anchor);
    }

    @Test
    void tinyAnchorReportsReducedDistinctDepthWithoutCrossingOrZeroQuantity() {
        MarketMakerProperties.Strategy strategy = strategy();
        strategy.setOrderLevels(20);
        MarketMakerProperties.Quoting quoting = quoting();
        quoting.setMinSpreadTicks(20L);
        quoting.setLevelSpacingTicks(8L);
        quoting.setMaxPriceDeviationPpm(5_000L);

        QuotePlan plan = quotePlanner.plan(strategy, quoting, risk(), instrument(100_000_000_000L),
                orderBook(77L, 79L), mark(7_808_312_833_333L), 0L);

        assertThat(plan.anchorPriceTicks()).isEqualTo(78L);
        assertThat(plan.quotes()).hasSize(2)
                .allSatisfy(quote -> assertThat(quote.quantitySteps()).isPositive());
        assertThat(plan.quotes()).extracting(quote -> quote.priceTicks()).containsExactly(77L, 79L);
        assertThat(plan.quotes().stream().map(quote -> quote.priceTicks()).distinct()).hasSize(2);
        long maxBid = plan.quotes().stream().filter(quote -> quote.side() == OrderSide.BUY)
                .mapToLong(quote -> quote.priceTicks()).max().orElseThrow();
        assertThat(maxBid).isLessThan(79L);
        assertThat(plan.suppressedDuplicateQuotes()).isEqualTo(38);
    }

    private MarketMakerProperties.Strategy strategy() {
        MarketMakerProperties.Strategy strategy = new MarketMakerProperties.Strategy();
        strategy.setStrategyId("47");
        strategy.setAccountIds(List.of(900001L));
        strategy.setInstrumentIds(List.of("1"));
        strategy.setBaseQuantitySteps(10L);
        strategy.setOrderLevels(2);
        return strategy;
    }

    private MarketMakerProperties.Quoting quoting() {
        MarketMakerProperties.Quoting quoting = new MarketMakerProperties.Quoting();
        quoting.setMinSpreadTicks(10L);
        quoting.setLevelSpacingTicks(10L);
        quoting.setOrderLevels(2);
        quoting.setMaxPriceDeviationPpm(10_000L);
        return quoting;
    }

    private MarketMakerProperties.Risk risk() {
        MarketMakerProperties.Risk risk = new MarketMakerProperties.Risk();
        risk.setMaxInventorySteps(1000L);
        risk.setMaxInventorySkewPpm(800_000L);
        return risk;
    }

    private InstrumentResponse instrument() {
        return instrument(100L);
    }

    private InstrumentResponse instrument(long priceTickUnits) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new InstrumentResponse(1, 3, 1, 1, 3, "1", 1L, InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL,
                "BTC", "USDT", "USDT", 1_000_000L, "BTC", priceTickUnits, 1L, 1L, 1_000_000L,
                1L, 1_000_000_000_000L, 1L, 2, 0, List.of("LIMIT"), List.of("GTX"), true,
                true, true, 100_000_000L, 10_000L, 5_000L, -100L, 500L,
                1_000_000_000L, 300_000L, 250_000_000L, 8, 100L, 3_000L, -3_000L,
                10_000_000L, 3, null, null, null, null, null, null, null, null,
                InstrumentStatus.TRADING, now, now, now, List.of(), List.of());
    }

    private InstrumentResponse spotInstrument() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new InstrumentResponse(1, 3, 1, 1, 3, "1", 1L, InstrumentType.SPOT, ContractType.SPOT,
                "BTC", "USDT", "USDT", 1_000_000L, "BTC", 100L, 1L, 1L, 1_000_000L,
                1L, 1_000_000_000_000L, 1L, 2, 0, List.of("LIMIT"), List.of("GTC"), true,
                true, true, 100_000_000L, 10_000L, 5_000L, -100L, 500L,
                1_000_000_000L, 300_000L, 250_000_000L, 8, 100L, 3_000L, -3_000L,
                10_000_000L, 3, null, null, null, null, null, null, null, null,
                InstrumentStatus.TRADING, now, now, now, List.of(), List.of());
    }

    private OrderBookSnapshotResponse orderBook(long bid, long ask) {
        return new OrderBookSnapshotResponse("1", 1L, 20,
                List.of(new OrderBookLevel(bid, 100L, 1L)),
                List.of(new OrderBookLevel(ask, 100L, 1L)),
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private MarkPriceResponse mark(long markPriceUnits) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new MarkPriceResponse("1", BigDecimal.valueOf(50_000L), markPriceUnits,
                BigDecimal.valueOf(50_000L), BigDecimal.valueOf(50_000L), BigDecimal.valueOf(50_000L),
                BigDecimal.valueOf(50_000L), BigDecimal.valueOf(49_990L), BigDecimal.valueOf(50_010L),
                BigDecimal.ZERO, now.plusSeconds(3600), 3600L, BigDecimal.ZERO, 60L,
                BigDecimal.valueOf(49_000L), BigDecimal.valueOf(51_000L), 1L, PriceStatus.HEALTHY, now);
    }
}
