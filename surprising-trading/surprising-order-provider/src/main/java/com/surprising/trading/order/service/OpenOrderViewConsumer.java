package com.surprising.trading.order.service;

import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.repository.OrderRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Projects both order lifecycle and matching results after their database writers have committed. */
@Service
public class OpenOrderViewConsumer {

    private final ObjectMapper mapper;
    private final OrderRepository repository;
    private final RedisOpenOrderView view;
    private final TradingOrderProperties properties;

    public OpenOrderViewConsumer(ObjectMapper mapper,
                                 OrderRepository repository,
                                 RedisOpenOrderView view,
                                 TradingOrderProperties properties) {
        this.mapper = mapper;
        this.repository = repository;
        this.view = view;
        this.properties = properties;
    }

    @KafkaListener(
            topics = {"#{__listener.orderEventsTopic()}", "#{__listener.matchResultsTopic()}"},
            groupId = "#{__listener.groupId()}",
            containerFactory = "orderOpenViewKafkaListenerContainerFactory")
    public void onEvents(List<ConsumerRecord<String, String>> records) throws Exception {
        if (records == null || records.isEmpty()) {
            return;
        }
        Set<Long> orderIds = new LinkedHashSet<>();
        for (ConsumerRecord<String, String> record : records) {
            if (orderEventsTopic().equals(record.topic())) {
                orderIds.add(mapper.readValue(record.value(), OrderEvent.class).orderId());
            } else if (matchResultsTopic().equals(record.topic())) {
                MatchResultEvent result = mapper.readValue(record.value(), MatchResultEvent.class);
                orderIds.add(result.orderId());
                for (MatchTradeEvent trade : result.trades()) {
                    orderIds.add(trade.makerOrderId());
                }
            } else {
                throw new IllegalArgumentException("unexpected open-order projection topic " + record.topic());
            }
        }
        Map<Long, OrderRecord> orders = repository.findByOrderIds(orderIds);
        if (orders.size() != orderIds.size()) {
            List<Long> missing = orderIds.stream().filter(id -> !orders.containsKey(id)).limit(10).toList();
            view.markNotReady(properties.getKafka().getProductLine());
            throw new IllegalStateException("open-order projection rows missing for orderIds=" + missing);
        }
        for (long orderId : orderIds) {
            OrderRecord order = orders.get(orderId);
            if (order.productLine() != properties.getKafka().getProductLine()) {
                continue;
            }
            view.synchronize(order);
        }
    }

    public String orderEventsTopic() {
        return properties.getKafka().getOrderEventsTopic();
    }

    public String matchResultsTopic() {
        return properties.getKafka().getMatchResultsTopic();
    }

    public String groupId() {
        return properties.getKafka().getOpenOrderViewGroupId();
    }
}
