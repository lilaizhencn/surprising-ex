package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.PositionCacheEvent;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.repository.AccountOutboxRepository;
import com.surprising.account.provider.repository.AccountSequenceRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AccountOutboxServiceTest {

    @Test
    void enqueuePositionUpdatedAllowsCurrentProductTopicWhenProductTopicsAreEnabled() {
        AccountOutboxRepository repository = mock(AccountOutboxRepository.class);
        AccountSequenceRepository sequenceRepository = mock(AccountSequenceRepository.class);
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        PositionCacheProjectionService projectionService = mock(PositionCacheProjectionService.class);
        AccountOutboxService service = new AccountOutboxService(
                repository, sequenceRepository, new ObjectMapper(), properties, projectionService);
        when(sequenceRepository.nextPositionEventId()).thenReturn(101L);
        when(projectionService.captureFinalSnapshot(
                ProductLine.LINEAR_DELIVERY, 1001L, "BTC-USDT-260925", MarginMode.CROSS, PositionSide.NET))
                .thenReturn(snapshot(ProductLine.LINEAR_DELIVERY));
        Instant now = Instant.parse("2026-07-01T00:00:00Z");

        var event = service.enqueuePositionUpdated(
                "surprising.linear-delivery.account.position.events.v1", 9201L, position(), now, "trace-1");

        verify(sequenceRepository).nextPositionEventId();
        verify(repository).insert(
                eq("LINEAR_DELIVERY"), eq("POSITION"), eq(101L),
                eq("surprising.linear-delivery.account.position.events.v1"),
                eq("LINEAR_DELIVERY:1001"), eq("POSITION_UPDATED"), any(String.class), eq(now));
        assertThat(event.partitionKey()).isEqualTo("LINEAR_DELIVERY:1001");
        assertThat(event.revision()).isEqualTo(77L);
        assertThat(event.entryValueTicks()).isEqualTo(600_000L);
        assertThat(event.marginUnits()).isEqualTo(20_000L);
    }

    @Test
    void enqueuePositionUpdatedRejectsOtherProductTopicBeforeWritingWhenProductTopicsAreEnabled() {
        AccountOutboxRepository repository = mock(AccountOutboxRepository.class);
        AccountSequenceRepository sequenceRepository = mock(AccountSequenceRepository.class);
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        PositionCacheProjectionService projectionService = mock(PositionCacheProjectionService.class);
        AccountOutboxService service = new AccountOutboxService(
                repository, sequenceRepository, new ObjectMapper(), properties, projectionService);

        assertThatThrownBy(() -> service.enqueuePositionUpdated(
                "surprising.linear-delivery.account.position.events.v1", 9201L, position(),
                Instant.parse("2026-07-01T00:00:00Z"), "trace-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("account outbox topic must match current product line")
                .hasMessageContaining("surprising.option.account.position.events.v1");

        verifyNoInteractions(repository, sequenceRepository, projectionService);
    }

    private PositionResponse position() {
        return new PositionResponse(1001L, "BTC-USDT-260925", 7L, MarginMode.CROSS, PositionSide.NET,
                10L, 60_000L, 0L, Instant.parse("2026-07-01T00:00:00Z"));
    }

    private PositionCacheEvent snapshot(ProductLine productLine) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new PositionCacheEvent(77L, productLine, 1001L, "BTC-USDT-260925", 7L,
                MarginMode.CROSS, PositionSide.NET, 10L, 60_000L, 600_000L, 0L,
                "USDT", 20_000L, now, now, 77L);
    }
}
