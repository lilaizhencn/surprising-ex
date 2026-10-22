package com.surprising.aeron.service.snapshot.state;

import com.surprising.aeron.model.CoreFeePolicyState;
import com.surprising.aeron.protocol.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 手续费策略独立快照的编解码器。新写入使用版本二的字段编号格式，按输入 Map 的策略编号键排序；读取同时支持已发布版本一的固定布局，拒绝重复编号、过量条目和无效业务值，最终返回只读策略表。 构造约定：工具类不创建实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CoreFeePolicySnapshotCodec {

    /** 仍允许读取的已发布固定布局版本一；当前 encode 实际写版本二。 */
    private static final int VERSION = 1;
    /** 版本一单条策略除币对文本以外至少需要的总字节数。 */
    private static final int FIXED_LENGTH = Long.BYTES * 7 + Integer.BYTES + Byte.BYTES + Short.BYTES;
    /** 版本一币对文本后费率、优先级、启停及有效期需要的字节数。 */
    private static final int TAIL_LENGTH = Long.BYTES * 4 + Integer.BYTES + Byte.BYTES;

    /**
     * 按策略 Map 编号键排序后逐项写入策略编号、修订、适用用户和币对、费率、优先级、启停及有效期，再加版本二的小端头。这里只编码，未另执行一百万项的读取上限校验。
     * @param policies 以策略编号为键的当前策略集合，按键排序后编码
     */
    public static byte[] encode(Map<Long, CoreFeePolicyState> policies) {
        // 版本二根字段记录，不包含外层版本头。
        byte[] fields = new SnapshotFields.Writer().list(1, new TreeMap<>(policies).values(), /* 当前待写入或已从快照构造的手续费策略值。 */ policy ->
                new SnapshotFields.Writer().number(1, policy.policyId()).number(2, policy.policyRevision())
                        .number(3, policy.userId()).text(4, policy.instrumentId())
                        .number(5, policy.makerFeeRatePpm()).number(6, policy.takerFeeRatePpm())
                        .number(7, policy.sourcePriority()).bool(8, policy.active())
                        .number(9, policy.effectiveFromEpochMillis()).number(10, policy.expireAtEpochMillis()).encode()).encode();
        return ByteBuffer.allocate(4 + fields.length).order(ByteOrder.LITTLE_ENDIAN).putInt(2).put(fields).array();
    }

    /**
     * 读取版本二的根列表与每条策略字段，构造业务值并检查条目上限和编号重复；合法但未知字段由字段读取器保留，未被本业务访问的字段不影响恢复。
     * @param payload 包含四字节版本头的完整策略快照字节
     */
    private static Map<Long, CoreFeePolicyState> decodeFields(byte[] payload) {
        // 按策略编号构建的恢复结果，返回时只读包装。
        Map<Long, CoreFeePolicyState> policies = new TreeMap<>();
        // 跳过四字节版本头后的根字段读取器。
        var root = new SnapshotFields.Reader(java.util.Arrays.copyOfRange(payload, 4, payload.length));
        try {
            for (var /* 当前策略条目的字段读取器，按固定编号取得业务参数。 */ r : root.list(1, SnapshotFields.Reader::new)) {
                // 当前待写入或已从快照构造的手续费策略值。
                var policy = new CoreFeePolicyState(r.number(1), r.number(2), r.number(3), r.text(4),
                        r.number(5), r.number(6), r.integer(7), r.bool(8), r.number(9), r.number(10));
                if (policies.size() >= 1_000_000 || policies.put(policy.policyId(), policy) != null)
                    throw new ProtocolException("invalid/duplicate fee policy snapshot");
            }
            return java.util.Collections.unmodifiableMap(policies);
        } catch (/* 版本二策略业务值构造失败的异常，包装时保留为原因。 */ IllegalArgumentException invalid) { throw new ProtocolException("invalid fee policy snapshot", invalid); }
    }

    /**
     * 校验基本载荷长度及版本，版本二交给字段读取器；版本一按条目数和固定布局解析币对名、费率、有效期等，检查每条剩余空间、重复编号与末尾多余字节。版本一币对文本使用普通 UTF-8 String 构造，严格文本校验由版本二字段读取器提供。
     * @param payload 包含四字节版本头的完整策略快照字节
     */
    public static Map<Long, CoreFeePolicyState> decode(byte[] payload) {
        if (payload == null || payload.length < Integer.BYTES * 2) {
            throw new ProtocolException("truncated fee policy snapshot");
        }
        // 版本一载荷的小端读取视图，位置随条目读取推进。
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        // 载荷开头保存的快照布局版本。
        int version = buffer.getInt();
        if (version == 2) return decodeFields(payload);
        if (version != VERSION) throw new ProtocolException("unsupported fee policy snapshot version");
        // 版本一声明的策略条目数，限制为零到一百万。
        int count = buffer.getInt();
        if (count < 0 || count > 1_000_000) throw new ProtocolException("invalid fee policy snapshot count");
        // 按策略编号构建的恢复结果，返回时只读包装。
        Map<Long, CoreFeePolicyState> policies = new TreeMap<>();
        for (/* 当前版本一策略在载荷中的零起点序号。 */ int index = 0; index < count; index++) {
            if (buffer.remaining() < FIXED_LENGTH) throw new ProtocolException("truncated fee policy snapshot item");
            // 版本一当前策略的业务编号，用于恢复 Map 和查重。
            long policyId = buffer.getLong();
            // 该策略保存的业务修订号。
            long revision = buffer.getLong();
            // 该策略适用的用户编号。
            long userId = buffer.getLong();
            // 版本一币对名的无符号短整型字节长度，最多六十四。
            int symbolLength = Short.toUnsignedInt(buffer.getShort());
            if (symbolLength > 64 || buffer.remaining() < symbolLength + TAIL_LENGTH) {
                throw new ProtocolException("invalid fee policy snapshot instrumentId");
            }
            // 版本一复制出来的币对名原始 UTF-8 字节。
            byte[] instrumentId = new byte[symbolLength];
            buffer.get(instrumentId);
            // 当前待写入或已从快照构造的手续费策略值。
            CoreFeePolicyState policy;
            try {
                policy = new CoreFeePolicyState(policyId, revision, userId,
                        new String(instrumentId, StandardCharsets.UTF_8), buffer.getLong(), buffer.getLong(),
                        buffer.getInt(), readBoolean(buffer), buffer.getLong(), buffer.getLong());
            } catch (/* 版本一业务值校验失败的异常，当前分支只保留其消息构造协议异常。 */ IllegalArgumentException exception) {
                throw new ProtocolException(exception.getMessage());
            }
            if (policies.put(policyId, policy) != null) {
                throw new ProtocolException("duplicate fee policy snapshot id");
            }
        }
        if (buffer.hasRemaining()) throw new ProtocolException("fee policy snapshot has trailing bytes");
        return java.util.Collections.unmodifiableMap(policies);
    }

    /**
     * 从版本一缓冲读一个字节，只允许零和一，其他启停值视为协议错误。
     * @param buffer 当前停在版本一启停标记之前的小端缓冲
     */
    private static boolean readBoolean(ByteBuffer buffer) {
        // 版本一启停标记候选，只有零和一有效。
        byte value = buffer.get();
        if (value != 0 && value != 1) throw new ProtocolException("invalid fee policy snapshot active flag");
        return value == 1;
    }
}
