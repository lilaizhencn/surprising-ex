package com.surprising.funding.provider.service;

import com.surprising.derivatives.lifecycle.DerivativesAeronClient;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.CoreResultCode;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FundingAeronGateway {

    private final DerivativesAeronClient clients;

    public FundingAeronGateway(DerivativesAeronClient clients) {
        this.clients = clients;
    }

    public void command(CoreMessageType type, UUID commandId, byte[] payload) {
        commandWithResponse(type, commandId, payload);
    }

    public CoreResponse commandWithResponse(CoreMessageType type, UUID commandId, byte[] payload) {
        var response = clients.command(type, commandId, 0, payload);
        if (response.commandStatus() != ResponseStatus.APPLIED
                && response.resultCode() != CoreResultCode.STALE_SETTLEMENT_ID) {
            throw new IllegalStateException(response.resultCode().name() + ": Aeron funding command rejected");
        }
        return response;
    }

    public CoreResponse query(CoreMessageType type, UUID queryId, byte[] payload) {
        return clients.query(type, queryId, 0, payload);
    }

}
