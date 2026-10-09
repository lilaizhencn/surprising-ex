package com.surprising.candlestick.provider.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.surprising.candlestick.api.model.*;
import com.surprising.candlestick.provider.aggregation.*;
import com.surprising.candlestick.provider.repository.CandleQueryRepository;
import com.surprising.candlestick.provider.service.*;
import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.kafka.support.serializer.JacksonJsonSerde;

class CandlestickLiveRollupTopologyTest {
    private static final Instant START = Instant.parse("2026-08-25T10:00:00Z");

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void allPeriodsReplaceLiveMinuteWithoutSqlAndRemainIsolated(ProductLine product) {
        try (var f = new Fixture(product)) {
            var first = minute(START, 1, CandleStatus.PARTIAL);
            f.input.pipeInput("1", first);
            assertThat(f.output.readValuesToList()).hasSize(11).allSatisfy(e -> {
                assertThat(e.baseVolume()).isEqualByComparingTo("0.01");
                assertThat(e.status()).isEqualTo(CandleStatus.PARTIAL);
            });
            var next = minute(START, 3, CandleStatus.PARTIAL);
            f.input.pipeInput("1", next);
            assertThat(f.output.readValuesToList()).hasSize(11).allSatisfy(e -> {
                assertThat(e.baseVolume()).isEqualByComparingTo("0.03");
                assertThat(e.closePrice()).isEqualByComparingTo("103");
                assertThat(e.tradeCount()).isEqualTo(3);
                assertThat(f.hot.latest("1", e.period()).orElseThrow().baseVolume()).isEqualByComparingTo("0.03");
            });
            f.input.pipeInput("1", next);
            f.input.pipeInput("1", first);
            assertThat(f.output.isEmpty()).isTrue();
            verifyNoInteractions(f.minutes);
            var store = f.driver.<String, CandleRollupAccumulator>getKeyValueStore(CandleStores.ROLLUP_STORE);
            try (var it = store.all()) {
                while (it.hasNext()) assertThat(it.next().key).startsWith(product.topicSegment()+"|");
            }
        }
    }

    @Test
    void minuteRolloverBeforeCloseNotificationDoesNotLoseOrDoubleCountVolume() {
        try (var f = new Fixture(ProductLine.LINEAR_PERPETUAL)) {
            f.input.pipeInput("1", minute(START, 3, CandleStatus.PARTIAL));
            f.output.readValuesToList();
            f.input.pipeInput("1", minute(START.plusSeconds(60), 2, CandleStatus.PARTIAL));
            assertThat(f.output.readValuesToList()).hasSize(11)
                    .allSatisfy(e -> assertThat(e.baseVolume()).isEqualByComparingTo("0.05"));
            f.input.pipeInput("1", minute(START, 3, CandleStatus.CLOSED));
            assertThat(f.output.isEmpty()).isTrue();
            f.input.pipeInput("1", minute(START.plusSeconds(60), 2, CandleStatus.CLOSED));
            assertThat(f.output.readValuesToList()).hasSize(11)
                    .allSatisfy(e -> assertThat(e.baseVolume()).isEqualByComparingTo("0.05"));
            f.input.pipeInput("1", minute(START.plusSeconds(60), 2, CandleStatus.CLOSED));
            assertThat(f.output.isEmpty()).isTrue();
            verifyNoInteractions(f.minutes);
        }
    }

    @Test
    void durableRevisionPreservesTheCurrentLiveTailAndRestartSerialization() {
        try (var f = new Fixture(ProductLine.LINEAR_PERPETUAL)) {
            f.input.pipeInput("1", minute(START, 3, CandleStatus.PARTIAL));
            f.input.pipeInput("1", minute(START.plusSeconds(60), 2, CandleStatus.PARTIAL));
            f.output.readValuesToList();
            var revised = minute(START, 4, CandleStatus.CLOSED);
            when(f.minutes.findRange(eq("1"),eq("1m"),any(),any(),anyInt()))
                    .thenReturn(List.of(response(revised)));
            f.input.pipeInput("1", revised);
            assertThat(f.output.readValuesToList()).hasSize(11)
                    .allSatisfy(e -> assertThat(e.baseVolume()).isEqualByComparingTo("0.06"));
            var store = f.driver.<String, CandleRollupAccumulator>getKeyValueStore(CandleStores.ROLLUP_STORE);
            var serde = serde(CandleRollupAccumulator.class);
            try (var it = store.all()) {
                while (it.hasNext()) {
                    var entry=it.next();
                    var restored=serde.deserializer().deserialize("restore",serde.serializer().serialize("restore",entry.value));
                    assertThat(restored.getActiveMinute()).isNotNull();
                    assertThat(restored.event(START.plusSeconds(90)).baseVolume()).isEqualByComparingTo("0.06");
                    store.put(entry.key,restored);
                }
            }
            f.input.pipeInput("1", minute(START.plusSeconds(60), 5, CandleStatus.PARTIAL));
            assertThat(f.output.readValuesToList()).hasSize(11)
                    .allSatisfy(e -> assertThat(e.baseVolume()).isEqualByComparingTo("0.09"));
            f.input.pipeInput("1", revised);
            assertThat(f.output.isEmpty()).isTrue();
            verify(f.minutes,times(11)).findRange(eq("1"),eq("1m"),any(),any(),anyInt());
        }
    }

    @Test
    void higherPeriodRolloverClosesThePreviousLiveTailOnce() {
        try (var f = new Fixture(ProductLine.LINEAR_PERPETUAL)) {
            f.input.pipeInput("1", minute(START.plusSeconds(240), 3, CandleStatus.PARTIAL));
            f.output.readValuesToList();
            f.input.pipeInput("1", minute(START.plusSeconds(300), 2, CandleStatus.PARTIAL));
            var events=f.output.readValuesToList();
            assertThat(events.stream().filter(e -> e.period().equals("5m")).toList())
                    .hasSize(2).satisfies(rows -> {
                        assertThat(rows.getFirst().status()).isEqualTo(CandleStatus.CLOSED);
                        assertThat(rows.getFirst().baseVolume()).isEqualByComparingTo("0.03");
                        assertThat(rows.getLast().status()).isEqualTo(CandleStatus.PARTIAL);
                        assertThat(rows.getLast().baseVolume()).isEqualByComparingTo("0.02");
                    });
        }
    }

    private static CandleUpdatedEvent minute(Instant open,long count,CandleStatus status) {
        var price=BigDecimal.valueOf(100+count);var volume=BigDecimal.valueOf(count,2);
        return new CandleUpdatedEvent("1","1m",open,open.plusSeconds(60),BigDecimal.valueOf(100),price,
                BigDecimal.valueOf(99),price,volume,price.multiply(volume),count,"first", "last-"+count,
                open.toEpochMilli()+1,open.toEpochMilli()+count,status,open.plusSeconds(count),open.plusSeconds(count),0,count);
    }

    private static CandleResponse response(CandleUpdatedEvent e) {
        return new CandleResponse(e.instrumentId(),e.period(),e.openTime(),e.closeTime(),e.openPrice(),e.highPrice(),
                e.lowPrice(),e.closePrice(),e.baseVolume(),e.quoteVolume(),e.tradeCount(),e.firstTradeId(),e.lastTradeId(),
                e.firstSequence(),e.lastSequence(),e.status(),e.eventTime());
    }

    private static <T> JacksonJsonSerde<T> serde(Class<T> type) {
        var serde=new JacksonJsonSerde<>(type);serde.ignoreTypeHeaders();serde.noTypeInfo();return serde;
    }

    private static class Fixture implements AutoCloseable {
        final CandleQueryRepository minutes=mock(CandleQueryRepository.class);
        final CandleHotCache hot=new CandleHotCache();
        final TopologyTestDriver driver;
        final TestInputTopic<String,CandleUpdatedEvent> input;
        final TestOutputTopic<String,CandleUpdatedEvent> output;
        Fixture(ProductLine product) {
            var p=new CandlestickProperties();p.getKafka().setProductLine(product);
            p.setPeriods(Arrays.stream(CandlePeriod.values()).map(CandlePeriod::code).toList());
            var builder=new StreamsBuilder();new CandlestickStreamConfiguration().candlestickTopology(builder,p,
                    mock(CandleSink.class),mock(SymbolRegistryService.class),mock(PublicTradeEventMapper.class),hot,minutes);
            var config=new Properties();config.put(StreamsConfig.APPLICATION_ID_CONFIG,"live-rollup-"+product);
            config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG,"unused:9092");
            driver=new TopologyTestDriver(builder.build(),config,START.plusSeconds(1));
            var serde=serde(CandleUpdatedEvent.class);
            input=driver.createInputTopic(p.getKafka().getCandleTopic(),Serdes.String().serializer(),serde.serializer());
            output=driver.createOutputTopic(p.getKafka().getCandleTopic(),Serdes.String().deserializer(),serde.deserializer());
        }
        public void close() { driver.close(); }
    }
}
