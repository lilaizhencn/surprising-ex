package com.surprising.trading.order.service;

import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.repository.OrderRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Projects both order lifecycle and matching results after their database writers have committed. */
@Service
public class OpenOrderViewConsumer {
    private final ObjectMapper mapper; private final OrderRepository repository; private final RedisOpenOrderView view; private final TradingOrderProperties properties;
    public OpenOrderViewConsumer(ObjectMapper mapper, OrderRepository repository, RedisOpenOrderView view, TradingOrderProperties properties){this.mapper=mapper;this.repository=repository;this.view=view;this.properties=properties;}
    @KafkaListener(topics="#{__listener.orderEventsTopic()}",groupId="#{__listener.groupId()}", containerFactory="orderOpenViewKafkaListenerContainerFactory")
    public void onOrder(ConsumerRecord<String,String> record)throws Exception{project(mapper.readValue(record.value(),OrderEvent.class).orderId());}
    @KafkaListener(topics="#{__listener.matchResultsTopic()}",groupId="#{__listener.groupId()}", containerFactory="orderOpenViewKafkaListenerContainerFactory")
    public void onMatch(ConsumerRecord<String,String> record)throws Exception{
        MatchResultEvent result=mapper.readValue(record.value(),MatchResultEvent.class);
        project(result.orderId());
        for(MatchTradeEvent trade:result.trades()){project(trade.makerOrderId());}
    }
    private void project(long id){
        repository.findByOrderId(id)
                .filter(order -> order.productLine() == properties.getKafka().getProductLine())
                .ifPresent(view::synchronize);
    }
    public String orderEventsTopic(){return properties.getKafka().getOrderEventsTopic();}
    public String matchResultsTopic(){return properties.getKafka().getMatchResultsTopic();}
    public String groupId(){return properties.getKafka().getOpenOrderViewGroupId();}
}
