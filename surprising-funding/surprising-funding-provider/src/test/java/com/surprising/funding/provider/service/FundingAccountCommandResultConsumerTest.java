package com.surprising.funding.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class FundingAccountCommandResultConsumerTest {

    @Test
    void filtersMalformedAndMiskeyedRecordsBeforeApplyingValidBatch() {
        ObjectMapper objectMapper = new ObjectMapper();
        FundingAccountCommandResultService resultService = mock(FundingAccountCommandResultService.class);
        FundingAccountCommandResultConsumer consumer = new FundingAccountCommandResultConsumer(
                objectMapper, new FundingProperties(), resultService);
        AccountCommandResultEvent event = result("FUNDING:1", 1001L);
        String payload = objectMapper.writeValueAsString(event);

        consumer.onResult(List.of(
                record("LINEAR_PERPETUAL:1001", payload),
                record("LINEAR_PERPETUAL:9999", payload),
                record("LINEAR_PERPETUAL:1001", "not-json")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountCommandResultEvent>> events = ArgumentCaptor.forClass(List.class);
        verify(resultService).applyBatch(events.capture());
        assertThat(events.getValue()).containsExactly(event);
    }

    private ConsumerRecord<String, String> record(String key, String payload) {
        return new ConsumerRecord<>(
                "surprising.linear-perp.account.command.results.v1", 0, 1L, key, payload);
    }

    private AccountCommandResultEvent result(String commandId, long userId) {
        return new AccountCommandResultEvent(
                1L,
                commandId,
                ProductLine.LINEAR_PERPETUAL,
                userId,
                AccountUserCommandType.FUNDING_SETTLE,
                AccountCommandStatus.APPLIED,
                "FUNDING",
                "1",
                null,
                null,
                null,
                Instant.parse("2026-07-01T00:00:00Z"),
                "trace-1");
    }
}
