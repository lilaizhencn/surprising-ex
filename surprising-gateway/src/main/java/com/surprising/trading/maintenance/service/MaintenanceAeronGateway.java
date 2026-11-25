package com.surprising.trading.maintenance.service;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.*;
import com.surprising.trading.order.service.OrderAeronGateway;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class MaintenanceAeronGateway {
    private final AeronClientPool clients;
    private final OrderAeronGateway views;
    public MaintenanceAeronGateway(AeronClientPool clients) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.views = new OrderAeronGateway(this.clients);
    }
    public CoreMaintenanceCodec.Page maintenance(String instrumentId,long afterUserId,int limit) { return views.maintenance(instrumentId,afterUserId,limit); }
    public CoreUserStateView userState(long userId) { return views.userState(userId); }
    public CoreOrderStateView orderState(long userId,long orderId) { return views.orderState(userId,orderId); }
    public List<CoreOrderStateView> openOrders(long userId,String instrumentId,long before,int limit) { return views.openOrders(userId,instrumentId,before,limit); }
    public CoreSettlementProgressView settlementProgress(String instrumentId) { return views.settlementProgress(instrumentId); }
    public CoreResponse command(CoreMessageType type,java.util.UUID id,long userId,byte[] payload) { return views.command(type,id,userId,payload); }
    public com.surprising.aeron.client.CoreCommandOutcome commandOutcome(CoreMessageType type,java.util.UUID id,long userId,byte[] payload) { return clients.commandOutcome(type,id,userId,payload); }
    public List<CoreTriggerOrderStateView> openTriggers(long userId,String instrumentId,long before,int limit) {
        return triggerQuery(CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY,userId,new CoreTriggerOrderQuery(0,instrumentId,before,limit));
    }
    public CoreTriggerOrderStateView triggerState(long userId,long id) {
        return triggerQuery(CoreMessageType.TRIGGER_ORDER_QUERY,userId,new CoreTriggerOrderQuery(id,"",0,1)).stream().findFirst().orElse(null);
    }
    private List<CoreTriggerOrderStateView> triggerQuery(CoreMessageType type,long userId,CoreTriggerOrderQuery query) {
        var response=clients.query(type,java.util.UUID.randomUUID(),userId,CoreTriggerOrderCodec.encodeQuery(query));
        if(response.resultCode()==CoreResultCode.ENTITY_NOT_FOUND) return List.of();
        if(response.status()!=ResponseStatus.OK) throw new IllegalStateException(response.resultCode()+": maintenance trigger query failed");
        return CoreTriggerOrderCodec.decodeList(response.data());
    }
}
