package com.surprising.instrument.api.kafka;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一生成 Instrument 事件消费者的基础配置。
 *
 * <p>各模块仍然保留自己的消费者组和监听容器，只把完全相同的序列化、提交和起始位点配置
 * 收敛到这里，避免不同模块出现细微差异。</p>
 */
public final class InstrumentKafkaConsumerProperties {

    private InstrumentKafkaConsumerProperties() {
    }

    public static Map<String, Object> create(String bootstrapServers,
                                              String groupId,
                                              String clientId,
                                              int maxPollRecords) {
        Map<String, Object> config = new HashMap<>();
        config.put("bootstrap.servers", bootstrapServers);
        config.put("group.id", groupId);
        config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        config.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        config.put("auto.offset.reset", "earliest");
        config.put("enable.auto.commit", false);
        if (clientId != null && !clientId.isBlank()) {
            config.put("client.id", clientId);
        }
        if (maxPollRecords > 0) {
            config.put("max.poll.records", maxPollRecords);
        }
        return config;
    }
}
