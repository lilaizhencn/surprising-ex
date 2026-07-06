package com.surprising.price.mark.service;

import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.PerpBookTickerEvent;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.price.api.model.PerpTradeEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.model.BasisWindow;
import com.surprising.price.mark.repository.MarkPriceRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class MarkPriceService {

    private static final Logger log = LoggerFactory.getLogger(MarkPriceService.class);
    private static final String SEQUENCE_MODULE = "price-mark";

    private final ObjectMapper objectMapper;
    private final MarkPriceProperties properties;
    private final MarkPriceCalculator markPriceCalculator;
    private final MarkPriceRepository markPriceRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String nodeId;

    private final ConcurrentHashMap<String, IndexPriceEvent> indexPrices = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PerpBookTickerEvent> bookTickers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PerpTradeEvent> trades = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PerpFundingRateEvent> fundingRates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BasisWindow> basisWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> symbolLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastPublishedAt = new ConcurrentHashMap<>();

    public MarkPriceService(ObjectMapper objectMapper,
                            MarkPriceProperties properties,
                            MarkPriceCalculator markPriceCalculator,
                            MarkPriceRepository markPriceRepository,
                            KafkaTemplate<String, Object> kafkaTemplate) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.markPriceCalculator = markPriceCalculator;
        this.markPriceRepository = markPriceRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.nodeId = resolveNodeId(properties.getCoordination().getNodeId());
    }

    @KafkaListener(topics = "#{__listener.indexPriceTopic()}", groupId = "#{__listener.groupId()}")
    public void onIndexPrice(String payload) {
        parse(payload, IndexPriceEvent.class, "index price", event -> {
            indexPrices.put(event.symbol(), event);
            publishIfReady(event.symbol());
        });
    }

    @KafkaListener(topics = "#{__listener.bookTickerTopic()}", groupId = "#{__listener.groupId()}")
    public void onBookTicker(String payload) {
        parse(payload, PerpBookTickerEvent.class, "book ticker", event -> {
            bookTickers.put(event.symbol(), event);
            publishIfReady(event.symbol());
        });
    }

    @KafkaListener(topics = "#{__listener.tradeTopic()}", groupId = "#{__listener.groupId()}")
    public void onTrade(String payload) {
        parse(payload, PerpTradeEvent.class, "trade", event -> {
            trades.put(event.symbol(), event);
            publishIfReady(event.symbol());
        });
    }

    @KafkaListener(topics = "#{__listener.fundingRateTopic()}",
            groupId = "#{__listener.groupId()}",
            autoStartup = "#{__listener.fundingRateListenerEnabled()}")
    public void onFundingRate(String payload) {
        parse(payload, PerpFundingRateEvent.class, "funding rate", event -> fundingRates.put(event.symbol(), event));
    }

    @Scheduled(fixedDelayString = "${surprising.price.mark.calculation.publish-delay-ms:1000}")
    public void publishMarkPrices() {
        Instant now = Instant.now();
        for (String symbol : symbols()) {
            publishIfReady(symbol, now);
        }
    }

    private void publishIfReady(String symbol) {
        publishIfReady(symbol, Instant.now());
    }

    private void publishIfReady(String symbol, Instant now) {
        Object lock = symbolLocks.computeIfAbsent(symbol, ignored -> new Object());
        synchronized (lock) {
            try {
                Instant last = lastPublishedAt.get(symbol);
                Duration publishDelay = Duration.ofMillis(properties.getCalculation().getPublishDelayMs());
                if (last != null && Duration.between(last, now).compareTo(publishDelay) < 0) {
                    return;
                }
                if (publishSymbol(symbol, now)) {
                    lastPublishedAt.put(symbol, now);
                }
            } catch (Exception ex) {
                log.error("Failed to publish mark price for symbol={}", symbol, ex);
            }
        }
    }

    private boolean publishSymbol(String symbol, Instant now) {
        IndexPriceEvent index = indexPrices.get(symbol);
        if (!fresh(index, now)) {
            return false;
        }
        PerpBookTickerEvent book = bookTickers.get(symbol);
        if (!fresh(book, now)) {
            book = syntheticBookTicker(index, now);
        }
        PerpTradeEvent trade = trades.get(symbol);
        if (!fresh(trade, now)) {
            trade = syntheticTrade(index, now);
        }
        if (!ownsSymbol(symbol)) {
            return false;
        }

        BasisWindow window = basisWindows.computeIfAbsent(symbol, ignored -> new BasisWindow());
        window.add(now, markPriceCalculator.basis(index, book), properties.getCalculation().getBasisWindow());
        BigDecimal basisAverage = window.average(now, properties.getCalculation().getBasisWindow(),
                properties.getCalculation().getScale());

        long sequence = markPriceRepository.nextSequence(SEQUENCE_MODULE, symbol);
        MarkPriceEvent event = markPriceCalculator.calculate(symbol, sequence, index, book, trade,
                fundingRates.get(symbol), basisAverage, now);
        markPriceRepository.save(event);
        kafkaTemplate.send(properties.markPriceTopic(), symbol, event);
        kafkaTemplate.send(properties.markPriceAuditTopic(), symbol, event);
        return true;
    }

    public String indexPriceTopic() {
        return properties.indexPriceTopic();
    }

    public String bookTickerTopic() {
        return properties.bookTickerTopic();
    }

    public String tradeTopic() {
        return properties.tradeTopic();
    }

    public String fundingRateTopic() {
        return properties.fundingRateTopic();
    }

    public boolean fundingRateListenerEnabled() {
        return properties.isFundingRateExpected();
    }

    public String groupId() {
        return properties.getKafka().getGroupId();
    }

    private PerpBookTickerEvent syntheticBookTicker(IndexPriceEvent index, Instant now) {
        return new PerpBookTickerEvent(index.symbol(), index.indexPrice(), index.indexPrice(), index.sequence(), now);
    }

    private PerpTradeEvent syntheticTrade(IndexPriceEvent index, Instant now) {
        return new PerpTradeEvent(index.symbol(), "index-bootstrap-" + index.sequence(), index.sequence(), now,
                index.indexPrice(), BigDecimal.ONE, "BUY");
    }

    private Set<String> symbols() {
        Set<String> symbols = ConcurrentHashMap.newKeySet();
        symbols.addAll(indexPrices.keySet());
        symbols.addAll(bookTickers.keySet());
        symbols.addAll(trades.keySet());
        return symbols;
    }

    private boolean ownsSymbol(String symbol) {
        if (!properties.getCoordination().isEnabled()) {
            return true;
        }
        return markPriceRepository.acquireLease(SEQUENCE_MODULE, symbol, nodeId,
                properties.getCoordination().getLeaseDuration());
    }

    private boolean fresh(IndexPriceEvent event, Instant now) {
        return event != null
                && event.indexPrice() != null
                && usableIndexStatus(event.status())
                && fresh(event.eventTime(), now);
    }

    private boolean usableIndexStatus(PriceStatus status) {
        return status == PriceStatus.HEALTHY || status == PriceStatus.DEGRADED;
    }

    private boolean fresh(PerpBookTickerEvent event, Instant now) {
        return event != null && event.bestBidPrice().compareTo(event.bestAskPrice()) <= 0 && fresh(event.eventTime(), now);
    }

    private boolean fresh(PerpTradeEvent event, Instant now) {
        return event != null && fresh(event.tradeTime(), now);
    }

    private boolean fresh(Instant eventTime, Instant now) {
        return eventTime != null && Duration.between(eventTime, now).compareTo(properties.getCalculation().getMaxInputAge()) <= 0;
    }

    private String resolveNodeId(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return "mark-" + UUID.randomUUID();
    }

    private <T> void parse(String payload, Class<T> type, String name, java.util.function.Consumer<T> consumer) {
        try {
            consumer.accept(objectMapper.readValue(payload, type));
        } catch (Exception ex) {
            log.warn("Dropped invalid {} payload: {}", name, ex.getMessage());
        }
    }
}
