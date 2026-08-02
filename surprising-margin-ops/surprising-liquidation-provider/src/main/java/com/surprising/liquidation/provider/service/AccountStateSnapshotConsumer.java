package com.surprising.liquidation.provider.service;

import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.liquidation.provider.config.LiquidationProperties;
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
 * 强平服务消费完整永续账户快照，先建立影子 JVM 投影。
 *
 * <p>完整快照就绪前强平仍使用现有持仓事件、候选锁和数据库最终校验；这里不允许回查账户库，
 * 也不允许丢弃版本间隙，否则重启后可能把旧持仓当成当前持仓执行强平。</p>
 */
@Service("liquidationAccountStateSnapshotConsumer")
@ConditionalOnExpression("'${surprising.liquidation.kafka.product-line:LINEAR_PERPETUAL}' == 'LINEAR_PERPETUAL'")
public class AccountStateSnapshotConsumer {

    private static final Logger log = LoggerFactory.getLogger(AccountStateSnapshotConsumer.class);

    private final ObjectMapper objectMapper;
    private final LiquidationProperties properties;
    private final PerpetualAccountStateSnapshotCache snapshotCache;

    public AccountStateSnapshotConsumer(ObjectMapper objectMapper,
                                       LiquidationProperties properties,
                                       @Qualifier("liquidationAccountStateSnapshot")
                                       PerpetualAccountStateSnapshotCache snapshotCache) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "liquidationAccountStateSnapshotKafkaListenerContainerFactory")
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
                    throw new IllegalArgumentException("完整账户快照产品线与强平服务不一致");
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
            log.error("强平服务完整账户 JVM 快照消费失败，批次将重试，records={}: {}",
                    records.size(), ex.getMessage(), ex);
            throw new IllegalStateException("强平服务完整账户 JVM 快照消费失败", ex);
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
        return properties.getKafka().getAccountStateSnapshotGroupId();
    }

    private void requireCurrentTopic(String topic) {
        if (properties.getKafka().isProductTopicsEnabled() && !topic().equals(topic)) {
            throw new IllegalArgumentException("完整账户快照 Topic 不属于当前产品线: expected="
                    + topic() + " actual=" + topic);
        }
    }

    private void requirePartitionKey(String key, PerpetualAccountStateUpdatedEvent event) {
        if (event.productLine() != properties.getKafka().getProductLine()) {
            throw new IllegalArgumentException("完整账户快照产品线与强平服务不一致");
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
