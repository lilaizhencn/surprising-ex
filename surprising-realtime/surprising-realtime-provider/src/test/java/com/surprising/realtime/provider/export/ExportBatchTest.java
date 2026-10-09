package com.surprising.realtime.provider.export;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ExportBatchTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void coalescesSmallCommittedBatchesUntilDeadlineAndFlushesInBusinessOrder(ProductLine product) {
        var orders = mock(CommittedOrderProjectionRepository.class);
        var sink = mock(ReliableTradeKafkaSink.class);
        var batch = new CommittedTradeExporter.ExportBatch(product, orders, sink);
        var projected = new ArrayList<RealtimeFrame>();
        var published = new ArrayList<RealtimeFrame>();
        doAnswer(call -> { projected.addAll(call.getArgument(1)); return null; })
                .when(orders).persist(eq(product), anyList(), anyLong());
        doAnswer(call -> { published.addAll(call.getArgument(0)); return null; })
                .when(sink).publish(anyList());
        for (int i = 0; i < 100; i++) {
            batch.add(List.of(frame(product, RealtimeFrame.Kind.ORDER, i),
                    frame(product, RealtimeFrame.Kind.TRADE, i)), i, i * 10_000L);
            batch.flushIfDue(i, i * 10_000L);
        }
        verifyNoInteractions(orders, sink);
        assertThat(batch.waitNanos(4_000_000,10_000_000)).isEqualTo(1_000_000);
        assertThat(batch.waitNanos(4_000_000,100_000_000)).isEqualTo(1_000_000);
        batch.flushIfDue(99, 5_000_000);
        var order = inOrder(orders, sink);
        order.verify(orders).persist(eq(product), anyList(), eq(99L));
        order.verify(sink).publish(anyList());
        assertThat(projected).extracting(RealtimeFrame::sequence).containsExactlyElementsOf(
                java.util.stream.LongStream.range(0,100).boxed().toList());
        assertThat(published).hasSize(100);
        batch.flush(99);
        verifyNoMoreInteractions(orders, sink);
        assertThat(batch.waitNanos(5_000_001,10_000_000)).isEqualTo(10_000_000);
    }

    @Test void enforcesCommandAndFrameBoundsBeforeDeadlineAndFlushesWatermarkWithoutOrders() {
        var orders = mock(CommittedOrderProjectionRepository.class);
        var sink = mock(ReliableTradeKafkaSink.class);
        var batch = new CommittedTradeExporter.ExportBatch(ProductLine.SPOT, orders, sink);
        for (int i=0;i<256;i++) batch.add(List.of(), i, i);
        verify(orders).persist(eq(ProductLine.SPOT), anyList(), eq(255L));
        verify(sink).publish(anyList());
        clearInvocations(orders, sink);
        batch.add(java.util.Collections.nCopies(4096, frame(ProductLine.SPOT, RealtimeFrame.Kind.ORDER, 256)),256,1000);
        verify(orders).persist(eq(ProductLine.SPOT), anyList(), eq(256L));
    }

    @Test void failuresRetainBatchAndDoNotMarkItFlushed() {
        var orders = mock(CommittedOrderProjectionRepository.class);
        var sink = mock(ReliableTradeKafkaSink.class);
        var batch = new CommittedTradeExporter.ExportBatch(ProductLine.SPOT, orders, sink);
        batch.add(List.of(frame(ProductLine.SPOT, RealtimeFrame.Kind.ORDER, 1)),1,0);
        doThrow(new IllegalStateException("SQL failed")).doNothing()
                .when(orders).persist(any(),anyList(),anyLong());
        assertThatThrownBy(()->batch.flush(1)).hasMessage("SQL failed");
        verifyNoInteractions(sink);
        doAnswer(call -> { assertThat(call.<List<RealtimeFrame>>getArgument(1)).hasSize(1); return null; })
                .when(orders).persist(any(),anyList(),anyLong());
        doThrow(new IllegalStateException("Kafka failed")).doNothing().when(sink).publish(anyList());
        assertThatThrownBy(()->batch.flush(1)).hasMessage("Kafka failed");
        batch.flush(1);
        verify(orders,times(3)).persist(any(),anyList(),eq(1L));
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void catchesUpInBoundedBatchesThenRestoresLiveDeadline(ProductLine product) {
        var orders = mock(CommittedOrderProjectionRepository.class);
        var sink = mock(ReliableTradeKafkaSink.class);
        var projected = new ArrayList<RealtimeFrame>();
        doAnswer(call -> { projected.addAll(call.getArgument(1)); return null; })
                .when(orders).persist(eq(product), anyList(), anyLong());
        var batch = new CommittedTradeExporter.ExportBatch(product, orders, sink);
        batch.catchUp(true);
        // A historical command taking 2ms previously triggered a SQL commit every
        // few commands. Recovery batches retain command order without this churn.
        for (int i = 0; i < 50; i++) {
            batch.add(List.of(frame(product, RealtimeFrame.Kind.ORDER, i)), i, i * 2_000_000L);
            batch.flushIfDue(i, i * 2_000_000L);
        }
        verifyNoInteractions(orders, sink);
        assertThat(batch.waitNanos(98_000_000L, 10_000_000L)).isEqualTo(2_000_000L);
        batch.flushIfDue(49, 100_000_000L);
        verify(orders).persist(eq(product), anyList(), eq(49L));
        assertThat(projected).extracting(RealtimeFrame::sequence).containsExactlyElementsOf(
                java.util.stream.LongStream.range(0, 50).boxed().toList());
        verify(sink).publish(anyList());
        clearInvocations(orders, sink);
        // The original capacity limits still force recovery batches to flush.
        for (int i = 0; i < 256; i++) batch.add(List.of(), 50 + i, 101_000_000L + i);
        verify(orders).persist(eq(product), anyList(), eq(305L));
        clearInvocations(orders, sink);
        batch.add(List.of(frame(product, RealtimeFrame.Kind.ORDER, 306)), 306, 102_000_000L);
        batch.catchUp(false);
        batch.flushIfDue(306, 107_000_000L);
        verify(orders).persist(eq(product), anyList(), eq(306L));
    }

    private static RealtimeFrame frame(ProductLine product, RealtimeFrame.Kind kind, long sequence) {
        return new RealtimeFrame(product,kind,42,sequence,0,0,0,"1","order",new byte[0]);
    }
}
