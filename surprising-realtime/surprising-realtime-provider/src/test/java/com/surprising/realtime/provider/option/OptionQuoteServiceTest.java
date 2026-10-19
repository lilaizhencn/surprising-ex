package com.surprising.realtime.provider.option;

import static org.assertj.core.api.Assertions.*;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.price.api.model.*;
import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class OptionQuoteServiceTest {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final InstrumentSnapshotCache instruments = new InstrumentSnapshotCache();
    private final MutableClock clock = new MutableClock();
    private final OptionQuoteService service = new OptionQuoteService(instruments, mapper, clock);

    @BeforeEach void initialize() { instrument("TRADING", 7, NOW.plusSeconds(365 * 86400)); }

    private void instrument(String status, long version, Instant expiry) {
        var fields = new HashMap<String, Object>();
        fields.putAll(Map.of("instrumentId", 74, "changeId", version, "lastChangeId", version,
                "instrumentType", "OPTION", "contractType", "VANILLA_OPTION", "symbol", "BTC-CALL-100"));
        fields.putAll(Map.of("baseAssetId", 3, "quoteAssetId", 1, "settleAssetId", 1,
                "baseAsset", "BTC", "quoteAsset", "USDT", "settleAsset", "USDT",
                "priceTickUnits", 1, "notionalMultiplierUnits", 1));
        fields.putAll(Map.of("status", status, "expiryTime", expiry, "strikePriceUnits", 10_000_000_000L,
                "optionType", "CALL", "optionExerciseStyle", "EUROPEAN", "settlementMethod", "CASH",
                "underlyingInstrumentId", "604", "underlyingProductLine", "LINEAR_PERPETUAL"));
        for (var component : InstrumentResponse.class.getRecordComponents()) {
            if (component.getType() == boolean.class) fields.putIfAbsent(component.getName(), false);
            else if (component.getType().isPrimitive()) fields.putIfAbsent(component.getName(), 0);
        }
        instruments.replace(ProductLine.OPTION, List.of(mapper.convertValue(fields, InstrumentResponse.class)), Map.of("USDT", 100_000_000L));
    }

    private MarkPricePublishedEvent event(long sequence, long version, PriceStatus status, Instant time, String premium) {
        var price = new BigDecimal(premium);
        var index = new IndexPriceEvent("74", new BigDecimal("100"), sequence, status, 1, 1, BigDecimal.ONE,
                time, List.of(), new OptionPriceReference(price, new BigDecimal("100"), NOW.plusSeconds(365 * 86400)));
        var mark = new MarkPriceEvent(ProductLine.OPTION, "74", version,
                price.multiply(new BigDecimal("100000000")).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact(),
                price.multiply(new BigDecimal("100000000")).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact(), price, new BigDecimal("100"), new BigDecimal("100"), null, null, null, null, null,
                BigDecimal.ZERO, null, 0, BigDecimal.ZERO, 0, price, price, sequence, status, time, time);
        return new MarkPricePublishedEvent(mark, index, null, null, null, BigDecimal.ZERO, 0, time);
    }
    private void accept(MarkPricePublishedEvent event) throws Exception {
        service.onPrice(new ConsumerRecord<>(service.topic(), 0, 1, "74", mapper.writeValueAsString(PricePublishedEvent.mark(event))));
    }
    private void unavailable() { assertThatThrownBy(() -> service.quote("74")).isInstanceOfSatisfying(ResponseStatusException.class,
            failure -> assertThat(failure.getStatusCode().value()).isEqualTo(503)); }

    @Test void servesCoherentQuoteWithExplicitModelUnitsAndShortValidity() throws Exception {
        accept(event(1, 7, PriceStatus.HEALTHY, NOW, "7.965567455405796"));
        var quote = service.quote("74");
        assertThat(quote.greeks().impliedVolatility()).isCloseTo(.2, within(1e-8));
        assertThat(quote.underlyingIndexPrice()).isEqualByComparingTo("100");
        assertThat(quote.sameExpiryForwardPrice()).isEqualByComparingTo("100");
        assertThat(quote.validUntil()).isEqualTo(NOW.plusSeconds(5));
        assertThat(quote.discountFactor()).isEqualTo(BigDecimal.ONE);
        assertThat(quote.unit()).isEqualTo("PER_UNDERLYING_UNIT");
        assertThat(mapper.readTree(mapper.writeValueAsString(quote)).path("greeks").path("forwardDelta").asDouble()).isGreaterThan(.5);
    }
    @Test void expiresWithoutNewEventsAndPrunesWithoutLosingFreshReplacement() throws Exception {
        accept(event(1, 7, PriceStatus.HEALTHY, NOW, "7.965567455405796"));
        clock.time = NOW.plusSeconds(5); unavailable(); service.prune(); unavailable();
        accept(event(2, 7, PriceStatus.HEALTHY, clock.time, "7.965567455405796"));
        assertThat(service.quote("74").sourcePriceSequence()).isEqualTo("2");
    }
    @Test void newerInvalidStatusDisablesOldQuoteButOutOfOrderCannotRegressIt() throws Exception {
        accept(event(2, 7, PriceStatus.HEALTHY, NOW, "7.965567455405796"));
        accept(event(1, 7, PriceStatus.STALE, NOW, "7.965567455405796"));
        assertThat(service.quote("74").sourcePriceSequence()).isEqualTo("2");
        accept(event(3, 7, PriceStatus.STALE, NOW, "7.965567455405796")); unavailable();
    }
    @Test void metadataVersionLifecycleAndExpiryMismatchCannotReuseAQuote() throws Exception {
        accept(event(1, 7, PriceStatus.HEALTHY, NOW, "7.965567455405796"));
        instrument("TRADING", 8, NOW.plusSeconds(365 * 86400)); unavailable();
        accept(event(2, 8, PriceStatus.HEALTHY, NOW, "7.965567455405796"));
        assertThat(service.quote("74").greeks()).isNotNull();
        instrument("HALT", 9, NOW.plusSeconds(365 * 86400)); unavailable();
        instrument("TRADING", 10, NOW.plusSeconds(365 * 86400 + 1));
        accept(event(3, 10, PriceStatus.HEALTHY, NOW, "7.965567455405796")); unavailable();
    }
    @Test void newerPrecisionVersionBlocksOldPriceBeforeMetadataArrives() throws Exception {
        accept(event(1, 7, PriceStatus.HEALTHY, NOW, "8"));
        accept(event(2, 8, PriceStatus.HEALTHY, NOW, "8")); unavailable();
        service.prune(); unavailable();
        accept(event(3, 7, PriceStatus.HEALTHY, NOW, "8")); unavailable();
        instrument("TRADING", 8, NOW.plusSeconds(365 * 86400));
        assertThat(service.quote("74").sourcePriceSequence()).isEqualTo("2");
    }

    @Test void lifecycleRevisionIsNotTheFrozenPriceEncodingVersion() throws Exception {
        var fields = mapper.convertValue(instruments.current(ProductLine.OPTION, 74).orElseThrow(), Map.class);
        var updated = new HashMap<String,Object>(fields);
        updated.put("lastChangeId", 99);
        instruments.replace(ProductLine.OPTION, List.of(mapper.convertValue(updated, InstrumentResponse.class)), Map.of("USDT", 100_000_000L));
        accept(event(1, 7, PriceStatus.HEALTHY, NOW, "8"));
        assertThat(service.quote("74").greeks()).isNotNull();
    }

    @Test void haltedContractRemainsQueryableWhileClosedOrExpiredContractsDoNot() throws Exception {
        instrument("HALT", 8, NOW.plusSeconds(365 * 86400));
        accept(event(1, 8, PriceStatus.HEALTHY, NOW, "8"));
        assertThat(service.quote("74").greeks()).isNotNull();
        instrument("CLOSED", 9, NOW.plusSeconds(365 * 86400)); unavailable();
        instrument("SETTLING", 10, NOW); unavailable();
    }

    @Test void retainsRealPriceWithoutInventingGreeksWhenModelHasNoSolution() throws Exception {
        accept(event(1, 7, PriceStatus.HEALTHY, NOW, "101"));
        assertThat(service.quote("74").premiumPrice()).isEqualByComparingTo("101");
        assertThat(service.quote("74").greeks()).isNull();
    }
    @Test void refusesUnknownInstrumentWrongTopicKeyProductAndFutureTime() throws Exception {
        assertThatThrownBy(() -> service.quote("bad")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.quote("75")).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode().value()).isEqualTo(404));
        var payload = mapper.writeValueAsString(PricePublishedEvent.mark(event(1, 7, PriceStatus.HEALTHY, NOW, "8")));
        assertThatThrownBy(() -> service.onPrice(new ConsumerRecord<>("wrong", 0, 1, "74", payload))).hasMessageContaining("topic");
        assertThatThrownBy(() -> service.onPrice(new ConsumerRecord<>(service.topic(), 0, 1, "75", payload))).hasMessageContaining("key");
        assertThatThrownBy(() -> service.onPrice(new ConsumerRecord<>(service.topic(), 0, 1, "74",
                payload.replace("\"productLine\":\"OPTION\"", "\"productLine\":\"SPOT\"")))).hasMessageContaining("identity");
        accept(event(2, 7, PriceStatus.HEALTHY, NOW.plusMillis(1), "8")); unavailable();
        assertThatThrownBy(() -> new OptionQuoteController(service).quote("74", "SPOT")).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode().value()).isEqualTo(404));
    }
    private static class MutableClock extends Clock {
        Instant time = NOW;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return time; }
    }
}
