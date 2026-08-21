package com.surprising.aeron.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StableIdentityLedger implements AutoCloseable {

    private static final Pattern FIELD = Pattern.compile("\\\"([A-Za-z]+)\\\":(\\\"(?:\\\\.|[^\\\"])*\\\"|-?[0-9]+)");
    private static final int CHECKPOINT_INTERVAL = 8_192;

    private final Path checkpointPath;
    private final String runId;
    private final long seed;
    private final FileChannel events;
    private final Map<Long, State> states = new LinkedHashMap<>();
    private int eventsSinceCheckpoint;

    private StableIdentityLedger(Path directory, String runId, long seed) throws IOException {
        Files.createDirectories(directory);
        this.checkpointPath = directory.resolve("checkpoint.json");
        this.runId = runId;
        this.seed = seed;
        Path eventsPath = directory.resolve("events.jsonl");
        validateCheckpoint(eventsPath);
        replay(eventsPath);
        this.events = FileChannel.open(eventsPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
    }

    static StableIdentityLedger open(Path directory, String runId, long seed) {
        try {
            return new StableIdentityLedger(directory, runId, seed);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot open identity ledger", exception);
        }
    }

    synchronized Intent intent(long sequence, WorkloadOperation operation, long userId,
                               String symbol, String expectedFinalState) {
        return intent(sequence, operation, userId, symbol, expectedFinalState, "");
    }

    synchronized Intent intent(long sequence, WorkloadOperation operation, long userId,
                               String symbol, String expectedFinalState, String targetIdentity) {
        State existing = states.get(sequence);
        if (existing != null) {
            Intent candidate = createIntent(sequence, operation, userId, symbol, expectedFinalState, targetIdentity);
            if (!existing.intent.equals(candidate)) {
                throw new IllegalStateException("intent mismatch at sequence " + sequence);
            }
            return existing.intent;
        }
        return createIntent(sequence, operation, userId, symbol, expectedFinalState, targetIdentity);
    }

    synchronized void scheduled(Intent intent, long intendedNanos) {
        if (states.containsKey(intent.sequence())) return;
        State state = new State(intent, intendedNanos);
        states.put(intent.sequence(), state);
        append("{\"event\":\"SCHEDULED\",\"sequence\":" + intent.sequence()
                + ",\"intentId\":\"" + intent.intentId() + "\",\"clientIdentity\":\""
                + escape(intent.clientIdentity()) + "\",\"operation\":\"" + intent.operation()
                + "\",\"userId\":" + intent.userId() + ",\"symbol\":\"" + escape(intent.symbol())
                + "\",\"expected\":\"" + escape(intent.expectedFinalState()) + "\",\"target\":\""
                + escape(intent.targetIdentity()) + "\",\"intendedNanos\":" + intendedNanos + "}");
    }

    synchronized void sent(long sequence, long sendNanos) {
        State state = required(sequence);
        state.sendNanos = sendNanos;
        append("{\"event\":\"SENT\",\"sequence\":" + sequence + ",\"sendNanos\":" + sendNanos + "}");
    }

    synchronized void http(long sequence, long httpNanos, int status, HttpOutcome outcome) {
        State state = required(sequence);
        state.httpNanos = httpNanos;
        state.httpStatus = status;
        state.outcome = outcome;
        append("{\"event\":\"HTTP\",\"sequence\":" + sequence + ",\"httpNanos\":" + httpNanos
                + ",\"status\":" + status + ",\"outcome\":\"" + outcome + "\"}");
    }

    synchronized void finished(long sequence, long finalNanos, String finalState, String resourceIdentity) {
        State state = required(sequence);
        if (state.terminal) return;
        state.finalNanos = finalNanos;
        state.finalState = finalState;
        state.resourceIdentity = resourceIdentity == null ? "" : resourceIdentity;
        state.terminal = true;
        append("{\"event\":\"FINAL\",\"sequence\":" + sequence + ",\"finalNanos\":" + finalNanos
                + ",\"finalState\":\"" + escape(finalState) + "\",\"resource\":\""
                + escape(state.resourceIdentity) + "\"}");
    }

    synchronized void aborted(long sequence, long finalNanos, String reason) {
        State state = required(sequence);
        if (state.terminal || state.aborted) return;
        state.finalNanos = finalNanos;
        state.aborted = true;
        state.finalState = reason;
        append("{\"event\":\"ABORTED\",\"sequence\":" + sequence + ",\"finalNanos\":" + finalNanos
                + ",\"reason\":\"" + escape(reason) + "\"}");
    }

    synchronized List<Intent> outstanding() {
        return states.values().stream().filter(state -> !state.terminal && !state.aborted)
                .map(state -> state.intent).sorted(Comparator.comparingLong(Intent::sequence)).toList();
    }

    synchronized List<Snapshot> snapshots() {
        return states.values().stream().map(State::snapshot).toList();
    }

    synchronized Snapshot snapshot(long sequence) {
        return required(sequence).snapshot();
    }

    synchronized long maxSequence() {
        return states.keySet().stream().mapToLong(Long::longValue).max().orElse(0L);
    }

    synchronized long scheduledCount() {
        return states.size();
    }

    synchronized long completedCount() {
        return states.values().stream().filter(state -> state.terminal).count();
    }

    synchronized long abortedCount() {
        return states.values().stream().filter(state -> state.aborted).count();
    }

    synchronized long outstandingCount() {
        return states.values().stream().filter(state -> !state.terminal && !state.aborted).count();
    }

    synchronized void flush() {
        try {
            events.force(false);
            checkpoint();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot flush identity ledger", exception);
        }
    }

    @Override
    public synchronized void close() {
        try {
            events.force(false);
            checkpoint();
            events.close();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot close identity ledger", exception);
        }
    }

    private Intent createIntent(long sequence, WorkloadOperation operation, long userId,
                                String symbol, String expectedFinalState, String targetIdentity) {
        if (sequence <= 0 || operation == null || userId <= 0 || symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("valid sequence, operation, user and symbol required");
        }
        String domain = runId + ':' + seed + ':' + sequence + ':' + operation;
        UUID intentId = UUID.nameUUIDFromBytes(("intent:" + domain).getBytes(StandardCharsets.UTF_8));
        String client = operation.name().toLowerCase(java.util.Locale.ROOT) + '-'
                + UUID.nameUUIDFromBytes(("client:" + domain).getBytes(StandardCharsets.UTF_8));
        return new Intent(sequence, intentId, client, operation, userId, symbol, expectedFinalState,
                targetIdentity == null ? "" : targetIdentity);
    }

    private State required(long sequence) {
        State state = states.get(sequence);
        if (state == null) throw new IllegalStateException("unknown intent sequence " + sequence);
        return state;
    }

    private void append(String line) {
        try {
            byte[] bytes = (line + '\n').getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) events.write(buffer);
            eventsSinceCheckpoint++;
            if (eventsSinceCheckpoint >= CHECKPOINT_INTERVAL) {
                events.force(false);
                checkpoint();
                eventsSinceCheckpoint = 0;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("cannot append identity ledger", exception);
        }
    }

    private void replay(Path path) throws IOException {
        if (!Files.exists(path)) return;
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            try {
                replay(fields(line));
            } catch (RuntimeException exception) {
                throw new IllegalStateException("corrupt ledger line " + (index + 1), exception);
            }
        }
    }

    private void replay(Map<String, String> fields) {
        String event = required(fields, "event");
        long sequence = number(fields, "sequence");
        if ("SCHEDULED".equals(event)) {
            WorkloadOperation operation = WorkloadOperation.valueOf(required(fields, "operation"));
            Intent intent = new Intent(sequence, UUID.fromString(required(fields, "intentId")),
                    required(fields, "clientIdentity"), operation, number(fields, "userId"),
                    required(fields, "symbol"), required(fields, "expected"), required(fields, "target"));
            Intent deterministic = createIntent(sequence, operation, intent.userId(), intent.symbol(),
                    intent.expectedFinalState(), intent.targetIdentity());
            if (!intent.intentId().equals(deterministic.intentId())
                    || !intent.clientIdentity().equals(deterministic.clientIdentity())) {
                throw new IllegalStateException("unstable identity at sequence " + sequence);
            }
            if (states.putIfAbsent(sequence, new State(intent, number(fields, "intendedNanos"))) != null) {
                throw new IllegalStateException("duplicate scheduled sequence " + sequence);
            }
            return;
        }
        State state = required(sequence);
        switch (event) {
            case "SENT" -> state.sendNanos = number(fields, "sendNanos");
            case "HTTP" -> {
                state.httpNanos = number(fields, "httpNanos");
                state.httpStatus = Math.toIntExact(number(fields, "status"));
                state.outcome = HttpOutcome.valueOf(required(fields, "outcome"));
            }
            case "FINAL" -> {
                state.finalNanos = number(fields, "finalNanos");
                state.finalState = required(fields, "finalState");
                state.resourceIdentity = required(fields, "resource");
                state.terminal = true;
            }
            case "ABORTED" -> {
                state.finalNanos = number(fields, "finalNanos");
                state.finalState = required(fields, "reason");
                state.aborted = true;
            }
            default -> throw new IllegalStateException("unknown ledger event " + event);
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

    private void checkpoint() throws IOException {
        Path temporary = checkpointPath.resolveSibling(checkpointPath.getFileName() + ".tmp");
        String json = "{\"runId\":\"" + escape(runId) + "\",\"seed\":" + seed + ",\"eventBytes\":"
                + events.size() + ",\"lastSequence\":" + maxSequence() + "}\n";
        Files.writeString(temporary, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(temporary, checkpointPath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, checkpointPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Map<String, String> fields(String json) {
        if (json == null || json.length() < 2 || json.charAt(0) != '{' || json.charAt(json.length() - 1) != '}') {
            throw new IllegalStateException("not a JSON object");
        }
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = FIELD.matcher(json);
        while (matcher.find()) {
            String raw = matcher.group(2);
            result.put(matcher.group(1), raw.charAt(0) == '"' ? unescape(raw.substring(1, raw.length() - 1)) : raw);
        }
        if (result.isEmpty()) throw new IllegalStateException("empty JSON object");
        return result;
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null) throw new IllegalStateException("missing ledger field " + name);
        return value;
    }

    private static long number(Map<String, String> values, String name) {
        return Long.parseLong(required(values, name));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaped) {
                result.append(current == 'n' ? '\n' : current == 'r' ? '\r' : current);
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else {
                result.append(current);
            }
        }
        if (escaped) throw new IllegalStateException("unterminated JSON escape");
        return result.toString();
    }

    record Intent(long sequence, UUID intentId, String clientIdentity, WorkloadOperation operation,
                  long userId, String symbol, String expectedFinalState, String targetIdentity) {
    }

    record Snapshot(Intent intent, long intendedNanos, long sendNanos, long httpNanos, long finalNanos,
                    int httpStatus, HttpOutcome outcome, String finalState, String resourceIdentity,
                    boolean terminal, boolean aborted) {
    }

    private static final class State {
        private final Intent intent;
        private final long intendedNanos;
        private long sendNanos;
        private long httpNanos;
        private long finalNanos;
        private int httpStatus;
        private HttpOutcome outcome;
        private String finalState = "";
        private String resourceIdentity = "";
        private boolean terminal;
        private boolean aborted;

        private State(Intent intent, long intendedNanos) {
            this.intent = intent;
            this.intendedNanos = intendedNanos;
        }

        private Snapshot snapshot() {
            return new Snapshot(intent, intendedNanos, sendNanos, httpNanos, finalNanos, httpStatus,
                    outcome, finalState, resourceIdentity, terminal, aborted);
        }
    }
}
