package com.surprising.realtime.provider.export;

import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.provider.export.service.CommittedOrderProjectionService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExportBatchTest {
    @Test void balanceAuditFactsReachTheSameProjectionBatchAndSurviveSqlFailure() {
        var orders = mock(com.surprising.realtime.provider.export.service.CommittedOrderProjectionService.class);
        var sink = mock(ReliableTradeKafkaSink.class);
        var flow = new com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedBalanceFlow(
                ProductLine.SPOT, 7, new java.util.UUID(54, 1), "USDT", "100", "1000", 64, 1700000000064L, null);
        var attempts = new java.util.ArrayList<List<com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedBalanceFlow>>();
        doAnswer(call -> {
            attempts.add(List.copyOf(call.getArgument(6)));
            if (attempts.size() == 1) throw new IllegalStateException("SQL failed");
            return null;
        }).when(orders).persist(eq(ProductLine.SPOT), anyList(), anyList(), eq(1L), anyList(), anyList(), anyList(), eq(List.of()));
        var batch = new CommittedTradeExporter.ExportBatch(ProductLine.SPOT, orders, sink);
        batch.add(List.of(), null, 1, 1, List.of(), flow);
        assertThatThrownBy(() -> batch.flush(1)).hasMessage("SQL failed");
        verifyNoInteractions(sink);
        batch.flush(1);
        assertThat(attempts).containsExactly(List.of(flow), List.of(flow));
        verify(orders, times(2)).persist(eq(ProductLine.SPOT), anyList(), anyList(), eq(1L), anyList(), anyList(), anyList(), eq(List.of()));
    }

    @Test void liquidationOnlyChangesFlushAtTheSameExportSequenceAndSurviveSqlFailure() {
        var orders = mock(CommittedOrderProjectionService.class);
        var sink = mock(ReliableTradeKafkaSink.class);
        var batch = new CommittedTradeExporter.ExportBatch(ProductLine.LINEAR_PERPETUAL, orders, sink);
        batch.flush(0);
        clearInvocations(orders, sink);
        var change = mock(com.surprising.aeron.service.realtime.state.RealtimeStateCapture.LiquidationChange.class);
        batch.add(List.of(), null, 0, 1, List.of(), null, List.of(change));
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            assertThat(call.<List<?>>getArgument(5)).isEqualTo(List.of(change));
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("SQL failed");
            return null;
        }).when(orders)
                .persist(eq(ProductLine.LINEAR_PERPETUAL), anyList(), anyList(), eq(0L), anyList(), anyList(), anyList(), eq(List.of()));
        assertThatThrownBy(() -> batch.flush(0)).hasMessage("SQL failed");
        verifyNoInteractions(sink);
        batch.flush(0);
        verify(orders, times(2)).persist(eq(ProductLine.LINEAR_PERPETUAL), anyList(), anyList(), eq(0L), anyList(), anyList(), anyList(), eq(List.of()));
        verify(sink).publish(List.of());
        batch.flush(0);
        verifyNoMoreInteractions(orders, sink);
    }

    @Test void liquidationRepairWritesOnlyItsHistoryWithoutKafkaOrOrderWatermark() {
        var orders = mock(CommittedOrderProjectionService.class);
        var sink = mock(ReliableTradeKafkaSink.class);
        var batch = new CommittedTradeExporter.ExportBatch(ProductLine.OPTION, orders, sink,
                CommittedTradeExporter.ExportMode.LIQUIDATION_REPAIR);
        var change = mock(com.surprising.aeron.service.realtime.state.RealtimeStateCapture.LiquidationChange.class);
        doAnswer(call -> {
            assertThat(call.<List<?>>getArgument(1)).isEqualTo(List.of(change));
            return null;
        }).when(orders).repairLiquidations(eq(ProductLine.OPTION), anyList());
        batch.add(List.of(frame(ProductLine.OPTION, RealtimeFrame.Kind.TRADE, 1)), null, 1, 0, List.of(), null, List.of(change));
        batch.flush(1);
        verify(orders).repairLiquidations(eq(ProductLine.OPTION), anyList());
        verifyNoMoreInteractions(orders);
        verifyNoInteractions(sink);
    }

    @Test void executionRepairNeverPublishesKafkaOrWritesOrdersAndReceipts() {
        var repository = mock(CommittedOrderProjectionService.class);
        var sink = mock(ReliableTradeKafkaSink.class);
        var batch = new CommittedTradeExporter.ExportBatch(ProductLine.OPTION,repository,sink,CommittedTradeExporter.ExportMode.EXECUTION_REPAIR);
        batch.add(List.of(frame(ProductLine.OPTION,RealtimeFrame.Kind.TRADE,1)),null,1,0);
        batch.flush(1);
        verify(repository).repairExecutions(eq(ProductLine.OPTION),anyList());
        verifyNoMoreInteractions(repository); verifyNoInteractions(sink);
    }
    @Test void receiptRepairNeverPublishesTradesOrAdvancesTheOrderProjection() {
        var repository = mock(CommittedOrderProjectionService.class);
        var sink = mock(ReliableTradeKafkaSink.class);
        var batch = new CommittedTradeExporter.ExportBatch(ProductLine.SPOT, repository, sink,
                CommittedTradeExporter.ExportMode.COMMAND_RESULT_REPAIR);
        var result = new com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedCommandResult(
                ProductLine.SPOT, 7, new java.util.UUID(0, 1), com.surprising.aeron.protocol.CoreMessageType.CANCEL_ORDER,
                null, null, com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                com.surprising.aeron.protocol.CoreResultCode.ORDER_NOT_FOUND, 1, 10, 1000, List.of());
        doAnswer(call -> {
            assertThat(call.<List<?>>getArgument(1)).isEqualTo(List.of(result));
            return null;
        }).when(repository).repairCommandResults(eq(ProductLine.SPOT), anyList());
        batch.add(List.of(frame(ProductLine.SPOT, RealtimeFrame.Kind.TRADE, 1)), result, 1, 0);
        batch.flush(1);
        verify(repository).repairCommandResults(eq(ProductLine.SPOT), anyList());
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(sink);
    }
    @Test void receiptsFlushEvenWhenThereAreNoOrderChangesAndTheExportSequenceHasNotChanged() {
        var orders = mock(CommittedOrderProjectionService.class);
        var sink = mock(ReliableTradeKafkaSink.class);
        var batch = new CommittedTradeExporter.ExportBatch(ProductLine.SPOT, orders, sink);
        batch.flush(0);
        clearInvocations(orders, sink);
        var result = new com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedCommandResult(
                ProductLine.SPOT, 7, new java.util.UUID(0, 1), com.surprising.aeron.protocol.CoreMessageType.CANCEL_ORDER,
                null, null, com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                com.surprising.aeron.protocol.CoreResultCode.ORDER_NOT_FOUND, 1, 10, 1000, List.of());
        batch.add(List.of(), result, 0, 1);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            assertThat(call.<List<?>>getArgument(2)).isEqualTo(List.of(result));
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("SQL failed");
            return null;
        }).when(orders).persist(eq(ProductLine.SPOT), anyList(), anyList(), eq(0L), anyList(), anyList(), anyList(), eq(List.of()));
        assertThatThrownBy(() -> batch.flush(0)).hasMessage("SQL failed");
        verifyNoInteractions(sink);
        batch.flush(0);
        verify(orders, times(2)).persist(eq(ProductLine.SPOT), anyList(), anyList(), eq(0L), anyList(), anyList(), anyList(), eq(List.of()));
        verify(sink).publish(List.of());
        batch.flush(0);
        verifyNoMoreInteractions(orders, sink);
    }
    @ParameterizedTest @EnumSource(ProductLine.class)
    void coalescesSmallCommittedBatchesUntilDeadlineAndFlushesInBusinessOrder(ProductLine product) {
        var orders = mock(CommittedOrderProjectionService.class);
        var sink = mock(ReliableTradeKafkaSink.class);
        var batch = new CommittedTradeExporter.ExportBatch(product, orders, sink);
        var projected = new ArrayList<RealtimeFrame>();
        var published = new ArrayList<RealtimeFrame>();
        doAnswer(call -> { projected.addAll(call.getArgument(1)); return null; })
                .when(orders).persist(eq(product), anyList(), anyList(), anyLong(), anyList(), anyList(), anyList(), eq(List.of()));
        doAnswer(call -> { published.addAll(call.getArgument(0)); return null; })
                .when(sink).publish(anyList());
        for (int i = 0; i < 100; i++) {
            batch.add(List.of(frame(product, RealtimeFrame.Kind.ORDER, i),
                    frame(product, RealtimeFrame.Kind.TRADE, i)), null, i, i * 10_000L);
            batch.flushIfDue(i, i * 10_000L);
        }
        verifyNoInteractions(orders, sink);
        assertThat(batch.waitNanos(4_000_000,10_000_000)).isEqualTo(1_000_000);
        assertThat(batch.waitNanos(4_000_000,100_000_000)).isEqualTo(1_000_000);
        batch.flushIfDue(99, 5_000_000);
        var order = inOrder(orders, sink);
        order.verify(orders).persist(eq(product), anyList(), anyList(), eq(99L), anyList(), anyList(), anyList(), eq(List.of()));
        order.verify(sink).publish(anyList());
        assertThat(projected).extracting(RealtimeFrame::sequence).containsExactlyElementsOf(
                java.util.stream.LongStream.range(0,100).boxed().flatMap(i -> java.util.stream.Stream.of(i,i)).toList());
        assertThat(published).hasSize(100);
        batch.flush(99);
        verifyNoMoreInteractions(orders, sink);
        assertThat(batch.waitNanos(5_000_001,10_000_000)).isEqualTo(10_000_000);
    }

    @Test void enforcesCommandAndFrameBoundsBeforeDeadlineAndFlushesWatermarkWithoutOrders() {
        var orders = mock(CommittedOrderProjectionService.class);
        var sink = mock(ReliableTradeKafkaSink.class);
        var batch = new CommittedTradeExporter.ExportBatch(ProductLine.SPOT, orders, sink);
        for (int i=0;i<256;i++) batch.add(List.of(), null, i, i);
        verify(orders).persist(eq(ProductLine.SPOT), anyList(), anyList(), eq(255L), anyList(), anyList(), anyList(), eq(List.of()));
        verify(sink).publish(anyList());
        clearInvocations(orders, sink);
        batch.add(java.util.Collections.nCopies(4096, frame(ProductLine.SPOT, RealtimeFrame.Kind.ORDER, 256)), null,256,1000);
        verify(orders).persist(eq(ProductLine.SPOT), anyList(), anyList(), eq(256L), anyList(), anyList(), anyList(), eq(List.of()));
    }

    @Test void failuresRetainBatchAndDoNotMarkItFlushed() {
        var orders = mock(CommittedOrderProjectionService.class);
        var sink = mock(ReliableTradeKafkaSink.class);
        var batch = new CommittedTradeExporter.ExportBatch(ProductLine.SPOT, orders, sink);
        batch.add(List.of(frame(ProductLine.SPOT, RealtimeFrame.Kind.ORDER, 1)), null,1,0);
        doThrow(new IllegalStateException("SQL failed")).doNothing()
                .when(orders).persist(any(),anyList(), anyList(),anyLong(), anyList(), anyList(), anyList(), eq(List.of()));
        assertThatThrownBy(()->batch.flush(1)).hasMessage("SQL failed");
        verifyNoInteractions(sink);
        doAnswer(call -> { assertThat(call.<List<RealtimeFrame>>getArgument(1)).hasSize(1); return null; })
                .when(orders).persist(any(),anyList(), anyList(),anyLong(), anyList(), anyList(), anyList(), eq(List.of()));
        doThrow(new IllegalStateException("Kafka failed")).doNothing().when(sink).publish(anyList());
        assertThatThrownBy(()->batch.flush(1)).hasMessage("Kafka failed");
        batch.flush(1);
        verify(orders,times(3)).persist(any(),anyList(), anyList(),eq(1L), anyList(), anyList(), anyList(), eq(List.of()));
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void catchesUpInBoundedBatchesThenRestoresLiveDeadline(ProductLine product) {
        var orders = mock(CommittedOrderProjectionService.class);
        var sink = mock(ReliableTradeKafkaSink.class);
        var projected = new ArrayList<RealtimeFrame>();
        doAnswer(call -> { projected.addAll(call.getArgument(1)); return null; })
                .when(orders).persist(eq(product), anyList(), anyList(), anyLong(), anyList(), anyList(), anyList(), eq(List.of()));
        var batch = new CommittedTradeExporter.ExportBatch(product, orders, sink);
        batch.catchUp(true);
        // A historical command taking 2ms previously triggered a SQL commit every
        // few commands. Recovery batches retain command order without this churn.
        for (int i = 0; i < 50; i++) {
            batch.add(List.of(frame(product, RealtimeFrame.Kind.ORDER, i)), null, i, i * 2_000_000L);
            batch.flushIfDue(i, i * 2_000_000L);
        }
        verifyNoInteractions(orders, sink);
        assertThat(batch.waitNanos(98_000_000L, 10_000_000L)).isEqualTo(2_000_000L);
        batch.flushIfDue(49, 100_000_000L);
        verify(orders).persist(eq(product), anyList(), anyList(), eq(49L), anyList(), anyList(), anyList(), eq(List.of()));
        assertThat(projected).extracting(RealtimeFrame::sequence).containsExactlyElementsOf(
                java.util.stream.LongStream.range(0, 50).boxed().toList());
        verify(sink).publish(anyList());
        clearInvocations(orders, sink);
        // The original capacity limits still force recovery batches to flush.
        for (int i = 0; i < 256; i++) batch.add(List.of(), null, 50 + i, 101_000_000L + i);
        verify(orders).persist(eq(product), anyList(), anyList(), eq(305L), anyList(), anyList(), anyList(), eq(List.of()));
        clearInvocations(orders, sink);
        batch.add(List.of(frame(product, RealtimeFrame.Kind.ORDER, 306)), null, 306, 102_000_000L);
        batch.catchUp(false);
        batch.flushIfDue(306, 107_000_000L);
        verify(orders).persist(eq(product), anyList(), anyList(), eq(306L), anyList(), anyList(), anyList(), eq(List.of()));
    }

    private static RealtimeFrame frame(ProductLine product, RealtimeFrame.Kind kind, long sequence) {
        return new RealtimeFrame(product,kind,42,sequence,0,0,0,"1","order",new byte[0]);
    }
}
