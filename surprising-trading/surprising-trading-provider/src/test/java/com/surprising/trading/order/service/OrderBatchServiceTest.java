package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.aeron.client.CoreCommandOutcome;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderBatchResult;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.InstrumentRuleLookup;
import com.surprising.trading.order.model.MarkPriceLookup;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import com.surprising.trading.order.model.ValidationResult;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrderBatchServiceTest {

    private final OrderAeronGateway aeron = mock(OrderAeronGateway.class);
    private final InstrumentRuleLookup instrumentRules = mock(InstrumentRuleLookup.class);
    private final MarkPriceLookup markPrices = mock(MarkPriceLookup.class);
    private AeronOrderCommandService service;

    @BeforeEach
    void setUp() {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        service = new AeronOrderCommandService(aeron, instrumentRules, markPrices, properties);
        when(instrumentRules.currentRule("BTC-USDT")).thenReturn(Optional.of(perpetualRule()));
        when(aeron.commandOutcome(any(), any(), anyLong(), any(byte[].class)))
                .thenReturn(new CoreCommandOutcome.Terminal(new CoreResponse(
                        ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE,
                        1L, 9L, 17L, new byte[0])));
    }

    @Test
    void usesOneRoundTripAndReplaysOriginalAggregate() {
        List<PlaceOrderRequest> requests = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> place("place-" + index)).toList();
        ValidationResult validation = ValidationResult.ok(7L, InstrumentType.PERPETUAL,
                ContractType.LINEAR_PERPETUAL);
        OrderFeeSnapshot fee = fee();
        CoreOrderBatchResult aggregate = new CoreOrderBatchResult(java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> new CoreOrderBatchResult.Item(index, 30_000L + index, 0L, 0L,
                        ResponseStatus.APPLIED, CoreResultCode.NONE, null, List.of()))
                .toList());
        when(aeron.commandOutcome(eq(CoreMessageType.PLACE_ORDER_BATCH), any(UUID.class), eq(1001L),
                any(byte[].class))).thenReturn(new CoreCommandOutcome.Terminal(new CoreResponse(
                ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE,
                1L, 9L, 17L, com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResult(aggregate))));

        AeronOrderCommandService.CommandExecution first = service.placeBatchCommand("place-batch", requests,
                java.util.Collections.nCopies(20, validation), java.util.Collections.nCopies(20, fee));
        AeronOrderCommandService.CommandExecution second = service.placeBatchCommand("place-batch", requests,
                java.util.Collections.nCopies(20, validation), java.util.Collections.nCopies(20, fee));

        assertThat(first.commandId()).isEqualTo(second.commandId());
        OrderCommandReceipt firstReceipt = service.receipt(first);
        OrderCommandReceipt replayReceipt = service.receipt(second);
        assertThat(replayReceipt).isEqualTo(firstReceipt);
        assertThat(firstReceipt.requiredExportSequence()).isEqualTo(9L);
        assertThat(firstReceipt.requiredExportSequence()).isNotEqualTo(1L);
        assertThat(firstReceipt.commandResultUrl()).isEqualTo(OrderCommandReceipt.commandResultUrl(first.commandId()));
        assertThat(firstReceipt.prospectiveOrderIds()).hasSize(20);
        assertThat(firstReceipt.result()).isInstanceOf(OrderBatchResponse.class);
        assertThat(((OrderBatchResponse) firstReceipt.result()).results())
                .extracting(value -> value.index())
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 20).boxed().toList());
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(aeron, times(2)).commandOutcome(eq(CoreMessageType.PLACE_ORDER_BATCH), eq(first.commandId()),
                eq(1001L), payload.capture());
        assertThat(payload.getAllValues()).hasSize(2);
        assertThat(com.surprising.aeron.protocol.TradingOrderBatchCodec
                .decodePlaceOrderBatch(payload.getAllValues().getFirst()).items()).hasSize(20);
    }

    @Test
    void mapsTerminalConflictAdmissionBackpressureAndUnknownWithoutFallback() {
        UUID commandId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        AeronOrderCommandService.CommandExecution conflict = new AeronOrderCommandService.CommandExecution(
                commandId, List.of(7001L), new CoreCommandOutcome.Terminal(new CoreResponse(
                ResponseStatus.REJECTED, ResponseStatus.REJECTED, CoreResultCode.IDEMPOTENCY_CONFLICT,
                4L, 0L, 8L, new byte[0])), AeronOrderCommandService.CommandKind.PLACE);
        OrderCommandReceipt conflictReceipt = service.receipt(conflict);
        assertThat(conflictReceipt.outcome()).isEqualTo("TERMINAL");
        assertThat(conflictReceipt.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(conflictReceipt.requiredExportSequence()).isNull();

        AeronOrderCommandService.CommandExecution backpressure = new AeronOrderCommandService.CommandExecution(
                commandId, List.of(7001L), new CoreCommandOutcome.NotAccepted(
                CoreCommandOutcome.NotAcceptedReason.CLIENT_BACKPRESSURED, -1L),
                AeronOrderCommandService.CommandKind.PLACE);
        OrderCommandReceipt backpressureReceipt = service.receipt(backpressure);
        assertThat(backpressureReceipt.outcome()).isEqualTo("NOT_ACCEPTED");
        assertThat(backpressureReceipt.code()).isEqualTo("CLIENT_BACKPRESSURED");
        assertThat(backpressureReceipt.commandResultUrl()).isNull();
        assertThat(backpressureReceipt.rawOfferResult()).isEqualTo(-1L);

        AeronOrderCommandService.CommandExecution unknown = new AeronOrderCommandService.CommandExecution(
                commandId, List.of(7001L), new CoreCommandOutcome.ResultUnknown(commandId),
                AeronOrderCommandService.CommandKind.PLACE);
        OrderCommandReceipt unknownReceipt = service.receipt(unknown);
        assertThat(unknownReceipt.outcome()).isEqualTo("RESULT_UNKNOWN");
        assertThat(unknownReceipt.code()).isEqualTo("RESULT_UNKNOWN");
        assertThat(unknownReceipt.commandResultUrl()).isEqualTo(OrderCommandReceipt.commandResultUrl(commandId));
    }

    @Test
    void preservesMatchingPendingForInitialReceiptAndCommandResultQuery() {
        UUID commandId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        CoreResponse pendingResponse = new CoreResponse(ResponseStatus.OK, ResponseStatus.OK,
                CoreResultCode.MATCHING_PENDING, 4L, 9L, 17L, new byte[0]);
        AeronOrderCommandService.CommandExecution execution = new AeronOrderCommandService.CommandExecution(
                commandId, List.of(7001L), new CoreCommandOutcome.Terminal(pendingResponse),
                AeronOrderCommandService.CommandKind.PLACE);
        when(aeron.commandResult(commandId)).thenReturn(pendingResponse);

        OrderCommandReceipt initial = service.receipt(execution);
        OrderCommandReceipt queried = service.commandResult(commandId);

        assertThat(initial.outcome()).isEqualTo("MATCHING_PENDING");
        assertThat(initial.code()).isEqualTo("MATCHING_PENDING");
        assertThat(initial.commandId()).isEqualTo(commandId);
        assertThat(initial.commandResultUrl()).isEqualTo(OrderCommandReceipt.commandResultUrl(commandId));
        assertThat(queried.outcome()).isEqualTo("MATCHING_PENDING");
        assertThat(queried.code()).isEqualTo("MATCHING_PENDING");
        assertThat(queried.commandId()).isEqualTo(commandId);
        assertThat(queried.commandResultUrl()).isEqualTo(OrderCommandReceipt.commandResultUrl(commandId));
    }

    @Test
    void maximumAmendAndCancelBatchesUseOneNativeGatewayCallEach() {
        List<AmendOrderRequest> amends = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> new AmendOrderRequest(1001L, 10_000L + index, "amend-" + index,
                        60_000L + index, 2L, TimeInForce.GTC, false, "request-" + index)).toList();
        List<CancelOrderRequest> cancels = java.util.stream.IntStream.range(0, 50)
                .mapToObj(index -> new CancelOrderRequest(1001L, 20_000L + index)).toList();

        service.amendBatchCommand("amend-batch", amends);
        service.cancelBatchCommand("cancel-batch", cancels);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(aeron).commandOutcome(eq(CoreMessageType.AMEND_ORDER_BATCH), any(UUID.class), eq(1001L),
                payload.capture());
        verify(aeron).commandOutcome(eq(CoreMessageType.CANCEL_ORDER_BATCH), any(UUID.class), eq(1001L),
                payload.capture());
        assertThat(com.surprising.aeron.protocol.TradingOrderBatchCodec
                .decodeAmendOrderBatch(payload.getAllValues().getFirst()).items()).hasSize(20);
        assertThat(com.surprising.aeron.protocol.TradingOrderBatchCodec
                .decodeCancelOrderBatch(payload.getAllValues().getLast()).items()).hasSize(50);
    }

    private static PlaceOrderRequest place(String clientOrderId) {
        return new PlaceOrderRequest(1001L, clientOrderId, "BTC-USDT", OrderSide.BUY, OrderType.LIMIT,
                TimeInForce.GTC, 60_000L, 2L, MarginMode.CROSS, PositionSide.NET, false, false);
    }

    private static OrderFeeSnapshot fee() {
        return new OrderFeeSnapshot(ProductLine.LINEAR_PERPETUAL, -10L, 25L, "test");
    }

    private static InstrumentRule perpetualRule() {
        return new InstrumentRule("BTC-USDT", 7, "TRADING", InstrumentType.PERPETUAL,
                ContractType.LINEAR_PERPETUAL, "BTC", "USDT", "USDT",
                Set.of("LIMIT", "MARKET"), Set.of("GTC", "IOC", "FOK", "GTX"),
                true, true, true, 1, 1, 1_000_000, 1, Long.MAX_VALUE, 1, 100_000_000, 10_000);
    }
}
