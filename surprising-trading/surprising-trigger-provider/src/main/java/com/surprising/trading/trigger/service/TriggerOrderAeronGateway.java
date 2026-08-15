package com.surprising.trading.trigger.service;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTriggerOrderCodec;
import com.surprising.aeron.protocol.CoreTriggerOrderQuery;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.TriggerCondition;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.api.model.TriggerOrderStatus;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.trigger.config.TriggerProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

@Service
public final class TriggerOrderAeronGateway implements AutoCloseable {
    private final AeronClientPool clients;
    public TriggerOrderAeronGateway(TriggerProperties properties) {
        var aeron = properties.getAeron();
        this.clients = new AeronClientPool("trigger", properties.getKafka().getProductLine(), aeron.getHostnames(),
                aeron.getEgressHostname(), aeron.getResponseTimeout(), aeron.getClientConnections(),
                "trigger-" + properties.getKafka().getProductLine().name() + "-node-" + aeron.getNodeId());
    }
    public CoreResponse command(CoreMessageType type, UUID id, long userId, byte[] payload) {
        CoreResponse response = clients.command(type, id, userId, payload);
        if (response.commandStatus() != ResponseStatus.APPLIED) {
            throw new IllegalStateException(response.resultCode().name() + ": Aeron trigger command rejected");
        }
        return response;
    }
    public List<CoreTriggerOrderStateView> openOrders(long userId, String symbol, long before, int limit) {
        CoreResponse response = clients.query(CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY, UUID.randomUUID(), userId,
                CoreTriggerOrderCodec.encodeQuery(new CoreTriggerOrderQuery(0, symbol, before, limit)));
        requireOk(response); return CoreTriggerOrderCodec.decodeList(response.data());
    }
    public CoreTriggerOrderStateView get(long userId, long triggerOrderId) {
        CoreResponse response = clients.query(CoreMessageType.TRIGGER_ORDER_QUERY, UUID.randomUUID(), userId,
                CoreTriggerOrderCodec.encodeQuery(new CoreTriggerOrderQuery(triggerOrderId, "", 0, 1)));
        if (response.status() == ResponseStatus.REJECTED && response.resultCode() == CoreResultCode.ENTITY_NOT_FOUND) return null;
        requireOk(response); return CoreTriggerOrderCodec.decodeList(response.data()).stream().findFirst().orElse(null);
    }
    public void cancel(long userId, long id) { command(CoreMessageType.CANCEL_TRIGGER_ORDER, stable("TRIGGER_CANCEL:" + userId + ':' + id), userId, CoreTriggerOrderCodec.encodeId(id)); }
    public void claim(long id, long sequence, long price, long at) { command(CoreMessageType.CLAIM_TRIGGER_ORDER, stable("TRIGGER_CLAIM:" + id + ':' + sequence), 0, CoreTriggerOrderCodec.encodeClaim(id, sequence, price, at)); }
    public void execute(long id, long sequence, long price, long at) { command(CoreMessageType.EXECUTE_TRIGGER_ORDER, stable("TRIGGER_EXECUTE:" + id + ':' + sequence), 0, CoreTriggerOrderCodec.encodeExecute(id, sequence, price, at)); }
    public void complete(long id, boolean success, long placedOrderId, String reason, long at) { command(CoreMessageType.COMPLETE_TRIGGER_ORDER, stable("TRIGGER_COMPLETE:" + id + ':' + placedOrderId), 0, CoreTriggerOrderCodec.encodeComplete(id, success, placedOrderId, reason, at)); }
    public void updateTrailing(long id, long highest, long lowest, long activatedAt) {
        command(CoreMessageType.UPDATE_TRIGGER_TRAILING, stable("TRIGGER_TRAILING:" + id + ':' + highest + ':' + lowest),
                0, CoreTriggerOrderCodec.encodeTrailing(id, highest, lowest, activatedAt));
    }
    public void expire(long id, long expiredAt) {
        command(CoreMessageType.EXPIRE_TRIGGER_ORDER, stable("TRIGGER_EXPIRE:" + id + ':' + expiredAt), 0,
                CoreTriggerOrderCodec.encodeLifecycle(id, expiredAt));
    }
    public void retry(long id, long staleBefore, long retryAt) {
        command(CoreMessageType.RETRY_TRIGGER_ORDER, stable("TRIGGER_RETRY:" + id + ':' + retryAt), 0,
                CoreTriggerOrderCodec.encodeLifecycle(id, staleBefore));
    }
    private void requireOk(CoreResponse response) { if (response.status() != ResponseStatus.OK) throw new IllegalStateException(response.resultCode().name() + ": Aeron trigger query failed"); }
    private static UUID stable(String value) { return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    public static TriggerOrderResponse response(CoreTriggerOrderStateView value) {
        if (value == null) return null;
        return new TriggerOrderResponse(value.triggerOrderId(), value.userId(), empty(value.clientTriggerOrderId()), empty(value.ocoGroupId()),
                value.symbol(), OrderSide.valueOf(value.side().name()), TriggerOrderType.valueOf(value.triggerType().name()),
                TriggerCondition.valueOf(value.triggerCondition().name()), value.triggerPriceTicks(),
                value.activationPriceTicks() == 0 ? null : value.activationPriceTicks(), value.callbackRatePpm() == 0 ? null : value.callbackRatePpm(),
                value.highestPriceTicks() == 0 ? null : value.highestPriceTicks(), value.lowestPriceTicks() == 0 ? null : value.lowestPriceTicks(),
                value.activatedAtEpochMillis() == 0 ? null : Instant.ofEpochMilli(value.activatedAtEpochMillis()),
                OrderType.valueOf(value.orderType().name()), TimeInForce.valueOf(value.timeInForce().name()), value.priceTicks(), value.quantitySteps(),
                MarginMode.valueOf(value.marginMode().name()), PositionSide.valueOf(value.positionSide().name()),
                TriggerOrderStatus.valueOf(value.status().name()), value.placedOrderId() == 0 ? null : value.placedOrderId(),
                value.triggerSequence() == 0 ? null : value.triggerSequence(), value.triggeredPriceTicks() == 0 ? null : value.triggeredPriceTicks(),
                empty(value.rejectReason()), empty(value.traceId()), value.expiresAtEpochMillis() == 0 ? null : Instant.ofEpochMilli(value.expiresAtEpochMillis()),
                value.triggeredAtEpochMillis() == 0 ? null : Instant.ofEpochMilli(value.triggeredAtEpochMillis()),
                Instant.ofEpochMilli(value.createdAtEpochMillis()), Instant.ofEpochMilli(value.updatedAtEpochMillis()));
    }
    private static String empty(String value) { return value == null || value.isEmpty() ? null : value; }
    @Override @PreDestroy public void close() { clients.close(); }
}
