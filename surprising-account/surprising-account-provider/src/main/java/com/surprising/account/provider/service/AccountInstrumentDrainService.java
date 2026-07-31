package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.repository.AccountCommandRepository;
import com.surprising.account.provider.repository.TradeSettlementSideRepository;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainComponent;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainEvent;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AccountInstrumentDrainService {

    private static final int PAGE_SIZE = 500;

    private final ObjectMapper objectMapper;
    private final AccountProperties properties;
    private final AccountCommandRepository commandRepository;
    private final TradeSettlementSideRepository tradeSettlementSideRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public AccountInstrumentDrainService(ObjectMapper objectMapper,
                                         AccountProperties properties,
                                         AccountCommandRepository commandRepository,
                                         TradeSettlementSideRepository tradeSettlementSideRepository,
                                         KafkaTemplate<String, String> kafkaTemplate) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.commandRepository = commandRepository;
        this.tradeSettlementSideRepository = tradeSettlementSideRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    public void confirmReleased(InstrumentLifecycleDrainEvent orderReady) {
        if (orderReady.component() != InstrumentLifecycleDrainComponent.ORDER
                || orderReady.productLine() != properties.getKafka().getProductLine()) {
            return;
        }
        if (!allReservationsReleased(orderReady.symbol())) {
            throw new IllegalStateException("订单冻结资金尚未全部释放: " + orderReady.symbol());
        }
        publishReady(orderReady);
    }

    private boolean allReservationsReleased(String symbol) {
        var productLine = properties.getKafka().getProductLine();
        if (commandRepository.hasPendingOrderReservations(productLine, symbol)) {
            return false;
        }
        long afterOrderId = 0L;
        while (true) {
            var reservations = commandRepository.orderReservationSnapshots(
                    productLine, symbol, afterOrderId, PAGE_SIZE);
            if (reservations.isEmpty()) {
                return true;
            }
            var usage = tradeSettlementSideRepository.marginUsage(productLine,
                    reservations.stream().map(AccountCommandRepository.OrderReservationSnapshot::orderId).toList());
            for (var reservation : reservations) {
                TradeSettlementSideRepository.MarginUsage tradeUsage =
                        usage.getOrDefault(reservation.orderId(),
                                new TradeSettlementSideRepository.MarginUsage(0L, 0L));
                long unavailable = Math.addExact(reservation.releasedUnits(),
                        Math.addExact(tradeUsage.consumedUnits(), tradeUsage.releasedUnits()));
                if (unavailable < reservation.reservedUnits()) {
                    return false;
                }
                afterOrderId = reservation.orderId();
            }
            if (reservations.size() < PAGE_SIZE) {
                return true;
            }
        }
    }

    private void publishReady(InstrumentLifecycleDrainEvent orderReady) {
        try {
            InstrumentLifecycleDrainEvent ready = new InstrumentLifecycleDrainEvent(
                    InstrumentLifecycleDrainEvent.CURRENT_SCHEMA_VERSION,
                    orderReady.symbol(),
                    orderReady.instrumentVersion(),
                    orderReady.productLine(),
                    InstrumentLifecycleDrainComponent.ACCOUNT,
                    Instant.now());
            kafkaTemplate.send(properties.getKafka().getInstrumentLifecycleDrainTopic(),
                            ready.symbol(), objectMapper.writeValueAsString(ready))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("账户冻结资金清理确认发布失败: " + orderReady.symbol(), ex);
        }
    }
}
