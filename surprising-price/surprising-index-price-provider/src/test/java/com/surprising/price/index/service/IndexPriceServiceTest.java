package com.surprising.price.index.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.SourceStatus;
import com.surprising.price.index.client.ExternalSpotPriceClient;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.model.SourceQuote;
import com.surprising.price.index.repository.IndexPriceRepository;
import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class IndexPriceServiceTest {

    @Test
    void publishesCompleteIndexSnapshotToTheProductSpecificTopicWithoutSynchronouslyWritingAuditTables() {
        IndexPriceProperties properties = properties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        IndexPriceProperties.SymbolConfig symbol = symbol("BTC-USDT-260925-70000-C");
        properties.setSymbols(List.of(symbol));
        IndexInstrumentConfigService configService = mock(IndexInstrumentConfigService.class);
        ExternalSpotPriceClient spotPriceClient = mock(ExternalSpotPriceClient.class);
        LatestSourceQuoteStore latestQuoteStore = mock(LatestSourceQuoteStore.class);
        IndexPriceRepository repository = mock(IndexPriceRepository.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        Instant now = Instant.now();

        when(configService.symbols()).thenReturn(List.of(symbol));
        when(repository.nextSequence("price-index", "BTC-USDT-260925-70000-C")).thenReturn(77L);
        for (IndexPriceProperties.SourceConfig source : symbol.getSources()) {
            when(latestQuoteStore.latest("BTC-USDT-260925-70000-C", source)).thenReturn(Optional.empty());
            when(spotPriceClient.fetch(source)).thenReturn(CompletableFuture.completedFuture(
                    quote(source.getName(), source.getSourceSymbol(), now)));
        }

        IndexPriceService service = new IndexPriceService(properties, configService, spotPriceClient,
                latestQuoteStore, new IndexPriceCalculator(properties), repository, kafkaTemplate);

        service.pollAndPublish();

        verify(kafkaTemplate).send(eq("surprising.option.index.price.v1"),
                eq("BTC-USDT-260925-70000-C"), any(IndexPriceEvent.class));
    }

    private IndexPriceProperties properties() {
        IndexPriceProperties properties = new IndexPriceProperties();
        properties.getCoordination().setEnabled(false);
        properties.getCalculation().setMinValidSources(3);
        properties.getInstrument().setEnabled(false);
        return properties;
    }

    private IndexPriceProperties.SymbolConfig symbol(String symbol) {
        IndexPriceProperties.SymbolConfig config = new IndexPriceProperties.SymbolConfig();
        config.setSymbol(symbol);
        config.setMinValidSources(3);
        config.setSources(List.of(source("BINANCE"), source("OKX"), source("BYBIT")));
        return config;
    }

    private IndexPriceProperties.SourceConfig source(String name) {
        IndexPriceProperties.SourceConfig source = new IndexPriceProperties.SourceConfig();
        source.setName(name);
        source.setSourceSymbol("BTCUSDT");
        source.setWebsocketEnabled(false);
        source.setWeight(BigDecimal.ONE);
        return source;
    }

    private SourceQuote quote(String source, String sourceSymbol, Instant now) {
        return new SourceQuote(source, sourceSymbol, new BigDecimal("100.00"),
                new BigDecimal("99.90"), new BigDecimal("100.10"), BigDecimal.ONE,
                SourceStatus.HEALTHY, null, now, now, 1L);
    }
}
