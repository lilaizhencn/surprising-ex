package com.surprising.funding.provider.service;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.funding.provider.config.FundingProperties;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FundingAeronGateway implements AutoCloseable {

    private final AeronClientPool clients;

    public FundingAeronGateway(FundingProperties properties) {
        FundingProperties.Aeron aeron = properties.getAeron();
        this.clients = new AeronClientPool("funding", properties.getKafka().getProductLine(),
                aeron.getHostnames(), aeron.getEgressHostname(), aeron.getResponseTimeout(),
                aeron.getClientConnections());
    }

    public void command(CoreMessageType type, UUID commandId, byte[] payload) {
        var response = clients.command(type, commandId, 0, payload);
        if (response.commandStatus() != ResponseStatus.APPLIED
                && response.resultCode() != CoreResultCode.STALE_SETTLEMENT_ID) {
            throw new IllegalStateException(response.resultCode().name() + ": Aeron funding command rejected");
        }
    }

    @Override
    @PreDestroy
    public void close() {
        clients.close();
    }
}
