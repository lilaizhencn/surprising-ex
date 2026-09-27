package com.surprising.candlestick.provider.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import org.apache.kafka.streams.KafkaStreams;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;

class CandlestickStreamConfigurationTest {
    private final CandlestickStreamConfiguration configuration = new CandlestickStreamConfiguration();

    @Test
    void provisionsBothSourcesForEachProductLineBeforeAnyTrade() {
        for (ProductLine line : ProductLine.values()) {
            var properties = new CandlestickProperties();
            properties.getKafka().setProductLine(line);
            var trades = configuration.candleTradeSourceTopic(properties);
            var candles = configuration.candleEventsTopic(properties);
            assertThat(trades.name()).isEqualTo(properties.getKafka().getTradeTopic());
            assertThat(candles.name()).isEqualTo(properties.getKafka().getCandleTopic());
            assertThat(trades.numPartitions()).isEqualTo(32);
            assertThat(candles.numPartitions()).isEqualTo(32);
        }
    }

    @Test
    void failedOrStoppedStreamsCannotReportHealthyHttpService() {
        var factory = mock(StreamsBuilderFactoryBean.class);
        var health = configuration.candleStreamsHealthIndicator(factory);
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        var streams = mock(KafkaStreams.class);
        when(factory.getKafkaStreams()).thenReturn(streams);
        for (KafkaStreams.State state : KafkaStreams.State.values()) {
            when(streams.state()).thenReturn(state);
            assertThat(health.health().getStatus()).isEqualTo(
                    state == KafkaStreams.State.RUNNING ? Status.UP : Status.DOWN);
        }
    }
}
