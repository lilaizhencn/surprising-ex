package com.surprising.trading.order.service;

import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainComponent;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainEvent;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.trigger.service.TriggerOrderService;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** 合约进入 SETTLING 时清理订单并通过进程内 Spring 事件通知 InstrumentLifecycleReadinessService。 */
@Service
public class InstrumentOrderDrainService {

    private static final int BATCH_SIZE = 1000;

    private final TradingOrderProperties properties;
    private final OrderService orderService;
    private final AlgoOrderService algoOrderService;
    private final TriggerOrderService triggerOrderService;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public InstrumentOrderDrainService(TradingOrderProperties properties,
                                       OrderService orderService,
                                       AlgoOrderService algoOrderService,
                                       TriggerOrderService triggerOrderService,
                                       ApplicationEventPublisher eventPublisher) {
        this.properties = properties;
        this.orderService = orderService;
        this.algoOrderService = algoOrderService;
        this.triggerOrderService = triggerOrderService;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void onInstrumentEvent(InstrumentEvent event) {
        if (event != null) {
            drain(event);
        }
    }

    public void drain(InstrumentEvent event) {
        if (event.status() != InstrumentStatus.SETTLING
                || event.productLine() != properties.getProductLine()) {
            return;
        }
        String instrumentId = Integer.toString(event.instrumentId());
        algoOrderService.cancelLifecycleOrders(instrumentId, BATCH_SIZE);
        triggerOrderService.cancelLifecycleOrders(instrumentId, BATCH_SIZE);
        orderService.requestLifecycleCancellation(instrumentId, BATCH_SIZE);
        if (algoOrderService.hasLifecycleActiveOrders(instrumentId)
                || triggerOrderService.hasLifecycleActiveOrders(instrumentId)
                || orderService.hasLifecycleActiveOrders(instrumentId)) {
            throw new IllegalStateException("订单尚未完成到期清理: " + instrumentId);
        }
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new InstrumentLifecycleDrainEvent(
                    InstrumentLifecycleDrainEvent.CURRENT_SCHEMA_VERSION,
                    instrumentId,
                    event.changeId(),
                    properties.getProductLine(),
                    InstrumentLifecycleDrainComponent.ORDER,
                    Instant.now()));
        }
    }
}
