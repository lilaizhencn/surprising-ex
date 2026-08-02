package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.eventstore.UserPartitionCommandLane;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.eventstore.UserPartitionWal;
import com.surprising.product.api.ProductLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AccountUserCommandWalProjectionWorkerTest {

    @Test
    void onlyAdvancesProjectionWatermarkAfterProcessorSucceeds() throws Exception {
        Path directory = Files.createTempDirectory("account-user-wal-worker-");
        ObjectMapper objectMapper = new ObjectMapper();
        AccountProperties properties = new AccountProperties();
        AccountUserCommandProcessor processor = mock(AccountUserCommandProcessor.class);
        AccountUserCommand command = command("worker-1", 1001L);
        String serialized = objectMapper.writeValueAsString(command);
        try (UserPartitionWal wal = new UserPartitionWal(directory)) {
            UserPartitionKey partition = new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1001L);
            wal.append(partition, command.commandId(), command.commandType().name(),
                    serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8), "fingerprint", command.occurredAt());
            when(processor.processBatch(anyList())).thenReturn(List.of(
                    AccountUserCommandProcessor.ProcessingOutcome.APPLIED));

            AccountUserCommandWalProjectionWorker worker = new AccountUserCommandWalProjectionWorker(
                    objectMapper, properties, wal, new UserPartitionCommandLane(), processor);
            worker.projectPending();

            assertThat(wal.lastProjectedSequence(partition)).isEqualTo(1L);
            verify(processor).processBatch(anyList());
        }
    }

    @Test
    void dependencyWaitStopsThePartitionWithoutSkippingTheEvent() throws Exception {
        Path directory = Files.createTempDirectory("account-user-wal-dependency-");
        ObjectMapper objectMapper = new ObjectMapper();
        AccountProperties properties = new AccountProperties();
        AccountUserCommandProcessor processor = mock(AccountUserCommandProcessor.class);
        AccountUserCommand command = command("worker-dependency-1", 1002L);
        String serialized = objectMapper.writeValueAsString(command);
        try (UserPartitionWal wal = new UserPartitionWal(directory)) {
            UserPartitionKey partition = new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1002L);
            wal.append(partition, command.commandId(), command.commandType().name(),
                    serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8), "fingerprint", command.occurredAt());
            when(processor.processBatch(anyList())).thenReturn(
                    List.of(AccountUserCommandProcessor.ProcessingOutcome.WAITING_DEPENDENCY),
                    List.of(AccountUserCommandProcessor.ProcessingOutcome.APPLIED));
            AccountUserCommandWalProjectionWorker worker = new AccountUserCommandWalProjectionWorker(
                    objectMapper, properties, wal, new UserPartitionCommandLane(), processor);

            worker.projectPending();
            assertThat(wal.lastProjectedSequence(partition)).isZero();
            worker.projectPending();
            assertThat(wal.lastProjectedSequence(partition)).isEqualTo(1L);
        }
    }

    private AccountUserCommand command(String commandId, long userId) {
        return new AccountUserCommand(AccountUserCommand.CURRENT_SCHEMA_VERSION, commandId,
                ProductLine.LINEAR_PERPETUAL, userId, AccountUserCommandType.ORDER_RELEASE,
                "TEST", commandId, null, "{}", Instant.parse("2026-07-20T00:00:00Z"), "trace-" + commandId);
    }
}
