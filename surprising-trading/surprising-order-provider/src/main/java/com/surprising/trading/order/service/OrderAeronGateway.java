package com.surprising.trading.order.service;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreLeverageView;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreUserStateView;
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
                aeron.getEgressHostname(), aeron.getResponseTimeout(), aeron.getClientConnections(),
                "order-" + properties.getKafka().getProductLine().name() + "-node-" + aeron.getNodeId());
    }

    public CoreResponse command(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        CoreResponse response = clients.command(type, commandId, userId, payload);
        if (response.commandStatus() != ResponseStatus.APPLIED) {
            throw new IllegalStateException(response.resultCode().name() + ": Aeron order command rejected");
        }
        return response;
    }

    public boolean tryCommand(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        return clients.command(type, commandId, userId, payload).commandStatus() == ResponseStatus.APPLIED;
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

    public CoreLeverageView leverage(long userId, String symbol, com.surprising.aeron.protocol.CoreMarginMode mode) {
        CoreUserStateView user = userState(userId);
        if (user == null) return null;
        return user.leverages().stream()
                .filter(value -> value.symbol().equalsIgnoreCase(symbol) && value.marginMode() == mode)
                .findFirst().orElse(null);
    }

    public PreflightResult preflight(long userId, com.surprising.aeron.protocol.PlaceOrderCommand command) {
        CoreResponse response = clients.query(CoreMessageType.ORDER_PREFLIGHT_QUERY, UUID.randomUUID(), userId,
                TradingCommandCodec.encodePlaceOrder(command));
        if (response.status() == ResponseStatus.OK) {
            return new PreflightResult(CoreResultCode.NONE,
                    com.surprising.aeron.protocol.CoreOrderPreflightCodec.decode(response.data()));
        }
        return new PreflightResult(response.resultCode(), null);
    }

    public java.util.List<com.surprising.aeron.protocol.CoreAlgoOrderView> algoOrders(
            long userId, String symbol, long dueAtEpochMillis, int limit) {
        CoreResponse response = clients.query(CoreMessageType.ALGO_ORDER_QUERY, UUID.randomUUID(), userId,
                com.surprising.aeron.protocol.CoreAlgoOrderCodec.encodeQuery(
                        userId, 0, symbol, dueAtEpochMillis, limit));
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(response.resultCode().name() + ": Aeron algo query failed");
        }
        return com.surprising.aeron.protocol.CoreAlgoOrderCodec.decodeList(response.data());
    }

    public com.surprising.aeron.protocol.CoreAlgoOrderView algoOrder(long userId, long algoOrderId) {
        CoreResponse response = clients.query(CoreMessageType.ALGO_ORDER_QUERY, UUID.randomUUID(), userId,
                com.surprising.aeron.protocol.CoreAlgoOrderCodec.encodeQuery(userId, algoOrderId, "", 0, 1));
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(response.resultCode().name() + ": Aeron algo query failed");
        }
        return com.surprising.aeron.protocol.CoreAlgoOrderCodec.decodeList(response.data()).stream()
                .findFirst().orElse(null);
    }

    public java.util.List<com.surprising.aeron.protocol.CoreCancelAllAfterView> cancelAllAfterTimers(
            long userId, String symbolScope, long dueAtEpochMillis, int limit) {
        CoreResponse response = clients.query(CoreMessageType.CANCEL_ALL_AFTER_QUERY, UUID.randomUUID(), userId,
                com.surprising.aeron.protocol.CoreCancelAllAfterCodec.encodeQuery(
                        userId, symbolScope, dueAtEpochMillis, limit));
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(response.resultCode().name() + ": Aeron cancel-all-after query failed");
        }
        return com.surprising.aeron.protocol.CoreCancelAllAfterCodec.decodeList(response.data());
    }

    @Override
    @PreDestroy
    public void close() {
        clients.close();
    }

    public record PreflightResult(CoreResultCode resultCode,
                                  com.surprising.aeron.protocol.CoreOrderPreflightView view) {
        public boolean accepted() {
            return resultCode == CoreResultCode.NONE && view != null;
        }
    }
}
