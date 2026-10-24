package com.surprising.aeron.service.snapshot.state;

import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.TreeMap;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 尚未完成的跨账户或跨产品线划转快照编解码。版本三按字段保存原命令的全部业务字段，所属用户直接取命令源用户。 构造约定：工具类不创建实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CoreTransferSnapshotCodec {

    /** 当前字段格式版本三。 */
    private static final int VERSION = 3;

    /**
     * 按输入划转 Map 的编号键排序，把划转编号、两端产品线和账户类型、资产金额、引用原因及显式收付款用户写成字段记录，并加版本三头；本入口没有额外检查待划转数量上限。
     * @param transfers 以划转编号为键的尚未完成划转集合
     */
    public static byte[] encode(Map<Long, TransferFundsCommand> transfers) {
        // 版本三的字段记录字节，外层另写版本头。
        byte[] fields = new SnapshotFields.Writer().list(1, new TreeMap<>(transfers).values(), /* 完整原命令包含两端用户、产品线与资金参数。 */ command -> {
            return new SnapshotFields.Writer()
                    .number(2, command.transferId())
                    .number(3, SnapshotEnumCodes.encode(command.sourceProductLine()))
                    .number(4, SnapshotEnumCodes.encode(command.targetProductLine()))
                    .text(5, command.sourceAccountType()).text(6, command.targetAccountType())
                    .text(7, command.asset()).number(8, command.amountUnits())
                    .text(9, command.referenceId()).text(10, command.reason())
                    .number(11, command.sourceUserId()).number(12, command.targetUserId()).encode();
        }).encode();
        return ByteBuffer.allocate(4 + fields.length).order(ByteOrder.LITTLE_ENDIAN).putInt(VERSION).put(fields).array();
    }

    /**
     * 按版本三字段恢复原始划转命令，要求产品线编号有效、条目数不超运行时上限且划转编号不重复；最后返回只读的按编号排序结果。
     * @param payload 带四字节版本头的完整划转快照
     */
    private static Map<Long, TransferFundsCommand> decodeFields(byte[] payload) {
        // 按划转编号构建的恢复结果，返回时包装为只读 Map。
        Map<Long, TransferFundsCommand> transfers = new TreeMap<>();
        // 版本三跳过版本头后的根字段读取器。
        var root = new SnapshotFields.Reader(java.util.Arrays.copyOfRange(payload, 4, payload.length));
        try {
            for (var /* 当前划转条目的字段读取器。 */ r : root.list(1, SnapshotFields.Reader::new)) {
                // 该划转保存的原业务命令，包含两端用户、产品线与资金参数。
                var command = new com.surprising.aeron.protocol.TransferFundsCommand(r.number(2),
                        SnapshotEnumCodes.readProductLine(r.integer(3)), SnapshotEnumCodes.readProductLine(r.integer(4)),
                        r.text(5), r.text(6), r.text(7), r.number(8), r.text(9), r.text(10), r.number(11), r.number(12));
                if (transfers.size() >= TradingAccountRuntime.MAX_PENDING_TRANSFERS
                        || transfers.put(command.transferId(), command) != null)
                    throw new ProtocolException("invalid/duplicate pending transfer snapshot");
            }
            return java.util.Collections.unmodifiableMap(transfers);
        } catch (/* 版本三命令或运行时值构造失败的业务异常，作为协议异常原因保留。 */ IllegalArgumentException invalid) { throw new ProtocolException("invalid transfer snapshot", invalid); }
    }

    /** 仅接受当前字段格式，不读取隐式用户的历史命令布局。 */
    public static Map<Long, TransferFundsCommand> decode(byte[] payload) {
        if (payload == null || payload.length < Integer.BYTES * 2) {
            throw new ProtocolException("truncated transfer snapshot");
        }
        if (ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt() != VERSION) {
            throw new ProtocolException("unsupported transfer snapshot version");
        }
        return decodeFields(payload);
    }
}
