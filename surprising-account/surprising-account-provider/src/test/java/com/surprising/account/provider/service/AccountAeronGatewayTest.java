package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.client.ResultUnknownException;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountAeronGatewayTest {

    @Test
    void resolvesAdmittedCommandByIdWithoutResubmitting() {
        AeronClientPool clients = mock(AeronClientPool.class);
        UUID commandId = UUID.randomUUID();
        when(clients.command(eq(CoreMessageType.SETTLE_INSTRUMENT), eq(commandId), eq(0L), any(byte[].class)))
                .thenThrow(new ResultUnknownException(commandId, "unknown"));
        CoreResponse committed = new CoreResponse(ResponseStatus.OK, ResponseStatus.APPLIED,
                CoreResultCode.NONE, 7, 8, 9, new byte[0]);
        when(clients.commandResult(commandId, 0L)).thenReturn(committed);
        AccountAeronGateway gateway = new AccountAeronGateway(clients);

        assertThat(gateway.command(CoreMessageType.SETTLE_INSTRUMENT, commandId, 0L, new byte[0]))
                .isEqualTo(committed);
        verify(clients).command(eq(CoreMessageType.SETTLE_INSTRUMENT), eq(commandId), eq(0L), any(byte[].class));
        verify(clients).commandResult(commandId, 0L);
    }
}
