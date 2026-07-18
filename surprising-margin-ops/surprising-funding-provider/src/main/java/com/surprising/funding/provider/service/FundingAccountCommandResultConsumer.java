package com.surprising.funding.provider.service;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.funding.provider.config.FundingProperties;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class FundingAccountCommandResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(FundingAccountCommandResultConsumer.class);

    private final ObjectMapper objectMapper;
    private final FundingProperties properties;
    private final FundingAccountCommandResultService resultService;

    public FundingAccountCommandResultConsumer(ObjectMapper objectMapper,
                                               FundingProperties properties,
                                               FundingAccountCommandResultService resultService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.resultService = resultService;
    }

    @KafkaListener(topics = "#{__listener.topic()}", groupId = "#{__listener.groupId()}",
            containerFactory = "fundingCommandResultsKafkaListenerContainerFactory")
    public void onResult(List<ConsumerRecord<String, String>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<AccountCommandResultEvent> events = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            AccountCommandResultEvent event;
            try {
                event = objectMapper.readValue(record.value(), AccountCommandResultEvent.class);
            } catch (Exception ex) {
                // The database reconciler is authoritative, so a poison notification does not block later results.
                log.error("Ignoring malformed account result topic={} partition={} offset={}: {}",
                        record.topic(), record.partition(), record.offset(), ex.getMessage(), ex);
                continue;
            }
            if (event.productLine() != properties.getKafka().getProductLine()) {
                continue;
            }
            String expectedKey = AccountUserCommand.partitionKey(event.productLine(), event.userId());
            if (!expectedKey.equals(record.key())) {
                log.error("Ignoring account result with invalid key commandId={} expected={} actual={}",
                        event.commandId(), expectedKey, record.key());
                continue;
            }
            events.add(event);
        }
        resultService.applyBatch(events);
    }

    public String topic() {
        return properties.getKafka().getCommandResultsTopic();
    }

    public String groupId() {
        return properties.getKafka().getCommandResultsGroupId();
    }
}
