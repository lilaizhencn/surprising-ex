package com.surprising.aeron.tools;

import static com.surprising.aeron.tools.StableLedgerJson.escape;
import static com.surprising.aeron.tools.StableLedgerJson.fields;
import static com.surprising.aeron.tools.StableLedgerJson.number;
import static com.surprising.aeron.tools.StableLedgerJson.required;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

final class StableLedgerJournal {

    private static final int CHECKPOINT_INTERVAL = 8_192;

    private final Path checkpointPath;
    private final String runId;
    private final long seed;
    private final FileChannel events;
    private final List<String> replayLines;
    private int eventsSinceCheckpoint;

    private StableLedgerJournal(Path directory, String runId, long seed, String configFingerprint) throws IOException {
        Files.createDirectories(directory);
        this.checkpointPath = directory.resolve("checkpoint.json");
        this.runId = runId;
        this.seed = seed;
        Path runPath = directory.resolve("run.json");
        Path eventsPath = directory.resolve("events.jsonl");
        validateOrCreateRun(runPath, configFingerprint);
        validateCheckpoint(eventsPath);
        discardInterruptedTail(eventsPath);
        replayLines = Files.exists(eventsPath) ? Files.readAllLines(eventsPath, StandardCharsets.UTF_8) : List.of();
        events = FileChannel.open(eventsPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
    }

    static StableLedgerJournal open(Path directory, String runId, long seed, String configFingerprint)
            throws IOException {
        return new StableLedgerJournal(directory, runId, seed, configFingerprint);
    }

    List<String> replayLines() {
        return replayLines;
    }

    void append(String line, long lastSequence) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap((line + '\n').getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) events.write(buffer);
            eventsSinceCheckpoint++;
            if (eventsSinceCheckpoint >= CHECKPOINT_INTERVAL) {
                events.force(false);
                checkpoint(lastSequence);
                eventsSinceCheckpoint = 0;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("cannot append identity ledger", exception);
        }
    }

    void flush(long lastSequence) {
        try {
            events.force(false);
            checkpoint(lastSequence);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot flush identity ledger", exception);
        }
    }

    void close(long lastSequence) {
        try {
            events.force(false);
            checkpoint(lastSequence);
            events.close();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot close identity ledger", exception);
        }
    }

    private void validateOrCreateRun(Path runPath, String configFingerprint) throws IOException {
        if (Files.exists(runPath)) {
            Map<String, String> values = fields(Files.readString(runPath, StandardCharsets.UTF_8).trim());
            if (!runId.equals(required(values, "runId")) || seed != number(values, "seed")) {
                throw new IllegalStateException("run identity does not match existing ledger");
            }
            if (!configFingerprint.equals(required(values, "configFingerprint"))) {
                throw new IllegalStateException("workload configuration does not match existing ledger");
            }
            return;
        }
        Path temporary = runPath.resolveSibling(runPath.getFileName() + ".tmp");
        Files.writeString(temporary, "{\"runId\":\"" + escape(runId) + "\",\"seed\":" + seed
                + ",\"configFingerprint\":\"" + escape(configFingerprint) + "\"}\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        try {
            Files.move(temporary, runPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, runPath);
        }
    }

    private void validateCheckpoint(Path eventsPath) throws IOException {
        if (!Files.exists(checkpointPath)) return;
        Map<String, String> values = fields(Files.readString(checkpointPath, StandardCharsets.UTF_8).trim());
        if (!runId.equals(required(values, "runId")) || seed != number(values, "seed")) {
            throw new IllegalStateException("checkpoint identity does not match run");
        }
        long eventBytes = number(values, "eventBytes");
        long actualBytes = Files.exists(eventsPath) ? Files.size(eventsPath) : 0L;
        if (eventBytes < 0 || eventBytes > actualBytes) {
            throw new IllegalStateException("corrupt ledger checkpoint beyond event log");
        }
    }

    private void discardInterruptedTail(Path path) throws IOException {
        if (!Files.exists(path) || Files.size(path) == 0L) return;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long size = channel.size();
            ByteBuffer value = ByteBuffer.allocate(1);
            channel.read(value, size - 1L);
            if (value.array()[0] == '\n') return;
            for (long cursor = size - 1L; cursor >= 0L; cursor--) {
                value.clear();
                channel.read(value, cursor);
                if (value.array()[0] == '\n') {
                    channel.truncate(cursor + 1L);
                    return;
                }
            }
            channel.truncate(0L);
        }
    }

    private void checkpoint(long lastSequence) throws IOException {
        Path temporary = checkpointPath.resolveSibling(checkpointPath.getFileName() + ".tmp");
        String json = "{\"runId\":\"" + escape(runId) + "\",\"seed\":" + seed + ",\"eventBytes\":"
                + events.size() + ",\"lastSequence\":" + lastSequence + "}\n";
        Files.writeString(temporary, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(temporary, checkpointPath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, checkpointPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
