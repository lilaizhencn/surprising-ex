package com.surprising.aeron.service.matcher;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.service.matcher.OrderBookSnapshot.ShardProgress;
import com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec;
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
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 原生订单簿快照清单版本十的二进制格式。使用 DataInput/DataOutput 的大端整数顺序，逐模块和整条正文分别校验 CRC32C，保存拓扑、配对水位/哈希、注册表及原生模块。与交易状态快照三十九的小端字段格式不同，不能混用读取器。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OrderBookSnapshotCodec {

    private static final int MAGIC = 0x4d534e50;
    private static final int VERSION = 10;
    /** 沿用分段 Core 快照规定的最大区段字节数，包含末尾八字节校验值。 */
    private static final int HARD_MAX_SNAPSHOT_BYTES = SectionedCoreSnapshotCodec.HARD_MAX_SECTION_BYTES;
    private static final int MAX_REGISTRY_ENTRIES = 1_000_000;
    /** 单个原生模块载荷上限，在总快照限额内预留一百二十八字节空间。 */
    private static final int MAX_MODULE_BYTES = HARD_MAX_SNAPSHOT_BYTES - 128;

    /** 在调用方已经扣除外层封套/段头和已编码部分的预算内写入，扩容前拒绝超限。 */
    public static byte[] encode(OrderBookSnapshot snapshot, int maximumSnapshotBytes) {
        if (maximumSnapshotBytes <= Long.BYTES || maximumSnapshotBytes > HARD_MAX_SNAPSHOT_BYTES)
            throw new IllegalArgumentException("invalid matcher snapshot byte limit");
        try {
            BoundedByteArrayOutputStream body =
                    new BoundedByteArrayOutputStream(maximumSnapshotBytes - Long.BYTES);
            try (DataOutputStream output = new DataOutputStream(body)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(ProductLineWireCode.encode(snapshot.productLine()));
                output.writeInt(snapshot.topology().matchingEngineCount());
                output.writeInt(snapshot.topology().accountLaneCount());
                output.writeLong(snapshot.topology().accountLaneSeed());
                output.writeLong(snapshot.snapshotId());
                output.writeLong(snapshot.coreSequence());
                output.writeLong(snapshot.matcherSequence());
                output.writeInt(snapshot.matcherShardProgress().size());
                for (ShardProgress progress : snapshot.matcherShardProgress()) {
                    output.writeInt(progress.matcherShardId());
                    output.writeLong(progress.matcherSequence());
                }
                output.writeLong(snapshot.coreBusinessStateHash());
                output.writeInt(snapshot.engineStateHash());
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
            int bodyLength = body.size();
            if (bodyLength > maximumSnapshotBytes - Long.BYTES) {
                throw new IllegalArgumentException("matcher snapshot exceeds maximum size");
            }
            byte[] encoded = new byte[bodyLength + Long.BYTES];
            body.copyTo(encoded);
            ByteBuffer.wrap(encoded, bodyLength, Long.BYTES)
                    .putLong(Integer.toUnsignedLong(checksum(encoded, 0, bodyLength)));
            return encoded;
        } catch (IOException exception) {
            throw new IllegalStateException("unable to encode matcher snapshot", exception);
        }
    }

    /** 路由来自快照，空环与队列容量来自当前部署。 */
    public static OrderBookSnapshot decode(byte[] encoded, LaneTopology deploymentTopology) {
        if (encoded != null && encoded.length > HARD_MAX_SNAPSHOT_BYTES) {
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
            LaneTopology topology = new LaneTopology(input.readInt(), input.readInt(), input.readLong(),
                    deploymentTopology.matcherWindowSize(), deploymentTopology.matchingCompletionCapacity(),
                    deploymentTopology.accountLaneQueueCapacity());
            long snapshotId = input.readLong();
            long coreSequence = input.readLong();
            long matcherSequence = input.readLong();
            int progressCount = readCount(input, "matcher shard progress");
            if (progressCount != topology.matchingEngineCount() + 1) {
                throw new ProtocolException("invalid matcher shard progress count");
            }
            List<ShardProgress> matcherShardProgress = new ArrayList<>(progressCount);
            for (int index = 0; index < progressCount; index++) {
                matcherShardProgress.add(new ShardProgress(input.readInt(), input.readLong()));
            }
            long businessHash = input.readLong();
            int engineHash = input.readInt();
            int symbolCount = readCount(input, "instrumentId registry");
            Map<String, Integer> symbols = new LinkedHashMap<>();
            Set<Integer> symbolIds = new LinkedHashSet<>();
            for (int index = 0; index < symbolCount; index++) {
                String instrumentId = readText(input);
                int symbolId = input.readInt();
                if (symbolId <= 0 || symbols.put(instrumentId, symbolId) != null || !symbolIds.add(symbolId)) {
                    throw new ProtocolException("duplicate matcher instrumentId");
                }
            }
            int userCount = readCount(input, "user registry");
            Set<Long> users = new LinkedHashSet<>();
            for (int index = 0; index < userCount; index++) {
                long userId = input.readLong();
                if (userId <= 0 || !users.add(userId)) throw new ProtocolException("invalid matcher user registry");
            }
            int moduleCount = readCount(input, "module");
            if (moduleCount != topology.matchingEngineCount()) {
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
            return new OrderBookSnapshot(productLine, topology, snapshotId, coreSequence,
                    matcherSequence, matcherShardProgress, businessHash, engineHash, symbols, users, modules);
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
            requireCapacity(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] data, int offset, int length) {
            requireCapacity(length);
            super.write(data, offset, length);
        }

        public synchronized void copyTo(byte[] destination) {
            System.arraycopy(buf, 0, destination, 0, count);
        }

        private void requireCapacity(int additionalBytes) {
            if (additionalBytes < 0 || count > maximumBytes - additionalBytes) {
                throw new IllegalArgumentException("matcher snapshot exceeds maximum size");
            }
        }
    }
}
