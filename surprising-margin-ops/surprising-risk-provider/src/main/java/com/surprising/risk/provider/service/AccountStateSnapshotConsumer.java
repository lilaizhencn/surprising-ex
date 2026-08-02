package com.surprising.risk.provider.service;

import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.risk.provider.config.RiskProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import tools.jackson.databind.ObjectMapper;

/**
 * 消费账户单写者发布的完整永续账户快照。
 *
 * <p>这是风险服务的影子投影。事件解析、产品线、Kafka key 和账户修订号任一不满足要求
 * 都必须抛错让 Kafka 重试，不能回查账户数据库，也不能把缺失事件当成零余额。</p>
 */
@Service("riskAccountStateSnapshotConsumer")
@ConditionalOnExpression("'${surprising.risk.kafka.product-line:LINEAR_PERPETUAL}' == 'LINEAR_PERPETUAL'")
public class AccountStateSnapshotConsumer {

    private static final Logger log = LoggerFactory.getLogger(AccountStateSnapshotConsumer.class);

    private final ObjectMapper objectMapper;
    private final RiskProperties properties;
    private final PerpetualAccountStateSnapshotCache snapshotCache;

    public AccountStateSnapshotConsumer(ObjectMapper objectMapper,
                                       RiskProperties properties,
                                       @Qualifier("riskAccountStateSnapshot")
                                       PerpetualAccountStateSnapshotCache snapshotCache) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "riskAccountWalletKafkaListenerContainerFactory")
    public void onAccountStateUpdated(List<ConsumerRecord<String, String>> records,
                                      Consumer<String, String> consumer) {
        if (records == null || records.isEmpty()) {
            return;
        }
        try {
            for (ConsumerRecord<String, String> record : records) {
                requireCurrentTopic(record.topic());
                PerpetualAccountStateUpdatedEvent event = objectMapper.readValue(
                        record.value(), PerpetualAccountStateUpdatedEvent.class);
                requirePartitionKey(record.key(), event);
                PerpetualAccountStateSnapshotCache.ApplyResult result = snapshotCache.apply(event);
                if (result == PerpetualAccountStateSnapshotCache.ApplyResult.PRODUCT_LINE_MISMATCH) {
                    throw new IllegalArgumentException("完整账户快照产品线与风险服务不一致");
                }
                if (result == PerpetualAccountStateSnapshotCache.ApplyResult.REVISION_GAP) {
                    throw new IllegalStateException("完整账户快照修订号存在间隙，等待缺失事件: userId="
                            + event.userId() + " revision=" + event.accountRevision());
                }
                if (result == PerpetualAccountStateSnapshotCache.ApplyResult.CONFLICT) {
                    throw new IllegalStateException("完整账户快照同一修订号内容冲突，等待 RPC 重建: userId="
                            + event.userId() + " revision=" + event.accountRevision());
                }
            }
            markReadyWhenCaughtUp(consumer);
        } catch (Exception ex) {
            log.error("风险服务完整账户 JVM 快照消费失败，批次将重试，records={}: {}",
                    records.size(), ex.getMessage(), ex);
            throw new IllegalStateException("风险服务完整账户 JVM 快照消费失败", ex);
        }
    }

    /** 保留测试和本地调用签名；没有 Kafka 位点时不擅自标记全局就绪。 */
    public void onAccountStateUpdated(List<ConsumerRecord<String, String>> records) {
        onAccountStateUpdated(records, null);
    }

    public String topic() {
        return properties.getKafka().getAccountStateEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getAccountStateGroupId();
    }

    private void requireCurrentTopic(String topic) {
        if (properties.getKafka().isProductTopicsEnabled() && !topic().equals(topic)) {
            throw new IllegalArgumentException("完整账户快照 Topic 不属于当前产品线: expected="
                    + topic() + " actual=" + topic);
        }
    }

    private void requirePartitionKey(String key, PerpetualAccountStateUpdatedEvent event) {
        if (event.productLine() != properties.getKafka().getProductLine()) {
            throw new IllegalArgumentException("完整账户快照产品线与风险服务不一致");
        }
        if (!event.partitionKey().equals(key)) {
            throw new IllegalArgumentException("完整账户快照 Kafka key 必须为 " + event.partitionKey());
        }
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
