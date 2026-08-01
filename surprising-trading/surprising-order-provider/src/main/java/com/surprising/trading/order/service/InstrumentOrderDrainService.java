package com.surprising.trading.order.service;

import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainComponent;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainEvent;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class InstrumentOrderDrainService {

    private static final int BATCH_SIZE = 1000;

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final OrderService orderService;
    private final AlgoOrderService algoOrderService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OrderInstrumentLifecycleFenceService lifecycleFenceService;

    public InstrumentOrderDrainService(ObjectMapper objectMapper,
                                       TradingOrderProperties properties,
                                       OrderService orderService,
                                       AlgoOrderService algoOrderService,
                                       KafkaTemplate<String, String> kafkaTemplate) {
        this(objectMapper, properties, orderService, algoOrderService, kafkaTemplate, null);
    }

    @Autowired
    public InstrumentOrderDrainService(ObjectMapper objectMapper,
                                       TradingOrderProperties properties,
                                       OrderService orderService,
                                       AlgoOrderService algoOrderService,
                                       KafkaTemplate<String, String> kafkaTemplate,
                                       OrderInstrumentLifecycleFenceService lifecycleFenceService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.orderService = orderService;
        this.algoOrderService = algoOrderService;
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
        algoOrderService.cancelLifecycleOrders(event.symbol(), BATCH_SIZE);
        orderService.requestLifecycleCancellation(event.symbol(), BATCH_SIZE);
        if (algoOrderService.hasLifecycleActiveOrders(event.symbol())
                || orderService.hasLifecycleActiveOrders(event.symbol())) {
            throw new IllegalStateException("订单尚未完成到期清理: " + event.symbol());
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
                    InstrumentLifecycleDrainComponent.ORDER,
                    Instant.now());
            String payload = objectMapper.writeValueAsString(ready);
            kafkaTemplate.send(properties.getKafka().getInstrumentLifecycleDrainTopic(),
                            event.symbol(), payload)
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("订单清理确认发布失败: " + event.symbol(), ex);
        }
    }
}
