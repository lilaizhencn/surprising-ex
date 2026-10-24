package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.trading.api.model.CancelAllAfterRequest;
import com.surprising.aeron.protocol.CoreCancelAllAfterStatus;
import com.surprising.aeron.protocol.CoreCancelAllAfterView;
import com.surprising.trading.api.model.CancelOpenTriggerOrdersRequest;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.TriggerOrderBatchResponse;
import com.surprising.trading.trigger.service.TriggerOrderService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CancelAllAfterServiceTest {

    @Test
    void sameMillisecondTimerChangesHaveDistinctIdentitiesWhileAnExactRetryKeepsItsIdentity() {
        var aeron = mock(OrderAeronGateway.class);
        var store = new AeronCancelAllAfterStore(aeron);
        var now = Instant.ofEpochMilli(1000);
        when(aeron.cancelAllAfterTimers(1001, "1", 0, 1)).thenReturn(List.of(
                new CoreCancelAllAfterView(1001, "1", CoreCancelAllAfterStatus.ACTIVE, 2000, 1000, 1)));

        store.set(1001, "1", 1000, now);
        store.set(1001, "1", 2000, now);
        store.set(1001, "1", 1000, now);
        store.set(1001, "1", 0, now);

        var ids = org.mockito.ArgumentCaptor.forClass(java.util.UUID.class);
        var payloads = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(aeron, org.mockito.Mockito.times(4)).command(
                org.mockito.ArgumentMatchers.eq(com.surprising.aeron.protocol.CoreMessageType.UPDATE_CANCEL_ALL_AFTER),
                ids.capture(), org.mockito.ArgumentMatchers.eq(1001L), payloads.capture());
        var identities = ids.getAllValues();
        assertThat(identities.get(0)).isNotEqualTo(identities.get(1)).isEqualTo(identities.get(2));
        assertThat(identities.get(3)).isNotIn(identities.get(0), identities.get(1));
        assertThat(payloads.getAllValues()).extracting(payload ->
                com.surprising.aeron.protocol.CoreCancelAllAfterCodec.decodeCommand(payload).countdownMillis())
                .containsExactly(1000L, 2000L, 1000L, 0L);
    }

    @Test
    void zeroCountdownDisablesAuthoritativeTimer() {
        AeronCancelAllAfterStore store = mock(AeronCancelAllAfterStore.class);
        CancelAllAfterService service = service(store, mock(OrderService.class), mock(TriggerOrderService.class));
        when(store.set(org.mockito.ArgumentMatchers.eq(1001L), org.mockito.ArgumentMatchers.eq("1"),
                org.mockito.ArgumentMatchers.eq(0L), any()))
                .thenReturn(timer("DISABLED", null));

        var response = service.set(new CancelAllAfterRequest(1001L, "1", 0L));

        assertThat(response.active()).isFalse();
        assertThat(response.instrumentId()).isEqualTo("1");
        assertThat(response.triggerAt()).isNull();
    }

    @Test
    void dueTimerCancelsBothOrderKindsAndRetriesUntilBothCorePagesAreEmpty() {
        AeronCancelAllAfterStore store = mock(AeronCancelAllAfterStore.class);
        OrderService orderService = mock(OrderService.class);
        TriggerOrderService triggerOrderService = mock(TriggerOrderService.class);
        CancelAllAfterService service = service(store, orderService, triggerOrderService);
        CoreCancelAllAfterView claimed = timer("TRIGGERING", Instant.now().minusMillis(1));
        when(orderService.cancelTimerOrders(1001L, "1", 1000)).thenReturn(new OrderBatchResponse(2, 2, 0, List.of()));
        when(triggerOrderService.cancelOpenOrders(any())).thenReturn(new TriggerOrderBatchResponse(1, 1, 0, List.of()));

        service.cancelDueTimer(claimed);

        verify(orderService).cancelTimerOrders(1001L, "1", 1000);
        verify(triggerOrderService).cancelOpenOrders(any(CancelOpenTriggerOrdersRequest.class));
        verify(store).retry(org.mockito.ArgumentMatchers.eq(claimed), any());
        verify(store, org.mockito.Mockito.never()).complete(any(), any());
    }

    @Test
    void scanClaimsAeronDueTimerBeforeExecuting() {
        AeronCancelAllAfterStore store = mock(AeronCancelAllAfterStore.class);
        OrderService orderService = mock(OrderService.class);
        TriggerOrderService triggerOrderService = mock(TriggerOrderService.class);
        CancelAllAfterService service = service(store, orderService, triggerOrderService);
        CoreCancelAllAfterView active = timer("ACTIVE", Instant.now().minusMillis(1));
        CoreCancelAllAfterView claimed = timer("TRIGGERING", Instant.ofEpochMilli(active.triggerAtEpochMillis()));
        when(store.due(any(), org.mockito.ArgumentMatchers.eq(100))).thenReturn(List.of(active));
        when(store.claim(org.mockito.ArgumentMatchers.eq(active), any())).thenReturn(Optional.of(claimed));
        when(orderService.cancelTimerOrders(1001L, "1", 1000)).thenReturn(new OrderBatchResponse(0, 0, 0, List.of()));
        when(triggerOrderService.cancelOpenOrders(any())).thenReturn(new TriggerOrderBatchResponse(0, 0, 0, List.of()));

        service.scanDueTimers();

        verify(store).claim(org.mockito.ArgumentMatchers.eq(active), any());
        verify(store).complete(org.mockito.ArgumentMatchers.eq(claimed), any());
    }

    @Test
    void aResetBetweenClaimAndReadDoesNotExecuteTheNewTimer() {
        var aeron = mock(OrderAeronGateway.class);
        var active = new CoreCancelAllAfterView(1001, "1",
                CoreCancelAllAfterStatus.ACTIVE, 2000, 1000, 1);
        var reset = new CoreCancelAllAfterView(1001, "1",
                CoreCancelAllAfterStatus.ACTIVE, 5000, 4000, 3);
        when(aeron.cancelAllAfterTimers(org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(""), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(100))).thenReturn(List.of(active));
        when(aeron.tryCommand(any(), any(), org.mockito.ArgumentMatchers.eq(1001L), any())).thenReturn(true);
        when(aeron.cancelAllAfterTimers(1001, "1", 0, 1)).thenReturn(List.of(reset));
        var orders = mock(OrderService.class);
        var triggers = mock(TriggerOrderService.class);

        service(new AeronCancelAllAfterStore(aeron), orders, triggers).scanDueTimers();

        org.mockito.Mockito.verifyNoInteractions(orders, triggers);
        verify(aeron, org.mockito.Mockito.never()).command(any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void completionAndRetryKeepTheClaimedRevisionWithoutRequerying() {
        var aeron = mock(OrderAeronGateway.class);
        var store = new AeronCancelAllAfterStore(aeron);
        var claimed = new CoreCancelAllAfterView(1001, "1",
                CoreCancelAllAfterStatus.TRIGGERING, 2000, 2000, 2);
        store.complete(claimed, Instant.ofEpochMilli(2100));
        store.retry(claimed, Instant.ofEpochMilli(2200));

        var payloads = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(aeron, org.mockito.Mockito.times(2)).command(
                org.mockito.ArgumentMatchers.eq(com.surprising.aeron.protocol.CoreMessageType.UPDATE_CANCEL_ALL_AFTER),
                any(), org.mockito.ArgumentMatchers.eq(1001L), payloads.capture());
        org.assertj.core.api.Assertions.assertThat(payloads.getAllValues())
                .allSatisfy(payload -> org.assertj.core.api.Assertions.assertThat(
                        com.surprising.aeron.protocol.CoreCancelAllAfterCodec.decodeCommand(payload).expectedRevision())
                        .isEqualTo(2));
        verify(aeron, org.mockito.Mockito.never()).cancelAllAfterTimers(
                org.mockito.ArgumentMatchers.anyLong(), any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void oneFailedClaimDoesNotLeaveOtherTimersPreclaimed() {
        var store = mock(AeronCancelAllAfterStore.class);
        var active = timer("ACTIVE", Instant.now().minusMillis(1));
        var next = new CoreCancelAllAfterView(1002, "1",
                CoreCancelAllAfterStatus.ACTIVE, 2000, 1000, 1);
        var claimed = new CoreCancelAllAfterView(1002, "1",
                CoreCancelAllAfterStatus.TRIGGERING, 2000, 2000, 2);
        when(store.due(any(), org.mockito.ArgumentMatchers.eq(100))).thenReturn(List.of(active, next));
        when(store.claim(org.mockito.ArgumentMatchers.eq(active), any())).thenThrow(new IllegalStateException("lost reply"));
        when(store.claim(org.mockito.ArgumentMatchers.eq(next), any())).thenReturn(Optional.of(claimed));
        var orders = mock(OrderService.class);
        var triggers = mock(TriggerOrderService.class);
        when(orders.cancelTimerOrders(1002L, "1", 1000)).thenReturn(new OrderBatchResponse(0, 0, 0, List.of()));
        when(triggers.cancelOpenOrders(any())).thenReturn(new TriggerOrderBatchResponse(0, 0, 0, List.of()));

        service(store, orders, triggers).scanDueTimers();

        var sequence = org.mockito.Mockito.inOrder(store, orders, triggers);
        sequence.verify(store).claim(org.mockito.ArgumentMatchers.eq(active), any());
        sequence.verify(store).claim(org.mockito.ArgumentMatchers.eq(next), any());
        sequence.verify(orders).cancelTimerOrders(1002L, "1", 1000);
        sequence.verify(triggers).cancelOpenOrders(any());
        sequence.verify(store).complete(org.mockito.ArgumentMatchers.eq(claimed), any());
    }

    private CancelAllAfterService service(AeronCancelAllAfterStore store, OrderService orderService,
                                          TriggerOrderService triggerOrderService) {
        return new CancelAllAfterService(orderService, triggerOrderService, store);
    }

    private CoreCancelAllAfterView timer(String status, Instant triggerAt) {
        return new CoreCancelAllAfterView(1001L, "1",
                CoreCancelAllAfterStatus.valueOf(status), triggerAt == null ? 0 : triggerAt.toEpochMilli(),
                Instant.now().toEpochMilli(), 1);
    }
}
