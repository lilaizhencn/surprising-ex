package com.surprising.realtime.provider.option;

import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.*;
import com.surprising.price.api.model.*;
import com.surprising.product.api.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** realtime 拥有可重建的期权价格读视图；不查询在线 Core、不写账户、不保存派生 Greeks。 */
@Service
@ConditionalOnProperty(name = "surprising.candlestick.kafka.product-line", havingValue = "OPTION")
public final class OptionQuoteService {
    private static final Duration MAX_AGE = Duration.ofSeconds(5);
    private final InstrumentSnapshotCache instruments;
    private final ObjectMapper mapper;
    private final Clock clock;
    // 每合约仅保留一个不可变源事件；Kafka 写、HTTP 读，过期/结束条目由 prune 清除。
    private final ConcurrentHashMap<String, MarkPricePublishedEvent> latest = new ConcurrentHashMap<>();
    private final String group = "surprising-option-reference-" + UUID.randomUUID();

    @Autowired
    public OptionQuoteService(InstrumentSnapshotCache instruments, ObjectMapper mapper, CandlestickProperties properties) {
        this(instruments, mapper, Clock.systemUTC());
        if (properties.getKafka().getProductLine() != ProductLine.OPTION)
            throw new IllegalArgumentException("option market view requires OPTION product");
    }

    OptionQuoteService(InstrumentSnapshotCache instruments, ObjectMapper mapper, Clock clock) {
        this.instruments = instruments;
        this.mapper = mapper;
        this.clock = clock;
    }

    public String topic() { return ProductTopicNames.of(ProductLine.OPTION).priceEventsTopic(); }
    public String groupId() { return group; }

    @KafkaListener(topics = "#{__listener.topic()}", groupId = "#{__listener.groupId()}",
            properties = "auto.offset.reset=latest",
            containerFactory = "candlestickInstrumentSnapshotKafkaListenerContainerFactory")
    public void onPrice(ConsumerRecord<String, String> record) throws Exception {
        if (!topic().equals(record.topic())) throw new IllegalArgumentException("option price topic mismatch");
        var publication = mapper.readValue(record.value(), PricePublishedEvent.class);
        if (!publication.instrumentId().equals(record.key())) throw new IllegalArgumentException("option price key mismatch");
        if (publication.eventType() != PriceEventType.MARK_PRICE) return;
        var mark = publication.markPrice().result();
        if (mark.productLine() != ProductLine.OPTION || mark.sequence() <= 0 || mark.instrumentChangeId() <= 0)
            throw new IllegalArgumentException("option price identity mismatch");
        var instrument = instruments.current(ProductLine.OPTION, InstrumentIds.parse(mark.instrumentId())).orElse(null);
        if (!active(instrument, clock.instant())) return;
        latest.compute(mark.instrumentId(), (id, previous) -> previous == null
                || mark.instrumentChangeId() > previous.result().instrumentChangeId()
                || mark.instrumentChangeId() == previous.result().instrumentChangeId() && mark.sequence() > previous.result().sequence()
                ? publication.markPrice() : previous);
    }

    public Quote quote(String instrumentId) {
        int id = InstrumentIds.parse(instrumentId);
        if (!instruments.initialized(ProductLine.OPTION)) throw unavailable();
        var instrument = instruments.current(ProductLine.OPTION, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "option instrument not found"));
        var now = clock.instant();
        if (!active(instrument, now)) throw unavailable();
        var publication = latest.get(instrumentId);
        if (publication == null) throw unavailable();
        var mark = publication.result();
        var index = publication.indexInput();
        var reference = index == null ? null : index.optionReference();
        if (mark.instrumentChangeId() != instrument.changeId() || !usable(mark.status())
                || !fresh(mark.eventTime(), now) || !fresh(mark.publishedAt(), now)
                || mark.publishedAt().isBefore(mark.eventTime())
                || index == null || !instrumentId.equals(index.instrumentId()) || !usable(index.status()) || !positive(index.indexPrice())
                || !fresh(index.eventTime(), now) || reference == null
                || reference.expiryTime().toEpochMilli() != instrument.expiryTime().toEpochMilli()
                || !positive(mark.markPrice()) || !positive(mark.indexPrice()) || !positive(mark.sameExpiryForwardPrice())
                || mark.markPrice().compareTo(reference.premiumPrice()) != 0
                || mark.indexPrice().compareTo(index.indexPrice()) != 0
                || mark.sameExpiryForwardPrice().compareTo(reference.sameExpiryForwardPrice()) != 0)
            throw unavailable();
        Long scale = instruments.scale(ProductLine.OPTION, instrument.quoteAsset()).orElse(null);
        if (scale == null || scale <= 0 || !Long.toString(scale).matches("10*")
                || instrument.strikePriceUnits() == null || instrument.strikePriceUnits() <= 0
                || instrument.settleAssetId() != instrument.quoteAssetId()
                || instrument.optionExerciseStyle() != OptionExerciseStyle.EUROPEAN
                || instrument.settlementMethod() != ContractSettlementMethod.CASH) throw unavailable();
        if (instrument.priceTickUnits() <= 0 || mark.markPriceUnits() <= 0 || mark.markPriceTicks() <= 0) throw unavailable();
        long units;
        long ticks;
        try {
            units = mark.markPrice().multiply(BigDecimal.valueOf(scale)).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
            ticks = Math.floorDiv(Math.addExact(units, instrument.priceTickUnits() / 2), instrument.priceTickUnits());
        } catch (ArithmeticException invalid) { throw unavailable(); }
        if (units != mark.markPriceUnits() || ticks != mark.markPriceTicks()) throw unavailable();
        var strike = BigDecimal.valueOf(instrument.strikePriceUnits()).divide(BigDecimal.valueOf(scale));
        double years = Duration.between(now, instrument.expiryTime()).toMillis() / (365.0 * 86400 * 1000);
        var greeks = Black76Reference.infer(instrument.optionType() == OptionType.CALL,
                mark.sameExpiryForwardPrice().doubleValue(), strike.doubleValue(), mark.markPrice().doubleValue(), years);
        var intrinsic = instrument.optionType() == OptionType.CALL
                ? mark.indexPrice().subtract(strike).max(BigDecimal.ZERO)
                : strike.subtract(mark.indexPrice()).max(BigDecimal.ZERO);
        Instant validUntil = mark.eventTime().plus(MAX_AGE);
        if (index.eventTime().plus(MAX_AGE).isBefore(validUntil)) validUntil = index.eventTime().plus(MAX_AGE);
        if (instrument.expiryTime().isBefore(validUntil)) validUntil = instrument.expiryTime();
        return new Quote("OPTION", instrumentId, instrument.underlyingInstrumentId(), instrument.underlyingProductLine(),
                instrument.optionType(), instrument.expiryTime(), Long.toString(instrument.strikePriceUnits()), strike,
                instrument.baseAsset(), instrument.quoteAsset(), mark.markPrice(), mark.indexPrice(), mark.sameExpiryForwardPrice(),
                intrinsic, mark.eventTime(), validUntil, Long.toString(mark.sequence()),
                "BLACK_76_ZERO_RATE_ACT_365", BigDecimal.ONE, "PER_UNDERLYING_UNIT", greeks);
    }

    @Scheduled(fixedDelay = 5000)
    public void prune() {
        var now = clock.instant();
        latest.forEach((id, value) -> {
            var instrument = instruments.current(ProductLine.OPTION, InstrumentIds.parse(id)).orElse(null);
            if (!active(instrument, now) || !fresh(value.result().eventTime(), now)
                    || instrument.changeId() > value.result().instrumentChangeId()) latest.remove(id, value);
        });
    }

    private static boolean active(InstrumentResponse instrument, Instant now) {
        return instrument != null && instrument.contractType() == ContractType.VANILLA_OPTION
                && instrument.status() != null && java.util.Set.of(InstrumentStatus.PRE_TRADING, InstrumentStatus.TRADING,
                        InstrumentStatus.HALT, InstrumentStatus.SETTLING).contains(instrument.status()) && instrument.expiryTime() != null
                && instrument.expiryTime().isAfter(now) && instrument.optionType() != null;
    }
    private static boolean fresh(Instant time, Instant now) { return time != null && !time.isAfter(now) && time.isAfter(now.minus(MAX_AGE)); }
    private static boolean usable(PriceStatus status) { return status != null && status != PriceStatus.STALE && status != PriceStatus.INSUFFICIENT_SOURCES; }
    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OPTION_REFERENCE_UNAVAILABLE"); }

    public record Quote(String productLine, String instrumentId, String underlyingInstrumentId, ProductLine underlyingProductLine,
                        OptionType optionType, Instant expiryTime, String strikePriceUnits, BigDecimal strikePrice,
                        String baseAsset, String quoteAsset, BigDecimal premiumPrice, BigDecimal underlyingIndexPrice,
                        BigDecimal sameExpiryForwardPrice, BigDecimal intrinsicValue, Instant asOf, Instant validUntil,
                        String sourcePriceSequence, String model, BigDecimal discountFactor, String unit, Black76Reference.Greeks greeks) {}
}
