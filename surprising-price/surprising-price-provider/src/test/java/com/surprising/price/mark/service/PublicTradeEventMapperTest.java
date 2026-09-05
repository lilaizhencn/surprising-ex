package com.surprising.price.mark.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PublicTradeEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublicTradeEventMapperTest {

    @Test
    void mapsCanonicalTradeUsingTheEventInstrumentVersionScales() {
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        InstrumentSnapshotCache cache = new InstrumentSnapshotCache();
        cache.replace(ProductLine.LINEAR_PERPETUAL,
                List.of(instrument(8L, 25L, 100L), instrument(9L, 50L, 1_000L)),
                Map.of("BTC", 100_000_000L, "USDT", 100_000_000L));
        MarkPriceProperties properties = new MarkPriceProperties();
        PublicTradeEventMapper mapper = new PublicTradeEventMapper(
                new MarkPriceEncodingService(properties, cache));

        var trade = mapper.toPerpTradeEvent(new PublicTradeEvent("trade-8", 42L, "BTC-USDT", 8L,
                OrderSide.BUY, 12_345L, 2_000_001L, now, "trace-8"));

        assertThat(trade.symbol()).isEqualTo("BTC-USDT");
        assertThat(trade.tradeId()).isEqualTo("trade-8");
        assertThat(trade.sequence()).isEqualTo(42L);
        assertThat(trade.tradeTime()).isEqualTo(now);
        assertThat(trade.price()).isEqualByComparingTo("0.00308625");
        assertThat(trade.quantity()).isEqualByComparingTo("2.000001");
        assertThat(trade.side()).isEqualTo("BUY");
    }

    private InstrumentResponse instrument(long version, long priceTickUnits, long quantityStepUnits) {
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        return new InstrumentResponse("BTC-USDT", version, InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL,
                "BTC", "USDT", "USDT", 1_000_000L, "BTC", priceTickUnits, quantityStepUnits,
                1L, 1_000_000L, 1L, 1_000_000_000L, 1L, 2, 0, List.of("LIMIT"), List.of("GTC"),
                true, true, true, 100_000_000L, 10_000L, 5_000L, 100L, 500L,
                1_000_000_000L, 300_000L, 250_000_000L, 8, 100L, 3_000L, -3_000L,
                10_000_000L, 3, null, null, null, null, null, null, null,
                InstrumentStatus.TRADING, now, now, now, List.of(), List.of());
    }
}
