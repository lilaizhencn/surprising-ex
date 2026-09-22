package com.surprising.trading.maintenance;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.*;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.service.OrderAeronGateway;
import java.util.List;
import org.springframework.stereotype.Service;

/** Dedicated ingress queue and source identity; never shares user order/trigger client capacity. */
@Service
public class MaintenanceAeronGateway implements AutoCloseable {
    private final AeronClientPool clients;
    private final OrderAeronGateway views;
    public MaintenanceAeronGateway(TradingOrderProperties properties) {
        clients=clients(properties);
        views=new OrderAeronGateway(clients);
    }
    public CoreMaintenanceCodec.Page maintenance(String symbol,long afterUserId,int limit) { return views.maintenance(symbol,afterUserId,limit); }
    public CoreUserStateView userState(long userId) { return views.userState(userId); }
    public CoreOrderStateView orderState(long userId,long orderId) { return views.orderState(userId,orderId); }
    public List<CoreOrderStateView> openOrders(long userId,String symbol,long before,int limit) { return views.openOrders(userId,symbol,before,limit); }
    public CoreSettlementProgressView settlementProgress(String symbol) { return views.settlementProgress(symbol); }
    public CoreResponse command(CoreMessageType type,java.util.UUID id,long userId,byte[] payload) { return views.command(type,id,userId,payload); }
    public com.surprising.aeron.client.CoreCommandOutcome commandOutcome(CoreMessageType type,java.util.UUID id,long userId,byte[] payload) { return clients.commandOutcome(type,id,userId,payload); }
    private static AeronClientPool clients(TradingOrderProperties properties) {
        var config=properties.getAeron(); var line=properties.getKafka().getProductLine();
        return new AeronClientPool("maintenance",line,config.getHostnames(),config.getEgressHostname(),
                config.getResponseTimeout(),1,"maintenance-"+line.name()+"-node-"+config.getNodeId());
    }
    public List<CoreTriggerOrderStateView> openTriggers(long userId,String symbol,long before,int limit) {
        return triggerQuery(CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY,userId,new CoreTriggerOrderQuery(0,symbol,before,limit));
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
    @Override @jakarta.annotation.PreDestroy public void close() { clients.close(); }
}
