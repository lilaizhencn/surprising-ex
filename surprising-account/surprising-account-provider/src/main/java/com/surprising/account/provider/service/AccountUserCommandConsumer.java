package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.eventstore.UserMutation;
import com.surprising.eventstore.UserMutationBatch;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.account.provider.config.AccountProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AccountUserCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(AccountUserCommandConsumer.class);

    private final ObjectMapper objectMapper;
    private final AccountUserCommandWalIngress walIngress;
    private final AccountUserStateCommandWorker stateWorker;
    private final AccountProperties properties;
    private final AccountCommandMetrics metrics;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Set<FailedRecord> failedRecords = ConcurrentHashMap.newKeySet();

    /** 生产入口只写同步 WAL；数据库审计投影由独立 worker 负责。 */
    public AccountUserCommandConsumer(ObjectMapper objectMapper,
                                      AccountUserCommandWalIngress walIngress,
                                      AccountUserStateCommandWorker stateWorker,
                                      AccountProperties properties,
                                      AccountCommandMetrics metrics) {
        this(objectMapper, walIngress, stateWorker, properties, metrics, null);
    }

    @Autowired
    public AccountUserCommandConsumer(ObjectMapper objectMapper,
                                      AccountUserCommandWalIngress walIngress,
                                      AccountUserStateCommandWorker stateWorker,
                                      AccountProperties properties,
                                      AccountCommandMetrics metrics,
                                      @Qualifier("accountKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this.objectMapper = objectMapper;
        this.walIngress = walIngress;
        this.stateWorker = stateWorker;
        this.properties = properties;
        this.metrics = metrics;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "accountUserCommandKafkaListenerContainerFactory")
    public void onCommands(List<ConsumerRecord<String, String>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        long startedAtNanos = System.nanoTime();
        List<ParsedRecord> parsed = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            try {
                parsed.add(new ParsedRecord(record, parseAndValidate(record)));
            } catch (AccountCommandPoisonPillException ex) {
                metrics.recordFailure(null, startedAtNanos);
                logFailureOnce(record, null, ex);
                throw new BatchListenerFailedException("invalid account user command", ex, record);
            }
        }
        try {
            UserMutationBatch mutationBatch = new UserMutationBatch(parsed.stream()
                    .map(value -> toUserMutation(value.command()))
                    .toList());
            for (UserMutation mutation : mutationBatch.mutations()) {
                publishMutation(mutation);
            }
            List<AccountUserCommandWalIngress.CommandEnvelope> envelopes = parsed.stream()
                    .map(value -> new AccountUserCommandWalIngress.CommandEnvelope(
                            value.command(), value.record().value()))
                    .toList();
            if (walIngress == null) {
                throw new IllegalStateException("账户 WAL 入口未配置，禁止回退数据库命令处理器");
            }
            if (stateWorker == null) {
                throw new IllegalStateException("账户状态执行器未配置，禁止只写 WAL 后提交 Kafka offset");
            }
            Map<UserPartitionKey, List<IndexedEnvelope>> envelopesByPartition =
                    new LinkedHashMap<>();
            for (int index = 0; index < envelopes.size(); index++) {
                AccountUserCommandWalIngress.CommandEnvelope envelope = envelopes.get(index);
                UserPartitionKey partition = new UserPartitionKey(envelope.command().productLine(),
                        envelope.command().userId());
                envelopesByPartition.computeIfAbsent(partition, ignored -> new ArrayList<>())
                        .add(new IndexedEnvelope(index, envelope));
            }
            List<AccountUserCommandWalIngress.AppendOutcome> outcomes = new ArrayList<>(parsed.size());
            for (int index = 0; index < parsed.size(); index++) {
                outcomes.add(null);
            }
            for (UserPartitionKey partition : mutationBatch.byPartition().keySet()) {
                List<IndexedEnvelope> partitionValues = envelopesByPartition.get(partition);
                if (partitionValues == null || partitionValues.isEmpty()) {
                    throw new IllegalStateException("account command partition batch is missing");
                }
                walIngress.runAsOwner(partition, () -> {
                    List<AccountUserCommandWalIngress.CommandEnvelope> partitionEnvelopes = partitionValues.stream()
                            .map(IndexedEnvelope::envelope)
                            .toList();
                    List<AccountUserCommandWalIngress.AppendOutcome> partitionOutcomes =
                            walIngress.append(partitionEnvelopes);
                    if (partitionOutcomes.size() != partitionEnvelopes.size()) {
                        throw new IllegalStateException("account command partition outcome size mismatch");
                    }
                    for (int localIndex = 0; localIndex < partitionValues.size(); localIndex++) {
                        outcomes.set(partitionValues.get(localIndex).index(), partitionOutcomes.get(localIndex));
                    }
                    stateWorker.applyPendingPartition(partition);
                    return null;
                });
            }
            if (outcomes.stream().anyMatch(outcome -> outcome == null)) {
                throw new IllegalStateException("account command batch outcome is incomplete");
            }
            for (int index = 0; index < parsed.size(); index++) {
                ParsedRecord value = parsed.get(index);
                failedRecords.remove(FailedRecord.from(value.record()));
                metrics.record(outcomes.get(index), value.command().occurredAt(), startedAtNanos);
            }
        } catch (RuntimeException ex) {
            for (ParsedRecord value : parsed) {
                metrics.recordFailure(value.command().occurredAt(), startedAtNanos);
                logFailureOnce(value.record(), value.command(), ex);
            }
            throw ex;
        }
    }

    private UserMutation toUserMutation(AccountUserCommand command) {
        return new UserMutation(UserMutation.CURRENT_SCHEMA_VERSION, command.commandId(), command.productLine(),
                command.userId(), command.commandType().name(), command.source(), command.sourceReference(),
                command.dependsOnCommandId(), command.payload(), command.occurredAt(), command.traceId());
    }

    private void publishMutation(UserMutation mutation) {
        if (kafkaTemplate == null) {
            return;
        }
        try {
            java.util.concurrent.CompletableFuture<?> send = kafkaTemplate.send(
                    properties.getKafka().getUserMutationsTopic(), mutation.partitionKey(),
                    objectMapper.writeValueAsString(mutation));
            if (!(kafkaTemplate.isTransactional() && kafkaTemplate.inTransaction())) {
                send.get(5, TimeUnit.SECONDS);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("用户 mutation canonical 入口发送失败: " + mutation.commandId(), ex);
        }
    }

    private AccountUserCommand parseAndValidate(ConsumerRecord<String, String> record) {
        AccountUserCommand command;
        try {
            command = objectMapper.readValue(record.value(), AccountUserCommand.class);
        } catch (Exception ex) {
            throw new AccountCommandPoisonPillException("invalid account user command envelope", ex);
        }
        if (!topic().equals(record.topic())) {
            throw new AccountCommandPoisonPillException("account command arrived on unexpected topic "
                    + record.topic());
        }
        if (command.productLine() != properties.getKafka().getProductLine()) {
            throw new AccountCommandPoisonPillException("account command product line does not match provider");
        }
        if (!command.partitionKey().equals(record.key())) {
            throw new AccountCommandPoisonPillException("account command Kafka key must be "
                    + command.partitionKey());
        }
        return command;
    }

    private void logFailureOnce(ConsumerRecord<String, String> record,
                                AccountUserCommand command,
                                RuntimeException ex) {
        if (failedRecords.add(FailedRecord.from(record))) {
            log.warn("Account command failed commandId={} commandType={} topic={} partition={} offset={}",
                    command == null ? null : command.commandId(),
                    command == null ? null : command.commandType(),
                    record.topic(), record.partition(), record.offset(), ex);
        }
    }

    public String topic() {
        return properties.getKafka().getUserCommandsTopic();
    }

    public String groupId() {
        return properties.getKafka().getUserCommandGroupId();
    }

    private record FailedRecord(String topic, int partition, long offset) {

        private static FailedRecord from(ConsumerRecord<?, ?> record) {
            return new FailedRecord(record.topic(), record.partition(), record.offset());
        }
    }

    private record ParsedRecord(ConsumerRecord<String, String> record, AccountUserCommand command) {
    }

    private record IndexedEnvelope(int index, AccountUserCommandWalIngress.CommandEnvelope envelope) {
    }
}
