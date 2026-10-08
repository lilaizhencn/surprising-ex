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
            assertThat(trades.numPartitions()).isEqualTo(-1); // broker 默认值，保留已有分区数
            assertThat(candles.numPartitions()).isEqualTo(-1);
        }
    }

    @Test
    void brokerDefaultCreatesSmallTopicsAndRestartPreservesExistingPartitions() throws Exception {
        var broker = new org.springframework.kafka.test.EmbeddedKafkaKraftBroker(1, 1)
                .brokerProperties(java.util.Map.of("num.partitions", "1"));
        try {
            broker.afterPropertiesSet();
            try (var admin = org.apache.kafka.clients.admin.Admin.create(java.util.Map.of(
                    "bootstrap.servers", broker.getBrokersAsString()))) {
                for (ProductLine line : ProductLine.values()) {
                    var properties = new CandlestickProperties();
                    properties.getKafka().setProductLine(line);
                    properties.getKafka().setBootstrapServers(broker.getBrokersAsString());
                    var kafkaAdmin = configuration.candleKafkaAdmin(properties);
                    var trades = configuration.candleTradeSourceTopic(properties);
                    var candles = configuration.candleEventsTopic(properties);
                    kafkaAdmin.createOrModifyTopics(trades, candles);
                    org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() -> {
                        var created = admin.describeTopics(java.util.List.of(trades.name(), candles.name()))
                                .allTopicNames().get(15, java.util.concurrent.TimeUnit.SECONDS);
                        assertThat(created.get(trades.name()).partitions()).hasSize(1);
                        assertThat(created.get(candles.name()).partitions()).hasSize(1);
                    });
                    admin.createPartitions(java.util.Map.of(trades.name(),
                            org.apache.kafka.clients.admin.NewPartitions.increaseTo(4)))
                            .all().get(15, java.util.concurrent.TimeUnit.SECONDS);
                    // Controller 确认变更后，Broker 元数据仍需异步传播；先确认已有 4 分区。
                    org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() ->
                            assertThat(admin.describeTopics(java.util.List.of(trades.name())).allTopicNames()
                                    .get(15, java.util.concurrent.TimeUnit.SECONDS).get(trades.name()).partitions()).hasSize(4));
                    kafkaAdmin.createOrModifyTopics(trades, candles);
                    assertThat(admin.describeTopics(java.util.List.of(trades.name())).allTopicNames()
                            .get(15, java.util.concurrent.TimeUnit.SECONDS).get(trades.name()).partitions()).hasSize(4);
                }
            }
        } finally {
            broker.destroy();
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
