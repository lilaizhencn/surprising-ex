package com.surprising.funding.provider.service;

import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.funding.provider.config.FundingProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 资金费模块的账户状态快照消费者。
 *
 * <p>资金费候选持仓只来自账户用户分区发布的完整快照。启动追赶完成前缓存保持未就绪，
 * 修订间隙或同修订冲突会暂停消费，不能用数据库持仓查询补洞。</p>
 */
@Service
public class FundingAccountStateSnapshotConsumer {

    private static final Logger log = LoggerFactory.getLogger(FundingAccountStateSnapshotConsumer.class);

    private final ObjectMapper objectMapper;
    private final FundingProperties properties;
    private final PerpetualAccountStateSnapshotCache snapshotCache;

    public FundingAccountStateSnapshotConsumer(ObjectMapper objectMapper,
                                               FundingProperties properties,
                                               @org.springframework.beans.factory.annotation.Qualifier(
                                                       "fundingAccountStateSnapshotCache")
                                               PerpetualAccountStateSnapshotCache snapshotCache) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    @KafkaListener(topics = "#{__listener.topic()}", groupId = "#{__listener.groupId()}",
            containerFactory = "fundingAccountStateSnapshotKafkaListenerContainerFactory")
    public void onAccountState(List<ConsumerRecord<String, String>> records,
                               Consumer<String, String> consumer) {
        if (records == null || records.isEmpty()) {
            return;
        }
        try {
            for (ConsumerRecord<String, String> record : records) {
                if (!topic().equals(record.topic())) {
                    throw new IllegalArgumentException("资金费账户快照 Topic 不正确");
                }
                PerpetualAccountStateUpdatedEvent event = objectMapper.readValue(
                        record.value(), PerpetualAccountStateUpdatedEvent.class);
                if (event.productLine() != properties.getKafka().getProductLine()) {
                    throw new IllegalArgumentException("资金费账户快照产品线不一致");
                }
                if (!event.partitionKey().equals(record.key())) {
                    throw new IllegalArgumentException("资金费账户快照 Kafka key 必须为 " + event.partitionKey());
                }
                PerpetualAccountStateSnapshotCache.ApplyResult result = snapshotCache.apply(event);
                if (result == PerpetualAccountStateSnapshotCache.ApplyResult.REVISION_GAP) {
                    throw new IllegalStateException("资金费账户快照修订号存在间隙");
                }
                if (result == PerpetualAccountStateSnapshotCache.ApplyResult.CONFLICT) {
                    throw new IllegalStateException("资金费账户快照同一修订号内容冲突");
                }
                if (result == PerpetualAccountStateSnapshotCache.ApplyResult.PRODUCT_LINE_MISMATCH) {
                    throw new IllegalArgumentException("资金费账户快照产品线不一致");
                }
            }
            markReadyWhenCaughtUp(consumer);
        } catch (Exception ex) {
            log.error("资金费账户 JVM 快照消费失败，批次将重试 records={}: {}", records.size(), ex.getMessage(), ex);
            throw new IllegalStateException("资金费账户 JVM 快照消费失败", ex);
        }
    }

    /** 保留测试调用签名；没有 Kafka 位点时不能擅自标记全局就绪。 */
    public void onAccountState(List<ConsumerRecord<String, String>> records) {
        onAccountState(records, null);
    }

    public String topic() {
        return properties.getKafka().getAccountStateEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getAccountStateSnapshotGroupId();
    }

    private void markReadyWhenCaughtUp(Consumer<String, String> consumer) {
        if (consumer == null) {
            return;
        }
        Set<TopicPartition> assignment = consumer.assignment();
        if (assignment.isEmpty()) {
            return;
        }
        Map<TopicPartition, Long> endOffsets = consumer.endOffsets(assignment);
        for (TopicPartition partition : assignment) {
            if (consumer.position(partition) < endOffsets.getOrDefault(partition, 0L)) {
                return;
            }
        }
        snapshotCache.markReady();
    }
}
