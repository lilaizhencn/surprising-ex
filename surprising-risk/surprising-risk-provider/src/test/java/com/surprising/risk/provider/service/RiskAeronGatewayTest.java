package com.surprising.risk.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.client.ResultUnknownException;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class RiskAeronGatewayTest {

    @Test
    void retriesIdempotentQueryWhenAdmittedResultIsUnknown() {
        AeronClientPool clients = mock(AeronClientPool.class);
        when(clients.query(eq(CoreMessageType.USER_STATE_QUERY), any(UUID.class), anyLong(), any(byte[].class)))
                .thenThrow(new CompletionException(new ResultUnknownException(UUID.randomUUID(), "unknown")))
                .thenReturn(new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.ENTITY_NOT_FOUND, 0, 0, 0, new byte[0]));
        RiskAeronGateway gateway = new RiskAeronGateway(clients);

        assertThat(gateway.userState(7)).isNull();
        verify(clients, times(2)).query(eq(CoreMessageType.USER_STATE_QUERY), any(UUID.class), eq(7L),
                any(byte[].class));
    }
}
