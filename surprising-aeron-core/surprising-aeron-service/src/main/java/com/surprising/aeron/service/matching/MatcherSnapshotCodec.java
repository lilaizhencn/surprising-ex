package com.surprising.aeron.service.matching;

import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.service.state.LaneTopology;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.processors.journaling.ISerializationProcessor.SerializedModuleType;
import exchange.core2.core.processors.journaling.InMemorySerializationProcessor.SerializedModule;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32C;

public final class MatcherSnapshotCodec {

    private static final int MAGIC = 0x4d534e50;
    private static final int VERSION = 3;
    private static final int MAX_SNAPSHOT_BYTES = 48 * 1024 * 1024;
    private static final int MAX_REGISTRY_ENTRIES = 1_000_000;
    private static final int MAX_MODULE_BYTES = 32 * 1024 * 1024;

    private MatcherSnapshotCodec() {
    }

    public static byte[] encode(MatcherSnapshot snapshot) {
        try {
            ByteArrayOutputStream body = new BoundedByteArrayOutputStream(MAX_SNAPSHOT_BYTES - Long.BYTES);
            try (DataOutputStream output = new DataOutputStream(body)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(ProductLineWireCode.encode(snapshot.productLine()));
                writeText(output, snapshot.coreShardId());
                output.writeInt(snapshot.routeVersion());
                output.writeInt(snapshot.topology().matchingEngineCount());
                output.writeInt(snapshot.topology().matcherShardMask());
                output.writeInt(snapshot.topology().accountLaneCount());
                output.writeLong(snapshot.topology().accountLaneSeed());
                output.writeInt(snapshot.topology().matcherWindowSize());
                output.writeInt(snapshot.topology().matchingCompletionCapacity());
                output.writeInt(snapshot.topology().accountLaneQueueCapacity());
                output.writeLong(snapshot.topologyHash());
                output.writeLong(snapshot.snapshotId());
                output.writeLong(snapshot.coreSequence());
                output.writeLong(snapshot.matcherSequence());
                output.writeLong(snapshot.matcherPrefixDigest());
                output.writeLong(snapshot.coreBusinessStateHash());
                output.writeInt(snapshot.engineStateHash());
                output.writeInt(snapshot.bookStateHash());
                output.writeLong(snapshot.symbolRegistryHash());
                output.writeLong(snapshot.symbolRouteHash());
                output.writeLong(snapshot.userRegistryHash());
                output.writeLong(snapshot.instrumentRegistryHash());
                output.writeLong(snapshot.activeOrderHash());
                writeText(output, snapshot.forkGitSha());
                writeText(output, snapshot.artifactSha256());
                output.writeLong(snapshot.matcherConfigHash());
                output.writeInt(snapshot.symbols().size());
                for (Map.Entry<String, Integer> entry : snapshot.symbols().entrySet()) {
                    writeText(output, entry.getKey());
                    output.writeInt(entry.getValue());
                }
                output.writeInt(snapshot.users().size());
                for (Long userId : snapshot.users()) output.writeLong(userId);
                output.writeInt(snapshot.modules().size());
                for (SerializedModule module : snapshot.modules()) {
                    byte[] data = module.data();
                    if (data.length == 0 || data.length > MAX_MODULE_BYTES) {
                        throw new IllegalArgumentException("matcher module exceeds maximum size");
                    }
                    output.writeInt(module.type().ordinal());
                    output.writeInt(module.instanceId());
                    output.writeLong(module.sequence());
                    output.writeLong(module.timestampNs());
                    output.writeInt(data.length);
                    output.writeLong(module.checksum());
                    output.write(data);
                }
            }
            byte[] encodedBody = body.toByteArray();
            if (encodedBody.length > MAX_SNAPSHOT_BYTES - Long.BYTES) {
                throw new IllegalArgumentException("matcher snapshot exceeds maximum size");
            }
            ByteBuffer encoded = ByteBuffer.allocate(encodedBody.length + Long.BYTES);
            encoded.put(encodedBody);
            encoded.putLong(Integer.toUnsignedLong(checksum(encodedBody)));
            return encoded.array();
        } catch (IOException exception) {
            throw new IllegalStateException("unable to encode matcher snapshot", exception);
        }
    }

    public static MatcherSnapshot decode(byte[] encoded) {
        if (encoded != null && encoded.length > MAX_SNAPSHOT_BYTES) {
            throw new ProtocolException("matcher snapshot exceeds maximum size");
        }
        if (encoded == null || encoded.length < 64) {
            throw new ProtocolException("matcher snapshot is truncated");
        }
        long storedChecksum = ByteBuffer.wrap(encoded, encoded.length - Long.BYTES, Long.BYTES).getLong();
        int actualChecksum = checksum(encoded, 0, encoded.length - Long.BYTES);
        if (storedChecksum != Integer.toUnsignedLong(actualChecksum)) {
            throw new ProtocolException("matcher snapshot checksum mismatch");
        }
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(encoded, 0, encoded.length - Long.BYTES))) {
            if (input.readInt() != MAGIC) throw new ProtocolException("invalid matcher snapshot magic");
            int version = input.readInt();
            if (version != VERSION) throw new ProtocolException("unsupported matcher snapshot version: " + version);
            ProductLine productLine = ProductLineWireCode.decode(input.readInt());
            String coreShardId = readText(input);
            int routeVersion = input.readInt();
            LaneTopology topology = new LaneTopology(routeVersion, input.readInt(), input.readInt(), input.readInt(),
                    input.readLong(), input.readInt(), input.readInt(), input.readInt());
            if (input.readLong() != topology.topologyHash()) {
                throw new ProtocolException("matcher topology hash mismatch");
            }
            long snapshotId = input.readLong();
            long coreSequence = input.readLong();
            long matcherSequence = input.readLong();
            long matcherPrefixDigest = input.readLong();
            long businessHash = input.readLong();
            int engineHash = input.readInt();
            int bookHash = input.readInt();
            long symbolHash = input.readLong();
            long symbolRouteHash = input.readLong();
            long userHash = input.readLong();
            long instrumentHash = input.readLong();
            long activeOrderHash = input.readLong();
            String forkGitSha = readText(input);
            String artifactSha256 = readText(input);
            long configHash = input.readLong();
            int symbolCount = readCount(input, "symbol registry");
            Map<String, Integer> symbols = new LinkedHashMap<>();
            Set<Integer> symbolIds = new LinkedHashSet<>();
            for (int index = 0; index < symbolCount; index++) {
                String symbol = readText(input);
                int symbolId = input.readInt();
                if (symbolId <= 0 || symbols.put(symbol, symbolId) != null || !symbolIds.add(symbolId)) {
                    throw new ProtocolException("duplicate matcher symbol");
                }
            }
            int userCount = readCount(input, "user registry");
            Set<Long> users = new LinkedHashSet<>();
            for (int index = 0; index < userCount; index++) {
                long userId = input.readLong();
                if (userId <= 0 || !users.add(userId)) throw new ProtocolException("invalid matcher user registry");
            }
            int moduleCount = readCount(input, "module");
            if (moduleCount != topology.matchingEngineCount() * 2) {
                throw new ProtocolException("invalid matcher module count");
            }
            List<SerializedModule> modules = new ArrayList<>(moduleCount);
            for (int index = 0; index < moduleCount; index++) {
                int typeOrdinal = input.readInt();
                if (typeOrdinal < 0 || typeOrdinal >= SerializedModuleType.values().length) {
                    throw new ProtocolException("invalid matcher module type");
                }
                int instanceId = input.readInt();
                long sequence = input.readLong();
                long timestampNs = input.readLong();
                int length = input.readInt();
                long moduleChecksum = input.readLong();
                if (length <= 0 || length > MAX_MODULE_BYTES || length > input.available()) {
                    throw new ProtocolException("invalid matcher module length");
                }
                byte[] data = input.readNBytes(length);
                if (moduleChecksum != Integer.toUnsignedLong(checksum(data))) {
                    throw new ProtocolException("matcher module checksum mismatch");
                }
                modules.add(new SerializedModule(snapshotId, sequence, timestampNs,
                        SerializedModuleType.values()[typeOrdinal], instanceId, data));
            }
            if (input.available() != 0) throw new ProtocolException("trailing matcher snapshot bytes");
            return new MatcherSnapshot(productLine, coreShardId, routeVersion, topology, snapshotId, coreSequence,
                    matcherSequence, matcherPrefixDigest, businessHash, engineHash, bookHash,
                    symbolHash, symbolRouteHash, userHash, instrumentHash,
                    activeOrderHash, forkGitSha, artifactSha256, configHash, symbols, users, modules);
        } catch (EOFException exception) {
            throw new ProtocolException("matcher snapshot is truncated: " + exception.getMessage());
        } catch (IOException | IllegalArgumentException exception) {
            if (exception instanceof ProtocolException protocolException) throw protocolException;
            throw new ProtocolException("invalid matcher snapshot: " + exception.getMessage());
        }
    }

    private static int readCount(DataInputStream input, String field) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_REGISTRY_ENTRIES) throw new ProtocolException("invalid " + field + " count");
        return count;
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > 256) throw new IllegalArgumentException("invalid matcher text");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readText(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > 256 || length > input.available()) {
            throw new ProtocolException("invalid matcher text length");
        }
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static int checksum(byte[] data) {
        return checksum(data, 0, data.length);
    }

    private static int checksum(byte[] data, int offset, int length) {
        CRC32C checksum = new CRC32C();
        checksum.update(data, offset, length);
        return (int) checksum.getValue();
    }

    private static final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
        private final int maximumBytes;

        private BoundedByteArrayOutputStream(int maximumBytes) {
            super(Math.min(8 * 1024, maximumBytes));
            this.maximumBytes = maximumBytes;
        }

        @Override
        public synchronized void write(int value) {
            ensureCapacityFor(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] data, int offset, int length) {
            ensureCapacityFor(length);
            super.write(data, offset, length);
        }

        private void ensureCapacityFor(int additionalBytes) {
            if (additionalBytes < 0 || count > maximumBytes - additionalBytes) {
                throw new IllegalArgumentException("matcher snapshot exceeds maximum size");
            }
        }
    }
}
