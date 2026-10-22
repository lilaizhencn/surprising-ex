package com.surprising.price.mark.service;

import com.surprising.realtime.api.RealtimeJsonPublisher;
import org.springframework.beans.factory.ObjectProvider;
import lombok.extern.slf4j.Slf4j;
import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPricePublishedEvent;
import com.surprising.price.api.model.PerpBookTickerEvent;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.price.api.model.PerpTradeEvent;
import com.surprising.price.api.model.PricePublishedEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.model.BasisWindow;
import com.surprising.price.mark.model.MarkPriceEncoding;
import com.surprising.price.mark.model.MarketInputs;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MarkPriceService {
    private final ObjectProvider<RealtimeJsonPublisher> realtime;

    private static final String SEQUENCE_MODULE = "price-mark";

    private final MarkPriceProperties properties;
    private final MarkPriceCalculator markPriceCalculator;
    private final MarkPriceCoordinationService coordinationService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final LatestMarkPriceCache latestMarkPriceCache;
    private final ObjectProvider<MarkPriceCorePublisher> corePublisher;
    private final String nodeId;

    private final ConcurrentHashMap<String, IndexPriceEvent> indexPrices = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PerpBookTickerEvent> bookTickers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PerpTradeEvent> trades = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PerpFundingRateEvent> fundingRates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BasisWindow> basisWindows = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong marketGeneration = new java.util.concurrent.atomic.AtomicLong();
    private volatile long restoredMarketGeneration;

    @org.springframework.beans.factory.annotation.Autowired
    public MarkPriceService(MarkPriceProperties properties,
                            MarkPriceCalculator markPriceCalculator,
                            MarkPriceCoordinationService coordinationService,
                            @Qualifier("markKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
                            LatestMarkPriceCache latestMarkPriceCache,
                            ObjectProvider<RealtimeJsonPublisher> realtime,
                            ObjectProvider<MarkPriceCorePublisher> corePublisher) {
        this.realtime = realtime;
        this.corePublisher = corePublisher;
        this.properties = properties;
        this.markPriceCalculator = markPriceCalculator;
        this.coordinationService = coordinationService;
        this.kafkaTemplate = kafkaTemplate;
        this.latestMarkPriceCache = latestMarkPriceCache;
        this.nodeId = resolveNodeId(properties.getCoordination().getNodeId());
    }

    public void acceptIndexPrice(IndexPriceEvent event) {
        if (event == null || event.instrumentId() == null || event.instrumentId().isBlank()) {
            throw new IllegalArgumentException("index price event is required");
        }
        indexPrices.compute(event.instrumentId(),
                (ignored, current) -> current == null || newer(event, current) ? event : current);
    }

    Set<String> indexSymbols() {
        return Set.copyOf(indexPrices.keySet());
    }

    public void acceptBookTicker(PerpBookTickerEvent event) {
        bookTickers.compute(event.instrumentId(), (instrumentId, current) -> current == null
                || event.sequence() > current.sequence()
                || event.sequence() == current.sequence() && !event.eventTime().isBefore(current.eventTime())
                ? event : current);
    }

    public void acceptTrade(PerpTradeEvent event) {
        if (event == null || event.instrumentId() == null || event.instrumentId().isBlank()) {
            throw new IllegalArgumentException("trade event is required");
        }
        trades.compute(event.instrumentId(),(instrumentId,current)->current==null || event.sequence()>current.sequence()
                || event.sequence()==current.sequence() && !event.tradeTime().isBefore(current.tradeTime())?event:current);
    }

    public void acceptFundingRate(PerpFundingRateEvent event) {
        fundingRates.put(event.instrumentId(), event);
        realtime.ifAvailable(publisher -> publisher.publish(properties.getKafka().getProductLine(),
                com.surprising.aeron.protocol.RealtimeFrame.Kind.FUNDING,event.instrumentId(),event.instrumentId(),event.sequence(),event.eventTime(),event));
    }

    /** Receiver callback only marks invalidity; it does not mutate owner-owned windows or do I/O. */
    void marketContinuityLost() { marketGeneration.incrementAndGet(); }

    /** Lease owner restores from new input after dropping the untrustworthy pre-gap market state. */
    void resetMarketInputs() {
        long generation = marketGeneration.get();
        bookTickers.clear();
        trades.clear();
        basisWindows.clear();
        restoredMarketGeneration = generation;
    }

    public void publishMarkPrices() {
        Instant now = Instant.now();
        for (String instrumentId : symbols()) {
            try {
                publishSymbol(instrumentId, now);
            } catch (Exception ex) {
                log.error("Failed to publish mark price for instrumentId={}", instrumentId, ex);
            }
        }
    }

    private boolean publishSymbol(String instrumentId, Instant now) {
        IndexPriceEvent index = indexPrices.get(instrumentId);
        if (!fresh(index, now)) {
            return false;
        }
        MarkPriceEncoding encoding;
        try {
            encoding = coordinationService.currentEncoding(instrumentId);
        } catch (IllegalStateException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("mark price encoding not found for ")) {
                return false;
            }
            throw ex;
        }
        long generation = marketGeneration.get();
        boolean marketAvailable = generation == restoredMarketGeneration;
        PerpBookTickerEvent book = marketAvailable ? bookTickers.get(instrumentId) : null;
        if (!fresh(book, now)) {
            basisWindows.remove(instrumentId);
            book = null;
        }
        PerpTradeEvent trade = marketAvailable ? trades.get(instrumentId) : null;
        if (!usableLastTrade(trade, now)) trade = null;
        if (!ownsSymbol(instrumentId)) {
            return false;
        }

        BigDecimal basisAverage = null;
        if (book != null) {
            BasisWindow window = basisWindows.computeIfAbsent(instrumentId, ignored -> new BasisWindow());
            window.add(now, markPriceCalculator.basis(index, book), properties.getCalculation().getBasisWindow());
            basisAverage = window.average(now, properties.getCalculation().getBasisWindow(),
                    properties.getCalculation().getScale());
        }

        long sequence = coordinationService.nextSequence(SEQUENCE_MODULE, instrumentId);
        MarkPriceEvent event = markPriceCalculator.calculate(instrumentId, sequence, index, book, trade,
                fundingRates.get(instrumentId), basisAverage, encoding, now);

        if (generation != marketGeneration.get()) return false;
        latestMarkPriceCache.update(event);
        if (log.isDebugEnabled()) log.debug("mark.calculated instrumentId={} indexSequence={} tradeSequence={}", instrumentId,
                index.sequence(), trade == null ? null : trade.sequence());
        corePublisher.ifAvailable(publisher -> publisher.publish(event));
        MarkPricePublishedEvent publication = new MarkPricePublishedEvent(event, index, book, trade,
                fundingRates.get(instrumentId), basisAverage,
                properties.getCalculation().getBasisWindow().toSeconds(), now);
        kafkaTemplate.send(properties.priceEventsTopic(), instrumentId, PricePublishedEvent.mark(publication));
        realtime.ifAvailable(publisher -> publisher.publish(properties.getKafka().getProductLine(),
                com.surprising.aeron.protocol.RealtimeFrame.Kind.MARK, instrumentId, instrumentId,
                event.sequence(), event.eventTime(), event));

        return true;
    }

    /** 查询诊断读取既有权威输入，不维护另一份状态。 */
    public MarketInputs inputs(String instrumentId) {
        Instant now = Instant.now();
        var index = indexPrices.get(instrumentId);
        boolean marketAvailable = marketGeneration.get() == restoredMarketGeneration;
        var book = marketAvailable ? bookTickers.get(instrumentId) : null;
        var trade = marketAvailable ? trades.get(instrumentId) : null;
        return new MarketInputs(index, book, trade, fresh(index, now), fresh(book, now), usableLastTrade(trade, now));
    }

    private Set<String> symbols() {
        Set<String> symbols = ConcurrentHashMap.newKeySet();
        symbols.addAll(indexPrices.keySet());
        symbols.addAll(bookTickers.keySet());
        symbols.addAll(trades.keySet());
        return symbols;
    }

    private boolean ownsSymbol(String instrumentId) {
        if (!properties.getCoordination().isEnabled()) {
            return true;
        }
        return coordinationService.acquireLease(SEQUENCE_MODULE, instrumentId, nodeId,
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
        return event != null && event.bestBidPrice() != null && event.bestAskPrice() != null
                && event.bestBidPrice().signum() > 0 && event.bestAskPrice().signum() > 0
                && event.bestBidPrice().compareTo(event.bestAskPrice()) < 0 && fresh(event.eventTime(), now);
    }

    private boolean usableLastTrade(PerpTradeEvent event, Instant now) {
        // 最后一笔真实成交是事件状态，不是心跳；无新成交不能伪造一笔，也不能阻止市场恢复。
        return event != null && event.price() != null && event.price().signum() > 0
                && event.tradeTime() != null && !event.tradeTime().isAfter(now.plusSeconds(1));
    }

    private boolean fresh(Instant eventTime, Instant now) {
        return eventTime != null && !eventTime.isAfter(now.plusSeconds(1)) && Duration.between(eventTime, now).compareTo(properties.getCalculation().getMaxInputAge()) <= 0;
    }

    private boolean newer(IndexPriceEvent candidate, IndexPriceEvent current) {
        return candidate.sequence() > current.sequence()
                || candidate.sequence() == current.sequence()
                && candidate.eventTime() != null
                && (current.eventTime() == null || candidate.eventTime().isAfter(current.eventTime()));
    }

    private String resolveNodeId(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return "mark-" + UUID.randomUUID();
    }

}
