package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public final class CorePendingTransferCodec {

    public static final int MAX_RESULTS = 256;

    private CorePendingTransferCodec() {
    }

    public static byte[] encodeQuery(int limit) {
        if (limit <= 0 || limit > MAX_RESULTS) throw new IllegalArgumentException("invalid pending transfer limit");
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(limit).array();
    }

    public static int decodeQuery(byte[] payload) {
        if (payload == null || payload.length != Integer.BYTES) {
            throw new ProtocolException("invalid pending transfer query");
        }
        int limit = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (limit <= 0 || limit > MAX_RESULTS) throw new ProtocolException("invalid pending transfer limit");
        return limit;
    }

    public static byte[] encode(List<TransferFundsCommand> transfers) {
        if (transfers == null || transfers.size() > MAX_RESULTS) {
            throw new IllegalArgumentException("too many pending transfers");
        }
        List<byte[]> commands = transfers.stream()
                .map(TradingCommandCodec::encodeTransferFunds).toList();
        int length = Integer.BYTES;
        for (byte[] command : commands) {
            length = Math.addExact(length, Integer.BYTES + command.length);
        }
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(transfers.size());
        for (int index = 0; index < transfers.size(); index++) {
            buffer.putInt(commands.get(index).length).put(commands.get(index));
        }
        return buffer.array();
    }

    public static List<TransferFundsCommand> decode(byte[] payload) {
        if (payload == null || payload.length < Integer.BYTES) {
            throw new ProtocolException("truncated pending transfer response");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int count = buffer.getInt();
        if (count < 0 || count > MAX_RESULTS) throw new ProtocolException("invalid pending transfer count");
        List<TransferFundsCommand> transfers = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            if (buffer.remaining() < Integer.BYTES) {
                throw new ProtocolException("truncated pending transfer item");
            }
            int commandLength = buffer.getInt();
            if (commandLength <= 0 || commandLength > buffer.remaining()) {
                throw new ProtocolException("invalid pending transfer command length");
            }
            TransferFundsCommand command =
                    TradingCommandCodec.decodeTransferFunds(payload, buffer.position(), commandLength);
            buffer.position(buffer.position() + commandLength);
            transfers.add(command);
        }
        if (buffer.hasRemaining()) throw new ProtocolException("pending transfer response has trailing bytes");
        return List.copyOf(transfers);
    }
}
