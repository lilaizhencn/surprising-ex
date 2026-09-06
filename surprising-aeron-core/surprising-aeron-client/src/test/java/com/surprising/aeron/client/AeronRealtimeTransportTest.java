package com.surprising.aeron.client;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import io.aeron.driver.MediaDriver;
import org.junit.jupiter.api.Test;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class AeronRealtimeTransportTest {
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
