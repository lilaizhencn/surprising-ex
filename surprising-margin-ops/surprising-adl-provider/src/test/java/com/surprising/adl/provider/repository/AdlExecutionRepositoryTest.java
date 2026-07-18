package com.surprising.adl.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.adl.api.model.AdlSide;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.adl.provider.model.AdlExecutionPlan;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AdlExecutionRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ObjectMapper objectMapper;
    private AdlExecutionRepository repository;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        AdlProperties properties = new AdlProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        repository = new AdlExecutionRepository(jdbcTemplate, objectMapper, properties);
    }

    @Test
    void createsReserveTargetFinalizeDependencyChainWithPerUserKeys() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        Instant now = Instant.parse("2026-07-18T00:00:00Z");

        repository.create(plan(), now);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(4)).update(sqlCaptor.capture(), argsCaptor.capture());

        List<Object[]> calls = argsCaptor.getAllValues();
        assertThat(sqlCaptor.getAllValues().get(0)).contains("INSERT INTO adl_execution_sagas");
        assertThat(calls.get(0)[0]).isEqualTo(7001L);

        AccountUserCommand reserve = envelope(calls.get(1));
        AccountUserCommand target = envelope(calls.get(2));
        AccountUserCommand finalize = envelope(calls.get(3));

        assertThat(reserve.commandType()).isEqualTo(AccountUserCommandType.ADL_DEFICIT_RESERVE);
        assertThat(reserve.partitionKey()).isEqualTo("LINEAR_PERPETUAL:11");
        assertThat(reserve.dependsOnCommandId()).isNull();

        assertThat(target.commandType()).isEqualTo(AccountUserCommandType.ADL_TARGET_SETTLE);
        assertThat(target.partitionKey()).isEqualTo("LINEAR_PERPETUAL:22");
        assertThat(target.dependsOnCommandId()).isEqualTo(reserve.commandId());

        assertThat(finalize.commandType()).isEqualTo(AccountUserCommandType.ADL_DEFICIT_FINALIZE);
        assertThat(finalize.partitionKey()).isEqualTo("LINEAR_PERPETUAL:11");
        assertThat(finalize.dependsOnCommandId()).isEqualTo(target.commandId());

        for (int index = 1; index <= 3; index++) {
            assertThat(calls.get(index)[0]).isEqualTo("LINEAR_PERPETUAL");
            assertThat(calls.get(index)[2])
                    .isEqualTo("surprising.linear-perp.account.user.commands.v1");
        }
    }

    @Test
    void failsTheSurroundingTransactionWhenAnyDurableWriteIsSkipped() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> repository.create(plan(), Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADL execution saga insert");
    }

    private AccountUserCommand envelope(Object[] outboxArguments) {
        return objectMapper.readValue((String) outboxArguments[5], AccountUserCommand.class);
    }

    private AdlExecutionPlan plan() {
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
