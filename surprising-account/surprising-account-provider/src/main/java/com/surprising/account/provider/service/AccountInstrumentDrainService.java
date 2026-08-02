package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.eventstore.UserPartitionStateStore;
import com.surprising.eventstore.UserPartitionWal;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainComponent;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainEvent;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AccountInstrumentDrainService {

    private final ObjectMapper objectMapper;
    private final AccountProperties properties;
    private final UserPartitionStateStore stateStore;
    private final UserPartitionWal wal;
    private final AccountUserStateReducer reducer;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public AccountInstrumentDrainService(ObjectMapper objectMapper,
                                         AccountProperties properties,
                                         UserPartitionStateStore stateStore,
                                         UserPartitionWal wal,
                                         AccountUserStateReducer reducer,
                                         KafkaTemplate<String, String> kafkaTemplate) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.stateStore = stateStore;
        this.wal = wal;
        this.reducer = reducer;
        this.kafkaTemplate = kafkaTemplate;
    }

    public void confirmReleased(InstrumentLifecycleDrainEvent orderReady) {
        if (orderReady.component() != InstrumentLifecycleDrainComponent.ORDER
                || orderReady.productLine() != properties.getKafka().getProductLine()) {
            return;
        }
        requireLocalProductLine(orderReady.productLine());
        if (!allReservationsReleased(orderReady.symbol())) {
            throw new IllegalStateException("订单冻结资金尚未全部释放: " + orderReady.symbol());
        }
        publishReady(orderReady);
    }

    private boolean allReservationsReleased(String symbol) {
        ProductLine productLine = properties.getKafka().getProductLine();
        Set<UserPartitionKey> statePartitions = stateStore.partitions().stream()
                .filter(partition -> partition.productLine() == productLine)
                .collect(Collectors.toSet());
        for (UserPartitionKey partition : wal.partitions()) {
            if (partition.productLine() != productLine) {
                continue;
            }
            long pending = wal.lastSequence(partition) - stateStore.lastAppliedSequence(partition);
            if (pending > 0L) {
                // 本地事实流尚未追平时不能提前确认生命周期完成，避免漏掉尚未落地的释放命令。
                return false;
            }
            statePartitions.add(partition);
        }
        for (UserPartitionKey partition : statePartitions) {
            AccountUserReducerState state = reducer.state(partition).orElse(null);
            if (state == null) {
                return false;
            }
            for (AccountUserReducerState.Reservation reservation : state.reservations()) {
                // 旧快照没有交易对时无法证明该预占属于哪个生命周期，宁可阻塞也不能误放行。
                if (reservation.symbol() == null || reservation.symbol().equalsIgnoreCase(symbol)) {
                    long unavailable = Math.addExact(reservation.releasedUnits(), reservation.consumedUnits());
                    if (unavailable < reservation.reservedUnits()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void requireLocalProductLine(ProductLine productLine) {
        if (productLine != ProductLine.LINEAR_PERPETUAL) {
            throw new IllegalStateException("产品线尚未接入本地账户事实流，禁止数据库生命周期核对: " + productLine);
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
