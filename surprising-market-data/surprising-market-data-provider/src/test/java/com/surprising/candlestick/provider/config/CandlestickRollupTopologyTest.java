package com.surprising.candlestick.provider.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.candlestick.api.model.CandleStatus;
import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import com.surprising.candlestick.api.model.TradeEvent;
import com.surprising.candlestick.api.model.TradeSide;
import com.surprising.candlestick.provider.aggregation.CandleSink;
import com.surprising.candlestick.provider.service.CandleHotCache;
import com.surprising.candlestick.provider.service.PublicTradeEventMapper;
import com.surprising.candlestick.provider.service.SymbolRegistryService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import com.surprising.trading.api.model.PublicTradeEvent;
import com.surprising.trading.api.model.OrderSide;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JacksonJsonSerde;

class CandlestickRollupTopologyTest {
    @Test
    void closedM1IsEmittedOnlyAfterSinkRetrySucceeds() {
        CandlestickProperties properties = new CandlestickProperties();
        properties.setPeriods(List.of("1m", "5m"));
        properties.getFlush().setInterval(Duration.ofSeconds(1));
        CandleSink sink = mock(CandleSink.class);
        doThrow(new IllegalStateException("database unavailable")).doNothing()
                .when(sink).upsertBatch(org.mockito.ArgumentMatchers.anyList());
        SymbolRegistryService symbols = mock(SymbolRegistryService.class);
        when(symbols.isEnabled("BTC-USDT")).thenReturn(true);
        PublicTradeEventMapper mapper = mock(PublicTradeEventMapper.class);
        Instant tradeTime = Instant.parse("2026-08-25T10:00:01Z");
        when(mapper.toTradeEvent(org.mockito.ArgumentMatchers.any())).thenReturn(new TradeEvent(
                "BTC-USDT", "t1", 1, tradeTime, BigDecimal.TWO, BigDecimal.ONE,
                TradeSide.BUY, null, null));
        StreamsBuilder builder = new StreamsBuilder();
        new CandlestickStreamConfiguration().candlestickTopology(builder, properties, sink,
                symbols, mapper, new CandleHotCache());

        Properties streams = new Properties();
        streams.put(StreamsConfig.APPLICATION_ID_CONFIG, "flush-boundary-test");
        streams.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        try (TopologyTestDriver driver = new TopologyTestDriver(builder.build(), streams,
                Instant.parse("2026-08-25T10:02:00Z"))) {
            JacksonJsonSerde<PublicTradeEvent> tradeSerde = jsonSerde(PublicTradeEvent.class);
            JacksonJsonSerde<CandleUpdatedEvent> candleSerde = jsonSerde(CandleUpdatedEvent.class);
            TestInputTopic<String, PublicTradeEvent> trades = driver.createInputTopic(
                    properties.getKafka().getTradeTopic(), Serdes.String().serializer(), tradeSerde.serializer());
            TestOutputTopic<String, CandleUpdatedEvent> output = driver.createOutputTopic(
                    properties.getKafka().getCandleTopic(), Serdes.String().deserializer(), candleSerde.deserializer());

            trades.pipeInput("BTC-USDT", new PublicTradeEvent(
                    "t1", 1, "BTC-USDT", 1, OrderSide.BUY, 2, 1, tradeTime, "trace"));
            assertThat(output.readValue().status()).isEqualTo(CandleStatus.PARTIAL);

            driver.advanceWallClockTime(Duration.ofSeconds(1));
            assertThat(output.isEmpty()).isTrue();

            driver.advanceWallClockTime(Duration.ofSeconds(1));
            CandleUpdatedEvent closed = output.readValue();
            assertThat(closed.period()).isEqualTo("1m");
            assertThat(closed.status()).isEqualTo(CandleStatus.CLOSED);
        }
    }

    @Test
    void feedbackConsumesOnlyClosedM1AndDoesNotRecursivelyRollHigherEvents() {
        CandlestickProperties properties = new CandlestickProperties();
        properties.setPeriods(List.of("1m", "5m"));
        StreamsBuilder builder = new StreamsBuilder();
        new CandlestickStreamConfiguration().candlestickTopology(builder, properties, mock(CandleSink.class),
                mock(SymbolRegistryService.class), mock(PublicTradeEventMapper.class), new CandleHotCache());

        Properties streams = new Properties();
        streams.put(StreamsConfig.APPLICATION_ID_CONFIG, "rollup-topology-test");
        streams.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        try (TopologyTestDriver driver = new TopologyTestDriver(builder.build(), streams)) {
            JacksonJsonSerde<CandleUpdatedEvent> serde = jsonSerde(CandleUpdatedEvent.class);
            TestInputTopic<String, CandleUpdatedEvent> input = driver.createInputTopic(
                    properties.getKafka().getCandleTopic(), Serdes.String().serializer(), serde.serializer());
            TestOutputTopic<String, CandleUpdatedEvent> output = driver.createOutputTopic(
                    properties.getKafka().getCandleTopic(), Serdes.String().deserializer(), serde.deserializer());

            input.pipeInput("BTC-USDT", minute(CandleStatus.PARTIAL), 1L);
            assertThat(output.isEmpty()).isTrue();

            input.pipeInput("BTC-USDT", minute(CandleStatus.CLOSED), 2L);
            CandleUpdatedEvent rollup = output.readValue();
            assertThat(rollup.period()).isEqualTo("5m");
            assertThat(rollup.status()).isEqualTo(CandleStatus.PARTIAL);

            input.pipeInput("BTC-USDT", rollup, 3L);
            assertThat(output.isEmpty()).isTrue();

            input.pipeInput("BTC-USDT", minute(CandleStatus.CLOSED), 4L);
            assertThat(output.isEmpty()).isTrue();

            input.pipeInput("BTC-USDT", minuteAt("2026-08-25T10:05:00Z", CandleStatus.CLOSED), 5L);
            CandleUpdatedEvent closedPrevious = output.readValue();
            CandleUpdatedEvent nextBucket = output.readValue();
            assertThat(closedPrevious.period()).isEqualTo("5m");
            assertThat(closedPrevious.openTime()).isEqualTo(Instant.parse("2026-08-25T10:00:00Z"));
            assertThat(closedPrevious.status()).isEqualTo(CandleStatus.CLOSED);
            assertThat(nextBucket.openTime()).isEqualTo(Instant.parse("2026-08-25T10:05:00Z"));
            assertThat(nextBucket.status()).isEqualTo(CandleStatus.PARTIAL);
        }
    }

    @Test
    void lateMinuteBeforeRollupWatermarkIsDropped() {
        CandlestickProperties properties = new CandlestickProperties();
        properties.setPeriods(List.of("1m", "5m"));
        StreamsBuilder builder = new StreamsBuilder();
        new CandlestickStreamConfiguration().candlestickTopology(builder, properties, mock(CandleSink.class),
                mock(SymbolRegistryService.class), mock(PublicTradeEventMapper.class), new CandleHotCache());

        Properties streams = new Properties();
        streams.put(StreamsConfig.APPLICATION_ID_CONFIG, "rollup-late-minute-test");
        streams.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        try (TopologyTestDriver driver = new TopologyTestDriver(builder.build(), streams)) {
            JacksonJsonSerde<CandleUpdatedEvent> serde = jsonSerde(CandleUpdatedEvent.class);
            TestInputTopic<String, CandleUpdatedEvent> input = driver.createInputTopic(
                    properties.getKafka().getCandleTopic(), Serdes.String().serializer(), serde.serializer());
            TestOutputTopic<String, CandleUpdatedEvent> output = driver.createOutputTopic(
                    properties.getKafka().getCandleTopic(), Serdes.String().deserializer(), serde.deserializer());

            input.pipeInput("BTC-USDT", minuteAt("2026-08-25T10:05:00Z", CandleStatus.CLOSED), 1L);
            CandleUpdatedEvent active = output.readValue();
            assertThat(active.openTime()).isEqualTo(Instant.parse("2026-08-25T10:05:00Z"));
            assertThat(active.status()).isEqualTo(CandleStatus.PARTIAL);

            input.pipeInput("BTC-USDT", minuteAt("2026-08-25T10:00:00Z", CandleStatus.CLOSED), 2L);
            assertThat(output.isEmpty()).isTrue();
        }
    }

    @Test
    void wallClockClosesLastRollupWithoutAnotherTradeBucket() {
        CandlestickProperties properties = new CandlestickProperties();
        properties.setPeriods(List.of("1m", "5m"));
        StreamsBuilder builder = new StreamsBuilder();
        new CandlestickStreamConfiguration().candlestickTopology(builder, properties, mock(CandleSink.class),
                mock(SymbolRegistryService.class), mock(PublicTradeEventMapper.class), new CandleHotCache());

        Properties streams = new Properties();
        streams.put(StreamsConfig.APPLICATION_ID_CONFIG, "rollup-wall-clock-close-test");
        streams.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        try (TopologyTestDriver driver = new TopologyTestDriver(builder.build(), streams,
                Instant.parse("2026-08-25T10:04:00Z"))) {
            JacksonJsonSerde<CandleUpdatedEvent> serde = jsonSerde(CandleUpdatedEvent.class);
            TestInputTopic<String, CandleUpdatedEvent> input = driver.createInputTopic(
                    properties.getKafka().getCandleTopic(), Serdes.String().serializer(), serde.serializer());
            TestOutputTopic<String, CandleUpdatedEvent> output = driver.createOutputTopic(
                    properties.getKafka().getCandleTopic(), Serdes.String().deserializer(), serde.deserializer());

            input.pipeInput("BTC-USDT", minute(CandleStatus.CLOSED), 1L);
            assertThat(output.readValue().status()).isEqualTo(CandleStatus.PARTIAL);

            driver.advanceWallClockTime(Duration.ofMinutes(1));
            CandleUpdatedEvent closed = output.readValue();
            assertThat(closed.openTime()).isEqualTo(Instant.parse("2026-08-25T10:00:00Z"));
            assertThat(closed.status()).isEqualTo(CandleStatus.CLOSED);
        }
    }

    @Test
    void lateTradeCannotReviseClosedOneMinuteCandle() {
        CandlestickProperties properties = new CandlestickProperties();
        properties.setPeriods(List.of("1m", "5m"));
        properties.getFlush().setInterval(Duration.ofSeconds(1));
        CandleSink sink = mock(CandleSink.class);
        SymbolRegistryService symbols = mock(SymbolRegistryService.class);
        when(symbols.isEnabled("BTC-USDT")).thenReturn(true);
        PublicTradeEventMapper mapper = mock(PublicTradeEventMapper.class);
        Instant tradeTime = Instant.parse("2026-08-25T10:00:01Z");
        when(mapper.toTradeEvent(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new TradeEvent("BTC-USDT", "t1", 1, tradeTime, BigDecimal.TWO, BigDecimal.ONE,
                                TradeSide.BUY, null, null),
                        new TradeEvent("BTC-USDT", "t2", 2, tradeTime.plusSeconds(1), BigDecimal.TEN,
                                BigDecimal.ONE, TradeSide.BUY, null, null));
        StreamsBuilder builder = new StreamsBuilder();
        new CandlestickStreamConfiguration().candlestickTopology(builder, properties, sink,
                symbols, mapper, new CandleHotCache());

        Properties streams = new Properties();
        streams.put(StreamsConfig.APPLICATION_ID_CONFIG, "closed-minute-immutable-test");
        streams.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        try (TopologyTestDriver driver = new TopologyTestDriver(builder.build(), streams,
                Instant.parse("2026-08-25T10:02:00Z"))) {
            JacksonJsonSerde<PublicTradeEvent> tradeSerde = jsonSerde(PublicTradeEvent.class);
            JacksonJsonSerde<CandleUpdatedEvent> candleSerde = jsonSerde(CandleUpdatedEvent.class);
            TestInputTopic<String, PublicTradeEvent> trades = driver.createInputTopic(
                    properties.getKafka().getTradeTopic(), Serdes.String().serializer(), tradeSerde.serializer());
            TestOutputTopic<String, CandleUpdatedEvent> output = driver.createOutputTopic(
                    properties.getKafka().getCandleTopic(), Serdes.String().deserializer(), candleSerde.deserializer());

            trades.pipeInput("BTC-USDT", new PublicTradeEvent(
                    "t1", 1, "BTC-USDT", 1, OrderSide.BUY, 2, 1, tradeTime, "trace-1"));
            assertThat(output.readValue().status()).isEqualTo(CandleStatus.PARTIAL);
            driver.advanceWallClockTime(Duration.ofSeconds(1));
            assertThat(output.readValue().status()).isEqualTo(CandleStatus.CLOSED);
            CandleUpdatedEvent rollup = output.readValue();
            assertThat(rollup.period()).isEqualTo("5m");
            assertThat(rollup.status()).isEqualTo(CandleStatus.PARTIAL);

            trades.pipeInput("BTC-USDT", new PublicTradeEvent(
                    "t2", 2, "BTC-USDT", 1, OrderSide.BUY, 10, 1, tradeTime.plusSeconds(1), "trace-2"));
            assertThat(output.readValuesToList()).isEmpty();
            verify(sink, times(1)).upsertBatch(org.mockito.ArgumentMatchers.anyList());
        }
    }

    private CandleUpdatedEvent minute(CandleStatus status) {
        return minuteAt("2026-08-25T10:01:00Z", status);
    }

    private CandleUpdatedEvent minuteAt(String time, CandleStatus status) {
        Instant open = Instant.parse(time);
        return new CandleUpdatedEvent("BTC-USDT", "1m", open, open.plusSeconds(60),
                BigDecimal.ONE, BigDecimal.TWO, BigDecimal.ONE, BigDecimal.TWO,
                BigDecimal.ONE, BigDecimal.TWO, 1, "a", "a", 1L, 1L,
                status, open.plusSeconds(59), open.plusSeconds(60), 0, 1L);
    }

    private <T> JacksonJsonSerde<T> jsonSerde(Class<T> type) {
        JacksonJsonSerde<T> serde = new JacksonJsonSerde<>(type);
        serde.ignoreTypeHeaders();
        serde.noTypeInfo();
        return serde;
    }
}
