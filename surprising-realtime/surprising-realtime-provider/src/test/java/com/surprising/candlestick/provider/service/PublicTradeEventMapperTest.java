package com.surprising.candlestick.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PublicTradeEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.surprising.product.api.ProductLine;
import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import static org.mockito.Mockito.mock;

class PublicTradeEventMapperTest {

    @Test
    void mapsLinearContractStepsToBaseQuantityUsingContractFace() {
        InstrumentSnapshotCache snapshotCache = new InstrumentSnapshotCache();
        snapshotCache.replace(com.surprising.product.api.ProductLine.LINEAR_PERPETUAL,
                List.of(instrument()), java.util.Map.of("BTC", 100_000_000L, "USDT", 1_000_000L));
        PublicTradeEventMapper mapper = new PublicTradeEventMapper(snapshotCache);
        PublicTradeEvent trade = new PublicTradeEvent(
                "trade:1",
                1L,
                "1",
                OrderSide.BUY,
                6_000_000L,
                2L,
                Instant.parse("2026-07-31T00:00:00Z"),
                "trace:1");

        var first = mapper.toTradeEvent(trade);
        var second = mapper.toTradeEvent(trade);

        assertThat(first.price()).isEqualByComparingTo("60");
        assertThat(first.quantity()).isEqualByComparingTo("0.0002");
        assertThat(second).isEqualTo(first);
    }

    @Test
    void inverseDeliveryUsesQuoteFaceAndActualFillPriceForBaseVolume() {
        InstrumentSnapshotCache snapshots = new InstrumentSnapshotCache();
        snapshots.replace(ProductLine.INVERSE_DELIVERY,
                List.of(instrument(ContractType.INVERSE_DELIVERY, InstrumentStatus.TRADING)),
                java.util.Map.of("BTC", 100_000_000L, "USDT", 1_000_000L));
        CandlestickProperties properties = new CandlestickProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_DELIVERY);
        PublicTradeEventMapper mapper = new PublicTradeEventMapper(snapshots, properties);
        var first = mapper.toTradeEvent(new PublicTradeEvent("inverse-1", 1, "1", OrderSide.BUY,
                500_000, 2, Instant.parse("2026-07-31T00:00:00Z"), "inverse"));
        var second = mapper.toTradeEvent(new PublicTradeEvent("inverse-2", 2, "1", OrderSide.SELL,
                250_000, 2, Instant.parse("2026-07-31T00:00:01Z"), "inverse"));
        assertThat(first.price()).isEqualByComparingTo("50000");
        assertThat(first.quantity()).isEqualByComparingTo("0.04");
        assertThat(second.quantity()).isEqualByComparingTo("0.08");
        assertThat(first.price().multiply(first.quantity())).isEqualByComparingTo("2000");
        assertThat(second.price().multiply(second.quantity())).isEqualByComparingTo("2000");
    }

    @ParameterizedTest
    @EnumSource(value = InstrumentStatus.class, names = {"SETTLING", "CLOSED"})
    void committedDeliveryTradesRemainEligibleAfterTradingEnds(InstrumentStatus status) {
        InstrumentSnapshotCache snapshots = new InstrumentSnapshotCache();
        snapshots.replace(ProductLine.INVERSE_DELIVERY,
                List.of(instrument(ContractType.INVERSE_DELIVERY, status)),
                java.util.Map.of("BTC", 100_000_000L, "USDT", 1_000_000L));
        CandlestickProperties properties = new CandlestickProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_DELIVERY);
        SymbolRegistryService registry = new SymbolRegistryService(properties, snapshots, mock(InstrumentRpcApi.class));
        registry.refresh();
        assertThat(registry.isEnabled("1")).isTrue();
        assertThat(registry.isEnabled("99")).isFalse();
    }

    private InstrumentResponse instrument() {
        return instrument(ContractType.LINEAR_PERPETUAL, InstrumentStatus.TRADING);
    }

    private InstrumentResponse instrument(ContractType contractType, InstrumentStatus status) {
        Instant now = Instant.parse("2026-07-31T00:00:00Z");
        return new InstrumentResponse(1, 3, 1, contractType.isInverse() ? 3 : 1, contractType.isInverse() ? 1 : 3, "1", 7L, contractType.isDelivery() ? InstrumentType.DELIVERY : InstrumentType.PERPETUAL, contractType,
                "BTC", "USDT", contractType.isInverse() ? "BTC" : "USDT", 100L, contractType.isInverse() ? "USDT" : "BTC", contractType.isInverse() ? 100_000L : 10L, 1_000L, 1L, 1_000_000L,
                1L, 1_000_000_000L, contractType.isInverse() ? 1_000_000_000L : 1L, 2, 0, List.of("LIMIT"), List.of("GTC"), true,
                true, true, 100_000_000L, 10_000L, 5_000L, 100L, 500L,
                1_000_000_000L, 300_000L, 250_000_000L, 8, 100L, 3_000L, -3_000L,
                10_000_000L, 3, null, null, null, null, null, null, null, null,
                status, now, now, now, List.of(), List.of());
    }
}
