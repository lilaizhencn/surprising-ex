package com.surprising.price.mark.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.model.MarkPriceEncoding;
import com.surprising.price.mark.repository.MarkAssetScaleRepository;
import com.surprising.price.mark.repository.MarkInstrumentCurrentVersionRepository;
import com.surprising.price.mark.repository.MarkInstrumentRepository;
import com.surprising.price.mark.repository.MarkInstrumentRepository.MarkInstrument;
import com.surprising.product.api.ProductLine;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class MarkPriceEncodingServiceTest {

    @Test
    void aggregatesEncodingFromThreeSingleTableRepositories() {
        MarkPriceProperties properties = new MarkPriceProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        MarkInstrumentRepository instruments = mock(MarkInstrumentRepository.class);
        MarkInstrumentCurrentVersionRepository versions = mock(MarkInstrumentCurrentVersionRepository.class);
        MarkAssetScaleRepository scales = mock(MarkAssetScaleRepository.class);
        when(versions.findVersion("BTC-USD")).thenReturn(Optional.of(8L));
        when(instruments.find("BTC-USD", 8L, "INVERSE_PERPETUAL"))
                .thenReturn(Optional.of(new MarkInstrument("BTC-USD", 8L, "USD", 10L)));
        when(scales.findScaleUnits("USD")).thenReturn(OptionalLong.of(100_000_000L));
        MarkPriceEncodingService service = new MarkPriceEncodingService(
                instruments, versions, scales, properties);

        MarkPriceEncoding encoding = service.encoding("BTC-USD");

        assertThat(encoding.instrumentVersion()).isEqualTo(8L);
        assertThat(encoding.quoteScaleUnits()).isEqualTo(100_000_000L);
        assertThat(encoding.priceTickUnits()).isEqualTo(10L);
        verify(versions).findVersion("BTC-USD");
        verify(instruments).find("BTC-USD", 8L, "INVERSE_PERPETUAL");
        verify(scales).findScaleUnits("USD");
    }
}
