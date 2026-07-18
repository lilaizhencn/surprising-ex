package com.surprising.funding.provider.service;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.funding.provider.config.FundingProperties;
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
    public void onResult(ConsumerRecord<String, String> record) {
        AccountCommandResultEvent event;
        try {
            event = objectMapper.readValue(record.value(), AccountCommandResultEvent.class);
        } catch (Exception ex) {
            // The database reconciler is authoritative, so poison result notifications must not
            // stop this group from consuming later valid results.
            log.error("Ignoring malformed account result topic={} partition={} offset={}: {}",
                    record.topic(), record.partition(), record.offset(), ex.getMessage(), ex);
            return;
        }
        if (event.productLine() != properties.getKafka().getProductLine()) {
            return;
        }
        String expectedKey = AccountUserCommand.partitionKey(event.productLine(), event.userId());
        if (!expectedKey.equals(record.key())) {
            log.error("Ignoring account result with invalid key commandId={} expected={} actual={}",
                    event.commandId(), expectedKey, record.key());
            return;
        }
        resultService.apply(event);
    }

    public String topic() {
        return properties.getKafka().getCommandResultsTopic();
    }

    public String groupId() {
        return properties.getKafka().getCommandResultsGroupId();
    }
}
