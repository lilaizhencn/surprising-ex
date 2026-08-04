package com.surprising.trading.order.service;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderAccountCommandResultConsumer {

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final OrderUserCommandGateway commandGateway;

    @Autowired
    public OrderAccountCommandResultConsumer(ObjectMapper objectMapper,
                                             TradingOrderProperties properties,
                                             OrderUserCommandGateway commandGateway) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.commandGateway = commandGateway;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "orderAccountCommandResultKafkaListenerContainerFactory")
    public void onResult(List<ConsumerRecord<String, String>> records) {
        try {
            if (records == null || records.isEmpty()) {
                return;
            }
            List<AccountCommandResultEvent> results = new ArrayList<>(records.size());
            for (ConsumerRecord<String, String> record : records) {
                AccountCommandResultEvent result = objectMapper.readValue(
                        record.value(), AccountCommandResultEvent.class);
                if (!topic().equals(record.topic())) {
                    throw new IllegalArgumentException("unexpected account command result topic " + record.topic());
                }
                String expectedKey = AccountUserCommand.partitionKey(result.productLine(), result.userId());
                if (!expectedKey.equals(record.key())) {
                    throw new IllegalArgumentException("invalid account command result key");
                }
                if (result.productLine() != properties.getKafka().getProductLine()) {
                    throw new IllegalArgumentException("account command result product line mismatch");
                }
                // 账户结果 Topic 是共享的，只有订单自己发出的预占/释放结果才属于订单状态机。
                // 余额调整、成交结算、资金费等结果必须留在账户模块，不能被当成订单预占结果。
                if (isOrderReservationResult(result)) {
                    results.add(result);
                }
            }
            for (AccountCommandResultEvent result : results) {
                commandGateway.forwardAccountResult(result);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("failed to process order account command result batch", ex);
        }
    }

    public String topic() {
        return properties.getKafka().getAccountCommandResultsTopic();
    }

    public String groupId() {
        return properties.getKafka().getAccountCommandResultsGroupId();
    }

    /** 判断账户结果是否属于订单预占状态机，避免共享 Topic 的无关事件阻塞消费分区。 */
    private boolean isOrderReservationResult(AccountCommandResultEvent result) {
        // 订单用户状态只等待预占结果。取消前释放结果由账户服务幂等落账即可，
        // 不再转发到订单用户分区，避免被误按预占命令校验而阻塞后续消息。
        return "ORDER".equals(result.source())
                && result.commandType() == AccountUserCommandType.ORDER_RESERVE;
    }
}
