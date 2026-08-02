package com.surprising.trading.order.service;

import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 撮合结果直接进入订单用户分区 WAL，数据库读模型不参与订单状态变更。 */
@Service
public class OrderMatchResultConsumer {

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final OrderUserStateService stateService;

    public OrderMatchResultConsumer(ObjectMapper objectMapper,
                                    TradingOrderProperties properties,
                                    OrderUserStateService stateService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.stateService = stateService;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "orderMatchResultKafkaListenerContainerFactory")
    public void onResult(List<ConsumerRecord<String, String>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<MatchResultEvent> results = new ArrayList<>(records.size());
        try {
            for (ConsumerRecord<String, String> record : records) {
                if (!topic().equals(record.topic())) {
                    throw new IllegalArgumentException("撮合结果 Topic 不匹配");
                }
                MatchResultEvent result = objectMapper.readValue(record.value(), MatchResultEvent.class);
                if (!result.symbol().equals(record.key())) {
                    throw new IllegalArgumentException("撮合结果 Kafka key 必须为交易对");
                }
                if (result.commandId() <= 0L || result.orderId() <= 0L) {
                    throw new IllegalArgumentException("撮合结果编号无效");
                }
                if (result.trades() == null) {
                    throw new IllegalArgumentException("撮合结果成交列表不能为空");
                }
                if (result.trades().stream().anyMatch(trade -> trade == null
                        || !result.symbol().equalsIgnoreCase(trade.symbol()))) {
                    throw new IllegalArgumentException("撮合结果成交交易对不一致");
                }
                results.add(result);
            }
            stateService.processMatchResults(results);
        } catch (Exception ex) {
            throw new IllegalStateException("撮合结果写入订单事实流失败", ex);
        }
    }

    public String topic() {
        return properties.getKafka().getMatchResultsTopic();
    }

    public String groupId() {
        return properties.getKafka().getProductLine().topicSegment() + "-order-state-match-v1";
    }
}
