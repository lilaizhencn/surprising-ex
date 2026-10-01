package com.surprising.aeron.service.state.snapshot;

import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.account.TransferRuntime;

import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.TradingCommandCodec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.TreeMap;

public final class CoreTransferSnapshotCodec {

    private static final int VERSION = 1;
    private CoreTransferSnapshotCodec() {
    }

    public static byte[] encode(Map<Long, TransferRuntime> transfers) {
        byte[] fields = new SnapshotFields.Writer().list(1, new TreeMap<>(transfers).values(), transfer -> {
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

    private static Map<Long, TransferRuntime> decodeFields(byte[] payload) {
        Map<Long, TransferRuntime> transfers = new TreeMap<>();
        var root = new SnapshotFields.Reader(java.util.Arrays.copyOfRange(payload, 4, payload.length));
        try {
            for (var r : root.list(1, SnapshotFields.Reader::new)) {
                var command = new com.surprising.aeron.protocol.TransferFundsCommand(r.number(2),
                        SnapshotEnumCodes.readProductLine(r.integer(3)), SnapshotEnumCodes.readProductLine(r.integer(4)),
                        r.text(5), r.text(6), r.text(7), r.number(8), r.text(9), r.text(10), r.number(11), r.number(12));
                var transfer = new TransferRuntime(r.number(1), command);
                if (transfers.size() >= TradingRuntimeState.MAX_PENDING_TRANSFERS
                        || transfers.put(command.transferId(), transfer) != null)
                    throw new ProtocolException("invalid/duplicate pending transfer snapshot");
            }
            return java.util.Collections.unmodifiableMap(transfers);
        } catch (IllegalArgumentException invalid) { throw new ProtocolException("invalid transfer snapshot", invalid); }
    }

    /** 仅已发布的 v1 划转快照使用不含显式收付款人的业务哈希。先由 decode 完整校验载荷。 */
    public static boolean usesImplicitUserHash(byte[] payload) {
        var buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != 1) return false;
        int count = buffer.getInt();
        int commandVersion = 0;
        for (int index = 0; index < count; index++) {
            buffer.getLong();
            int length = buffer.getInt();
            int current = buffer.getInt(buffer.position());
            if (commandVersion != 0 && commandVersion != current)
                throw new ProtocolException("mixed transfer hash schema in snapshot");
            commandVersion = current;
            buffer.position(buffer.position() + length);
        }
        return commandVersion == 1;
    }

    public static Map<Long, TransferRuntime> decode(byte[] payload) {
        if (payload == null || payload.length < Integer.BYTES * 2) {
            throw new ProtocolException("truncated transfer snapshot");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int version = buffer.getInt();
        if (version == 2) return decodeFields(payload);
        if (version != VERSION) throw new ProtocolException("unsupported transfer snapshot version");
        int count = buffer.getInt();
        if (count < 0 || count > TradingRuntimeState.MAX_PENDING_TRANSFERS) {
            throw new ProtocolException("invalid transfer snapshot count");
        }
        Map<Long, TransferRuntime> transfers = new TreeMap<>();
        for (int index = 0; index < count; index++) {
            if (buffer.remaining() < Long.BYTES + Integer.BYTES) {
                throw new ProtocolException("truncated transfer snapshot item");
            }
            long userId = buffer.getLong();
            int commandLength = buffer.getInt();
            if (commandLength <= 0 || commandLength > buffer.remaining()) {
                throw new ProtocolException("invalid transfer snapshot command length");
            }
            byte[] commandPayload = new byte[commandLength];
            buffer.get(commandPayload);
            TransferRuntime transfer;
            try {
                transfer = new TransferRuntime(userId, TradingCommandCodec.decodeTransferFunds(commandPayload, userId));
            } catch (IllegalArgumentException exception) {
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
