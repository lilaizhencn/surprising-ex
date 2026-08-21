package com.surprising.websocket.validation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

public final class WebSocketAuditLedger implements AutoCloseable {

    private static final String GENESIS_HASH = "0".repeat(64);

    private final Path path;
    private final ObjectMapper objectMapper;
    private final FileChannel channel;
    private final List<WebSocketAuditRecord> records;
    private long nextSequence;
    private String previousHash;

    private WebSocketAuditLedger(Path path,
                                 ObjectMapper objectMapper,
                                 FileChannel channel,
                                 List<WebSocketAuditRecord> records,
                                 long nextSequence,
                                 String previousHash) {
        this.path = path;
        this.objectMapper = objectMapper;
        this.channel = channel;
        this.records = records;
        this.nextSequence = nextSequence;
        this.previousHash = previousHash;
    }

    public static WebSocketAuditLedger open(Path path, ObjectMapper objectMapper) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(objectMapper, "objectMapper");
        try {
            Path absolute = path.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Loaded loaded = load(absolute, objectMapper);
            FileChannel channel = FileChannel.open(absolute, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            return new WebSocketAuditLedger(absolute, objectMapper, channel,
                    new ArrayList<>(loaded.records()), loaded.nextSequence(), loaded.previousHash());
        } catch (Exception ex) {
            throw corrupt(path, ex);
        }
    }

    public synchronized void append(WebSocketAuditRecord record) {
        Objects.requireNonNull(record, "record");
        ensureOpen();
        try {
            String recordJson = objectMapper.writeValueAsString(record);
            String checksum = checksum(nextSequence, previousHash, recordJson);
            LedgerLine line = new LedgerLine(nextSequence, previousHash, record, checksum);
            byte[] bytes = (objectMapper.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8);
            try (var ignored = channel.lock()) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
            records.add(record);
            nextSequence++;
            previousHash = checksum;
        } catch (Exception ex) {
            throw new IllegalStateException("cannot append WebSocket audit ledger " + path, ex);
        }
    }

    public synchronized List<WebSocketAuditRecord> records() {
        return List.copyOf(records);
    }

    public synchronized void flush() {
        ensureOpen();
        try {
            channel.force(true);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot flush WebSocket audit ledger " + path, ex);
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (channel.isOpen()) {
                channel.force(true);
            }
            channel.close();
        } catch (IOException ex) {
            throw new IllegalStateException("cannot close WebSocket audit ledger " + path, ex);
        }
    }

    private void ensureOpen() {
        if (!channel.isOpen()) {
            throw new IllegalStateException("WebSocket audit ledger is closed");
        }
    }

    private static Loaded load(Path path, ObjectMapper objectMapper) throws IOException {
        if (!Files.exists(path)) {
            return new Loaded(List.of(), 0L, GENESIS_HASH);
        }
        List<WebSocketAuditRecord> records = new ArrayList<>();
        String previousHash = GENESIS_HASH;
        long sequence = 0L;
        for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (rawLine.isBlank()) {
                throw new IllegalStateException("blank ledger line at sequence " + sequence);
            }
            LedgerLine line = objectMapper.readValue(rawLine, LedgerLine.class);
            String recordJson = objectMapper.writeValueAsString(line.record());
            String expectedChecksum = checksum(sequence, previousHash, recordJson);
            if (line.sequence() != sequence || !previousHash.equals(line.previousHash())
                    || !expectedChecksum.equals(line.checksum())) {
                throw new IllegalStateException("checksum chain mismatch at sequence " + sequence);
            }
            records.add(line.record());
            previousHash = line.checksum();
            sequence++;
        }
        return new Loaded(List.copyOf(records), sequence, previousHash);
    }

    private static String checksum(long sequence, String previousHash, String recordJson) {
        return WebSocketAuditRecord.sha256(sequence + "\n" + previousHash + "\n" + recordJson);
    }

    private static IllegalStateException corrupt(Path path, Exception cause) {
        return new IllegalStateException("WebSocket audit ledger is corrupt: " + path, cause);
    }

    private record LedgerLine(long sequence,
                              String previousHash,
                              WebSocketAuditRecord record,
                              String checksum) {
    }

    private record Loaded(List<WebSocketAuditRecord> records, long nextSequence, String previousHash) {
    }
}
