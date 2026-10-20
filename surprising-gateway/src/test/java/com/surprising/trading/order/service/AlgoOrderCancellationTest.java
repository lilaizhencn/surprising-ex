package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.*;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.repository.AlgoOrderProjectionRepository;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class AlgoOrderCancellationTest {
    @Test
    void cancellationWaitsForTheSchedulerToLinkItsInFlightChild() throws Exception {
        var fixture = new Fixture(List.of());
        var placing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelRead = new CountDownLatch(1);
        var cancelThread = new AtomicReference<Thread>();
        fixture.onRead = () -> {
            if (Thread.currentThread() == cancelThread.get()) cancelRead.countDown();
        };
        when(fixture.orders.place(any())).thenAnswer(call -> {
            placing.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            fixture.childActive.set(true);
            var child = mock(OrderResponse.class);
            when(child.orderId()).thenReturn(900L);
            when(child.status()).thenReturn(OrderStatus.ACCEPTED);
            return child;
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var scan = executor.submit(fixture.service::scanDueAlgoOrders);
            assertThat(placing.await(5, TimeUnit.SECONDS)).isTrue();
            var cancel = executor.submit(() -> {
                cancelThread.set(Thread.currentThread());
                return fixture.service.cancel(new CancelAlgoOrderRequest(7, 701));
            });
            try {
                assertThat(cancelRead.await(5, TimeUnit.SECONDS)).isTrue();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (cancelThread.get().getState() != Thread.State.BLOCKED && !cancel.isDone()
                        && System.nanoTime() < deadline) Thread.sleep(1);
                assertThat(cancel.isDone()).as("cancel waits for the in-flight slice").isFalse();
                assertThat(cancelThread.get().getState()).isEqualTo(Thread.State.BLOCKED);
            } finally {
                release.countDown();
            }
            scan.get(5, TimeUnit.SECONDS);
            assertThat(cancel.get(5, TimeUnit.SECONDS).status()).isEqualTo(AlgoOrderStatus.CANCELED);
            verify(fixture.orders).cancel(new CancelOrderRequest(7, 900));
            assertThat(fixture.childActive).isFalse();
        } finally {
            release.countDown();
        }
    }

    @Test
    void unknownChildCancellationCannotMarkTheParentCanceledAndCanBeRetried() {
        var fixture = new Fixture(List.of(900L));
        fixture.childActive.set(true);
        var failure = new IllegalStateException("child cancel result unknown");
        doThrow(failure).when(fixture.orders).cancel(any());
        assertThatThrownBy(() -> fixture.service.cancel(new CancelAlgoOrderRequest(7, 701))).isSameAs(failure);
        assertThat(fixture.store.get(7, 701).statusCode()).isEqualTo(AlgoOrderStatus.CANCEL_REQUESTED.ordinal());
        assertThat(fixture.store.get(7, 701).activeChildOrderCount()).isEqualTo(1);

        fixture.completeCancellation();
        assertThat(fixture.service.cancel(new CancelAlgoOrderRequest(7, 701)).status()).isEqualTo(AlgoOrderStatus.CANCELED);
        verify(fixture.orders, times(2)).cancel(new CancelOrderRequest(7, 900));
    }

    private static final class Fixture {
        final AtomicReference<CoreAlgoOrderView> state;
        final AtomicBoolean childActive = new AtomicBoolean();
        final OrderService orders = mock(OrderService.class);
        final AeronAlgoOrderStore store;
        final AlgoOrderService service;
        Runnable onRead = () -> {};

        Fixture(List<Long> children) {
            state = new AtomicReference<>(new CoreAlgoOrderView(701, 7, "cancel-701", "1", 1,
                    CoreOrderSide.BUY, 100, 2, 1, 1, 30, CoreMarginMode.CROSS, CorePositionSide.NET,
                    false, false, CoreTimeInForce.GTC, AlgoOrderStatus.RUNNING.ordinal(), 0, "",
                    1, 1, 0, 1, 1, 1, children, 0, 0, 0));
            var properties = new TradingOrderProperties();
            properties.getKafka().setProductLine(ProductLine.SPOT);
            properties.getAlgo().setEnabled(true);
            var gateway = mock(OrderAeronGateway.class);
            when(gateway.algoOrder(7, 701)).thenAnswer(call -> {
                onRead.run();
                var v = state.get();
                boolean active = childActive.get() && !v.childOrderIds().isEmpty();
                return new CoreAlgoOrderView(v.algoOrderId(), v.userId(), v.clientAlgoOrderId(), v.instrumentId(),
                        v.algoTypeCode(), v.side(), v.priceTicks(), v.quantitySteps(), v.childQuantitySteps(),
                        v.intervalSeconds(), v.durationSeconds(), v.marginMode(), v.positionSide(), v.reduceOnly(),
                        v.postOnly(), v.timeInForce(), v.statusCode(), v.currentOrderId(), v.rejectReason(),
                        v.startAtEpochMillis(), v.nextSliceAtEpochMillis(), v.completedAtEpochMillis(),
                        v.createdAtEpochMillis(), v.updatedAtEpochMillis(), v.revision(), v.childOrderIds(),
                        0, active ? 1 : 0, active ? 1 : 0);
            });
            when(gateway.algoOrders(anyLong(), anyString(), anyLong(), anyInt()))
                    .thenAnswer(call -> List.of(state.get()));
            when(gateway.tryCommand(eq(CoreMessageType.UPSERT_ALGO_ORDER), any(), eq(7L), any()))
                    .thenAnswer(call -> { state.set(CoreAlgoOrderCodec.decode(call.getArgument(3))); return true; });
            when(gateway.command(eq(CoreMessageType.UPSERT_ALGO_ORDER), any(), eq(7L), any()))
                    .thenAnswer(call -> { state.set(CoreAlgoOrderCodec.decode(call.getArgument(3))); return null; });
            store = new AeronAlgoOrderStore(gateway, properties);
            service = new AlgoOrderService(properties, orders, store, mock(AlgoOrderProjectionRepository.class));
            completeCancellation();
        }

        void completeCancellation() {
            doAnswer(call -> { childActive.set(false); return null; }).when(orders).cancel(any());
        }
    }
}
