package com.surprising.aeron.service.state.snapshot;

import com.surprising.aeron.protocol.ProtocolException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.util.*;
import java.util.function.Function;

/** 持久化边界的字段编号/长度编码；不进入撮合、结算或账户热路径。 */
final class SnapshotFields {
    private SnapshotFields() { }
    private static final int MAX_FIELDS = 1024;
    static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final Set<Integer> ids = new HashSet<>();
        Writer bytes(int id, byte[] value) {
            if (id <= 0 || !ids.add(id) || ids.size() > MAX_FIELDS)
                throw new IllegalArgumentException("duplicate/invalid snapshot field: " + id);
            output.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(id).putInt(value.length).array());
            output.writeBytes(value);
            return this;
        }
        Writer number(int id, long value) {
            return bytes(id, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
        }
        Writer bool(int id, boolean value) { return number(id, value ? 1 : 0); }
        Writer text(int id, String value) { return bytes(id, value.getBytes(StandardCharsets.UTF_8)); }
        Writer uuid(int id, UUID value) {
            return bytes(id, ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array());
        }
        <T> Writer list(int id, Collection<T> values, Function<T, byte[]> encode) {
            var out = new ByteArrayOutputStream();
            for (T value : values) {
                byte[] data = encode.apply(value);
                out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length).array());
                out.writeBytes(data);
            }
            return bytes(id, out.toByteArray());
        }
        byte[] encode() { return output.toByteArray(); }
    }
    static final class Reader {
        private final Map<Integer, byte[]> fields = new HashMap<>();
        Reader(byte[] encoded) {
            if (encoded == null) throw new ProtocolException("missing snapshot record");
            var input = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
            while (input.hasRemaining()) {
                if (input.remaining() < 8) throw new ProtocolException("truncated snapshot field header");
                int id = input.getInt(), length = input.getInt();
                if (id <= 0 || length < 0 || length > input.remaining() || fields.size() >= MAX_FIELDS)
                    throw new ProtocolException("invalid snapshot field length/id: " + id);
                byte[] value = new byte[length]; input.get(value);
                if (fields.put(id, value) != null) throw new ProtocolException("duplicate snapshot field: " + id);
            }
        }
        byte[] bytes(int id) {
            byte[] value = fields.get(id);
            if (value == null) throw new ProtocolException("required snapshot field missing: " + id);
            return value;
        }
        long number(int id) {
            byte[] value = bytes(id);
            if (value.length != 8) throw new ProtocolException("invalid numeric snapshot field: " + id);
            return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getLong();
        }
        long numberOr(int id, long defaultValue) { return fields.containsKey(id) ? number(id) : defaultValue; }
        int integer(int id) { return Math.toIntExact(number(id)); }
        boolean bool(int id) {
            long value = number(id);
            if (value != 0 && value != 1) throw new ProtocolException("invalid boolean snapshot field: " + id);
            return value == 1;
        }
        String text(int id) {
            byte[] value = bytes(id);
            if (value.length > 2048) throw new ProtocolException("snapshot text too long: " + id);
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(value)).toString();
            } catch (java.nio.charset.CharacterCodingException invalid) {
                throw new ProtocolException("invalid UTF-8 snapshot field: " + id, invalid);
            }
        }
        UUID uuid(int id) {
            byte[] value = bytes(id);
            if (value.length != 16) throw new ProtocolException("invalid UUID snapshot field: " + id);
            var buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
        <T> List<T> list(int id, Function<byte[], T> decode) {
            var input = ByteBuffer.wrap(bytes(id)).order(ByteOrder.LITTLE_ENDIAN);
            List<T> values = new ArrayList<>();
            while (input.hasRemaining()) {
                if (input.remaining() < 4) throw new ProtocolException("truncated snapshot list");
                int length = input.getInt();
                if (length <= 0 || length > input.remaining()) throw new ProtocolException("invalid snapshot list item");
                byte[] value = new byte[length]; input.get(value);
                values.add(decode.apply(value));
            }
            return List.copyOf(values);
        }
    }
}
