package com.surprising.aeron.service.orchestration.snapshot;

import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import com.surprising.product.api.ProductLine;

/**
 * 核心快照的兼容编解码入口。
 *
 * <p>它统一执行快照大小限制，并把实际分片格式交给 {@link SectionedCoreSnapshotCodec}。</p>
 */
public final class CoreStateSnapshotCodec {

    /** 单条命令结果在快照中的固定元数据长度。 */
    public static final int RESULT_FIXED_LENGTH = 92;
    /** 单个核心快照允许的最大字节数。 */
    public static final int MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024;
    /** 单个快照分片允许的最大字节数。 */
    public static final int MAX_SECTION_BYTES = SectionedCoreSnapshotCodec.MAX_SECTION_BYTES;

    private CoreStateSnapshotCodec() {
    }

    /** 编码当前交易运行时和 Matcher 快照。 */
    public static byte[] encode(TradingCoreRuntime state, MatcherSnapshot matcherSnapshot) {
        return SectionedCoreSnapshotCodec.encode(state, matcherSnapshot).toByteArray();
    }

    /** 读取快照清单，不恢复交易运行时。 */
    public static CoreSnapshotManifest manifest(byte[] snapshot, ProductLine expectedProductLine) {
        rejectOversizedSnapshot(snapshot);
        return SectionedCoreSnapshotCodec.manifest(snapshot, expectedProductLine);
    }

    /** 校验并恢复指定产品线的交易运行时。 */
    public static TradingCoreRuntime decode(byte[] snapshot, ProductLine expectedProductLine) {
        rejectOversizedSnapshot(snapshot);
        return SectionedCoreSnapshotCodec.decode(snapshot, expectedProductLine);
    }

    private static void rejectOversizedSnapshot(byte[] snapshot) {
        if (snapshot != null && snapshot.length > MAX_SNAPSHOT_BYTES) {
            throw new ProtocolException("core snapshot exceeds maximum size");
        }
    }
}
