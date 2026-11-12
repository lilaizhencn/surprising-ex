package com.surprising.aeron.service.snapshot.state;

import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.service.core.AccountBook;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.TreeMap;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 尚未完成的跨账户或跨产品线划转快照编解码。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CoreTransferSnapshotCodec {

    private static final int VERSION = 3;

    /** 按输入划转 Map 的编号键排序，把划转编号、两端产品线和账户类型、资产金额、引用原因及显式收付款用户写成字段记录，并加版本三头。 */
    public static byte[] encode(Map<Long, TransferFundsCommand> transfers) {
        byte[] fields = new SnapshotFields.Writer().list(1, new TreeMap<>(transfers).values(), command -> {
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

    /** 按版本三字段恢复原始划转命令，要求产品线编号有效、条目数不超运行时上限且划转编号不重复。 */
    private static Map<Long, TransferFundsCommand> decodeFields(byte[] payload) {
        Map<Long, TransferFundsCommand> transfers = new TreeMap<>();
        var root = new SnapshotFields.Reader(java.util.Arrays.copyOfRange(payload, 4, payload.length));
        try {
            for (var r : root.list(1, SnapshotFields.Reader::new)) {
                var command = new com.surprising.aeron.protocol.TransferFundsCommand(r.number(2),
                        SnapshotEnumCodes.readProductLine(r.integer(3)), SnapshotEnumCodes.readProductLine(r.integer(4)),
                        r.text(5), r.text(6), r.text(7), r.number(8), r.text(9), r.text(10), r.number(11), r.number(12));
                if (transfers.size() >= com.surprising.aeron.service.treasury.SystemFunds.MAX_PENDING_TRANSFERS
                        || transfers.put(command.transferId(), command) != null)
                    throw new ProtocolException("invalid/duplicate pending transfer snapshot");
            }
            return java.util.Collections.unmodifiableMap(transfers);
        } catch (IllegalArgumentException invalid) { throw new ProtocolException("invalid transfer snapshot", invalid); }
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
