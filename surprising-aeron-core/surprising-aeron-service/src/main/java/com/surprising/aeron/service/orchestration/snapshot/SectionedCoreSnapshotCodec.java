package com.surprising.aeron.service.orchestration.snapshot;

import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import com.surprising.aeron.service.orchestration.SectionedCoreSnapshotRecovery;
import com.surprising.aeron.service.orchestration.SectionedCoreSnapshotWriter;
import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * 核心快照分片格式的公开入口。
 *
 * <p>本类只定义格式常量、分片接收和编解码入口；订单、账户和恢复状态仍由编排包内部实现拥有。</p>
 */
public final class SectionedCoreSnapshotCodec {

    /** 快照文件格式魔数。 */
    public static final int MAGIC = 0x5358534E;
    /** 当前快照格式版本。 */
    public static final int VERSION = 23;
    /** 快照封套长度。 */
    public static final int ENVELOPE_LENGTH = 12;
    /** 每个分片头的长度。 */
    public static final int SECTION_HEADER_LENGTH = 8;
    public static final int FORK_GIT_SHA_LENGTH = 40;
    public static final int ARTIFACT_SHA256_LENGTH = 64;
    public static final int HEADER_LENGTH = 322;
    public static final int SOURCE_SEQUENCE_LENGTH = 24;
    public static final int RESULT_FIXED_LENGTH = 84;
    public static final int FOOTER_LENGTH = Long.BYTES;
    public static final int BASE_SECTION_COUNT = 9;
    public static final int MAX_SECTION_COUNT = BASE_SECTION_COUNT + Long.SIZE;
    public static final int MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024;
    public static final int MAX_SECTION_BYTES = MAX_SNAPSHOT_BYTES
            - ENVELOPE_LENGTH - MAX_SECTION_COUNT * SECTION_HEADER_LENGTH;

    /** 根据账户 Lane 数量计算快照分片数量。 */
    public static int sectionCount(int accountLaneCount) {
        if (accountLaneCount < 1 || accountLaneCount > Long.SIZE) {
            throw new IllegalArgumentException("invalid account lane section count");
        }
        return BASE_SECTION_COUNT + accountLaneCount;
    }

    private SectionedCoreSnapshotCodec() {
    }

    /** 判断字节流是否使用当前分片快照格式。 */
    public static boolean isSectioned(byte[] snapshot) {
        if (snapshot == null || snapshot.length < Integer.BYTES + Short.BYTES) return false;
        ByteBuffer buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);
        return buffer.getInt() == MAGIC && Short.toUnsignedInt(buffer.getShort()) == VERSION;
    }

    /** 编码基础交易运行时和 Matcher 快照。 */
    public static SectionedSnapshot encode(TradingCoreRuntime state, MatcherSnapshot matcherSnapshot) {
        return SectionedCoreSnapshotWriter.encode(
                state, matcherSnapshot, matcherSnapshot.snapshotId(), matcherSnapshot.coreSequence(), 0, 0);
    }

    /** 按指定集群时间和日志位置编码快照。 */
    public static SectionedSnapshot encode(
            TradingCoreRuntime state,
            MatcherSnapshot matcherSnapshot,
            long snapshotId,
            long coreSequence,
            long clusterTimestamp,
            long clusterPosition) {
        return SectionedCoreSnapshotWriter.encode(state, matcherSnapshot, snapshotId, coreSequence,
                clusterTimestamp, clusterPosition);
    }

    /** 解码并恢复指定产品线的交易运行时。 */
    public static TradingCoreRuntime decode(byte[] snapshot, ProductLine expectedProductLine) {
        return recovery(snapshot).decode(expectedProductLine);
    }

    /** 从完整快照读取清单信息。 */
    public static CoreSnapshotManifest manifest(byte[] snapshot, ProductLine expectedProductLine) {
        return recovery(snapshot).manifest(expectedProductLine);
    }

    private static RecoveryBuffer recovery(byte[] snapshot) {
        if (snapshot == null) throw new ProtocolException("snapshot is null");
        RecoveryBuffer recovery = new RecoveryBuffer();
        recovery.accept(new UnsafeBuffer(snapshot), 0, snapshot.length);
        return recovery;
    }

    public static final class RecoveryBuffer {
        private final SectionedCoreSnapshotRecovery delegate = new SectionedCoreSnapshotRecovery();

        /** 创建一个可接收分片快照的恢复缓冲区。 */
        public RecoveryBuffer() {
        }

        /** 接收一个快照分片。 */
        public void accept(DirectBuffer source, int offset, int length) {
            delegate.accept(source, offset, length);
        }

        /** 将已接收的分片恢复为交易运行时。 */
        public TradingCoreRuntime decode(ProductLine expectedProductLine) {
            return delegate.decode(expectedProductLine);
        }

        /** 从已接收的分片读取快照清单。 */
        public CoreSnapshotManifest manifest(ProductLine expectedProductLine) {
            return delegate.manifest(expectedProductLine);
        }

        /** 返回已经接收的分片数量。 */
        public int ownedSectionCount() {
            return delegate.ownedSectionCount();
        }

        /** 返回已经接收的字节数。 */
        public int totalLength() {
            return delegate.totalLength();
        }

        /** 返回恢复器已分配的字节数。 */
        public int allocatedBytes() {
            return delegate.allocatedBytes();
        }
    }

    /** 不可变的分片快照结果。 */
    public static final class SectionedSnapshot {
        private final List<byte[]> chunks;
        private final int length;

        public SectionedSnapshot(List<byte[]> chunks, int length) {
            this.chunks = List.copyOf(chunks);
            this.length = length;
        }

        /** 返回分片列表。 */
        public List<byte[]> chunks() {
            return chunks;
        }

        /** 返回完整快照长度。 */
        public int length() {
            return length;
        }

        /** 合并分片并返回完整快照字节。 */
        public byte[] toByteArray() {
            byte[] snapshot = new byte[length];
            int offset = 0;
            for (byte[] chunk : chunks()) {
                System.arraycopy(chunk, 0, snapshot, offset, chunk.length);
                offset += chunk.length;
            }
            return snapshot;
        }
    }
}
