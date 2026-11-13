package com.surprising.instrument.provider.service;

import com.surprising.account.provider.service.ExpiringContractSettlementFanoutService;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.instrument.api.model.DeliverySettlementEvent;
import com.surprising.instrument.api.model.OptionExerciseEvent;
import com.surprising.instrument.provider.config.InstrumentProperties;
import com.surprising.instrument.provider.model.InstrumentOutboxRecord;
import com.surprising.instrument.provider.repository.InstrumentOutboxRepository;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
public class InstrumentOutboxPublisher {

    private static final Duration MINIMUM_LEASE = Duration.ofSeconds(30);

    @FunctionalInterface
    interface SettlementDispatcher {
        void dispatch(ProductLine productLine, Object event);
    }

    private final InstrumentProperties properties;
    private final InstrumentOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final SettlementDispatcher dispatcher;
    private final AtomicBoolean publishing = new AtomicBoolean(false);

    @Autowired
    public InstrumentOutboxPublisher(InstrumentProperties properties,
                                     InstrumentOutboxRepository outboxRepository,
                                     ObjectMapper objectMapper,
                                     ObjectProvider<GatewayProductServices> productServices) {
        this(properties, outboxRepository, objectMapper, (line, event) -> {
            var services = productServices.getIfAvailable();
            if (services == null) {
                throw new IllegalStateException("GatewayProductServices unavailable");
            }
            var fanout = services.service(line, ExpiringContractSettlementFanoutService.class);
            if (event instanceof DeliverySettlementEvent delivery) {
                fanout.fanout(delivery);
            } else if (event instanceof OptionExerciseEvent option) {
                fanout.fanout(option);
            } else {
                throw new IllegalArgumentException("unsupported settlement event: " + event);
            }
        });
    }

    InstrumentOutboxPublisher(InstrumentProperties properties,
                              InstrumentOutboxRepository outboxRepository,
                              ObjectMapper objectMapper,
                              SettlementDispatcher dispatcher) {
        this.properties = properties;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.dispatcher = dispatcher;
    }

    public void publishPending() {
        if (!publishing.compareAndSet(false, true)) {
            return;
        }
        try {
            Instant now = Instant.now();
            Duration timeout = properties.getOutbox().getSendTimeout();
            Duration lease = timeout.plusSeconds(5);
            if (lease.compareTo(MINIMUM_LEASE) < 0) {
                lease = MINIMUM_LEASE;
            }
            var rows = outboxRepository.claimPending(
                    Math.max(1, properties.getOutbox().getBatchSize()), now.plus(lease), now);
            for (InstrumentOutboxRecord row : rows) {
                publish(row);
            }
        } finally {
            publishing.set(false);
        }
    }

    public void cleanupPublished() {
        int batchSize = Math.max(1, properties.getOutbox().getCleanupBatchSize());
        Instant cutoff = Instant.now().minus(properties.getOutbox().getRetention());
        int total = 0;
        for (int index = 0; index < Math.max(1, properties.getOutbox().getCleanupMaxBatches()); index++) {
            int deleted = outboxRepository.deletePublishedBefore(cutoff, batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("已清理 {} 条 instrument outbox 历史事件", total);
        }
    }

    private void publish(InstrumentOutboxRecord row) {
        try {
            switch (row.eventType()) {
                case "DELIVERY_SETTLEMENT" -> {
                    DeliverySettlementEvent event = objectMapper.readValue(row.payload(), DeliverySettlementEvent.class);
                    dispatcher.dispatch(event.contractType().productLine(), event);
                }
                case "OPTION_EXERCISE" -> {
                    OptionExerciseEvent event = objectMapper.readValue(row.payload(), OptionExerciseEvent.class);
                    dispatcher.dispatch(ProductLine.OPTION, event);
                }
                default -> {
                    // 历史非结算生命周期事件已改由同进程 ApplicationEvent 分发，直接标记完成。
                }
            }
            outboxRepository.markPublished(row.id(), Instant.now());
        } catch (Exception ex) {
            log.warn("instrument outbox 结算派发失败 id={} eventType={}: {}",
                    row.id(), row.eventType(), ex.getMessage());
            outboxRepository.markFailed(row.id(), ex.getMessage(), Instant.now());
        }
    }
}
