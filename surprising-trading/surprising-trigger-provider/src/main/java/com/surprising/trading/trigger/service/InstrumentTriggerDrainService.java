package com.surprising.trading.trigger.service;

import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainComponent;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainEvent;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.trading.trigger.config.TriggerProperties;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class InstrumentTriggerDrainService {

    private static final int BATCH_SIZE = 1000;

    private final ObjectMapper objectMapper;
    private final TriggerProperties properties;
    private final TriggerOrderService triggerOrderService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TriggerInstrumentLifecycleFenceService lifecycleFenceService;

    public InstrumentTriggerDrainService(ObjectMapper objectMapper,
                                         TriggerProperties properties,
                                         TriggerOrderService triggerOrderService,
                                         KafkaTemplate<String, String> kafkaTemplate) {
        this(objectMapper, properties, triggerOrderService, kafkaTemplate, null);
    }

    @Autowired
    public InstrumentTriggerDrainService(ObjectMapper objectMapper,
                                         TriggerProperties properties,
                                         TriggerOrderService triggerOrderService,
                                         @Qualifier("triggerKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
                                         TriggerInstrumentLifecycleFenceService lifecycleFenceService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.triggerOrderService = triggerOrderService;
        this.kafkaTemplate = kafkaTemplate;
        this.lifecycleFenceService = lifecycleFenceService;
    }

    public void drain(InstrumentEvent event) {
        if (event.status() != InstrumentStatus.SETTLING
                || event.productLine() != properties.getKafka().getProductLine()) {
            return;
        }
        if (lifecycleFenceService != null) {
            lifecycleFenceService.blockForSettlement(
                    properties.getKafka().getProductLine(), event.symbol(), event.version());
        }
        triggerOrderService.cancelLifecycleOrders(event.symbol(), BATCH_SIZE);
        if (triggerOrderService.hasLifecycleActiveOrders(event.symbol())) {
            throw new IllegalStateException("触发单尚未完成到期清理: " + event.symbol());
        }
        publishReady(event);
    }

    private void publishReady(InstrumentEvent event) {
        try {
            InstrumentLifecycleDrainEvent ready = new InstrumentLifecycleDrainEvent(
                    InstrumentLifecycleDrainEvent.CURRENT_SCHEMA_VERSION,
                    event.symbol(),
                    event.version(),
                    properties.getKafka().getProductLine(),
                    InstrumentLifecycleDrainComponent.TRIGGER,
                    Instant.now());
            kafkaTemplate.send(properties.getKafka().getInstrumentLifecycleDrainTopic(),
                            event.symbol(), objectMapper.writeValueAsString(ready))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("触发单清理确认发布失败: " + event.symbol(), ex);
        }
    }
}
