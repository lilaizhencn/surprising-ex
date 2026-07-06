package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.instrument.api.model.DeliverySettlementEvent;
import com.surprising.instrument.api.model.OptionExerciseEvent;
import com.surprising.trading.api.KafkaSymbolKeyValidator;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class ExpiringContractSettlementConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExpiringContractSettlementConsumer.class);

    private final ObjectMapper objectMapper;
    private final AccountService accountService;
    private final AccountProperties properties;

    public ExpiringContractSettlementConsumer(ObjectMapper objectMapper, AccountService accountService) {
        this(objectMapper, accountService, new AccountProperties());
    }

    @Autowired
    public ExpiringContractSettlementConsumer(ObjectMapper objectMapper,
                                             AccountService accountService,
                                             AccountProperties properties) {
        this.objectMapper = objectMapper;
        this.accountService = accountService;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "#{__listener.deliverySettlementsTopic()}",
            groupId = "#{__listener.groupId()}",
            autoStartup = "#{__listener.deliverySettlementsListenerEnabled()}",
            containerFactory = "accountKafkaListenerContainerFactory")
    public void onDeliverySettlements(List<ConsumerRecord<String, String>> records) {
        for (ConsumerRecord<String, String> record : records) {
            onDeliverySettlement(record);
        }
    }

    public void onDeliverySettlement(ConsumerRecord<String, String> record) {
        try {
            DeliverySettlementEvent event = objectMapper.readValue(record.value(), DeliverySettlementEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "delivery settlement");
            int settled = accountService.processDeliverySettlement(event);
            log.info("Processed delivery settlement symbol={} version={} positions={}",
                    event.symbol(), event.version(), settled);
        } catch (Exception ex) {
            log.error("Failed to process delivery settlement: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to process delivery settlement", ex);
        }
    }

    @KafkaListener(
            topics = "#{__listener.optionExercisesTopic()}",
            groupId = "#{__listener.groupId()}",
            autoStartup = "#{__listener.optionExercisesListenerEnabled()}",
            containerFactory = "accountKafkaListenerContainerFactory")
    public void onOptionExercises(List<ConsumerRecord<String, String>> records) {
        for (ConsumerRecord<String, String> record : records) {
            onOptionExercise(record);
        }
    }

    public void onOptionExercise(ConsumerRecord<String, String> record) {
        try {
            OptionExerciseEvent event = objectMapper.readValue(record.value(), OptionExerciseEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "option exercise");
            int settled = accountService.processOptionExercise(event);
            log.info("Processed option exercise symbol={} version={} positions={}",
                    event.symbol(), event.version(), settled);
        } catch (Exception ex) {
            log.error("Failed to process option exercise: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to process option exercise", ex);
        }
    }

    public String deliverySettlementsTopic() {
        return properties.getKafka().getDeliverySettlementsTopic();
    }

    public boolean deliverySettlementsListenerEnabled() {
        return properties.getKafka().isDeliverySettlementsTopicEnabled();
    }

    public String optionExercisesTopic() {
        return properties.getKafka().getOptionExercisesTopic();
    }

    public boolean optionExercisesListenerEnabled() {
        return properties.getKafka().isOptionExercisesTopicEnabled();
    }

    public String groupId() {
        return properties.getKafka().getGroupId();
    }
}
