package com.surprising.price.index.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.PricePublishedEvent;
import com.surprising.price.api.model.QuoteTransport;
import com.surprising.price.api.model.SourceStatus;
import com.surprising.price.index.client.ExternalSpotPriceClient;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.model.SourceQuote;
import com.surprising.price.index.repository.IndexPriceLeaseRepository;
import com.surprising.price.index.repository.IndexPriceSequenceRepository;
import com.surprising.price.mark.service.MarkPriceService;
import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

class IndexPriceServiceTest {

    @Test
    void publishesCompleteIndexSnapshotToTheProductSpecificTopicWithoutSynchronouslyWritingAuditTables() {
        IndexPriceProperties properties = properties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        IndexPriceProperties.SymbolConfig instrumentId = instrumentId("45");
        IndexInstrumentConfigService configService = mock(IndexInstrumentConfigService.class);
        ExternalSpotPriceClient spotPriceClient = mock(ExternalSpotPriceClient.class);
        LatestSourceQuoteStore latestQuoteStore = mock(LatestSourceQuoteStore.class);
        IndexPriceLeaseRepository leaseRepository = mock(IndexPriceLeaseRepository.class);
        IndexPriceSequenceRepository sequenceRepository = mock(IndexPriceSequenceRepository.class);
        LatestIndexPriceCache latestIndexPriceCache = mock(LatestIndexPriceCache.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        MarkPriceService markPriceService = mock(MarkPriceService.class);
        Instant now = Instant.now();

        when(configService.symbols()).thenReturn(List.of(instrumentId));
        when(sequenceRepository.next("price-index", "45")).thenReturn(77L);
        for (IndexPriceProperties.SourceConfig source : instrumentId.getSources()) {
            when(latestQuoteStore.latest("45", source)).thenReturn(Optional.empty());
            when(spotPriceClient.fetch(source)).thenReturn(CompletableFuture.completedFuture(
                    quote(source.getName(), source.getSourceSymbol(), now)));
        }

        IndexPriceService service = new IndexPriceService(properties, configService, spotPriceClient,
                latestQuoteStore, new IndexPriceCalculator(properties), leaseRepository, sequenceRepository,
                latestIndexPriceCache,
                kafkaTemplate, markPriceService);

        service.pollAndPublish();

        verify(kafkaTemplate).send(eq("surprising.option.price.events.v1"),
                eq("45"), any(PricePublishedEvent.class));
        verify(markPriceService).acceptIndexPrice(any(IndexPriceEvent.class));
    }

    @Test
    void doesNotFallbackToRestWhenAConfiguredPublicWebSocketQuoteIsMissing() {
        IndexPriceProperties properties = properties();
        IndexPriceProperties.SymbolConfig instrumentId = instrumentId("1");
        instrumentId.getSources().forEach(source -> source.setWebsocketEnabled(true));
        IndexInstrumentConfigService configService = mock(IndexInstrumentConfigService.class);
        ExternalSpotPriceClient spotPriceClient = mock(ExternalSpotPriceClient.class);
        LatestSourceQuoteStore latestQuoteStore = mock(LatestSourceQuoteStore.class);
        IndexPriceLeaseRepository leaseRepository = mock(IndexPriceLeaseRepository.class);
        IndexPriceSequenceRepository sequenceRepository = mock(IndexPriceSequenceRepository.class);
        LatestIndexPriceCache latestIndexPriceCache = mock(LatestIndexPriceCache.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        MarkPriceService markPriceService = mock(MarkPriceService.class);

        when(configService.symbols()).thenReturn(List.of(instrumentId));
        when(sequenceRepository.next("price-index", "1")).thenReturn(1L);
        for (IndexPriceProperties.SourceConfig source : instrumentId.getSources()) {
            when(latestQuoteStore.latest("1", source)).thenReturn(Optional.empty());
        }

        new IndexPriceService(properties, configService, spotPriceClient, latestQuoteStore,
                new IndexPriceCalculator(properties), leaseRepository, sequenceRepository, latestIndexPriceCache,
                kafkaTemplate, markPriceService).pollAndPublish();

        verifyNoInteractions(spotPriceClient);
    }

    @Test
    void onlyUsesRestFallbackWhenItIsExplicitlyEnabled() {
        IndexPriceProperties properties = properties();
        properties.getWebSocket().setRestFallbackEnabled(true);
        IndexPriceProperties.SymbolConfig instrumentId = instrumentId("1");
        instrumentId.getSources().forEach(source -> source.setWebsocketEnabled(true));
        IndexInstrumentConfigService configService = mock(IndexInstrumentConfigService.class);
        ExternalSpotPriceClient spotPriceClient = mock(ExternalSpotPriceClient.class);
        LatestSourceQuoteStore latestQuoteStore = mock(LatestSourceQuoteStore.class);
        IndexPriceLeaseRepository leaseRepository = mock(IndexPriceLeaseRepository.class);
        IndexPriceSequenceRepository sequenceRepository = mock(IndexPriceSequenceRepository.class);
        LatestIndexPriceCache latestIndexPriceCache = mock(LatestIndexPriceCache.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        MarkPriceService markPriceService = mock(MarkPriceService.class);
        Instant now = Instant.now();

        when(configService.symbols()).thenReturn(List.of(instrumentId));
        when(sequenceRepository.next("price-index", "1")).thenReturn(1L);
        for (IndexPriceProperties.SourceConfig source : instrumentId.getSources()) {
            when(latestQuoteStore.latest("1", source)).thenReturn(Optional.empty());
            when(spotPriceClient.fetch(source)).thenReturn(CompletableFuture.completedFuture(
                    quote(source.getName(), source.getSourceSymbol(), now)));
        }

        new IndexPriceService(properties, configService, spotPriceClient, latestQuoteStore,
                new IndexPriceCalculator(properties), leaseRepository, sequenceRepository, latestIndexPriceCache,
                kafkaTemplate, markPriceService).pollAndPublish();

        verify(spotPriceClient).fetch(instrumentId.getSources().getFirst());
        ArgumentCaptor<IndexPriceEvent> eventCaptor = ArgumentCaptor.forClass(IndexPriceEvent.class);
        verify(latestIndexPriceCache).update(eventCaptor.capture());
        assertThat(eventCaptor.getValue().components())
                .allMatch(component -> component.transport() == QuoteTransport.REST);
    }

    private IndexPriceProperties properties() {
        IndexPriceProperties properties = new IndexPriceProperties();
        properties.getCoordination().setEnabled(false);
        properties.getCalculation().setMinValidSources(3);
        return properties;
    }

    private IndexPriceProperties.SymbolConfig instrumentId(String instrumentId) {
        IndexPriceProperties.SymbolConfig config = new IndexPriceProperties.SymbolConfig();
        config.setInstrumentId(instrumentId);
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
                SourceStatus.HEALTHY, null, now, now, 1L, QuoteTransport.REST);
    }
}
