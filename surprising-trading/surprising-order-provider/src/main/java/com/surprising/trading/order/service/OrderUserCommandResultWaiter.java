package com.surprising.trading.order.service;

import com.surprising.eventstore.UserPartitionKey;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderUserCommandResult;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 订单 HTTP 命令的 Kafka 结果等待器。
 *
 * <p>等待键同时包含用户分区和命令编号，防止不同用户复用命令编号时串读结果。超时只
 * 表示调用方没有在窗口内拿到终态，命令本身已经可靠写入用户命令 Topic，客户端可以用
 * 同一幂等键重试。</p>
 */
@Service
public class OrderUserCommandResultWaiter {

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final Map<CommandKey, WaitSlot> waiting = new ConcurrentHashMap<>();
    private final Map<CommandKey, OrderUserCommandResult> completed = new ConcurrentHashMap<>();

    public OrderUserCommandResultWaiter(ObjectMapper objectMapper,
                                         TradingOrderProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public OrderUserCommandResult await(UserPartitionKey partition, String commandId, Duration timeout) {
        if (partition == null || commandId == null || commandId.isBlank()
                || timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("订单用户命令等待参数无效");
        }
        CommandKey key = new CommandKey(partition, commandId);
        WaitSlot slot = waiting.compute(key, (ignored, existing) -> {
            WaitSlot selected = existing == null ? new WaitSlot() : existing;
            selected.waiterCount.incrementAndGet();
            return selected;
        });
        try {
            OrderUserCommandResult alreadyCompleted = completed.get(key);
            if (alreadyCompleted != null) {
                slot.result.complete(alreadyCompleted);
            }
            return slot.result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("订单用户命令已持久化但等待结果超时: " + key, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待订单用户命令时被中断: " + key, ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("等待订单用户命令结果失败: " + key, ex.getCause());
        } finally {
            if (slot.waiterCount.decrementAndGet() == 0) {
                waiting.remove(key, slot);
            }
        }
    }

    /** 结果 Topic 只做同步通知，不作为本地订单事实的来源。 */
    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "orderUserCommandResultKafkaListenerContainerFactory")
    public void onResult(ConsumerRecord<String, String> record) {
        try {
            OrderUserCommandResult result = objectMapper.readValue(record.value(), OrderUserCommandResult.class);
            if (result.productLine() != properties.getKafka().getProductLine()) {
                return;
            }
            String expectedKey = result.partitionKey();
            if (!expectedKey.equals(record.key())) {
                throw new IllegalArgumentException("订单用户命令结果 Kafka key 不匹配");
            }
            CommandKey key = new CommandKey(new UserPartitionKey(result.productLine(), result.userId()),
                    result.commandId());
            completed.putIfAbsent(key, result);
            WaitSlot slot = waiting.get(key);
            if (slot != null) {
                slot.result.complete(result);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("订单用户命令结果无法解析", ex);
        }
    }

    public String topic() {
        return properties.getKafka().getOrderUserCommandResultsTopic();
    }

    public String groupId() {
        return properties.getKafka().getOrderUserCommandResultsGroupId();
    }

    private static final class WaitSlot {
        private final CompletableFuture<OrderUserCommandResult> result = new CompletableFuture<>();
        private final AtomicInteger waiterCount = new AtomicInteger();
    }

    private record CommandKey(UserPartitionKey partition, String commandId) {
        private CommandKey {
            if (partition == null || commandId == null || commandId.isBlank()) {
                throw new IllegalArgumentException("订单用户命令等待键无效");
            }
        }

        @Override
        public String toString() {
            return partition.value() + ":" + commandId;
        }
    }
}
