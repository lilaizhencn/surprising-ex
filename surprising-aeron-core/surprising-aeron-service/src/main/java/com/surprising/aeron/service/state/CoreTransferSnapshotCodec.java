package com.surprising.aeron.service.state;

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
        int length = Integer.BYTES * 2;
        Map<Long, byte[]> payloads = new TreeMap<>();
        for (TransferRuntime transfer : transfers.values()) {
            byte[] payload = TradingCommandCodec.encodeTransferFunds(transfer.command());
            payloads.put(transfer.transferId(), payload);
            length = Math.addExact(length, Long.BYTES + Integer.BYTES + payload.length);
        }
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(VERSION).putInt(payloads.size());
        payloads.forEach((transferId, payload) -> buffer
                .putLong(transfers.get(transferId).userId()).putInt(payload.length).put(payload));
        return buffer.array();
    }

    public static Map<Long, TransferRuntime> decode(byte[] payload) {
        if (payload == null || payload.length < Integer.BYTES * 2) {
            throw new ProtocolException("truncated transfer snapshot");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != VERSION) throw new ProtocolException("unsupported transfer snapshot version");
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
                transfer = new TransferRuntime(userId, TradingCommandCodec.decodeTransferFunds(commandPayload));
            } catch (IllegalArgumentException exception) {
                throw new ProtocolException(exception.getMessage());
            }
            if (transfers.put(transfer.transferId(), transfer) != null) {
                throw new ProtocolException("duplicate transfer snapshot id");
            }
        }
        if (buffer.hasRemaining()) throw new ProtocolException("transfer snapshot has trailing bytes");
        return Map.copyOf(transfers);
    }
}
