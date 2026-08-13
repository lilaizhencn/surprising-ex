package com.surprising.trading.order.service;

import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.trading.order.config.TradingOrderProperties;
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
 * 订单服务消费完整永续账户状态，用于持仓模式等下单前不可变读数据。
 *
 * <p>账户余额和冻结仍由账户单写者异步确认；快照未就绪、Kafka key 错误或修订间隙时直接拒单，
 * 不再通过订单服务回查账户数据库。</p>
 */
@Service
public class OrderAccountStateSnapshotConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderAccountStateSnapshotConsumer.class);

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final PerpetualAccountStateSnapshotCache snapshotCache;
    private final OrderMarginSnapshotCache marginSnapshotCache;

    public OrderAccountStateSnapshotConsumer(ObjectMapper objectMapper,
                                             TradingOrderProperties properties,
                                             PerpetualAccountStateSnapshotCache snapshotCache,
                                             OrderMarginSnapshotCache marginSnapshotCache) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
        this.marginSnapshotCache = marginSnapshotCache;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "orderStateKafkaListenerContainerFactory")
    public void onAccountStateUpdated(List<ConsumerRecord<String, String>> records,
                                      Consumer<String, String> consumer) {
        if (records == null || records.isEmpty()) {
            return;
        }
        try {
            for (ConsumerRecord<String, String> record : records) {
                if (!topic().equals(record.topic())) {
                    throw new IllegalArgumentException("完整账户快照 Topic 不属于订单服务");
                }
                PerpetualAccountStateUpdatedEvent event = objectMapper.readValue(
                        record.value(), PerpetualAccountStateUpdatedEvent.class);
                if (event.productLine() != properties.getKafka().getProductLine()) {
                    throw new IllegalArgumentException("完整账户快照产品线与订单服务不一致");
                }
                if (!event.partitionKey().equals(record.key())) {
                    throw new IllegalArgumentException("完整账户快照 Kafka key 必须为 " + event.partitionKey());
                }
                PerpetualAccountStateSnapshotCache.ApplyResult result = snapshotCache.apply(event);
                if (result == PerpetualAccountStateSnapshotCache.ApplyResult.REVISION_GAP) {
                    throw new IllegalStateException("完整账户快照修订号存在间隙，等待缺失事件");
                }
                if (result == PerpetualAccountStateSnapshotCache.ApplyResult.PRODUCT_LINE_MISMATCH) {
                    throw new IllegalArgumentException("完整账户快照产品线与订单服务不一致");
                }
                if (result == PerpetualAccountStateSnapshotCache.ApplyResult.CONFLICT) {
                    throw new IllegalStateException("完整账户快照同一修订号内容冲突，等待 RPC 重建");
                }
                if (result == PerpetualAccountStateSnapshotCache.ApplyResult.APPLIED
                        || result == PerpetualAccountStateSnapshotCache.ApplyResult.STALE) {
                    marginSnapshotCache.applyAccountSnapshot(event);
                }
            }
            markReadyWhenCaughtUp(consumer);
        } catch (Exception ex) {
            log.error("订单服务完整账户 JVM 快照消费失败，批次将重试，records={}: {}",
                    records.size(), ex.getMessage(), ex);
            throw new IllegalStateException("订单服务完整账户 JVM 快照消费失败", ex);
        }
    }

    /** 保留测试调用签名；没有 Kafka 位点时不能擅自标记全局就绪。 */
    public void onAccountStateUpdated(List<ConsumerRecord<String, String>> records) {
        onAccountStateUpdated(records, null);
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
            long endOffset = endOffsets.getOrDefault(partition, 0L);
            long visibleEndOffset = endOffset == 0L ? 0L : endOffset - 1L;
            if (consumer.position(partition) < visibleEndOffset) {
                return;
            }
        }
        snapshotCache.markReady();
        marginSnapshotCache.markAccountSnapshotReady(properties.getKafka().getProductLine());
    }
}
