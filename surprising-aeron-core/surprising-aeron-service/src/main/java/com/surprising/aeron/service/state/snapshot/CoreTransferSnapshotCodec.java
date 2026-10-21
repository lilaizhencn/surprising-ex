package com.surprising.aeron.service.state.snapshot;

import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.account.TransferRuntime;

import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.TradingCommandCodec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.TreeMap;

/**
 * 尚未完成的跨账户或跨产品线划转快照编解码。版本二按字段保存原命令、所属用户及显式收付款用户；读取也支持已发布版本一的嵌套命令布局，恢复后仍以原 TransferRuntime 交给既有划转生命周期推进。
 */
public final class CoreTransferSnapshotCodec {

    /** 仍允许读取的已发布嵌套命令布局版本一；新编码使用版本二。 */
    private static final int VERSION = 1;
    /**
     * 工具类不创建实例。
     */
    private CoreTransferSnapshotCodec() {
    }

    /**
     * 按输入划转 Map 的编号键排序，把用户、划转编号、两端产品线和账户类型、资产金额、引用原因及显式收付款用户写成字段记录，并加版本二头；本入口没有额外检查待划转数量上限。
     * @param transfers 以划转编号为键的尚未完成划转集合
     */
    public static byte[] encode(Map<Long, TransferRuntime> transfers) {
        // 版本二的字段记录字节，外层另写版本头。
        byte[] fields = new SnapshotFields.Writer().list(1, new TreeMap<>(transfers).values(), /* 当前要保存或已经恢复的划转生命周期值。 */ transfer -> {
            // 该划转保存的原业务命令，包含两端用户、产品线与资金参数。
            var command = transfer.command();
            return new SnapshotFields.Writer()
                    .number(1, transfer.userId()).number(2, command.transferId())
                    .number(3, SnapshotEnumCodes.encode(command.sourceProductLine()))
                    .number(4, SnapshotEnumCodes.encode(command.targetProductLine()))
                    .text(5, command.sourceAccountType()).text(6, command.targetAccountType())
                    .text(7, command.asset()).number(8, command.amountUnits())
                    .text(9, command.referenceId()).text(10, command.reason())
                    .number(11, command.sourceUserId()).number(12, command.targetUserId()).encode();
        }).encode();
        return ByteBuffer.allocate(4 + fields.length).order(ByteOrder.LITTLE_ENDIAN).putInt(2).put(fields).array();
    }

    /**
     * 按版本二字段恢复命令及 TransferRuntime，要求产品线编号有效、条目数不超运行时上限且划转编号不重复；最后返回只读的按编号排序结果。
     * @param payload 带四字节版本头的完整划转快照
     */
    private static Map<Long, TransferRuntime> decodeFields(byte[] payload) {
        // 按划转编号构建的恢复结果，返回时包装为只读 Map。
        Map<Long, TransferRuntime> transfers = new TreeMap<>();
        // 版本二跳过版本头后的根字段读取器。
        var root = new SnapshotFields.Reader(java.util.Arrays.copyOfRange(payload, 4, payload.length));
        try {
            for (var /* 当前划转条目的字段读取器。 */ r : root.list(1, SnapshotFields.Reader::new)) {
                // 该划转保存的原业务命令，包含两端用户、产品线与资金参数。
                var command = new com.surprising.aeron.protocol.TransferFundsCommand(r.number(2),
                        SnapshotEnumCodes.readProductLine(r.integer(3)), SnapshotEnumCodes.readProductLine(r.integer(4)),
                        r.text(5), r.text(6), r.text(7), r.number(8), r.text(9), r.text(10), r.number(11), r.number(12));
                // 当前要保存或已经恢复的划转生命周期值。
                var transfer = new TransferRuntime(r.number(1), command);
                if (transfers.size() >= TradingRuntimeState.MAX_PENDING_TRANSFERS
                        || transfers.put(command.transferId(), transfer) != null)
                    throw new ProtocolException("invalid/duplicate pending transfer snapshot");
            }
            return java.util.Collections.unmodifiableMap(transfers);
        } catch (/* 版本二命令或运行时值构造失败的业务异常，作为协议异常原因保留。 */ IllegalArgumentException invalid) { throw new ProtocolException("invalid transfer snapshot", invalid); }
    }

    /**
     * 仅用于已先经 decode 完整校验的载荷，检查版本一快照内嵌命令是否统一为不含显式收付款用户的版本一哈希布局；混用命令版本拒绝。其他快照版本或空条目表返回 false，这里不重复执行完整字节边界校验。
     * @param payload 带四字节版本头的完整划转快照
     */
    public static boolean usesImplicitUserHash(byte[] payload) {
        // 当前版本一载荷的小端读取视图。
        var buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != 1) return false;
        // 载荷声明的待划转条目数量。
        int count = buffer.getInt();
        // 已观察到的内嵌命令版本，初始零表示尚未读取任何命令。
        int commandVersion = 0;
        for (/* 当前划转条目的零起点序号。 */ int index = 0; index < count; index++) {
            buffer.getLong();
            // 已校验载荷内本条嵌套命令的字节长度，用于跳到下一条。
            int length = buffer.getInt();
            // 当前嵌套命令的版本号，要求与前面条目一致。
            int current = buffer.getInt(buffer.position());
            if (commandVersion != 0 && commandVersion != current)
                throw new ProtocolException("mixed transfer hash schema in snapshot");
            commandVersion = current;
            buffer.position(buffer.position() + length);
        }
        return commandVersion == 1;
    }

    /**
     * 读取版本与数量；版本二使用字段格式，版本一逐项检查所属用户、命令长度与剩余空间，并用原协议解码器恢复命令。重复编号、多余尾部字节或无效业务值均拒绝，结果只读。
     * @param payload 带四字节版本头的完整划转快照
     */
    public static Map<Long, TransferRuntime> decode(byte[] payload) {
        if (payload == null || payload.length < Integer.BYTES * 2) {
            throw new ProtocolException("truncated transfer snapshot");
        }
        // 当前版本一载荷的小端读取视图。
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        // 快照最外层的布局版本。
        int version = buffer.getInt();
        if (version == 2) return decodeFields(payload);
        if (version != VERSION) throw new ProtocolException("unsupported transfer snapshot version");
        // 载荷声明的待划转条目数量。
        int count = buffer.getInt();
        if (count < 0 || count > TradingRuntimeState.MAX_PENDING_TRANSFERS) {
            throw new ProtocolException("invalid transfer snapshot count");
        }
        // 按划转编号构建的恢复结果，返回时包装为只读 Map。
        Map<Long, TransferRuntime> transfers = new TreeMap<>();
        for (/* 当前划转条目的零起点序号。 */ int index = 0; index < count; index++) {
            if (buffer.remaining() < Long.BYTES + Integer.BYTES) {
                throw new ProtocolException("truncated transfer snapshot item");
            }
            // 版本一条目保存的所属用户编号。
            long userId = buffer.getLong();
            // 版本一嵌套划转命令的正数长度，不能超过剩余字节。
            int commandLength = buffer.getInt();
            if (commandLength <= 0 || commandLength > buffer.remaining()) {
                throw new ProtocolException("invalid transfer snapshot command length");
            }
            // 按校验后长度复制的版本一命令字节。
            byte[] commandPayload = new byte[commandLength];
            buffer.get(commandPayload);
            // 当前要保存或已经恢复的划转生命周期值。
            TransferRuntime transfer;
            try {
                transfer = new TransferRuntime(userId, TradingCommandCodec.decodeTransferFunds(commandPayload, userId));
            } catch (/* 版本一命令或运行时值构造失败的异常，此处只取消息构造协议异常。 */ IllegalArgumentException exception) {
                throw new ProtocolException(exception.getMessage());
            }
            if (transfers.put(transfer.transferId(), transfer) != null) {
                throw new ProtocolException("duplicate transfer snapshot id");
            }
        }
        if (buffer.hasRemaining()) throw new ProtocolException("transfer snapshot has trailing bytes");
        return java.util.Collections.unmodifiableMap(transfers);
    }
}
