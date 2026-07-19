package com.surprising.trading.order.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OrderAccountCommandResultConsumerTest {

    @Test
    void validatesAndProcessesEveryResultInTheBatch() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        OrderService orderService = mock(OrderService.class);
        OrderAccountCommandResultConsumer consumer =
                new OrderAccountCommandResultConsumer(objectMapper, properties, orderService);
        AccountCommandResultEvent first = result(1L, "ORDER:1", 1001L);
        AccountCommandResultEvent second = result(2L, "ORDER:2", 1002L);

        consumer.onResult(List.of(record(consumer, objectMapper, first), record(consumer, objectMapper, second)));

        verify(orderService).processAccountCommandResults(List.of(first, second));
    }

    private ConsumerRecord<String, String> record(OrderAccountCommandResultConsumer consumer,
                                                   ObjectMapper objectMapper,
                                                   AccountCommandResultEvent event) throws Exception {
        return new ConsumerRecord<>(consumer.topic(), 0, event.eventId(),
                AccountUserCommand.partitionKey(event.productLine(), event.userId()),
                objectMapper.writeValueAsString(event));
    }

    private AccountCommandResultEvent result(long eventId, String commandId, long userId) {
        return new AccountCommandResultEvent(
                eventId,
                commandId,
                ProductLine.LINEAR_PERPETUAL,
                userId,
                AccountUserCommandType.ORDER_RESERVE,
                AccountCommandStatus.APPLIED,
                "ORDER",
                Long.toString(eventId),
                null,
                null,
                null,
                Instant.parse("2026-07-19T00:00:00Z"),
                "trace-" + eventId);
    }
}
