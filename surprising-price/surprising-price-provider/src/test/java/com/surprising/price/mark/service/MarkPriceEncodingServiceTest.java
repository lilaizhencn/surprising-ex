package com.surprising.price.mark.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.model.MarkPriceEncoding;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarkPriceEncodingServiceTest {

    @Test
    void readsEncodingFromTheInstrumentSnapshot() {
        MarkPriceProperties properties = new MarkPriceProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        InstrumentSnapshotCache cache = new InstrumentSnapshotCache();
        cache.replace(ProductLine.INVERSE_PERPETUAL, List.of(instrument()), Map.of("USD", 100_000_000L));
        MarkPriceEncodingService service = new MarkPriceEncodingService(properties, cache);

        MarkPriceEncoding encoding = service.encoding("BTC-USD");

        assertThat(encoding.instrumentVersion()).isEqualTo(8L);
        assertThat(encoding.quoteScaleUnits()).isEqualTo(100_000_000L);
        assertThat(encoding.priceTickUnits()).isEqualTo(10L);
    }

    private InstrumentResponse instrument() {
        Instant now = Instant.parse("2026-07-31T00:00:00Z");
        return new InstrumentResponse("BTC-USD", 8L, InstrumentType.PERPETUAL, ContractType.INVERSE_PERPETUAL,
                "BTC", "USD", "USD", 1_000_000L, "BTC", 10L, 1L, 1L, 1_000_000L,
                1L, 1_000_000_000L, 1L, 2, 0, List.of("LIMIT"), List.of("GTC"), true,
                true, true, 100_000_000L, 10_000L, 5_000L, 100L, 500L,
                1_000_000_000L, 300_000L, 250_000_000L, 8, 100L, 3_000L, -3_000L,
                10_000_000L, 3, null, null, null, null, null, null, null,
                InstrumentStatus.TRADING, now, now, now, List.of(), List.of());
    }
}
