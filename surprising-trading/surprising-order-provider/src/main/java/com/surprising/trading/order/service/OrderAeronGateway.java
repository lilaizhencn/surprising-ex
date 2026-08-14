package com.surprising.trading.order.service;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.trading.order.config.TradingOrderProperties;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OrderAeronGateway implements AutoCloseable {

    private final AeronClientPool clients;

    public OrderAeronGateway(TradingOrderProperties properties) {
        TradingOrderProperties.Aeron aeron = properties.getAeron();
        this.clients = new AeronClientPool("order", properties.getKafka().getProductLine(), aeron.getHostnames(),
                aeron.getEgressHostname(), aeron.getResponseTimeout(), aeron.getClientConnections());
    }

    public CoreResponse command(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        CoreResponse response = clients.command(type, commandId, userId, payload);
        if (response.commandStatus() != ResponseStatus.APPLIED) {
            throw new IllegalStateException(response.resultCode().name() + ": Aeron order command rejected");
        }
        return response;
    }

    public CoreOrderStateView order(long userId, long orderId) {
        return query(CoreMessageType.ORDER_STATE_QUERY, userId,
                TradingCommandCodec.encodeOrderStateQuery(orderId));
    }

    public CoreOrderStateView order(long userId, String clientOrderId) {
        return query(CoreMessageType.CLIENT_ORDER_STATE_QUERY, userId,
                CoreStateQueryCodec.encodeClientOrderStateQuery(clientOrderId));
    }

    public CoreUserStateView userState(long userId) {
        CoreResponse response = clients.query(CoreMessageType.USER_STATE_QUERY, UUID.randomUUID(), userId, new byte[0]);
        if (response.status() == ResponseStatus.REJECTED && response.resultCode() == CoreResultCode.ENTITY_NOT_FOUND) {
            return null;
        }
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(response.resultCode().name() + ": Aeron user query failed");
        }
        return CoreStateQueryCodec.decodeUserState(response.data());
    }

    private CoreOrderStateView query(CoreMessageType type, long userId, byte[] payload) {
        CoreResponse response = clients.query(type, UUID.randomUUID(), userId, payload);
        if (response.status() == ResponseStatus.REJECTED && response.resultCode() == CoreResultCode.ENTITY_NOT_FOUND) {
            return null;
        }
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(response.resultCode().name() + ": Aeron order query failed");
        }
        return CoreStateQueryCodec.decodeOrderState(response.data());
    }

    @Override
    @PreDestroy
    public void close() {
        clients.close();
    }
}
