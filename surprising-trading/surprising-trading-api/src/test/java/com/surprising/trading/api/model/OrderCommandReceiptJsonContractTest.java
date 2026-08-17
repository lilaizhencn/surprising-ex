package com.surprising.trading.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class OrderCommandReceiptJsonContractTest {

    private static final UUID COMMAND_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutationResults")
    void realObjectMapperRoundTripPreservesMutationResultType(
            String mutation, OrderCommandResult result, Class<?> resultType, String wireType) throws Exception {
        OrderCommandReceipt receipt = new OrderCommandReceipt(COMMAND_ID, "TERMINAL", "NONE", "completed",
                OrderCommandReceipt.commandResultUrl(COMMAND_ID), List.of(42L), 17L, result, null);

        String json = objectMapper.writeValueAsString(receipt);
        OrderCommandReceipt decoded = objectMapper.readValue(json, OrderCommandReceipt.class);

        assertThat(json).contains("\"resultType\":\"" + wireType + "\"");
        assertThat(decoded.commandId()).isEqualTo(COMMAND_ID);
        assertThat(decoded.outcome()).isEqualTo("TERMINAL");
        assertThat(decoded.code()).isEqualTo("NONE");
        assertThat(decoded.message()).isEqualTo("completed");
        assertThat(decoded.commandResultUrl()).isEqualTo(OrderCommandReceipt.commandResultUrl(COMMAND_ID));
        assertThat(decoded.prospectiveOrderIds()).containsExactly(42L);
        assertThat(decoded.requiredExportSequence()).isEqualTo(17L);
        assertThat(decoded.result()).as(mutation).isExactlyInstanceOf(resultType).isEqualTo(result);
    }

    private static Stream<Arguments> mutationResults() {
        OrderResponse order = order();
        AmendOrderResponse amend = new AmendOrderResponse(order, order, true, "order amended");
        return Stream.of(
                Arguments.of("place", order, OrderResponse.class, "order"),
                Arguments.of("place-batch", batch(order), OrderBatchResponse.class, "order-batch"),
                Arguments.of("amend", amend, AmendOrderResponse.class, "amend"),
                Arguments.of("amend-batch", amendBatch(amend), AmendOrderBatchResponse.class, "amend-batch"),
                Arguments.of("cancel", order, OrderResponse.class, "order"),
                Arguments.of("cancel-batch", batch(order), OrderBatchResponse.class, "order-batch"));
    }

    private static OrderResponse order() {
        return new OrderResponse(42L, 7L, "client-42", "BTC-USDT", 1L, OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 60_000L, 2L, 0L, 2L, MarginMode.CROSS,
                PositionSide.NET, 10L, 20L, false, false, OrderStatus.ACCEPTED, null, null, null);
    }

    private static OrderBatchResponse batch(OrderResponse order) {
        return new OrderBatchResponse(1, 1, 0,
                List.of(new OrderBatchItemResponse(0, true, "completed", order)));
    }

    private static AmendOrderBatchResponse amendBatch(AmendOrderResponse amend) {
        return new AmendOrderBatchResponse(1, 1, 0,
                List.of(new AmendOrderBatchItemResponse(0, true, "completed", amend)));
    }
}
