package com.surprising.trading.order.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

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
        OrderUserCommandGateway commandGateway = mock(OrderUserCommandGateway.class);
        OrderAccountCommandResultConsumer consumer =
                new OrderAccountCommandResultConsumer(objectMapper, properties, commandGateway);
        AccountCommandResultEvent first = result(1L, "ORDER:1", 1001L);
        AccountCommandResultEvent second = result(2L, "ORDER:2", 1002L);

        consumer.onResult(List.of(record(consumer, objectMapper, first), record(consumer, objectMapper, second)));

        verify(commandGateway).forwardAccountResult(first);
        verify(commandGateway).forwardAccountResult(second);
        verifyNoMoreInteractions(commandGateway);
    }

    @Test
    void ignoresSharedAccountResultsThatDoNotBelongToOrderReservation() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        OrderUserCommandGateway commandGateway = mock(OrderUserCommandGateway.class);
        OrderAccountCommandResultConsumer consumer =
                new OrderAccountCommandResultConsumer(objectMapper, properties, commandGateway);
        AccountCommandResultEvent balanceAdjust = new AccountCommandResultEvent(
                11L,
                "account-api:product_balance_adjust:11",
                ProductLine.LINEAR_PERPETUAL,
                1001L,
                AccountUserCommandType.PRODUCT_BALANCE_ADJUST,
                AccountCommandStatus.APPLIED,
                "ACCOUNT_API",
                "smoke-usdt",
                null,
                null,
                null,
                Instant.parse("2026-07-19T00:00:00Z"),
                "trace-11");

        consumer.onResult(List.of(record(consumer, objectMapper, balanceAdjust)));

        verifyNoMoreInteractions(commandGateway);
    }

    @Test
    void ignoresOrderReleaseResultsBecauseOrderStateDoesNotConsumeThem() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.SPOT);
        OrderUserCommandGateway commandGateway = mock(OrderUserCommandGateway.class);
        OrderAccountCommandResultConsumer consumer =
                new OrderAccountCommandResultConsumer(objectMapper, properties, commandGateway);
        AccountCommandResultEvent release = new AccountCommandResultEvent(
                12L,
                "ORDER_RELEASE:SPOT:100:ORDER_CANCEL_BEFORE_ACCEPT",
                ProductLine.SPOT,
                1001L,
                AccountUserCommandType.ORDER_RELEASE,
                AccountCommandStatus.APPLIED,
                "ORDER",
                "100",
                null,
                null,
                null,
                Instant.parse("2026-07-19T00:00:00Z"),
                "trace-12");

        consumer.onResult(List.of(record(consumer, objectMapper, release)));

        verifyNoMoreInteractions(commandGateway);
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
