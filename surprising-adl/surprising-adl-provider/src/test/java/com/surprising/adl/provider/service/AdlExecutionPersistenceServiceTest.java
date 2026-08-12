package com.surprising.adl.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.adl.api.model.AdlSide;
import com.surprising.adl.provider.model.AdlExecutionPlan;
import com.surprising.adl.provider.repository.AdlAccountOutboxRepository;
import com.surprising.adl.provider.repository.AdlExecutionSagaRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class AdlExecutionPersistenceServiceTest {

    @Test
    void createsSagaAndReserveTargetFinalizeDependencyChainWithPerUserKeys() {
        AdlExecutionSagaRepository sagaRepository = mock(AdlExecutionSagaRepository.class);
        AdlAccountOutboxRepository outboxRepository = mock(AdlAccountOutboxRepository.class);
        AdlExecutionPersistenceService service = new AdlExecutionPersistenceService(
                sagaRepository, outboxRepository, new ObjectMapper());
        Instant now = Instant.parse("2026-07-18T00:00:00Z");

        service.create(plan(), now);

        verify(sagaRepository).insert(plan(), now);
        ArgumentCaptor<AccountUserCommand> commands = ArgumentCaptor.forClass(AccountUserCommand.class);
        verify(outboxRepository, times(3)).enqueue(eq(7001L), commands.capture(), eq(now));
        AccountUserCommand reserve = commands.getAllValues().get(0);
        AccountUserCommand target = commands.getAllValues().get(1);
        AccountUserCommand finalize = commands.getAllValues().get(2);
        assertThat(reserve.commandType()).isEqualTo(AccountUserCommandType.ADL_DEFICIT_RESERVE);
        assertThat(reserve.partitionKey()).isEqualTo("LINEAR_PERPETUAL:11");
        assertThat(reserve.dependsOnCommandId()).isNull();
        assertThat(target.commandType()).isEqualTo(AccountUserCommandType.ADL_TARGET_SETTLE);
        assertThat(target.partitionKey()).isEqualTo("LINEAR_PERPETUAL:22");
        assertThat(target.dependsOnCommandId()).isEqualTo(reserve.commandId());
        assertThat(finalize.commandType()).isEqualTo(AccountUserCommandType.ADL_DEFICIT_FINALIZE);
        assertThat(finalize.partitionKey()).isEqualTo("LINEAR_PERPETUAL:11");
        assertThat(finalize.dependsOnCommandId()).isEqualTo(target.commandId());
    }

    private static AdlExecutionPlan plan() {
        return new AdlExecutionPlan(
                7001L,
                ProductLine.LINEAR_PERPETUAL,
                "USDT_PERPETUAL",
                11L,
                22L,
                "USDT",
                "BTC-USDT",
                AdlSide.LONG,
                MarginMode.CROSS,
                PositionSide.NET,
                10L,
                4L,
                60_000L,
                61_000L,
                1_000L,
                700L,
                600L,
                900_000L,
                "ADL_RESERVE:LINEAR_PERPETUAL:7001",
                "ADL_TARGET:LINEAR_PERPETUAL:7001",
                "ADL_FINALIZE:LINEAR_PERPETUAL:7001");
    }
}
