package com.surprising.trading.order.service;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AlgoOrderResponse;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderUserCommand;
import com.surprising.trading.api.model.OrderUserCommandResult;
import com.surprising.trading.api.model.OrderUserCommandStatus;
import com.surprising.trading.api.model.OrderUserCommandType;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.AlgoOrderChild;
import com.surprising.trading.order.model.AlgoOrderRecord;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.model.OrderUserAlgoChildCommand;
import com.surprising.trading.order.model.OrderUserCancelCommand;
import com.surprising.trading.order.model.OrderUserCancelOpenCommand;
import com.surprising.trading.order.model.OrderUserPruneReduceOnlyCommand;
import com.surprising.account.api.model.PositionUpdatedEvent;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 订单用户分区命令的唯一写入入口。
 *
 * <p>HTTP、算法任务和其他 Kafka 消费者都通过这里把写操作交给用户分区 Topic，不能直接
 * 在当前 JVM 打开另一个用户分区的 WAL。真正写 WAL 的代码只存在于命令消费者所在的单写
 * lane。</p>
 */
@Service
public class OrderUserCommandGateway {

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OrderUserCommandResultWaiter resultWaiter;

    public OrderUserCommandGateway(ObjectMapper objectMapper,
                                   TradingOrderProperties properties,
                                   KafkaTemplate<String, String> kafkaTemplate,
                                   OrderUserCommandResultWaiter resultWaiter) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.kafkaTemplate = kafkaTemplate;
        this.resultWaiter = resultWaiter;
    }

    public OrderResponse place(OrderRecord order) {
        return submit(order.productLine(), order.userId(), "ORDER_PLACE:" + order.orderId(),
                OrderUserCommandType.PLACE, order, OrderResponse.class);
    }

    public OrderResponse cancel(ProductLine productLine, long userId, long orderId, String reason) {
        return submit(productLine, userId, "ORDER_CANCEL:" + orderId,
                OrderUserCommandType.CANCEL, new OrderUserCancelCommand(orderId, reason), OrderResponse.class);
    }

    public OrderBatchResponse cancelOpen(ProductLine productLine, long userId, String symbol, int limit) {
        return cancelOpen(productLine, userId, symbol, limit, "USER_CANCEL_ALL");
    }

    public OrderBatchResponse cancelOpen(ProductLine productLine, long userId, String symbol, int limit,
                                         String reason) {
        return submit(productLine, userId, "ORDER_CANCEL_OPEN:" + userId + ":" + (symbol == null ? "ALL" : symbol),
                OrderUserCommandType.CANCEL_OPEN, new OrderUserCancelOpenCommand(symbol, limit, reason),
                OrderBatchResponse.class);
    }

    public AlgoOrderResponse placeAlgo(AlgoOrderRecord order) {
        return submit(order.productLine(), order.userId(), "ORDER_ALGO_PLACE:" + order.algoOrderId(),
                OrderUserCommandType.ALGO_PLACE, order, AlgoOrderResponse.class);
    }

    public void updateAlgo(AlgoOrderRecord order) {
        publish(order.productLine(), order.userId(), "ORDER_ALGO_UPDATE:" + order.algoOrderId()
                        + ":" + order.updatedAt().toEpochMilli(), OrderUserCommandType.ALGO_UPDATE, order);
    }

    public void linkAlgoChild(AlgoOrderRecord order, AlgoOrderChild child) {
        publish(order.productLine(), order.userId(), "ORDER_ALGO_CHILD:" + order.algoOrderId()
                        + ":" + child.sliceIndex(), OrderUserCommandType.ALGO_CHILD,
                new OrderUserAlgoChildCommand(order, child));
    }

    /** 账户预占结果改由用户命令 Topic 路由到订单状态所有者。 */
    public void forwardAccountResult(AccountCommandResultEvent result) {
        if (result == null) {
            return;
        }
        publish(result.productLine(), result.userId(), "ORDER_ACCOUNT_RESULT:" + result.commandId(),
                OrderUserCommandType.ACCOUNT_RESULT, result);
    }

    /** 撮合结果按参与用户拆成两个用户分区命令，避免按交易对消费节点直接写任意用户 WAL。 */
    public void forwardMatchResult(MatchResultEvent result, long userId) {
        if (result == null || userId <= 0L) {
            throw new IllegalArgumentException("撮合结果用户分区参数无效");
        }
        publish(resultProductLine(result), userId,
                "ORDER_MATCH_RESULT:" + result.commandId() + ":" + result.orderId() + ":" + userId,
                OrderUserCommandType.MATCH_RESULT, result);
    }

    public void pruneReduceOnly(PositionUpdatedEvent event, String reason) {
        if (event == null) {
            throw new IllegalArgumentException("持仓事件不能为空");
        }
        publish(event.productLine(), event.userId(), "ORDER_PRUNE_REDUCE_ONLY:" + event.userId() + ":"
                        + event.symbol() + ":" + event.instrumentVersion() + ":" + event.positionSide(),
                OrderUserCommandType.PRUNE_REDUCE_ONLY,
                new OrderUserPruneReduceOnlyCommand(event, reason));
    }

    private ProductLine resultProductLine(MatchResultEvent result) {
        return properties.getKafka().getProductLine();
    }

    private <T> T submit(ProductLine productLine,
                         long userId,
                         String commandId,
                         OrderUserCommandType type,
                         Object payload,
                         Class<T> resultType) {
        UserPartitionKey partition = publish(productLine, userId, commandId, type, payload);
        OrderUserCommandResult result = resultWaiter.await(partition, commandId,
                properties.getKafka().getUserCommandTimeout());
        if (result.status() == OrderUserCommandStatus.REJECTED) {
            String code = result.errorCode() == null ? "ORDER_COMMAND_REJECTED" : result.errorCode();
            throw new IllegalStateException(code + ": " + result.errorMessage());
        }
        if (result.resultPayload() == null) {
            throw new IllegalStateException("订单用户命令已执行但没有返回结果: " + commandId);
        }
        return objectMapper.readValue(result.resultPayload(), resultType);
    }

    private UserPartitionKey publish(ProductLine productLine,
                                      long userId,
                                      String commandId,
                                      OrderUserCommandType type,
                                      Object payload) {
        OrderUserCommand command = new OrderUserCommand(
                OrderUserCommand.CURRENT_SCHEMA_VERSION, commandId, productLine, userId, type,
                objectMapper.writeValueAsString(payload), Instant.now(), null);
        try {
            kafkaTemplate.send(properties.getKafka().getOrderUserCommandsTopic(), command.partitionKey(),
                    objectMapper.writeValueAsString(command)).get(
                    properties.getEventPublish().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return new UserPartitionKey(productLine, userId);
        } catch (Exception ex) {
            throw new KafkaException("订单用户命令发送失败: " + commandId, ex);
        }
    }
}
