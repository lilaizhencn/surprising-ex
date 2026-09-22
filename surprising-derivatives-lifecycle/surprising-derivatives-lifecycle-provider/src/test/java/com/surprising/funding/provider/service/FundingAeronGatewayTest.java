package com.surprising.funding.provider.service;

import com.surprising.aeron.protocol.*;
import com.surprising.derivatives.lifecycle.DerivativesAeronClient;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FundingAeronGatewayTest {
    @Test
    void usesSharedClientAndPreservesSettlementIdentity() {
        var client = mock(DerivativesAeronClient.class);
        var id = UUID.randomUUID(); var payload = new byte[]{1};
        var applied = new CoreResponse(ResponseStatus.OK, ResponseStatus.APPLIED, CoreResultCode.NONE, 1, payload);
        when(client.command(CoreMessageType.APPLY_FUNDING, id, 0, payload)).thenReturn(applied);
        assertThat(new FundingAeronGateway(client).commandWithResponse(CoreMessageType.APPLY_FUNDING, id, payload))
                .isSameAs(applied);
        verify(client).command(CoreMessageType.APPLY_FUNDING, id, 0, payload);
        verifyNoMoreInteractions(client);
    }

    @Test
    void staleSettlementIsIdempotentButOtherRejectionsFail() {
        var client = mock(DerivativesAeronClient.class);
        var id = UUID.randomUUID(); var payload = new byte[0];
        var stale = new CoreResponse(ResponseStatus.OK, ResponseStatus.REJECTED, CoreResultCode.STALE_SETTLEMENT_ID, 1, payload);
        var rejected = new CoreResponse(ResponseStatus.OK, ResponseStatus.REJECTED, CoreResultCode.INVALID_COMMAND, 2, payload);
        when(client.command(CoreMessageType.APPLY_FUNDING, id, 0, payload)).thenReturn(stale, rejected);
        var gateway = new FundingAeronGateway(client);
        assertThat(gateway.commandWithResponse(CoreMessageType.APPLY_FUNDING, id, payload)).isSameAs(stale);
        assertThatThrownBy(() -> gateway.commandWithResponse(CoreMessageType.APPLY_FUNDING, id, payload))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("INVALID_COMMAND");
    }

    @Test
    void unknownTransportResultPropagatesWithoutRetry() {
        var client = mock(DerivativesAeronClient.class);
        var id = UUID.randomUUID(); var payload = new byte[0];
        var unknown = new IllegalStateException("response timeout");
        when(client.command(CoreMessageType.APPLY_FUNDING, id, 0, payload)).thenThrow(unknown);
        assertThatThrownBy(() -> new FundingAeronGateway(client).commandWithResponse(CoreMessageType.APPLY_FUNDING, id, payload))
                .isSameAs(unknown);
        verify(client).command(CoreMessageType.APPLY_FUNDING, id, 0, payload);
        verifyNoMoreInteractions(client);
    }
}
