package com.surprising.risk.provider.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.surprising.account.api.model.AccountRiskWalletUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.provider.config.RiskProperties;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AccountRiskWalletTriggerConsumerTest {

    @Test
    void forwardsValidatedWalletEventsToRiskService() throws Exception {
        RiskService service = mock(RiskService.class);
        RiskProperties properties = new RiskProperties();
        AccountRiskWalletTriggerConsumer consumer = new AccountRiskWalletTriggerConsumer(
                new ObjectMapper(), service, properties);
        AccountRiskWalletUpdatedEvent event = event(properties.getKafka().getProductLine());

        consumer.onAccountRiskWalletUpdated(List.of(new ConsumerRecord<>(
                "surprising.account.risk-wallet.events.v1", 0, 1L,
                event.partitionKey(), new ObjectMapper().writeValueAsString(event))));

        verify(service).scanAccountWalletUpdates(anyList());
    }

    @Test
    void rejectsWrongKafkaKeyBeforeRiskScan() throws Exception {
        RiskService service = mock(RiskService.class);
        RiskProperties properties = new RiskProperties();
        AccountRiskWalletTriggerConsumer consumer = new AccountRiskWalletTriggerConsumer(
                new ObjectMapper(), service, properties);
        AccountRiskWalletUpdatedEvent event = event(properties.getKafka().getProductLine());

        assertThatThrownBy(() -> consumer.onAccountRiskWalletUpdated(List.of(new ConsumerRecord<>(
                "surprising.account.risk-wallet.events.v1", 0, 1L,
                "LINEAR_PERPETUAL:9999", new ObjectMapper().writeValueAsString(event)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to process account risk wallet batch");
        verifyNoInteractions(service);
    }

    private AccountRiskWalletUpdatedEvent event(ProductLine productLine) {
        return new AccountRiskWalletUpdatedEvent(1, 11L, 2L, productLine, 1001L,
                "USDT_PERPETUAL", "USDT", 99_000L, Instant.parse("2026-07-01T00:00:00Z"), "trace");
    }
}
