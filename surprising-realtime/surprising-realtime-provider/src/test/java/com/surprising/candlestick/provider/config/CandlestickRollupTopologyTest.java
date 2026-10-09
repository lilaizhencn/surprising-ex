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
    void revisionsReplaceEveryPeriodAndQueuedMinuteNotificationsCannotDoubleCount() {
        var properties = new CandlestickProperties();
        properties.setPeriods(java.util.Arrays.stream(com.surprising.candlestick.api.model.CandlePeriod.values())
                .map(com.surprising.candlestick.api.model.CandlePeriod::code).toList());
        var durable = new java.util.TreeMap<Instant, com.surprising.candlestick.api.model.CandleResponse>();
        var repository = mock(com.surprising.candlestick.provider.repository.CandleQueryRepository.class);
        when(repository.findRange(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("1m"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(call -> List.copyOf(durable.subMap(call.getArgument(2), true, call.getArgument(3), false).values()));
        var builder = new StreamsBuilder();
        new CandlestickStreamConfiguration().candlestickTopology(builder, properties, mock(CandleSink.class),
                mock(SymbolRegistryService.class), mock(PublicTradeEventMapper.class), new CandleHotCache(), repository);
        var config = new Properties();
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "all-period-replacement-test");
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        try (var driver = new TopologyTestDriver(builder.build(), config, Instant.parse("2026-08-25T10:02:00Z"))) {
            var serde = jsonSerde(CandleUpdatedEvent.class);
            var input = driver.createInputTopic(properties.getKafka().getCandleTopic(), Serdes.String().serializer(), serde.serializer());
            var output = driver.createOutputTopic(properties.getKafka().getCandleTopic(), Serdes.String().deserializer(), serde.deserializer());
            var first = minuteAt("2026-08-25T10:00:00Z", CandleStatus.CLOSED);
            input.pipeInput("1", first);
            assertThat(output.readValuesToList()).hasSize(properties.getPeriods().size() - 1);
            var future = minuteAt("2026-08-25T10:01:00Z", CandleStatus.CLOSED);
            for (var m : List.of(first, future)) durable.put(m.openTime(), new com.surprising.candlestick.api.model.CandleResponse(
                    m.instrumentId(), m.period(), m.openTime(), m.closeTime(), m.openPrice(), m.highPrice(), m.lowPrice(),
                    m.closePrice(), m.baseVolume(), m.quoteVolume(), m.tradeCount(), m.firstTradeId(), m.lastTradeId(),
                    m.firstSequence(), m.lastSequence(), m.status(), m.eventTime()));
            // Replaying the first notification sees both already durable minutes. A later
            // notification of that second minute must not add its volume a second time.
            var revision = revisedMinute(first, 2);
            durable.put(first.openTime(), response(revision));
            input.pipeInput("1", revision);
            assertThat(output.readValuesToList()).hasSize(properties.getPeriods().size() - 1)
                    .allSatisfy(row -> { assertThat(row.tradeCount()).isEqualTo(3); assertThat(row.baseVolume()).isEqualByComparingTo("3"); });
            input.pipeInput("1", future);
            assertThat(output.isEmpty()).isTrue();
            revision = revisedMinute(first, 3);
            durable.put(first.openTime(), response(revision));
            input.pipeInput("1", revision);
            assertThat(output.readValuesToList()).hasSize(properties.getPeriods().size() - 1)
                    .allSatisfy(row -> { assertThat(row.tradeCount()).isEqualTo(4); assertThat(row.baseVolume()).isEqualByComparingTo("4");
                        assertThat(row.highPrice()).isEqualByComparingTo("10"); });
            for (int i = 0; i < 20; i++) input.pipeInput("1", revision);
            assertThat(output.isEmpty()).isTrue();
            verify(repository, times(2 * (properties.getPeriods().size() - 1))).findRange(
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("1m"),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
        }
    }

    @Test
    void activeWeekCannotDoubleCountAnOldMinuteAfterItsSeenMarkerExpires() {
        var properties = new CandlestickProperties();
        properties.setPeriods(List.of("1m", "1w"));
        var week = com.surprising.candlestick.api.model.CandlePeriod.W1;
        var first = minuteAt("2026-08-21T10:00:00Z", CandleStatus.CLOSED);
        var last = minuteAt("2026-08-25T10:00:00Z", CandleStatus.CLOSED);
        var repository = mock(com.surprising.candlestick.provider.repository.CandleQueryRepository.class);
        var rows = java.util.stream.Stream.of(first, last).map(m ->
                new com.surprising.candlestick.api.model.CandleResponse(m.instrumentId(), m.period(),
                        m.openTime(), m.closeTime(), m.openPrice(), m.highPrice(), m.lowPrice(), m.closePrice(),
                        m.baseVolume(), m.quoteVolume(), m.tradeCount(), m.firstTradeId(), m.lastTradeId(),
                        m.firstSequence(), m.lastSequence(), m.status(), m.eventTime())).toList();
        when(repository.findRange("1", "1m", week.floor(first.openTime()),
                week.closeTime(week.floor(first.openTime())), 10080)).thenReturn(rows);
        var builder = new StreamsBuilder();
        new CandlestickStreamConfiguration().candlestickTopology(builder, properties, mock(CandleSink.class),
                mock(SymbolRegistryService.class), mock(PublicTradeEventMapper.class), new CandleHotCache(), repository);
        var config = new Properties();
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "expired-weekly-minute-test");
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        try (var driver = new TopologyTestDriver(builder.build(), config, last.closeTime())) {
            var serde = jsonSerde(CandleUpdatedEvent.class);
            var input = driver.createInputTopic(properties.getKafka().getCandleTopic(), Serdes.String().serializer(), serde.serializer());
            var output = driver.createOutputTopic(properties.getKafka().getCandleTopic(), Serdes.String().deserializer(), serde.deserializer());
            input.pipeInput("1", first);
            input.pipeInput("1", last);
            assertThat(output.readValuesToList().getLast().tradeCount()).isEqualTo(2);
            var seen = driver.<String, Long>getKeyValueStore(
                    com.surprising.candlestick.provider.aggregation.CandleStores.ROLLUP_SEEN_STORE);
            var keys = new java.util.ArrayList<String>();
            try (var iterator = seen.all()) { while (iterator.hasNext()) keys.add(iterator.next().key); }
            keys.forEach(seen::delete); // retention cleanup of an active, longer-lived week
            input.pipeInput("1", first);
            assertThat(output.isEmpty()).isTrue();
            String oldKey = keys.stream().filter(key -> key.endsWith("|" + first.openTime().toEpochMilli())).findFirst().orElseThrow();
            seen.put(oldKey, first.openTime().toEpochMilli());
            input.pipeInput("1", first);
            assertThat(output.isEmpty()).isTrue();
            assertThat(seen.get(oldKey)).isEqualTo(~first.tradeCount());
            driver.advanceWallClockTime(Duration.ofDays(3));
            assertThat(seen.get(oldKey)).isNull();
            verify(repository, times(2)).findRange("1", "1m", week.floor(first.openTime()),
                    week.closeTime(week.floor(first.openTime())), 10080);
        }
    }

    @Test
    void weeklyRevisionRebuildIsBoundedTo10080MinutesAndRemainsIdempotent() {
        var properties = new CandlestickProperties();
        properties.setPeriods(List.of("1m", "1w"));
        var week = com.surprising.candlestick.api.model.CandlePeriod.W1;
        Instant open = week.floor(Instant.parse("2026-08-25T10:00:00Z"));
        var rows = new java.util.ArrayList<com.surprising.candlestick.api.model.CandleResponse>(10080);
        for (int i = 0; i < 10080; i++) {
            Instant time = open.plusSeconds(i * 60L);
            rows.add(new com.surprising.candlestick.api.model.CandleResponse("1", "1m", time, time.plusSeconds(60),
                    BigDecimal.ONE, BigDecimal.TWO, BigDecimal.ONE, BigDecimal.TWO, BigDecimal.ONE, BigDecimal.TWO,
                    1, "first-"+i, "last-"+i, (long)i+1, (long)i+1, CandleStatus.CLOSED, time.plusSeconds(60)));
        }
        var repository = mock(com.surprising.candlestick.provider.repository.CandleQueryRepository.class);
        when(repository.findRange("1", "1m", open, week.closeTime(open), 10080)).thenReturn(rows);
        var builder = new StreamsBuilder();
        new CandlestickStreamConfiguration().candlestickTopology(builder, properties, mock(CandleSink.class),
                mock(SymbolRegistryService.class), mock(PublicTradeEventMapper.class), new CandleHotCache(), repository);
        var config = new Properties();
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "weekly-replacement-test");
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        try (var driver = new TopologyTestDriver(builder.build(), config, week.closeTime(open))) {
            var serde = jsonSerde(CandleUpdatedEvent.class);
            var input = driver.createInputTopic(properties.getKafka().getCandleTopic(), Serdes.String().serializer(), serde.serializer());
            var output = driver.createOutputTopic(properties.getKafka().getCandleTopic(), Serdes.String().deserializer(), serde.deserializer());
            var minute = minuteAt(open.toString(), CandleStatus.CLOSED);
            input.pipeInput("1", minute);
            output.readValuesToList();
            var revision = revisedMinute(minute, 2);
            rows.set(0, response(revision));
            input.pipeInput("1", revision);
            assertThat(output.readValue()).satisfies(row -> {
                assertThat(row.tradeCount()).isEqualTo(10081);
                assertThat(row.baseVolume()).isEqualByComparingTo("10081");
                assertThat(row.status()).isEqualTo(CandleStatus.CLOSED);
                assertThat(row.lastSequence()).isEqualTo(10080);
            });
            input.pipeInput("1", revision);
            assertThat(output.isEmpty()).isTrue();
            verify(repository).findRange("1", "1m", open, week.closeTime(open), 10080);
        }
    }

    @Test
    void closedM1IsEmittedOnlyAfterSinkRetrySucceeds() {
        CandlestickProperties properties = new CandlestickProperties();
        properties.setPeriods(List.of("1m", "5m"));
        properties.getFlush().setInterval(Duration.ofSeconds(1));
        CandleSink sink = mock(CandleSink.class);
        doThrow(new IllegalStateException("database unavailable")).doNothing()
                .when(sink).upsertBatch(org.mockito.ArgumentMatchers.anyList());
        SymbolRegistryService symbols = mock(SymbolRegistryService.class);
        when(symbols.isEnabled("1")).thenReturn(true);
        PublicTradeEventMapper mapper = mock(PublicTradeEventMapper.class);
        Instant tradeTime = Instant.parse("2026-08-25T10:00:01Z");
        when(mapper.toTradeEvent(org.mockito.ArgumentMatchers.any())).thenReturn(new TradeEvent(
                "1", "t1", 1, tradeTime, BigDecimal.TWO, BigDecimal.ONE,
                TradeSide.BUY, null, null));
        StreamsBuilder builder = new StreamsBuilder();
        var router = mock(com.surprising.realtime.provider.RealtimeRouter.class);
        var configuration = new CandlestickStreamConfiguration();
        var json = new tools.jackson.databind.ObjectMapper();
        org.springframework.test.util.ReflectionTestUtils.setField(configuration, "realtime", router);
        org.springframework.test.util.ReflectionTestUtils.setField(configuration, "objectMapper", json);
        configuration.candlestickTopology(builder, properties, sink, symbols, mapper, new CandleHotCache(), minutes());

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

            trades.pipeInput("1", new PublicTradeEvent(
                    "t1", 1, "1", OrderSide.BUY, 2, 1, tradeTime, "trace"));
            assertThat(output.readValuesToList()).hasSize(2)
                    .allSatisfy(event -> assertThat(event.status()).isEqualTo(CandleStatus.PARTIAL));
            // Export can repeat the same identity after Kafka commit / checkpoint crash window.
            trades.pipeInput("1", new PublicTradeEvent(
                    "t1", 1, "1", OrderSide.BUY, 2, 1, tradeTime, "trace"));
            assertThat(output.isEmpty()).isTrue();


            driver.advanceWallClockTime(Duration.ofSeconds(1));
            assertThat(output.isEmpty()).isTrue();

            driver.advanceWallClockTime(Duration.ofSeconds(1));
            CandleUpdatedEvent closed = output.readValue();
            assertThat(closed.period()).isEqualTo("1m");
            assertThat(closed.status()).isEqualTo(CandleStatus.CLOSED);
            var frames = org.mockito.ArgumentCaptor.forClass(com.surprising.aeron.protocol.RealtimeFrame.class);
            verify(router, times(4)).offer(frames.capture());
            assertThat(frames.getAllValues()).allSatisfy(frame -> {
                assertThat(frame.kind()).isEqualTo(com.surprising.aeron.protocol.RealtimeFrame.Kind.CANDLE);
                assertThat(frame.productLine()).isEqualTo(properties.getKafka().getProductLine());
            });
            assertThat(frames.getAllValues()).extracting(com.surprising.aeron.protocol.RealtimeFrame::entityId)
                    .containsExactlyInAnyOrder("1m", "1m", "5m", "5m");
        }
    }

    @Test
    void feedbackConsumesMinuteSnapshotsAndDoesNotRecursivelyRollHigherEvents() {
        CandlestickProperties properties = new CandlestickProperties();
        properties.setPeriods(List.of("1m", "5m"));
        StreamsBuilder builder = new StreamsBuilder();
        new CandlestickStreamConfiguration().candlestickTopology(builder, properties, mock(CandleSink.class),
                mock(SymbolRegistryService.class), mock(PublicTradeEventMapper.class), new CandleHotCache(), minutes());

        Properties streams = new Properties();
        streams.put(StreamsConfig.APPLICATION_ID_CONFIG, "rollup-topology-test");
        streams.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        try (TopologyTestDriver driver = new TopologyTestDriver(builder.build(), streams, Instant.parse("2026-08-25T10:02:00Z"))) {
            JacksonJsonSerde<CandleUpdatedEvent> serde = jsonSerde(CandleUpdatedEvent.class);
            TestInputTopic<String, CandleUpdatedEvent> input = driver.createInputTopic(
                    properties.getKafka().getCandleTopic(), Serdes.String().serializer(), serde.serializer());
            TestOutputTopic<String, CandleUpdatedEvent> output = driver.createOutputTopic(
                    properties.getKafka().getCandleTopic(), Serdes.String().deserializer(), serde.deserializer());

            input.pipeInput("1", minute(CandleStatus.PARTIAL), 1L);
            assertThat(output.readValue()).satisfies(event -> {
                assertThat(event.period()).isEqualTo("5m");
                assertThat(event.baseVolume()).isEqualByComparingTo("1");
            });

            input.pipeInput("1", minute(CandleStatus.CLOSED), 2L);
            CandleUpdatedEvent rollup = output.readValue();
            assertThat(rollup.period()).isEqualTo("5m");
            assertThat(rollup.status()).isEqualTo(CandleStatus.PARTIAL);

            input.pipeInput("1", rollup, 3L);
            assertThat(output.isEmpty()).isTrue();

            input.pipeInput("1", minute(CandleStatus.CLOSED), 4L);
            assertThat(output.isEmpty()).isTrue();

            input.pipeInput("1", minuteAt("2026-08-25T10:05:00Z", CandleStatus.CLOSED), 5L);
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
    void lateMinuteBeforeRollupWatermarkRebuildsItsOwnClosedBucket() {
        CandlestickProperties properties = new CandlestickProperties();
        properties.setPeriods(List.of("1m", "5m"));
        StreamsBuilder builder = new StreamsBuilder();
        new CandlestickStreamConfiguration().candlestickTopology(builder, properties, mock(CandleSink.class),
                mock(SymbolRegistryService.class), mock(PublicTradeEventMapper.class), new CandleHotCache(), minutes());

        Properties streams = new Properties();
        streams.put(StreamsConfig.APPLICATION_ID_CONFIG, "rollup-late-minute-test");
        streams.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        try (TopologyTestDriver driver = new TopologyTestDriver(builder.build(), streams, Instant.parse("2026-08-25T10:07:00Z"))) {
            JacksonJsonSerde<CandleUpdatedEvent> serde = jsonSerde(CandleUpdatedEvent.class);
            TestInputTopic<String, CandleUpdatedEvent> input = driver.createInputTopic(
                    properties.getKafka().getCandleTopic(), Serdes.String().serializer(), serde.serializer());
            TestOutputTopic<String, CandleUpdatedEvent> output = driver.createOutputTopic(
                    properties.getKafka().getCandleTopic(), Serdes.String().deserializer(), serde.deserializer());

            input.pipeInput("1", minuteAt("2026-08-25T10:05:00Z", CandleStatus.CLOSED), 1L);
            CandleUpdatedEvent active = output.readValue();
            assertThat(active.openTime()).isEqualTo(Instant.parse("2026-08-25T10:05:00Z"));
            assertThat(active.status()).isEqualTo(CandleStatus.PARTIAL);

            input.pipeInput("1", minuteAt("2026-08-25T10:00:00Z", CandleStatus.CLOSED), 2L);
            var repaired = output.readValue();
            assertThat(repaired.openTime()).isEqualTo(Instant.parse("2026-08-25T10:00:00Z"));
            assertThat(repaired.status()).isEqualTo(CandleStatus.CLOSED);
        }
    }

    @Test
    void wallClockClosesLastRollupWithoutAnotherTradeBucket() {
        CandlestickProperties properties = new CandlestickProperties();
        properties.setPeriods(List.of("1m", "5m"));
        StreamsBuilder builder = new StreamsBuilder();
        new CandlestickStreamConfiguration().candlestickTopology(builder, properties, mock(CandleSink.class),
                mock(SymbolRegistryService.class), mock(PublicTradeEventMapper.class), new CandleHotCache(), minutes());

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

            input.pipeInput("1", minute(CandleStatus.CLOSED), 1L);
            assertThat(output.readValue().status()).isEqualTo(CandleStatus.PARTIAL);

            driver.advanceWallClockTime(Duration.ofMinutes(1));
            CandleUpdatedEvent closed = output.readValue();
            assertThat(closed.openTime()).isEqualTo(Instant.parse("2026-08-25T10:00:00Z"));
            assertThat(closed.status()).isEqualTo(CandleStatus.CLOSED);
        }
    }

    @Test
    void lateTradeRevisesClosedMinuteOnlyAfterDurableWriteAndDoesNotDoubleCount() {
        CandlestickProperties properties = new CandlestickProperties();
        properties.setPeriods(List.of("1m", "5m"));
        properties.getFlush().setInterval(Duration.ofSeconds(1));
        CandleSink sink = mock(CandleSink.class);
        SymbolRegistryService symbols = mock(SymbolRegistryService.class);
        when(symbols.isEnabled("1")).thenReturn(true);
        PublicTradeEventMapper mapper = mock(PublicTradeEventMapper.class);
        Instant tradeTime = Instant.parse("2026-08-25T10:00:01Z");
        when(mapper.toTradeEvent(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new TradeEvent("1", "t1", 1, tradeTime, BigDecimal.TWO, BigDecimal.ONE,
                                TradeSide.BUY, null, null),
                        new TradeEvent("1", "t2", 2, tradeTime.plusSeconds(1), BigDecimal.TEN,
                                BigDecimal.ONE, TradeSide.BUY, null, null));
        var durable = new java.util.concurrent.atomic.AtomicReference<com.surprising.candlestick.provider.aggregation.CandleSnapshot>();
        var failFirstRevision = new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(call -> {
            var next = call.<List<com.surprising.candlestick.provider.aggregation.CandleSnapshot>>getArgument(0).getFirst();
            if (next.getTradeCount() == 2 && failFirstRevision.getAndSet(false)) throw new IllegalStateException("revision write unavailable");
            durable.set(next); return null;
        })
                .when(sink).upsertBatch(org.mockito.ArgumentMatchers.anyList());
        var minutes = mock(com.surprising.candlestick.provider.repository.CandleQueryRepository.class);
        when(minutes.findRange(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("1m"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(call -> { var c = durable.get(); return List.of(new com.surprising.candlestick.api.model.CandleResponse(
                        c.getInstrumentId(), c.getPeriod(), c.getOpenTime(), c.getCloseTime(), c.getOpenPrice(),
                        c.getHighPrice(), c.getLowPrice(), c.getClosePrice(), c.getBaseVolume(), c.getQuoteVolume(),
                        c.getTradeCount(), c.getFirstTradeId(), c.getLastTradeId(), c.getFirstSequence(), c.getLastSequence(),
                        c.getStatus(), c.getUpdatedAt())); });
        StreamsBuilder builder = new StreamsBuilder();
        new CandlestickStreamConfiguration().candlestickTopology(builder, properties, sink,
                symbols, mapper, new CandleHotCache(), minutes);

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

            trades.pipeInput("1", new PublicTradeEvent(
                    "t1", 1, "1", OrderSide.BUY, 2, 1, tradeTime, "trace-1"));
            assertThat(output.readValuesToList()).hasSize(2)
                    .allSatisfy(event -> assertThat(event.status()).isEqualTo(CandleStatus.PARTIAL));
            driver.advanceWallClockTime(Duration.ofSeconds(1));
            assertThat(output.readValue().status()).isEqualTo(CandleStatus.CLOSED);
            CandleUpdatedEvent rollup = output.readValue();
            assertThat(rollup.period()).isEqualTo("5m");
            assertThat(rollup.status()).isEqualTo(CandleStatus.PARTIAL);

            trades.pipeInput("1", new PublicTradeEvent(
                    "t2", 2, "1", OrderSide.BUY, 10, 1, tradeTime.plusSeconds(1), "trace-2"));
            assertThat(output.isEmpty()).isTrue(); // replacement is not published before durable write
            driver.advanceWallClockTime(Duration.ofSeconds(1));
            assertThat(output.isEmpty()).isTrue(); // failed SQL keeps the dirty replacement for retry
            trades.pipeInput("1", new PublicTradeEvent(
                    "t2", 2, "1", OrderSide.BUY, 10, 1, tradeTime.plusSeconds(1), "duplicate"));
            driver.advanceWallClockTime(Duration.ofSeconds(1));
            var revised = output.readValue();
            assertThat(revised.status()).isEqualTo(CandleStatus.CLOSED);
            assertThat(revised.closePrice()).isEqualByComparingTo("10");
            assertThat(revised.baseVolume()).isEqualByComparingTo("2");
            assertThat(revised.tradeCount()).isEqualTo(2);
            assertThat(output.readValue().period()).isEqualTo("5m");
            verify(sink, times(3)).upsertBatch(org.mockito.ArgumentMatchers.anyList());
        }
    }

    private com.surprising.candlestick.provider.repository.CandleQueryRepository minutes() {
        var repository = mock(com.surprising.candlestick.provider.repository.CandleQueryRepository.class);
        when(repository.findRange(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("1m"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(call -> {
                    Instant open = call.getArgument(2);
                    var m = minuteAt(open.equals(Instant.parse("2026-08-25T10:00:00Z"))
                            ? "2026-08-25T10:01:00Z" : open.toString(), CandleStatus.CLOSED);
                    return List.of(new com.surprising.candlestick.api.model.CandleResponse(m.instrumentId(), m.period(),
                            m.openTime(), m.closeTime(), m.openPrice(), m.highPrice(), m.lowPrice(), m.closePrice(),
                            m.baseVolume(), m.quoteVolume(), m.tradeCount(), m.firstTradeId(), m.lastTradeId(),
                            m.firstSequence(), m.lastSequence(), m.status(), m.eventTime()));
                });
        return repository;
    }

    private CandleUpdatedEvent minute(CandleStatus status) {
        return minuteAt("2026-08-25T10:01:00Z", status);
    }

    private CandleUpdatedEvent revisedMinute(CandleUpdatedEvent minute, long count) {
        return new CandleUpdatedEvent(minute.instrumentId(), minute.period(), minute.openTime(), minute.closeTime(),
                minute.openPrice(), BigDecimal.TEN, minute.lowPrice(), BigDecimal.TEN,
                BigDecimal.valueOf(count), BigDecimal.TEN, count, minute.firstTradeId(), "revision-" + count,
                minute.firstSequence(), count, CandleStatus.CLOSED, minute.eventTime().plusSeconds(count),
                minute.emittedAt(), minute.sourcePartition(), minute.sourceOffset());
    }

    private com.surprising.candlestick.api.model.CandleResponse response(CandleUpdatedEvent m) {
        return new com.surprising.candlestick.api.model.CandleResponse(m.instrumentId(), m.period(),
                m.openTime(), m.closeTime(), m.openPrice(), m.highPrice(), m.lowPrice(), m.closePrice(),
                m.baseVolume(), m.quoteVolume(), m.tradeCount(), m.firstTradeId(), m.lastTradeId(),
                m.firstSequence(), m.lastSequence(), m.status(), m.eventTime());
    }

    private CandleUpdatedEvent minuteAt(String time, CandleStatus status) {
        Instant open = Instant.parse(time);
        return new CandleUpdatedEvent("1", "1m", open, open.plusSeconds(60),
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
