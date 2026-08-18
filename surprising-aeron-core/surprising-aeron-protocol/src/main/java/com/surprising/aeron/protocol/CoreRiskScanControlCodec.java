package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class CoreRiskScanControlCodec {

    private static final int VERSION = 1;

    private CoreRiskScanControlCodec() {
    }

    public static byte[] encodeCommand(UpdateRiskScanControlCommand command) {
        byte[] ruleName = text(command.ruleName());
        byte[] adminUserId = text(command.adminUserId());
        byte[] reason = text(command.reason());
        return ByteBuffer.allocate(Integer.BYTES + Long.BYTES * 2 + Integer.BYTES + Byte.BYTES
                        + Short.BYTES * 3 + ruleName.length + adminUserId.length + reason.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(VERSION)
                .putLong(command.expectedVersion())
                .put((byte) (command.enabled() ? 1 : 0))
                .putLong(command.scanDelayMs())
                .putInt(command.scanBatchSize())
                .putShort((short) ruleName.length).put(ruleName)
                .putShort((short) adminUserId.length).put(adminUserId)
                .putShort((short) reason.length).put(reason)
                .array();
    }

    public static UpdateRiskScanControlCommand decodeCommand(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        requireVersion(buffer);
        long expectedVersion = buffer.getLong();
        boolean enabled = readBoolean(buffer);
        long scanDelayMs = buffer.getLong();
        int scanBatchSize = buffer.getInt();
        String ruleName = readText(buffer);
        String adminUserId = readText(buffer);
        String reason = readText(buffer);
        requireConsumed(buffer);
        return new UpdateRiskScanControlCommand(expectedVersion, ruleName, enabled, scanDelayMs,
                scanBatchSize, adminUserId, reason);
    }

    public static byte[] encodeView(CoreRiskScanControlView view) {
        byte[] ruleName = text(view.ruleName());
        byte[] updatedBy = text(view.updatedBy());
        byte[] reason = text(view.reason());
        return ByteBuffer.allocate(Integer.BYTES + Long.BYTES * 3 + Integer.BYTES + Byte.BYTES
                        + Short.BYTES * 3 + ruleName.length + updatedBy.length + reason.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(VERSION)
                .putLong(view.version())
                .put((byte) (view.enabled() ? 1 : 0))
                .putLong(view.scanDelayMs())
                .putInt(view.scanBatchSize())
                .putShort((short) ruleName.length).put(ruleName)
                .putShort((short) updatedBy.length).put(updatedBy)
                .putShort((short) reason.length).put(reason)
                .putLong(view.updatedAtEpochMillis())
                .array();
    }

    public static CoreRiskScanControlView decodeView(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        requireVersion(buffer);
        long version = buffer.getLong();
        boolean enabled = readBoolean(buffer);
        long scanDelayMs = buffer.getLong();
        int scanBatchSize = buffer.getInt();
        String ruleName = readText(buffer);
        String updatedBy = readText(buffer);
        String reason = readText(buffer);
        long updatedAtEpochMillis = buffer.getLong();
        requireConsumed(buffer);
        return new CoreRiskScanControlView(version, ruleName, enabled, scanDelayMs, scanBatchSize,
                updatedBy, reason, updatedAtEpochMillis);
    }

    private static ByteBuffer readable(byte[] payload) {
        if (payload == null) throw new ProtocolException("risk scan control payload is required");
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void requireVersion(ByteBuffer buffer) {
        requireRemaining(buffer, Integer.BYTES + Long.BYTES + Byte.BYTES + Long.BYTES + Integer.BYTES);
        int version = buffer.getInt();
        if (version != VERSION) throw new ProtocolException("unsupported risk scan control version: " + version);
    }

    private static boolean readBoolean(ByteBuffer buffer) {
        byte value = buffer.get();
        if (value != 0 && value != 1) throw new ProtocolException("invalid risk scan control enabled flag");
        return value == 1;
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String readText(ByteBuffer buffer) {
        requireRemaining(buffer, Short.BYTES);
        int length = Short.toUnsignedInt(buffer.getShort());
        if (length == 0) throw new ProtocolException("risk scan control text is empty");
        requireRemaining(buffer, length);
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void requireRemaining(ByteBuffer buffer, int length) {
        if (length < 0 || buffer.remaining() < length) {
            throw new ProtocolException("truncated risk scan control payload");
        }
    }

    private static void requireConsumed(ByteBuffer buffer) {
        if (buffer.hasRemaining()) throw new ProtocolException("trailing risk scan control payload bytes");
    }
}
