package com.surprising.price.index.service;

import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.index.config.IndexPriceProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 从标记价共用的业务 Topic 维护本地最新指数价缓存。 */
@Component
public class IndexPriceKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(IndexPriceKafkaConsumer.class);

    private final ObjectMapper objectMapper;
    private final LatestIndexPriceCache cache;
    private final IndexPriceProperties properties;

    public IndexPriceKafkaConsumer(ObjectMapper objectMapper,
                                   LatestIndexPriceCache cache,
                                   IndexPriceProperties properties) {
        this.objectMapper = objectMapper;
        this.cache = cache;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "#{__listener.indexPriceTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "indexPriceCacheKafkaListenerContainerFactory")
    public void onIndexPrice(ConsumerRecord<String, String> record) {
        try {
            if (properties.getKafka().isProductTopicsEnabled()
                    && !indexPriceTopic().equals(record.topic())) {
                throw new IllegalArgumentException("index price topic must match current product line: expected="
                        + indexPriceTopic() + " actual=" + record.topic());
            }
            IndexPriceEvent event = objectMapper.readValue(record.value(), IndexPriceEvent.class);
            if (record.key() == null || !record.key().equals(event.symbol())) {
                throw new IllegalArgumentException("index price Kafka key must match payload symbol");
            }
            cache.update(event);
        } catch (Exception ex) {
            // 无法修复的历史或损坏消息直接丢弃，不能阻塞后续实时指数价。
            log.warn("Discarding invalid index price topic={} partition={} offset={}: {}",
                    record.topic(), record.partition(), record.offset(), ex.getMessage(), ex);
        }
    }

    public String indexPriceTopic() {
        return properties.getKafka().getIndexPriceTopic();
    }

    public String groupId() {
        return properties.getKafka().getCacheGroupId();
    }
}
