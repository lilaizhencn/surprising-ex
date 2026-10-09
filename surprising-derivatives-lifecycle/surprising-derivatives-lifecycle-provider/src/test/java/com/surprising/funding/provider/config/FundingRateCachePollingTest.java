package com.surprising.funding.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.funding.provider.service.FundingRateKafkaConsumer;
import com.surprising.funding.provider.service.LatestFundingRateCache;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.TopicPartitionOffset;
import tools.jackson.databind.ObjectMapper;

class FundingRateCachePollingTest {
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "INVERSE_PERPETUAL"})
    void rebuildsAndReplaysTheSameCacheWithPerPollInsteadOfPerRecordCommits(ProductLine product) throws Exception {
        var baseline = replay(product, true);
        var batched = replay(product, false);
        var restarted = replay(product, false);
        assertThat(baseline).hasSize(1001);
        assertThat(batched).containsExactly(500L, 1000L, 1001L);
        assertThat(restarted).isEqualTo(batched);
    }

    private List<Long> replay(ProductLine product, boolean perRecordBaseline) throws Exception {
        var properties = new FundingProperties();
        properties.getKafka().setProductLine(product);
        var topic = properties.getKafka().getFundingRateTopic();
        var partition = new TopicPartition(topic, 0);
        var committed = new CountDownLatch(1);
        var commits = new ArrayList<Long>();
        var consumer = new MockConsumer<String, String>("earliest") {
            @Override public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets, Duration timeout) {
                super.commitSync(offsets, timeout);
                var offset = offsets.get(partition);
                if (offset != null) {
                    commits.add(offset.offset());
                    if (offset.offset() == 1001) committed.countDown();
                }
            }
        };
        consumer.setMaxPollRecords(500);
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        consumer.updateEndOffsets(Map.of(partition, 1001L));
        var configuration = new FundingKafkaConfiguration();
        var original = configuration.fundingRateCacheConsumerFactory(properties);
        var source = new DefaultKafkaConsumerFactory<String, String>(original.getConfigurationProperties()) {
            @Override public Consumer<String, String> createConsumer(
                    String groupId, String clientIdPrefix, String clientIdSuffix, Properties overrides) {
                return consumer;
            }
        };
        var factory = configuration.fundingRateCacheKafkaListenerContainerFactory(source, properties);
        if (perRecordBaseline) factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        var cache = new LatestFundingRateCache(properties);
        var mapper = new ObjectMapper();
        var listener = new FundingRateKafkaConsumer(mapper, cache, properties);
        var now = Instant.now();
        var records = new ArrayList<ConsumerRecord<String, String>>();
        for (int i = 0; i < 1001; i++) {
            // Replaying an older prediction at the end must not replace sequence 1000.
            long sequence = i == 1000 ? 1 : i + 1;
            var event = new PerpFundingRateEvent("604", new BigDecimal("0.0001"),
                    now.plusSeconds(3600), 8, sequence, now);
            records.add(new ConsumerRecord<>(topic, 0, i, "604", mapper.writeValueAsString(event)));
        }
        consumer.schedulePollTask(() -> records.forEach(consumer::addRecord));
        var container = factory.createContainer(new TopicPartitionOffset(topic, 0, 0L));
        container.setBeanName("funding-cache-polling-test-" + product);
        container.getContainerProperties().setPollTimeout(10);
        container.setupMessageListener((MessageListener<String, String>) listener::onFundingRate);
        try {
            container.start();
            assertThat(committed.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(cache.requireFresh("604").sequence()).isEqualTo(1000);
        } finally {
            container.stop();
        }
        return List.copyOf(commits);
    }
}
