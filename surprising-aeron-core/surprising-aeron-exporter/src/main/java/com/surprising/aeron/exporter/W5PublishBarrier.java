package com.surprising.aeron.exporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

final class W5PublishBarrier {

    enum State {
        READY,
        PUBLISHED
    }

    private static final String MARKER_NAME = "publish-before-ack.marker";
    private final Path marker;
    private final CountDownLatch crash = new CountDownLatch(1);

    private W5PublishBarrier(Path marker) {
        this.marker = marker;
    }

    static Path markerPath(Path runDirectory) {
        return runDirectory.toAbsolutePath().normalize().resolve("barriers").resolve(MARKER_NAME);
    }

    static W5PublishBarrier create(Path runDirectory, Path requestedMarker) throws IOException {
        Path marker = requireOwnedMarker(runDirectory, requestedMarker);
        Path transition = transitionPath(marker);
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(transition, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("stale publish barrier marker: " + marker);
        }
        return new W5PublishBarrier(marker);
    }

    static W5PublishBarrier observe(Path runDirectory, Path requestedMarker) throws IOException {
        return new W5PublishBarrier(requireOwnedMarker(runDirectory, requestedMarker));
    }

    void markReady() throws IOException {
        writeTransition(State.READY, false);
    }

    void markPublished() throws IOException {
        State current = state().orElse(null);
        if (current != State.READY) {
            throw new IllegalStateException("invalid publish barrier transition expected=READY actual=" + current);
        }
        writeTransition(State.PUBLISHED, true);
    }

    CoreExportSink blockingSink(CoreExportSink delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return (productLine, events) -> {
            delegate.publish(productLine, events);
            markPublished();
            System.out.printf("CONTROLLED_EXPORTER_BARRIER=PUBLISHED marker=%s events=%d%n", marker, events.size());
            System.out.flush();
            crash.await();
        };
    }

    void await(State expected, Duration timeout) throws IOException, InterruptedException {
        Objects.requireNonNull(expected, "expected");
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<State> current = state();
            if (current.orElse(null) == expected) {
                return;
            }
            if (current.filter(value -> value.ordinal() > expected.ordinal()).isPresent()) {
                throw new IllegalStateException("publish barrier advanced past expected state=" + expected);
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("timed out waiting for publish barrier state=" + expected
                + " marker=" + marker);
    }

    private Optional<State> state() throws IOException {
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        String value = Files.readString(marker).trim();
        try {
            return Optional.of(State.valueOf(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("invalid publish barrier state marker=" + marker + " value=" + value,
                    exception);
        }
    }

    private static Path requireOwnedMarker(Path runDirectory, Path requestedMarker) throws IOException {
        Objects.requireNonNull(runDirectory, "runDirectory");
        Objects.requireNonNull(requestedMarker, "requestedMarker");
        Path ownedRun = runDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(ownedRun, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("owned run directory is missing: " + ownedRun);
        }
        Path expected = markerPath(ownedRun);
        Path requested = requestedMarker.toAbsolutePath().normalize();
        if (!requested.equals(expected)) {
            throw new IllegalArgumentException("publish barrier marker is outside owned run directory: " + requested);
        }
        Files.createDirectories(expected.getParent());
        Path realRun = ownedRun.toRealPath();
        Path realParent = expected.getParent().toRealPath();
        if (!Objects.equals(realParent.getParent(), realRun)) {
            throw new IllegalArgumentException("publish barrier directory is not owned by run: " + realParent);
        }
        return expected;
    }

    private static Path transitionPath(Path marker) {
        return marker.resolveSibling(marker.getFileName() + ".next");
    }

    private void writeTransition(State state, boolean replace) throws IOException {
        Path transition = transitionPath(marker);
        Files.writeString(transition, state.name() + System.lineSeparator(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        if (replace) {
            Files.move(transition, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(transition, marker, StandardCopyOption.ATOMIC_MOVE);
        }
    }
}
