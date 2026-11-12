package com.surprising.aeron.service.snapshot;

import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.product.api.ProductLine;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.agrona.concurrent.UnsafeBuffer;

/** 分段快照 v30 的公开格式入口：封套、段头及基础数据按小端编码。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SectionedCoreSnapshotCodec {

    public static final int MAGIC = 0x5358534E;
    public static final int VERSION = 30;
    public static final int ENVELOPE_LENGTH = 12;
    /** 每个段头八字节：顺序段编号和负载长度。 */
    public static final int SECTION_HEADER_LENGTH = 8;
    public static final int HEADER_LENGTH = 81;
    public static final int SOURCE_SEQUENCE_LENGTH = 20;
    public static final int RESULT_FIXED_LENGTH = 76;
    public static final int FOOTER_LENGTH = Long.BYTES;
    public static final int BASE_SECTION_COUNT = 9;
    public static final int MAX_SECTION_COUNT = BASE_SECTION_COUNT + Long.SIZE;
    /** 未配置时的总字节上限一 GiB，用于限制异常或损坏快照的分配规模。 */
    public static final int DEFAULT_MAX_SNAPSHOT_BYTES = 1024 * 1024 * 1024;
    /** 部署配置允许的最小总上限六十四 MiB，显式恢复器构造不使用此下限。 */
    public static final int MIN_MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024;
    public static final int HARD_MAX_SNAPSHOT_BYTES = Integer.MAX_VALUE - 8;
    public static final int HARD_MAX_SECTION_BYTES = HARD_MAX_SNAPSHOT_BYTES
            - ENVELOPE_LENGTH - MAX_SECTION_COUNT * SECTION_HEADER_LENGTH;

    /** 检查账户 Lane 数为一至六十四，返回八个基础负载、账户 Lane 段与一个页尾段的总数量。 */
    public static int sectionCount(int accountLaneCount) {
        if (accountLaneCount < 1 || accountLaneCount > Long.SIZE) {
            throw new IllegalArgumentException("invalid account lane section count");
        }
        return BASE_SECTION_COUNT + accountLaneCount;
    }

    /** 把完整字节送入有界增量恢复器，校验全部组件并创建、激活指定产品线运行时。 */
    public static TradingCore decode(byte[] snapshot, ProductLine expectedProductLine) {
        return recovery(snapshot).decode(expectedProductLine);
    }

    public static TradingCore decode(byte[] snapshot, ProductLine expectedProductLine,
            com.surprising.aeron.config.CoreRuntimeConfiguration configuration,
            com.surprising.aeron.config.LaneTopology deploymentTopology) {
        return recovery(snapshot, configuration.snapshot().maxBytes())
                .decode(expectedProductLine, configuration, deploymentTopology);
    }

    public static TradingCore decodePassive(byte[] snapshot, ProductLine expectedProductLine,
            com.surprising.aeron.config.CoreRuntimeConfiguration configuration,
            com.surprising.aeron.config.LaneTopology deploymentTopology) {
        return recovery(snapshot, configuration.snapshot().maxBytes())
                .decodePassive(expectedProductLine, configuration, deploymentTopology);
    }

    /** 完整接收并解析快照、核对产品线及组件配对后返回清单，不激活真实运行时。 */
    public static CoreSnapshotManifest manifest(byte[] snapshot, ProductLine expectedProductLine) {
        return recovery(snapshot).manifest(expectedProductLine);
    }

    /** 明确拒绝空数组引用，创建部署上限的恢复器并一次送入全部字节。其他截断或格式错误由恢复器拒绝。 */
    private static SectionedCoreSnapshotRecovery recovery(byte[] snapshot) {
        return recovery(snapshot, com.surprising.aeron.config.CoreRuntimeConfiguration.standalone().snapshot().maxBytes());
    }

    private static SectionedCoreSnapshotRecovery recovery(byte[] snapshot, int maximumSnapshotBytes) {
        if (snapshot == null) throw new ProtocolException("snapshot is null");
        SectionedCoreSnapshotRecovery recovery = new SectionedCoreSnapshotRecovery(maximumSnapshotBytes);
        recovery.accept(new UnsafeBuffer(snapshot), 0, snapshot.length);
        return recovery;
    }

    /** 编码后的有序字节片段与总长度。 */
    public static final class SectionedSnapshot {
        /** 返回不可修改的外层片段列表，其中每个 byte 数组仍是共享引用，调用方不得在消费期间改写。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private final List<byte[]> chunks;
        @Accessors(fluent = true, chain = false)
        @Getter
        private final int length;

        /** 复制并固定外层片段列表，内部 byte 数组继续共享。 */
        public SectionedSnapshot(List<byte[]> chunks, int length) {
            this.chunks = List.copyOf(chunks);
            this.length = length;
        }

        /** 按保存的总长度分配新数组并依次复制所有片段。 */
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
