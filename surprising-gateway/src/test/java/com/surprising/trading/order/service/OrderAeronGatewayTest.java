package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.client.CoreCommandOutcome;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderAeronGatewayTest {

    @Test
    void preservesTerminalBusinessRejectionWithoutInternalTransportMessage() {
        var clients = mock(AeronClientPool.class);
        var id = UUID.randomUUID();
        when(clients.command(CoreMessageType.UPDATE_LEVERAGE, id, 7L, new byte[0]))
                .thenReturn(new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.LEVERAGE_UPDATE_BLOCKED, 4L, new byte[0]));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new OrderAeronGateway(clients)
                .command(CoreMessageType.UPDATE_LEVERAGE, id, 7L, new byte[0]))
                .isInstanceOfSatisfying(OrderCommandRejectedException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(409);
                    assertThat(error.getReason()).contains("Close positions").doesNotContain("Aeron");
                    assertThat(error.getBody().getProperties()).containsEntry("code", "LEVERAGE_UPDATE_BLOCKED");
                });
    }

    @Test
    void forwardsTypedCommandOutcomeWithoutConvertingAdmissionOrUnknown() {
        AeronClientPool clients = mock(AeronClientPool.class);
        OrderAeronGateway gateway = new OrderAeronGateway(clients);
        UUID commandId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        CoreCommandOutcome expected = new CoreCommandOutcome.ResultUnknown(commandId);
        when(clients.commandOutcome(CoreMessageType.PLACE_ORDER, commandId, 1001L, new byte[] {1, 2}))
                .thenReturn(expected);

        CoreCommandOutcome actual = gateway.commandOutcome(CoreMessageType.PLACE_ORDER, commandId, 1001L,
                new byte[] {1, 2});

        assertThat(actual).isSameAs(expected);
        verify(clients).commandOutcome(CoreMessageType.PLACE_ORDER, commandId, 1001L, new byte[] {1, 2});
    }

    @Test
    void commandResultUsesReservedControlQuery() {
        AeronClientPool clients = mock(AeronClientPool.class);
        OrderAeronGateway gateway = new OrderAeronGateway(clients);
        UUID commandId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        CoreResponse expected = new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, CoreResultCode.NONE,
                3L, new byte[] {5});
        when(clients.commandResult(commandId, 0L)).thenReturn(expected);

        assertThat(gateway.commandResult(commandId)).isSameAs(expected);
        verify(clients).commandResult(commandId, 0L);
    }
}
