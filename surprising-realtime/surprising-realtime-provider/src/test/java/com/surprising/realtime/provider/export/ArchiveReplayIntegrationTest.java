package com.surprising.realtime.provider.export;

import com.surprising.product.api.ProductLine;
import io.aeron.*;
import io.aeron.archive.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.archive.status.RecordingPos;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import java.net.DatagramSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ArchiveReplayIntegrationTest {
    @TempDir Path temp;

    @Test
    void followsCommitBoundaryWithOneReplayAndRetainsPartialMessage() throws Exception {
        String directory = temp.resolve("driver").toString();
        int port;
        try (var socket = new DatagramSocket(0)) {
            port = socket.getLocalPort();
        }
        String control = "aeron:udp?endpoint=127.0.0.1:" + port;
        var available = new AtomicReference<Image>();
        try (var driver = MediaDriver.launch(new MediaDriver.Context()
                     .aeronDirectoryName(directory).dirDeleteOnShutdown(true).threadingMode(ThreadingMode.SHARED));
             var server = Archive.launch(new Archive.Context().aeronDirectoryName(directory)
                     .archiveDir(temp.resolve("archive").toFile()).controlChannel(control)
                     .replicationChannel("aeron:udp?endpoint=127.0.0.1:0").threadingMode(ArchiveThreadingMode.SHARED));
             var aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(directory)
                     .availableImageHandler(image -> {
                         if (image.subscription().streamId() == 22001) available.set(image);
                     }));
             var archive = AeronArchive.connect(new AeronArchive.Context().aeron(aeron)
                     .ownsAeronClient(false).controlRequestChannel(control).controlResponseChannel("aeron:ipc"))) {
            archive.startRecording("aeron:ipc", 1001, SourceLocation.LOCAL);
            try (var publication = aeron.addExclusivePublication("aeron:ipc?mtu=128", 1001);
                 var replay = new CommittedTradeExporter.ArchiveReplay(aeron, archive, ProductLine.SPOT)) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                int recordingCounter;
                while ((recordingCounter = RecordingPos.findCounterIdBySession(
                        aeron.countersReader(), publication.sessionId())) < 0) {
                    checkDeadline(deadline);
                    Thread.sleep(1);
                }
                long recordingId = RecordingPos.getRecordingId(aeron.countersReader(), recordingCounter);
                long first = offer(publication, (byte) 1);
                long second = offer(publication, (byte) 2);
                long third = offer(publication, (byte) 3);
                while (aeron.countersReader().getCounterValue(recordingCounter) < third) {
                    checkDeadline(deadline);
                    Thread.sleep(1);
                }
                List<Byte> messages = new ArrayList<>();
                var assembler = new FragmentAssembler((buffer, offset, length, header) ->
                        messages.add(buffer.getByte(offset)));
                replay.follow(recordingId, 0, first);
                while (available.get() == null) {
                    checkDeadline(deadline);
                    Thread.sleep(1);
                }
                Image image = available.get();
                pollTo(image, assembler, first);
                assertThat(messages).containsExactly((byte) 1);
                // The second command spans two fragments. Commit only its first fragment.
                replay.follow(recordingId, first, first + 128);
                pollTo(image, assembler, first + 128);
                assertThat(messages).containsExactly((byte) 1);
                // Even unbounded consumer polling cannot read past Archive's commit counter.
                for (int i = 0; i < 20; i++) {
                    image.poll(assembler, 16);
                    Thread.sleep(5);
                }
                assertThat(image.position()).isEqualTo(first + 128);
                replay.follow(recordingId, first + 128, second);
                pollTo(image, assembler, second);
                assertThat(messages).containsExactly((byte) 1, (byte) 2);
                replay.follow(recordingId, second, third);
                pollTo(image, assembler, third);
                assertThat(messages).containsExactly((byte) 1, (byte) 2, (byte) 3);
                assertThat(available.get()).isSameAs(image);
                assertThat(image.isClosed()).isFalse();
            }
        }
    }

    private static long offer(ExclusivePublication publication, byte value) throws Exception {
        var payload = new byte[180];
        payload[0] = value;
        var buffer = new UnsafeBuffer(payload);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long result;
        while ((result = publication.offer(buffer)) < 0) {
            checkDeadline(deadline);
            Thread.sleep(1);
        }
        return result;
    }

    private static void pollTo(Image image, FragmentAssembler assembler, long position) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (image.position() < position) {
            image.boundedPoll(assembler, position, 16);
            checkDeadline(deadline);
            Thread.sleep(1);
        }
        assertThat(image.position()).isEqualTo(position);
    }

    private static void checkDeadline(long deadline) {
        if (System.nanoTime() > deadline) throw new AssertionError("archive replay test timed out");
    }
}
