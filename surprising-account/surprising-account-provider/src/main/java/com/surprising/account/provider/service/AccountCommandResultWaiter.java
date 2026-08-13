package com.surprising.account.provider.service;

import com.surprising.account.provider.model.AccountCommandTerminalResult;
import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.eventstore.BoundedExpiringCache;
import com.surprising.eventstore.UserPartitionResultStore;
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
 * 等待同步 HTTP 命令结果，不为每次请求和轮询间隔查询数据库；Kafka 结果 Topic 不是正确性前提。
 */
@Service
    public class AccountCommandResultWaiter {

    private final ObjectMapper objectMapper;
    private final AccountProperties properties;
    private final UserPartitionResultStore resultStore;
    private final Map<CommandKey, WaitSlot> waiting = new ConcurrentHashMap<>();
    private final BoundedExpiringCache<CommandKey, AccountCommandTerminalResult> completed;

    public AccountCommandResultWaiter(ObjectMapper objectMapper,
                                      AccountProperties properties) {
        this(objectMapper, properties, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AccountCommandResultWaiter(ObjectMapper objectMapper,
                                      AccountProperties properties,
                                      UserPartitionResultStore resultStore) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.resultStore = resultStore;
        this.completed = new BoundedExpiringCache<>(properties.getCommandWait().getCompletedCacheTtl(),
                properties.getCommandWait().getCompletedCacheMaxEntries());
    }

    public AccountCommandTerminalResult await(UserPartitionKey partition, String commandId, Duration timeout) {
        if (partition == null || commandId == null || commandId.isBlank() || timeout == null
                || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("账户命令等待参数无效");
        }
        CommandKey key = new CommandKey(partition, commandId);
        WaitSlot slot = waiting.compute(key, (ignored, existing) -> {
            WaitSlot selected = existing == null ? new WaitSlot() : existing;
            selected.waiterCount.incrementAndGet();
            return selected;
        });
        try {
            AccountCommandTerminalResult alreadyCompleted = completed.get(key);
            if (alreadyCompleted != null) {
                slot.result.complete(alreadyCompleted);
            }
            AccountCommandTerminalResult persisted = readPersistedResult(key);
            if (persisted != null) {
                completed.putIfAbsent(key, persisted);
                slot.result.complete(persisted);
            }
            return slot.result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new AccountCommandTimeoutException(
                    "account command is durable but did not finish before timeout: " + key);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AccountCommandTimeoutException(
                    "interrupted while waiting for account command " + key);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("failed while waiting for account command " + key,
                    ex.getCause());
        } finally {
            if (slot.waiterCount.decrementAndGet() == 0) {
                waiting.remove(key, slot);
            }
        }
    }

    private AccountCommandTerminalResult readPersistedResult(CommandKey key) {
        if (resultStore == null) {
            return null;
        }
        return resultStore.read(key.partition(), key.commandId()).map(bytes -> {
            try {
                return objectMapper.readValue(new String(bytes, java.nio.charset.StandardCharsets.UTF_8),
                        AccountCommandTerminalResult.class);
            } catch (Exception ex) {
                throw new IllegalStateException("账户命令结果损坏 commandId=" + key.commandId(), ex);
            }
        }).orElse(null);
    }

    /** 结果主题是唯一结果入口，数据库不再参与同步等待。 */
    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "accountStateProjectionKafkaListenerContainerFactory")
    public void onResult(ConsumerRecord<String, String> record) {
        try {
            AccountCommandResultEvent event = objectMapper.readValue(record.value(),
                    AccountCommandResultEvent.class);
            if (event.productLine() != properties.getKafka().getProductLine()) {
                return;
            }
            if (!event.productLine().name().equals(record.key().split(":", 2)[0])
                    || event.userId() != Long.parseLong(record.key().split(":", 2)[1])) {
                throw new IllegalArgumentException("账户命令结果 Kafka key 不匹配");
            }
            AccountCommandTerminalResult result = new AccountCommandTerminalResult(
                    event.status(), event.resultPayload(), event.errorCode(), event.errorMessage());
            CommandKey key = new CommandKey(new UserPartitionKey(event.productLine(), event.userId()),
                    event.commandId());
            completed.putIfAbsent(key, result);
            WaitSlot slot = waiting.get(key);
            if (slot != null) {
                slot.result.complete(result);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("账户命令结果无法解析", ex);
        }
    }

    public String topic() {
        return properties.getKafka().getCommandResultsTopic();
    }

    /** 使用独立消费组广播到当前 API 实例，client-id 必须按实例唯一。 */
    public String groupId() {
        return properties.getKafka().getUserCommandGroupId() + "-result-" + properties.getKafka().getClientId();
    }

    /** 维护任务入口；结果由 Kafka listener 实时完成，不再轮询数据库。 */
    public void completeTerminalCommands() {
        completed.cleanup();
        waiting.forEach((key, slot) -> {
            AccountCommandTerminalResult result = completed.get(key);
            if (result != null) {
                slot.result.complete(result);
            }
        });
    }

    private static final class WaitSlot {
        private final CompletableFuture<AccountCommandTerminalResult> result = new CompletableFuture<>();
        private final AtomicInteger waiterCount = new AtomicInteger();
    }

    private record CommandKey(UserPartitionKey partition, String commandId) {
        private CommandKey {
            if (partition == null || commandId == null || commandId.isBlank()) {
                throw new IllegalArgumentException("账户命令等待键无效");
            }
        }

        @Override
        public String toString() {
            return partition.value() + ":" + commandId;
        }
    }
}
