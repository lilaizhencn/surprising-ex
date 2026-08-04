package com.surprising.account.provider.service;

import com.surprising.account.api.cache.PositionSnapshotCache;
import com.surprising.account.provider.config.AccountProperties;
import java.util.Collection;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.stereotype.Component;

/**
 * 在持仓投影消费者完成分区分配时判断是否已经追平 Topic。
 *
 * <p>空 Topic 不会触发消息回调，若只在消息回调中标记就绪，刚启动且没有持仓的产品线会
 * 永远处于不可读状态。这里仅在每个分区的当前位置不落后于末端位点时标记就绪；仍有历史
 * 消息时继续由投影消费者逐条消费并在最后一条消息后标记。</p>
 */
@Component
public class PositionCacheRebalanceListener implements ConsumerAwareRebalanceListener {

    private final PositionSnapshotCache snapshotCache;
    private final RedisPositionCache redisCache;
    private final AccountProperties properties;

    public PositionCacheRebalanceListener(PositionSnapshotCache snapshotCache,
                                           RedisPositionCache redisCache,
                                           AccountProperties properties) {
        this.snapshotCache = snapshotCache;
        this.redisCache = redisCache;
        this.properties = properties;
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        snapshotCache.markNotReady();
        redisCache.markNotReady(properties.getKafka().getProductLine());
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        // 旧版 Kafka 回调没有 Consumer，实际判断由下面带 Consumer 的重载完成。
    }

    @Override
    public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        if (consumer == null || partitions == null || partitions.isEmpty()) {
            return;
        }
        // 分配回调中只能读取位点，不能跳过尚未消费的历史持仓事件。
        try {
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            for (TopicPartition partition : partitions) {
                if (consumer.position(partition) < endOffsets.getOrDefault(partition, 0L)) {
                    return;
                }
            }
            snapshotCache.markReady();
            redisCache.markReady(properties.getKafka().getProductLine());
        } catch (RuntimeException ignored) {
            // 读取位点失败时保持失败关闭，下一条消息或下一次重平衡会再次判断。
        }
    }
}
