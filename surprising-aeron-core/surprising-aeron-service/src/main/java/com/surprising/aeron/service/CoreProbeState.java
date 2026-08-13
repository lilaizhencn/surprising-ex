package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.product.api.ProductLine;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class CoreProbeState {

    static final int MAX_IDEMPOTENCY_RESULTS = 128;
    private static final long HASH_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long HASH_PRIME = 0x100000001b3L;

    private final ProductLine productLine;
    private final LinkedHashMap<UUID, StoredResult> commandResults;
    private final LinkedHashMap<SourceKey, Long> lastSourceSequences;
    private long appliedCommandCount;
    private long probeValue;

    public CoreProbeState(ProductLine productLine) {
        this(productLine, 0, 0, new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    private CoreProbeState(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            LinkedHashMap<UUID, StoredResult> commandResults,
            LinkedHashMap<SourceKey, Long> lastSourceSequences) {
        this.productLine = productLine;
        this.appliedCommandCount = appliedCommandCount;
        this.probeValue = probeValue;
        this.commandResults = commandResults;
        this.lastSourceSequences = lastSourceSequences;
    }

    static CoreProbeState restore(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            Map<UUID, StoredResult> commandResults,
            Map<SourceKey, Long> lastSourceSequences) {
        if (appliedCommandCount < 0 || commandResults.size() > MAX_IDEMPOTENCY_RESULTS) {
            throw new IllegalArgumentException("invalid restored probe state");
        }
        return new CoreProbeState(productLine, appliedCommandCount, probeValue,
                new LinkedHashMap<>(commandResults), new LinkedHashMap<>(lastSourceSequences));
    }

    public CoreResponse apply(CoreMessage message) {
        if (message.header().productLine() != productLine) {
            return new CoreResponse(ResponseStatus.REJECTED, appliedCommandCount, stateHash());
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.STATE_HASH_QUERY) {
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, stateHash());
        }
        StoredResult duplicate = commandResults.get(message.header().commandId());
        if (duplicate != null) {
            return new CoreResponse(ResponseStatus.DUPLICATE,
                    duplicate.appliedCommandCount(), duplicate.stateHash());
        }
        if (message.header().kind() != WireMessageKind.COMMAND) {
            return new CoreResponse(ResponseStatus.REJECTED, appliedCommandCount, stateHash());
        }
        SourceKey sourceKey = new SourceKey(message.header().source(), message.header().sourceId());
        Long lastSourceSequence = lastSourceSequences.get(sourceKey);
        if (lastSourceSequence != null && message.header().sourceSequence() <= lastSourceSequence) {
            return new CoreResponse(ResponseStatus.DUPLICATE, appliedCommandCount, stateHash());
        }
        if (message.header().messageType() == CoreMessageType.PROBE_INCREMENT) {
            probeValue = Math.addExact(probeValue, CoreProtocol.decodeProbeDelta(message.payload()));
        } else if (message.header().messageType() != CoreMessageType.VERIFY_STATE_HASH) {
            return new CoreResponse(ResponseStatus.REJECTED, appliedCommandCount, stateHash());
        }
        appliedCommandCount = Math.incrementExact(appliedCommandCount);
        lastSourceSequences.put(sourceKey, message.header().sourceSequence());
        commandResults.put(message.header().commandId(),
                new StoredResult(ResponseStatus.APPLIED, appliedCommandCount, 0));
        trimIdempotencyWindow();
        long stateHash = stateHash();
        commandResults.put(message.header().commandId(),
                new StoredResult(ResponseStatus.APPLIED, appliedCommandCount, stateHash));
        return new CoreResponse(ResponseStatus.APPLIED, appliedCommandCount, stateHash);
    }

    public long stateHash() {
        long hash = HASH_OFFSET_BASIS;
        hash = mix(hash, productLine.ordinal());
        hash = mix(hash, appliedCommandCount);
        hash = mix(hash, probeValue);
        for (Map.Entry<SourceKey, Long> entry : lastSourceSequences.entrySet()) {
            hash = mix(hash, entry.getKey().source().wireCode());
            hash = mix(hash, entry.getKey().sourceId());
            hash = mix(hash, entry.getValue());
        }
        for (Map.Entry<UUID, StoredResult> entry : commandResults.entrySet()) {
            hash = mix(hash, entry.getKey().getMostSignificantBits());
            hash = mix(hash, entry.getKey().getLeastSignificantBits());
            hash = mix(hash, entry.getValue().status().wireCode());
            hash = mix(hash, entry.getValue().appliedCommandCount());
        }
        return hash;
    }

    public byte[] snapshot() {
        return CoreStateSnapshotCodec.encode(this);
    }

    public static CoreProbeState fromSnapshot(ProductLine productLine, byte[] snapshot) {
        return CoreStateSnapshotCodec.decode(snapshot, productLine);
    }

    public ProductLine productLine() {
        return productLine;
    }

    public long appliedCommandCount() {
        return appliedCommandCount;
    }

    public long probeValue() {
        return probeValue;
    }

    Map<UUID, StoredResult> commandResults() {
        return Collections.unmodifiableMap(commandResults);
    }

    Map<SourceKey, Long> lastSourceSequences() {
        return Collections.unmodifiableMap(lastSourceSequences);
    }

    private void trimIdempotencyWindow() {
        while (commandResults.size() > MAX_IDEMPOTENCY_RESULTS) {
            UUID oldest = commandResults.keySet().iterator().next();
            commandResults.remove(oldest);
        }
    }

    private static long mix(long hash, long value) {
        long result = hash;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            result ^= (value >>> shift) & 0xff;
            result *= HASH_PRIME;
        }
        return result;
    }

    record StoredResult(ResponseStatus status, long appliedCommandCount, long stateHash) {
    }

    record SourceKey(CommandSource source, long sourceId) {
    }
}
