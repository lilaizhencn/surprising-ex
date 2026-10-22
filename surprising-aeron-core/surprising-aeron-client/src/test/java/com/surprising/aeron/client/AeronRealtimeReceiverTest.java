package com.surprising.aeron.client;

import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.protocol.RealtimeFrameCodec;
import com.surprising.product.api.ProductLine;
import io.aeron.driver.MediaDriver;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;

class AeronRealtimeReceiverTest {
    @Test void decodeAndHandlerFailuresHaveSeparateCountersAndRequireRecovery() throws Exception {
        String directory = System.getProperty("java.io.tmpdir") + "/receiver-failure-" + UUID.randomUUID();
        var faults = new LinkedBlockingQueue<AeronRealtimeReceiver.Failure>();
        var received = new LinkedBlockingQueue<RealtimeFrame>();
        var connected = new AtomicBoolean();
        var reject = new AtomicBoolean(true);
        var outbox = new RealtimeOutbox(64, 1_048_576);
        AeronRealtimeReceiver receiver;
        try (var driver = MediaDriver.launch(new MediaDriver.Context().aeronDirectoryName(directory)
                .dirDeleteOnStart(true).dirDeleteOnShutdown(true));
             var endpoint = new AeronRealtimeReceiver(directory, "aeron:ipc", 93, frame -> {
                 if (frame.sequence() == 122) {
                     connected.set(true);
                     return;
                 }
                 if (reject.get()) throw new IllegalStateException("downstream unavailable");
                 received.add(frame);
             }, faults::add);
             var sender = new AeronRealtimeSender(outbox, directory, "aeron:ipc", 93)) {
            receiver = endpoint;
            await(endpoint::ready);
            var probe = new RealtimeFrame(ProductLine.SPOT, RealtimeFrame.Kind.ORDER, 42, 122, 0, 456, 0,
                    "1", "789", new byte[0]);
            awaitConnection(outbox, sender, RealtimeFrameCodec.encode(probe), connected::get);
            byte[] corrupt = new byte[64];
            sendOnce(outbox, sender, corrupt);
            await(() -> endpoint.decodeFailures() > 0);
            var decode = faults.poll(1, TimeUnit.SECONDS);
            assertThat(decode).isNotNull();
            assertThat(decode.kind()).isEqualTo(AeronRealtimeReceiver.FailureKind.DECODE);
            assertThat(decode.product()).isEqualTo("unknown");
            assertThat(decode.sequence()).isEqualTo(-1);
            assertThat(endpoint.decodeFailures()).isEqualTo(1);
            assertThat(endpoint.ready()).isFalse();
            assertThat(endpoint.recoveryRequired()).isTrue();
            assertThat(endpoint.handlerFailures()).isZero();
            long decodeVersion = endpoint.recoveryVersion();
            assertThat(decodeVersion).isPositive();
            assertThat(endpoint.resumeAfterRecovery(decodeVersion)).isTrue();
            var frame = new RealtimeFrame(ProductLine.SPOT, RealtimeFrame.Kind.ORDER, 42, 123, 0, 456, 0,
                    "1", "789", new byte[0]);
            sendOnce(outbox, sender, RealtimeFrameCodec.encode(frame));
            await(() -> endpoint.handlerFailures() > 0);
            var handler = faults.poll(1, TimeUnit.SECONDS);
            assertThat(handler).isNotNull();
            assertThat(handler.kind()).isEqualTo(AeronRealtimeReceiver.FailureKind.HANDLER);
            assertThat(handler.product()).isEqualTo("SPOT");
            assertThat(handler.sequence()).isEqualTo(123);
            assertThat(endpoint.decodeFailures()).isEqualTo(1);
            assertThat(endpoint.handlerFailures()).isEqualTo(1);
            assertThat(endpoint.transportFailures()).isZero();
            assertThat(endpoint.ready()).isFalse();
            assertThat(endpoint.recoveryRequired()).isTrue();
            long handlerVersion = endpoint.recoveryVersion();
            assertThat(handlerVersion).isGreaterThan(decodeVersion);
            reject.set(false);
            assertThat(endpoint.resumeAfterRecovery(handlerVersion)).isTrue();
            sendOnce(outbox, sender, RealtimeFrameCodec.encode(frame));
            await(() -> !received.isEmpty());
            assertThat(received.poll()).isEqualTo(frame);
            assertThat(endpoint.ready()).isTrue();
            assertThat(endpoint.recoveryRequired()).isFalse();
            assertThat(endpoint.failures()).isEqualTo(2);
            assertThat(sender.failures()).isZero();
            assertThat(faults).isEmpty();
        }
        assertThat(receiver.terminated()).isTrue();
    }

    @Test void oldRecoveryAcknowledgementCannotClearANewerConcurrentFailure() {
        try (var receiver = new AeronRealtimeReceiver(System.getProperty("java.io.tmpdir") + "/receiver-generation-" + UUID.randomUUID(),
                "aeron:ipc", 92, ignored -> {}, ignored -> {})) {
            receiver.transportFailed(new IllegalStateException("first"));
            long first = receiver.recoveryVersion();
            receiver.transportFailed(new IllegalStateException("second"));
            long second = receiver.recoveryVersion();
            assertThat(second).isGreaterThan(first);
            assertThat(receiver.resumeAfterRecovery(first)).isFalse();
            assertThat(receiver.recoveryRequired()).isTrue();
            assertThat(receiver.ready()).isFalse();
            assertThat(receiver.resumeAfterRecovery(second)).isTrue();
            assertThat(receiver.recoveryRequired()).isFalse();
        }
    }

    @Test void closeWithoutDriverConfirmsWorkerExitAndRestoresInterruptFlag() throws Exception {
        var receiver = new AeronRealtimeReceiver(System.getProperty("java.io.tmpdir") + "/missing-driver-" + UUID.randomUUID(),
                "aeron:ipc", 91, ignored -> {});
        Thread.sleep(250);
        Thread.currentThread().interrupt();
        try {
            receiver.close();
            assertThat(receiver.terminated()).isTrue();
            assertThat(receiver.ready()).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            receiver.close();
        } finally { Thread.interrupted(); }
    }

    private static void await(BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(done.getAsBoolean()).isTrue();
    }
    private static void awaitConnection(RealtimeOutbox outbox, AeronRealtimeSender sender, byte[] probe,
                                        BooleanSupplier connected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        long submitted = 0;
        // Only this harmless sequence may repeat while the publication is still NOT_CONNECTED.
        while (!connected.getAsBoolean() && System.nanoTime() < deadline) {
            send(outbox, probe);
            submitted++;
            while (sender.sent() + sender.dropped() < submitted && System.nanoTime() < deadline)
                Thread.sleep(1);
            Thread.sleep(10);
        }
        assertThat(connected.getAsBoolean()).isTrue();
        assertThat(sender.sent() + sender.dropped()).isEqualTo(submitted);
    }

    private static void sendOnce(RealtimeOutbox outbox, AeronRealtimeSender sender, byte[] bytes)
            throws InterruptedException {
        long sent = sender.sent(), dropped = sender.dropped();
        send(outbox, bytes);
        await(() -> sender.sent() + sender.dropped() > sent + dropped);
        assertThat(sender.sent()).isEqualTo(sent + 1);
        assertThat(sender.dropped()).isEqualTo(dropped);
    }

    private static void send(RealtimeOutbox outbox, byte[] bytes) {
        outbox.begin(); outbox.stage(bytes); outbox.commit();
    }
}
