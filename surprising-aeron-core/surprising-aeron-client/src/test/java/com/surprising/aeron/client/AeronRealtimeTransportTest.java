package com.surprising.aeron.client;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import io.aeron.driver.MediaDriver;
import org.junit.jupiter.api.Test;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class AeronRealtimeTransportTest {
    @Test void driverLossDoesNotExitProcessAndBothIdleEndpointsReconnect() throws Exception {
        String directory = System.getProperty("java.io.tmpdir") + "/realtime-restart-" + java.util.UUID.randomUUID();
        var received = new LinkedBlockingQueue<RealtimeFrame>();
        var outbox = new RealtimeOutbox(64, 1_048_576);
        MediaDriver driver = MediaDriver.launch(new MediaDriver.Context().aeronDirectoryName(directory)
                .dirDeleteOnStart(true).dirDeleteOnShutdown(true));
        try (var receiver = new AeronRealtimeReceiver(directory, "aeron:ipc", 73, received::add);
             var sender = new AeronRealtimeSender(outbox, directory, "aeron:ipc", 73)) {
            var frame = new RealtimeFrame(ProductLine.SPOT, RealtimeFrame.Kind.ORDER, 42, 123, 0, 456, 0,
                    "BTC-USDT", "789", new byte[0]);
            assertThat(deliver(outbox, received, frame)).usingRecursiveComparison().isEqualTo(frame);
            driver.close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while ((sender.failures() == 0 || receiver.failures() == 0) && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertThat(sender.failures()).isPositive();
            assertThat(receiver.failures()).isPositive();
            assertThat(receiver.ready()).isFalse();
            driver = MediaDriver.launch(new MediaDriver.Context().aeronDirectoryName(directory)
                    .dirDeleteOnStart(true).dirDeleteOnShutdown(true));
            received.clear();
            var next = new RealtimeFrame(ProductLine.SPOT, RealtimeFrame.Kind.ORDER, 42, 124, 0, 457, 0,
                    "BTC-USDT", "790", new byte[0]);
            assertThat(deliver(outbox, received, next)).usingRecursiveComparison().isEqualTo(next);
            assertThat(receiver.ready()).isTrue();
        } finally { driver.close(); }
    }

    private static RealtimeFrame deliver(RealtimeOutbox outbox, LinkedBlockingQueue<RealtimeFrame> received,
                                         RealtimeFrame frame) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        RealtimeFrame actual = null;
        while (actual == null && System.nanoTime() < deadline) {
            outbox.begin();outbox.stage(RealtimeFrameCodec.encode(frame));outbox.commit();
            actual = received.poll(50, TimeUnit.MILLISECONDS);
        }
        return actual;
    }

    @Test void deliversFragmentedFramesThroughRealDriverAndKeepsNodeStreamsSeparate() throws Exception {
        String directory = System.getProperty("java.io.tmpdir") + "/realtime-test-" + java.util.UUID.randomUUID();
        var received = new LinkedBlockingQueue<RealtimeFrame>();
        var other = new LinkedBlockingQueue<RealtimeFrame>();
        try (var driver = MediaDriver.launch(new MediaDriver.Context().aeronDirectoryName(directory)
                    .dirDeleteOnStart(true).dirDeleteOnShutdown(true));
             var receiver = new AeronRealtimeReceiver(directory,"aeron:ipc",71,received::add);
             var unrelated = new AeronRealtimeReceiver(directory,"aeron:ipc",72,other::add)) {
            var outbox = new RealtimeOutbox(64,1_048_576);
            try (var sender = new AeronRealtimeSender(outbox,directory,"aeron:ipc",71)) {
                var frame = new RealtimeFrame(ProductLine.SPOT,RealtimeFrame.Kind.ORDER,42,123,0,456,0,
                        "BTC-USDT","789",new byte[100_000]);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                RealtimeFrame actual=null;
                while(actual==null && System.nanoTime()<deadline) {
                    outbox.begin();outbox.stage(RealtimeFrameCodec.encode(frame));outbox.commit();
                    actual=received.poll(50,TimeUnit.MILLISECONDS);
                }
                assertThat(actual).usingRecursiveComparison().isEqualTo(frame);
                assertThat(other).isEmpty(); assertThat(receiver.failures()).isZero();
            }
        }
    }
}
