package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.eventstore.UserPartitionCommandLane;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.eventstore.UserPartitionStateStore;
import com.surprising.eventstore.UserPartitionWal;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainComponent;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainEvent;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

class AccountInstrumentDrainServiceTest {

    @TempDir
    Path directory;

    @Test
    void retriesWhileLocalAccountWalIsPending() {
        try (UserPartitionStateStore stateStore = new UserPartitionStateStore(directory.resolve("state"));
             UserPartitionWal wal = new UserPartitionWal(directory.resolve("wal"))) {
            UserPartitionKey partition = new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1001L);
            wal.append(partition, "command-1", "ORDER_RESERVE", "{}".getBytes(StandardCharsets.UTF_8),
                    "fingerprint", Instant.now());

            assertThatThrownBy(() -> service(stateStore, wal).confirmReleased(orderReady(ProductLine.LINEAR_PERPETUAL)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("尚未全部释放");
        }
    }

    @Test
    void publishesAccountReadyWhenLocalPartitionsHaveNoUnreleasedReservation() {
        try (UserPartitionStateStore stateStore = new UserPartitionStateStore(directory.resolve("state"));
             UserPartitionWal wal = new UserPartitionWal(directory.resolve("wal"))) {
            KafkaTemplate<String, String> kafkaTemplate = kafkaTemplate();
            when(kafkaTemplate.send(any(String.class), any(String.class), any(String.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));

            service(stateStore, wal, kafkaTemplate).confirmReleased(orderReady(ProductLine.LINEAR_PERPETUAL));

            verify(kafkaTemplate).send(
                    org.mockito.ArgumentMatchers.eq("surprising.instrument.lifecycle-drain.v1"),
                    org.mockito.ArgumentMatchers.eq("BTC-USDT"), any(String.class));
        }
    }

    @Test
    void rejectsUnsupportedProductLineWithoutDatabaseFallback() {
        try (UserPartitionStateStore stateStore = new UserPartitionStateStore(directory.resolve("state"));
             UserPartitionWal wal = new UserPartitionWal(directory.resolve("wal"))) {
            assertThatThrownBy(() -> service(stateStore, wal, kafkaTemplate(), ProductLine.LINEAR_DELIVERY)
                    .confirmReleased(orderReady(ProductLine.LINEAR_DELIVERY)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("禁止数据库生命周期核对");
        }
    }

    private AccountInstrumentDrainService service(UserPartitionStateStore stateStore, UserPartitionWal wal) {
        return service(stateStore, wal, kafkaTemplate());
    }

    private AccountInstrumentDrainService service(UserPartitionStateStore stateStore,
                                                  UserPartitionWal wal,
                                                  KafkaTemplate<String, String> kafkaTemplate) {
        return service(stateStore, wal, kafkaTemplate, ProductLine.LINEAR_PERPETUAL);
    }

    private AccountInstrumentDrainService service(UserPartitionStateStore stateStore,
                                                  UserPartitionWal wal,
                                                  KafkaTemplate<String, String> kafkaTemplate,
                                                  ProductLine productLine) {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(productLine);
        AccountUserStateReducer reducer = new AccountUserStateReducer(
                new ObjectMapper(), stateStore, new UserPartitionCommandLane());
        return new AccountInstrumentDrainService(
                new ObjectMapper(), properties, stateStore, wal, reducer, kafkaTemplate);
    }

    private InstrumentLifecycleDrainEvent orderReady(ProductLine productLine) {
        return new InstrumentLifecycleDrainEvent(
                InstrumentLifecycleDrainEvent.CURRENT_SCHEMA_VERSION,
                "BTC-USDT",
                2L,
                productLine,
                InstrumentLifecycleDrainComponent.ORDER,
                Instant.now());
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> kafkaTemplate() {
        return mock(KafkaTemplate.class);
    }
}
