package com.surprising.trading.order.service;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.UUID;
import com.surprising.product.api.ProductLine;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.trading.api.model.*;

class FeePolicyCoreImporterTest {
    @Test void replayKeepsPolicyRevisionButDoesNotReuseExpiredCommandIdentity() {
        var gateway = mock(OrderAeronGateway.class);
        var importer = new FeePolicyCoreImporter(gateway);
        var now = Instant.parse("2026-10-06T14:00:00Z");
        var policy = new FeeScheduleResponse(123, ProductLine.LINEAR_PERPETUAL, 2, "604", 0, 500,
                FeeScheduleSourceType.MARKET_MAKER, "TEST", "test", FeeScheduleStatus.ACTIVE,
                now, null, now, now);
        importer.importPolicy(policy); importer.importPolicy(policy);
        var ids = ArgumentCaptor.forClass(UUID.class);
        var payloads = ArgumentCaptor.forClass(byte[].class);
        verify(gateway, times(2)).command(eq(CoreMessageType.UPSERT_FEE_POLICY), ids.capture(), eq(2L), payloads.capture());
        assertThat(ids.getAllValues().get(0)).isNotEqualTo(ids.getAllValues().get(1));
        assertThat(payloads.getAllValues().get(0)).containsExactly(payloads.getAllValues().get(1));
    }
    @Test void resolvesAdmittedImportUsingTheOriginalCommandIdWithoutResubmitting() {
        var gateway = mock(OrderAeronGateway.class);
        var now = Instant.parse("2026-10-06T14:00:00Z");
        var policy = new FeeScheduleResponse(123, ProductLine.LINEAR_PERPETUAL, 2, "604", 0, 500,
                FeeScheduleSourceType.MARKET_MAKER, "TEST", "test", FeeScheduleStatus.ACTIVE, now, null, now, now);
        when(gateway.command(any(), any(), anyLong(), any())).thenAnswer(invocation -> {
            throw new com.surprising.aeron.client.ResultUnknownException(invocation.getArgument(1), "unknown");
        });
        when(gateway.commandResult(any())).thenReturn(new com.surprising.aeron.protocol.CoreResponse(
                com.surprising.aeron.protocol.ResponseStatus.OK, com.surprising.aeron.protocol.ResponseStatus.APPLIED, 1));
        new FeePolicyCoreImporter(gateway).importPolicy(policy);
        var command = ArgumentCaptor.forClass(UUID.class);
        verify(gateway, times(1)).command(any(), command.capture(), anyLong(), any());
        verify(gateway).commandResult(command.getValue());
    }
    @Test void unresolvedImportStillFailsStartup() {
        var gateway = mock(OrderAeronGateway.class);
        var now = Instant.parse("2026-10-06T14:00:00Z");
        var policy = new FeeScheduleResponse(123, ProductLine.LINEAR_PERPETUAL, 2, "604", 0, 500,
                FeeScheduleSourceType.MARKET_MAKER, "TEST", "test", FeeScheduleStatus.ACTIVE, now, null, now, now);
        when(gateway.command(any(), any(), anyLong(), any())).thenAnswer(invocation -> {
            throw new com.surprising.aeron.client.ResultUnknownException(invocation.getArgument(1), "unknown");
        });
        when(gateway.commandResult(any())).thenReturn(new com.surprising.aeron.protocol.CoreResponse(
                com.surprising.aeron.protocol.ResponseStatus.OK, com.surprising.aeron.protocol.ResponseStatus.REJECTED, 1));
        assertThatThrownBy(() -> new FeePolicyCoreImporter(gateway).importPolicy(policy)).hasMessageContaining("not confirmed");
        verify(gateway, times(1)).command(any(), any(), anyLong(), any());
    }

}
