package com.surprising.marketmaker.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

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
    void fallsBackToOrderBookMidWhenMarkPriceIsUnavailable() {
        QuotePlan plan = quotePlanner.plan(strategy(), quoting(), risk(), instrument(),
                orderBook(49_900L, 50_100L), null, 0L);

        assertThat(plan.anchorPriceTicks()).isEqualTo(50_000L);
    }

    @Test
    void mirrorsReferenceMarketDistancesAndQuantitiesWhenAvailable() {
        ReferenceOrderBookSnapshot reference = new ReferenceOrderBookSnapshot("BINANCE", "BTC-USDT",
                List.of(new ReferenceOrderBookLevel(49_990L, 3L),
                        new ReferenceOrderBookLevel(49_970L, 4L)),
                List.of(new ReferenceOrderBookLevel(50_020L, 7L),
                        new ReferenceOrderBookLevel(50_040L, 8L)),
                Instant.parse("2026-01-01T00:00:00Z"));

        QuotePlan plan = quotePlanner.plan(strategy(), quoting(), risk(), instrument(),
                orderBook(49_900L, 50_100L), mark(5_000_000L), 0L, reference);

        assertThat(plan.anchorPriceTicks()).isEqualTo(50_000L);
        assertThat(plan.quotes()).hasSize(4);
        assertThat(plan.quotes().get(0).side()).isEqualTo(OrderSide.BUY);
        assertThat(plan.quotes().get(0).priceTicks()).isEqualTo(49_985L);
        assertThat(plan.quotes().get(0).quantitySteps()).isEqualTo(3L);
        assertThat(plan.quotes().get(1).side()).isEqualTo(OrderSide.SELL);
        assertThat(plan.quotes().get(1).priceTicks()).isEqualTo(50_015L);
        assertThat(plan.quotes().get(1).quantitySteps()).isEqualTo(7L);
        assertThat(plan.quotes().get(2).priceTicks()).isEqualTo(49_965L);
        assertThat(plan.quotes().get(2).quantitySteps()).isEqualTo(4L);
        assertThat(plan.quotes().get(3).priceTicks()).isEqualTo(50_035L);
        assertThat(plan.quotes().get(3).quantitySteps()).isEqualTo(8L);
    }

    private MarketMakerProperties.Strategy strategy() {
        MarketMakerProperties.Strategy strategy = new MarketMakerProperties.Strategy();
        strategy.setStrategyId("btc-usdt-mm-a");
        strategy.setAccountIds(List.of(900001L));
        strategy.setSymbols(List.of("BTC-USDT"));
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
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new InstrumentResponse("BTC-USDT", 1L, InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL,
                "BTC", "USDT", "USDT", 1_000_000L, "BTC", 100L, 1L, 1L, 1_000_000L,
                1L, 1_000_000_000_000L, 1L, 2, 0, List.of("LIMIT"), List.of("GTX"), true,
                true, true, 100_000_000L, 10_000L, 5_000L, -100L, 500L,
                1_000_000_000L, 300_000L, 250_000_000L, 8, 100L, 3_000L, -3_000L,
                10_000_000L, 3, InstrumentStatus.TRADING, now, now, now, List.of(), List.of());
    }

    private OrderBookSnapshotResponse orderBook(long bid, long ask) {
        return new OrderBookSnapshotResponse("BTC-USDT", 1L, 20,
                List.of(new OrderBookLevel(bid, 100L, 1L)),
                List.of(new OrderBookLevel(ask, 100L, 1L)),
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private MarkPriceResponse mark(long markPriceUnits) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new MarkPriceResponse("BTC-USDT", BigDecimal.valueOf(50_000L), markPriceUnits,
                BigDecimal.valueOf(50_000L), BigDecimal.valueOf(50_000L), BigDecimal.valueOf(50_000L),
                BigDecimal.valueOf(50_000L), BigDecimal.valueOf(49_990L), BigDecimal.valueOf(50_010L),
                BigDecimal.ZERO, now.plusSeconds(3600), 3600L, BigDecimal.ZERO, 60L,
                BigDecimal.valueOf(49_000L), BigDecimal.valueOf(51_000L), 1L, PriceStatus.HEALTHY, now);
    }
}
