package com.surprising.aeron.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class W5PublishBarrierTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void realPublishTransitionsReadyToPublishedAndBlocksBeforeAck() throws Exception {
        Path runDirectory = runDirectory("publish-before-ack");
        Path marker = W5PublishBarrier.markerPath(runDirectory);
        W5PublishBarrier barrier = W5PublishBarrier.create(runDirectory, marker);
        AtomicInteger publishes = new AtomicInteger();
        CoreExportSink sink = barrier.blockingSink((productLine, events) -> publishes.incrementAndGet());
        ExecutorService executor = Executors.newSingleThreadExecutor();

        barrier.markReady();
        Future<?> publish = executor.submit(() -> {
            sink.publish(ProductLine.LINEAR_PERPETUAL, List.of());
            return null;
        });
        try {
            barrier.await(W5PublishBarrier.State.PUBLISHED, Duration.ofSeconds(1));

            assertThat(publishes).hasValue(1);
            assertThat(publish.isDone()).isFalse();
        } finally {
            publish.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void publishedTransitionIsRejectedBeforeReady() throws Exception {
        Path runDirectory = runDirectory("invalid-transition");
        W5PublishBarrier barrier = W5PublishBarrier.create(
                runDirectory, W5PublishBarrier.markerPath(runDirectory));

        assertThatThrownBy(barrier::markPublished)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected=READY");
    }

    @Test
    void foreignMarkerPathIsRejected() throws Exception {
        Path runDirectory = runDirectory("foreign-marker");
        Path foreignMarker = temporaryDirectory.resolve("foreign").resolve("publish-before-ack.marker");

        assertThatThrownBy(() -> W5PublishBarrier.create(runDirectory, foreignMarker))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside owned run directory");
    }

    @Test
    void staleOwnedMarkerIsRejected() throws Exception {
        Path runDirectory = runDirectory("stale-marker");
        Path marker = W5PublishBarrier.markerPath(runDirectory);
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "PUBLISHED\n");

        assertThatThrownBy(() -> W5PublishBarrier.create(runDirectory, marker))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale publish barrier marker");
    }

    private Path runDirectory(String runId) throws Exception {
        Path runDirectory = temporaryDirectory.resolve("runs").resolve(runId);
        Files.createDirectories(runDirectory);
        return runDirectory;
    }
}
