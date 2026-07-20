package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.provider.config.AccountProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AccountUserCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(AccountUserCommandConsumer.class);

    private final ObjectMapper objectMapper;
    private final AccountUserCommandProcessor processor;
    private final AccountProperties properties;
    private final AccountCommandMetrics metrics;
    private final Set<FailedRecord> failedRecords = ConcurrentHashMap.newKeySet();

    public AccountUserCommandConsumer(ObjectMapper objectMapper,
                                      AccountUserCommandProcessor processor,
                                      AccountProperties properties,
                                      AccountCommandMetrics metrics) {
        this.objectMapper = objectMapper;
        this.processor = processor;
        this.properties = properties;
        this.metrics = metrics;
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
            List<AccountUserCommandProcessor.ProcessingOutcome> outcomes = processor.processBatch(parsed.stream()
                    .map(value -> new AccountUserCommandProcessor.CommandEnvelope(
                            value.command(), value.record().value()))
                    .toList());
            if (outcomes.size() != parsed.size()) {
                throw new IllegalStateException("account command batch outcome size mismatch");
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
}
