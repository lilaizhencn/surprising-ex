package com.surprising.price.index.service;

import com.surprising.price.api.model.IndexComponentSnapshot;
import com.surprising.price.api.model.IndexComponentEvent;
import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.index.client.ExternalSpotPriceClient;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.model.SourceQuote;
import com.surprising.price.index.repository.IndexPriceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class IndexPriceService {

    private static final Logger log = LoggerFactory.getLogger(IndexPriceService.class);
    private static final String SEQUENCE_MODULE = "price-index";

    private final IndexPriceProperties properties;
    private final IndexInstrumentConfigService indexInstrumentConfigService;
    private final ExternalSpotPriceClient externalSpotPriceClient;
    private final LatestSourceQuoteStore latestSourceQuoteStore;
    private final IndexPriceCalculator indexPriceCalculator;
    private final IndexPriceRepository indexPriceRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String nodeId;

    public IndexPriceService(IndexPriceProperties properties,
                             IndexInstrumentConfigService indexInstrumentConfigService,
                             ExternalSpotPriceClient externalSpotPriceClient,
                             LatestSourceQuoteStore latestSourceQuoteStore,
                             IndexPriceCalculator indexPriceCalculator,
                             IndexPriceRepository indexPriceRepository,
                             @Qualifier("indexKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate) {
        this.properties = properties;
        this.indexInstrumentConfigService = indexInstrumentConfigService;
        this.externalSpotPriceClient = externalSpotPriceClient;
        this.latestSourceQuoteStore = latestSourceQuoteStore;
        this.indexPriceCalculator = indexPriceCalculator;
        this.indexPriceRepository = indexPriceRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.nodeId = resolveNodeId(properties.getCoordination().getNodeId());
    }

    @Scheduled(fixedDelayString = "${surprising.price.index.calculation.poll-delay-ms:1000}")
    public void pollAndPublish() {
        for (IndexPriceProperties.SymbolConfig symbolConfig : indexInstrumentConfigService.symbols()) {
            try {
                publishSymbol(symbolConfig);
            } catch (Exception ex) {
                log.error("Failed to publish index price for symbol={}", symbolConfig.getSymbol(), ex);
            }
        }
    }

    private void publishSymbol(IndexPriceProperties.SymbolConfig symbolConfig) {
        String symbol = normalizeSymbol(symbolConfig.getSymbol());
        if (!ownsSymbol(symbol)) {
            return;
        }

        Instant now = Instant.now();
        List<CompletableFuture<SourceQuote>> futures = symbolConfig.getSources().stream()
                .map(source -> websocketQuoteOrRest(symbol, source, now))
                .toList();
        List<SourceQuote> quotes = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        long sequence = indexPriceRepository.nextSequence(SEQUENCE_MODULE, symbol);
        IndexPriceEvent event = indexPriceCalculator.calculate(symbol, sequence, symbolConfig.getMinValidSources(),
                quotes, Instant.now());
        indexPriceRepository.save(event);

        if (event.status() != PriceStatus.INSUFFICIENT_SOURCES && event.indexPrice() != null) {
            kafkaTemplate.send(properties.getKafka().getIndexPriceTopic(), symbol, event);
        }
        for (IndexComponentSnapshot component : event.components()) {
            kafkaTemplate.send(properties.getKafka().getIndexComponentsTopic(), symbol,
                    new IndexComponentEvent(symbol, event.sequence(), event.eventTime(), component));
        }
    }

    private boolean ownsSymbol(String symbol) {
        if (!properties.getCoordination().isEnabled()) {
            return true;
        }
        return indexPriceRepository.acquireLease(SEQUENCE_MODULE, symbol, nodeId,
                properties.getCoordination().getLeaseDuration());
    }

    private CompletableFuture<SourceQuote> websocketQuoteOrRest(String symbol, IndexPriceProperties.SourceConfig source,
                                                                Instant now) {
        if (properties.getWebSocket().isEnabled() && source.isWebsocketEnabled()) {
            return latestSourceQuoteStore.latest(symbol, source)
                    .filter(quote -> freshEnough(quote, now))
                    .map(CompletableFuture::completedFuture)
                    .orElseGet(() -> externalSpotPriceClient.fetch(source));
        }
        return externalSpotPriceClient.fetch(source);
    }

    private boolean freshEnough(SourceQuote quote, Instant now) {
        if (!quote.healthy() || quote.receivedAt() == null) {
            return false;
        }
        Duration age = Duration.between(quote.receivedAt(), now);
        return !age.isNegative() && age.compareTo(properties.getCalculation().getMaxSourceAge()) <= 0;
    }

    private String resolveNodeId(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return "index-" + UUID.randomUUID();
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || !symbol.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("Invalid symbol: " + symbol);
        }
        return symbol;
    }
}
