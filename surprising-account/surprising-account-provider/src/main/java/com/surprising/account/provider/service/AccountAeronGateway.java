package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AccountAeronGateway implements AutoCloseable {

    private final AeronClientPool clients;

    public AccountAeronGateway(AccountProperties properties) {
        ProductLine productLine = properties.getKafka().getProductLine();
        AccountProperties.Aeron aeron = properties.getAeron();
        this.clients = new AeronClientPool("account", productLine, aeron.getHostnames(),
                aeron.getEgressHostname(), aeron.getResponseTimeout(), aeron.getClientConnections());
    }

    public CoreResponse command(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        return requireApplied(clients.command(type, commandId, userId, payload));
    }

    public CoreUserStateView userState(long userId) {
        CoreResponse response = clients.query(CoreMessageType.USER_STATE_QUERY, UUID.randomUUID(), userId, new byte[0]);
        if (response.status() == ResponseStatus.REJECTED
                && response.resultCode() == com.surprising.aeron.protocol.CoreResultCode.ENTITY_NOT_FOUND) {
            return null;
        }
        if (response.status() != ResponseStatus.OK) {
            throw new AccountStateUnavailableException("Aeron user query failed: " + response.resultCode());
        }
        return CoreStateQueryCodec.decodeUserState(response.data());
    }

    @Override
    @PreDestroy
    public void close() {
        clients.close();
    }

    private static CoreResponse requireApplied(CoreResponse response) {
        if (response.commandStatus() != ResponseStatus.APPLIED) {
            throw new AccountCommandRejectedException(response.resultCode().name(),
                    "Aeron command rejected: " + response.resultCode());
        }
        return response;
    }

}
