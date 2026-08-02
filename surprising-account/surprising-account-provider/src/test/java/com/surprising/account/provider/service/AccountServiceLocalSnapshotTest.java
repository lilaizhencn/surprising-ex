package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.eventstore.UserPartitionCommandLane;
import com.surprising.eventstore.UserPartitionStateStore;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AccountServiceLocalSnapshotTest {

    @Test
    void balanceAndPositionsReadOnlyTheLocalUserSnapshot() throws Exception {
        Path directory = Files.createTempDirectory("account-service-local-");
        ObjectMapper objectMapper = new ObjectMapper();
        AccountQueryService projection = mock(AccountQueryService.class);
        try (UserPartitionStateStore stateStore = new UserPartitionStateStore(directory)) {
            AccountUserStateReducer reducer = new AccountUserStateReducer(
                    objectMapper, stateStore, new UserPartitionCommandLane());
            reducer.initialize(snapshot());
            AccountService service = new AccountService(properties(), reducer, mock(AccountCommandGateway.class), projection);

            assertThat(service.balance(1001L, "usdt").availableUnits()).isEqualTo(800L);
            assertThat(service.position(1001L, "btc-usdt").signedQuantitySteps()).isEqualTo(10L);
            assertThat(service.positions(1001L).count()).isEqualTo(1);
            assertThat(service.positionMode(1001L).positionMode()).isEqualTo(PositionMode.ONE_WAY);
            verifyNoInteractions(projection);
        }
    }

    @Test
    void missingSnapshotFailsClosedInsteadOfQueryingProjectionDatabase() throws Exception {
        Path directory = Files.createTempDirectory("account-service-missing-");
        ObjectMapper objectMapper = new ObjectMapper();
        AccountQueryService projection = mock(AccountQueryService.class);
        try (UserPartitionStateStore stateStore = new UserPartitionStateStore(directory)) {
            AccountUserStateReducer reducer = new AccountUserStateReducer(
                    objectMapper, stateStore, new UserPartitionCommandLane());
            AccountService service = new AccountService(properties(), reducer, mock(AccountCommandGateway.class), projection);

            assertThatThrownBy(() -> service.balance(1001L, "USDT"))
                    .isInstanceOf(AccountStateUnavailableException.class);
            verifyNoInteractions(projection);
        }
    }

    @Test
    void unsupportedProductLineAndTransferDoNotFallBackToDatabase() throws Exception {
        Path directory = Files.createTempDirectory("account-service-scope-");
        ObjectMapper objectMapper = new ObjectMapper();
        AccountQueryService projection = mock(AccountQueryService.class);
        AccountCommandGateway gateway = mock(AccountCommandGateway.class);
        try (UserPartitionStateStore stateStore = new UserPartitionStateStore(directory)) {
            AccountUserStateReducer reducer = new AccountUserStateReducer(
                    objectMapper, stateStore, new UserPartitionCommandLane());
            reducer.initialize(snapshot());
            AccountService service = new AccountService(properties(), reducer, gateway, projection);

            assertThatThrownBy(() -> service.positionMode(ProductLine.LINEAR_DELIVERY, 1001L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.transfer(new com.surprising.account.api.model.ProductTransferRequest(
                    1001L, AccountType.USDT_PERPETUAL, AccountType.FUNDING, "USDT", 1L, "ref", "test")))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(projection);
        }
    }

    private static AccountProperties properties() {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        properties.getKafka().setProductTopicsEnabled(true);
        return properties;
    }

    private static PerpetualAccountStateUpdatedEvent snapshot() {
        return new PerpetualAccountStateUpdatedEvent(
                PerpetualAccountStateUpdatedEvent.CURRENT_SCHEMA_VERSION, 1L, 1L,
                ProductLine.LINEAR_PERPETUAL, 1001L, AccountType.USDT_PERPETUAL.name(),
                List.of(new PerpetualAccountStateUpdatedEvent.Balance("USDT", 800L, 200L)),
                List.of(),
                List.of(new PerpetualAccountStateUpdatedEvent.Position(
                        "BTC-USDT", 1L, MarginMode.CROSS, PositionSide.NET,
                        10L, 100L, 1000L, 0L, Instant.parse("2026-08-02T00:00:00Z"))),
                List.of(), List.of(), PositionMode.ONE_WAY,
                Instant.parse("2026-08-02T00:00:00Z"), "test");
    }
}
