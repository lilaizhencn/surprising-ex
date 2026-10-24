package com.surprising.aeron.service.snapshot;

import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.matcher.MatcherSnapshot;
import com.surprising.product.api.ProductLine;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/** 分段快照 v30 的公开格式入口：外层封套、段头及本类基础数据按小端编码，内嵌 Matcher/交易状态负载遵循各自编解码器。提供整体编码、恢复和增量接收；格式解析与配对检查分别由 Writer、Recovery、Parser 和 Validation 承担。 构造约定：禁止实例化纯静态格式入口。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SectionedCoreSnapshotCodec {

    /** 外层小端魔数，标识 Surprising 核心分段快照。 */
    public static final int MAGIC = 0x5358534E;
    /** 当前外层格式版本三十，不代表内嵌负载版本。 */
    public static final int VERSION = 30;
    /** 封套十二字节：魔数、版本、保留位与总段数。 */
    public static final int ENVELOPE_LENGTH = 12;
    /** 每个段头八字节：顺序段编号和负载长度。 */
    public static final int SECTION_HEADER_LENGTH = 8;
    /** 第一段固定元数据负载长度八十一字节。 */
    public static final int HEADER_LENGTH = 81;
    /** 一个来源水位条目二十字节，包含来源码、实例号与序号。 */
    public static final int SOURCE_SEQUENCE_LENGTH = 20;
    /** 一个命令结果负载的固定部分七十六字节，不含前置条目长度和变长响应数据。 */
    public static final int RESULT_FIXED_LENGTH = 76;
    /** CRC32C 数值用八字节 long 保存的页尾负载长度。 */
    public static final int FOOTER_LENGTH = Long.BYTES;
    /** 八个基础业务负载加一个校验页尾，共九段，不含各账户 Lane 段。 */
    public static final int BASE_SECTION_COUNT = 9;
    /** 九个固定段加最多六十四个账户 Lane 段的总段数上限。 */
    public static final int MAX_SECTION_COUNT = BASE_SECTION_COUNT + Long.SIZE;
    /** 未配置时的总字节上限一 GiB，用于限制异常或损坏快照的分配规模。 */
    public static final int DEFAULT_MAX_SNAPSHOT_BYTES = 1024 * 1024 * 1024;
    /** 部署配置允许的最小总上限六十四 MiB，显式恢复器构造不使用此下限。 */
    public static final int MIN_MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024;
    /** 协议和数组实现允许的硬上限；部署预算在配置中独立绑定。 */
    public static final int HARD_MAX_SNAPSHOT_BYTES = Integer.MAX_VALUE - 8;
    /** 按总上限扣除封套及最大数量段头得出的单段负载上限，总长度仍需额外核对。 */
    public static final int HARD_MAX_SECTION_BYTES = HARD_MAX_SNAPSHOT_BYTES
            - ENVELOPE_LENGTH - MAX_SECTION_COUNT * SECTION_HEADER_LENGTH;

    /**
     * 检查账户 Lane 数为一至六十四，返回八个基础负载、账户 Lane 段与一个页尾段的总数量。
     * @param accountLaneCount 账户 Lane 数量，范围一至六十四
     */
    public static int sectionCount(int accountLaneCount) {
        if (accountLaneCount < 1 || accountLaneCount > Long.SIZE) {
            throw new IllegalArgumentException("invalid account lane section count");
        }
        return BASE_SECTION_COUNT + accountLaneCount;
    }

    /**
     * 使用 Matcher 快照自己的编号与 Core 水位，Cluster 时间和位置填零，交给写入器捕获当前运行时并编码；调用方须已建立一致性边界。
     * @param state 已经建立快照一致性边界的交易运行时
     * @param matcherSnapshot 同一产品线与 Core 冻结点的原生撮合快照
     */
    public static SectionedSnapshot encode(TradingCoreRuntime state, MatcherSnapshot matcherSnapshot) {
        return SectionedCoreSnapshotWriter.encode(
                state, matcherSnapshot, matcherSnapshot.snapshotId(), matcherSnapshot.coreSequence(), 0, 0);
    }

    /**
     * 把完整字节送入有界增量恢复器，校验全部组件并创建、激活指定产品线运行时；不是仅反序列化一个 Map。
     * @param snapshot 待探测或恢复的完整快照字节数组
     * @param expectedProductLine 调用方明确要求恢复或检查的产品线
     */
    public static TradingCoreRuntime decode(byte[] snapshot, ProductLine expectedProductLine) {
        return recovery(snapshot).decode(expectedProductLine);
    }

    public static TradingCoreRuntime decode(byte[] snapshot, ProductLine expectedProductLine,
            com.surprising.aeron.config.CoreRuntimeConfiguration configuration) {
        return decode(snapshot, expectedProductLine, configuration,
                com.surprising.aeron.config.CoreConfiguration.standaloneLaneTopology());
    }

    public static TradingCoreRuntime decode(byte[] snapshot, ProductLine expectedProductLine,
            com.surprising.aeron.config.CoreRuntimeConfiguration configuration,
            com.surprising.aeron.config.LaneTopology deploymentTopology) {
        return recovery(snapshot, configuration.snapshot().maxBytes())
                .decode(expectedProductLine, configuration, deploymentTopology);
    }

    public static TradingCoreRuntime decodePassive(byte[] snapshot, ProductLine expectedProductLine,
            com.surprising.aeron.config.CoreRuntimeConfiguration configuration) {
        return decodePassive(snapshot, expectedProductLine, configuration,
                com.surprising.aeron.config.CoreConfiguration.standaloneLaneTopology());
    }

    public static TradingCoreRuntime decodePassive(byte[] snapshot, ProductLine expectedProductLine,
            com.surprising.aeron.config.CoreRuntimeConfiguration configuration,
            com.surprising.aeron.config.LaneTopology deploymentTopology) {
        return recovery(snapshot, configuration.snapshot().maxBytes())
                .decodePassive(expectedProductLine, configuration, deploymentTopology);
    }

    /**
     * 完整接收并解析快照、核对产品线及组件配对后返回清单，不激活真实运行时；内部仍需解码状态并在返回时关闭临时终态保留索引。
     * @param snapshot 待探测或恢复的完整快照字节数组
     * @param expectedProductLine 调用方明确要求恢复或检查的产品线
     */
    public static CoreSnapshotManifest manifest(byte[] snapshot, ProductLine expectedProductLine) {
        return recovery(snapshot).manifest(expectedProductLine);
    }

    /**
     * 明确拒绝空数组引用，创建部署上限的恢复器并一次送入全部字节；其他截断或格式错误由恢复器拒绝。
     * @param snapshot 待探测或恢复的完整快照字节数组
     */
    private static RecoveryBuffer recovery(byte[] snapshot) {
        return recovery(snapshot, com.surprising.aeron.config.CoreRuntimeConfiguration.standalone().snapshot().maxBytes());
    }

    private static RecoveryBuffer recovery(byte[] snapshot, int maximumSnapshotBytes) {
        if (snapshot == null) throw new ProtocolException("snapshot is null");
        // 接收整个快照的内部有界恢复缓冲。
        RecoveryBuffer recovery = new RecoveryBuffer(maximumSnapshotBytes);
        recovery.accept(new UnsafeBuffer(snapshot), 0, snapshot.length);
        return recovery;
    }

    /**
     * 对外暴露的有界增量接收入口，内部保存完整的各段负载；接收完成后才能读清单或创建运行时。
     */
    public static final class RecoveryBuffer {
        /** 拥有增量缓冲、CRC 和接收进度的内部恢复器。 */
        private final SectionedCoreSnapshotRecovery delegate;

        /**
         * 以本部署配置的最大快照字节数创建增量恢复器。
         */
        public RecoveryBuffer() {
            this(com.surprising.aeron.config.CoreRuntimeConfiguration.standalone().snapshot().maxBytes());
        }

        /**
         * 使用显式总字节上限创建增量恢复器，供边界测试或离线读取使用。
         * @param maximumSnapshotBytes 显式恢复器允许接收的总字节上限
         */
        public RecoveryBuffer(int maximumSnapshotBytes) {
            delegate = new SectionedCoreSnapshotRecovery(maximumSnapshotBytes);
        }

        /**
         * 按给定范围增量接收字节，片段可跨封套、段头和负载边界；格式、长度及 CRC 检查委托内部恢复器。
         * @param source 本次接收的片段来源缓冲
         * @param offset 片段在来源缓冲中的起始字节偏移
         * @param length 本次接收的字节数，或 SectionedSnapshot 保存的总长度，具体含义见当前签名
         */
        public void accept(DirectBuffer source, int offset, int length) {
            delegate.accept(source, offset, length);
        }

        /**
         * 要求完整接收后解析组件，恢复并激活指定产品线的运行时，失败不返回半恢复对象。
         * @param expectedProductLine 调用方明确要求恢复或检查的产品线
         */
        public TradingCoreRuntime decode(ProductLine expectedProductLine) {
            return delegate.decode(expectedProductLine);
        }

        public TradingCoreRuntime decode(ProductLine expectedProductLine,
                com.surprising.aeron.config.CoreRuntimeConfiguration configuration) {
            return decode(expectedProductLine, configuration,
                    com.surprising.aeron.config.CoreConfiguration.standaloneLaneTopology());
        }

        public TradingCoreRuntime decode(ProductLine expectedProductLine,
                com.surprising.aeron.config.CoreRuntimeConfiguration configuration,
                com.surprising.aeron.config.LaneTopology deploymentTopology) {
            return delegate.decode(expectedProductLine, configuration, deploymentTopology);
        }

        public TradingCoreRuntime decodePassive(ProductLine expectedProductLine,
                com.surprising.aeron.config.CoreRuntimeConfiguration configuration) {
            return decodePassive(expectedProductLine, configuration,
                    com.surprising.aeron.config.CoreConfiguration.standaloneLaneTopology());
        }

        public TradingCoreRuntime decodePassive(ProductLine expectedProductLine,
                com.surprising.aeron.config.CoreRuntimeConfiguration configuration,
                com.surprising.aeron.config.LaneTopology deploymentTopology) {
            return delegate.decodePassive(expectedProductLine, configuration, deploymentTopology);
        }

        /**
         * 完整解析并检查组件后返回公开清单，不启动 Matcher；临时终态索引由内部恢复器关闭。
         * @param expectedProductLine 调用方明确要求恢复或检查的产品线
         */
        public CoreSnapshotManifest manifest(ProductLine expectedProductLine) {
            return delegate.manifest(expectedProductLine);
        }

        /**
         * 返回已经接收完整的段数，当前尚未收齐但已分配的段不计入，完成时包含页尾段。
         */
        public int ownedSectionCount() {
            return delegate.ownedSectionCount();
        }

        /**
         * 返回已成功拷入内部数组的累计字节数，包含封套、段头和负载。
         */
        public int totalLength() {
            return delegate.totalLength();
        }

        /**
         * 返回固定封套/段头数组及已分配负载数组的字节长度之和，不计对象头、引用数组或解析后对象。
         */
        public int allocatedBytes() {
            return delegate.allocatedBytes();
        }
    }

    /**
     * 编码后的有序字节片段与总长度。外层 List 固定，内层数组共享；可逐片持久化，也可在需要整体数组时合并复制。
     */
    public static final class SectionedSnapshot {
        /**  固定的外层字节片段列表，内层数组不复制。 返回不可修改的外层片段列表，其中每个 byte 数组仍是共享引用，调用方不得在消费期间改写。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private final List<byte[]> chunks;
        /**  构造时传入并保存的总字节长度。 返回构造器保存的总字节长度，不重新遍历片段。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private final int length;

        /**
         * 复制并固定外层片段列表，内部 byte 数组继续共享；总长度按传入值保存，不在此重算或检查与片段总和一致。
         * @param chunks 按封套、段头和负载顺序排列的共享字节数组列表
         * @param length 本次接收的字节数，或 SectionedSnapshot 保存的总长度，具体含义见当前签名
         */
        public SectionedSnapshot(List<byte[]> chunks, int length) {
            this.chunks = List.copyOf(chunks);
            this.length = length;
        }

        /**
         * 按保存的总长度分配新数组并依次复制所有片段；合法 Writer 输出长度相等，手工构造不一致时可能越界或留下末尾零字节。
         */
        public byte[] toByteArray() {
            // 新分配的完整快照数组，按片段顺序写入。
            byte[] snapshot = new byte[length];
            // 已经写入目标完整数组的字节数量。
            int offset = 0;
            for (byte[] /* 当前顺序复制的字节片段引用。 */ chunk : chunks()) {
                System.arraycopy(chunk, 0, snapshot, offset, chunk.length);
                offset += chunk.length;
            }
            return snapshot;
        }
    }
}
