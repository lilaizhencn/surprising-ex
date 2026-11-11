package com.surprising.aeron.service.snapshot.state;

import com.surprising.aeron.protocol.ProtocolException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 快照持久化边界的字段容器，每个字段写正数编号、四字节长度和内容，数值头按小端编码。同一记录字段不能重复，单记录最多一千零二十四项。字段出现顺序不影响读取，未知合法字段可以保留而由业务读取器不使用。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class SnapshotFields {
    private static final int MAX_FIELDS = 1024;
    /** 仅本次编码使用的字段写入器，持有输出缓冲和已用编号。 */
    static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final Set<Integer> ids = new HashSet<>();
        /** 检查正数编号、当前记录内唯一性和字段数上限，再追加编号、内容长度及原始内容。 */
        Writer bytes(int id, byte[] value) {
            if (id <= 0 || !ids.add(id) || ids.size() > MAX_FIELDS)
                throw new IllegalArgumentException("duplicate/invalid snapshot field: " + id);
            output.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(id).putInt(value.length).array());
            output.writeBytes(value);
            return this;
        }
        /** 把 long 写成八字节小端内容，再按字段编号追加。 */
        Writer number(int id, long value) {
            return bytes(id, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
        }
        /** 把布尔值转换为零或一，沿用八字节数值字段编码。 */
        Writer bool(int id, boolean value) { return number(id, value ? 1 : 0); }
        /** 把字符串按 UTF-8 编码为字段内容。 */
        Writer text(int id, String value) { return bytes(id, value.getBytes(StandardCharsets.UTF_8)); }
        /** 依次写 UUID 的高六十四位和低六十四位，每部分使用小端编码，共十六字节。 */
        Writer uuid(int id, UUID value) {
            return bytes(id, ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array());
        }
        /** 按集合当前迭代顺序编码各条目，每项前写四字节长度，最后整体作为一个字段追加。 */
        <T> Writer list(int id, Collection<T> values, Function<T, byte[]> encode) {
            var out = new ByteArrayOutputStream();
            for (T value : values) {
                byte[] data = encode.apply(value);
                out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length).array());
                out.writeBytes(data);
            }
            return bytes(id, out.toByteArray());
        }
        /** 复制并返回当前已写字段字节，不清空 Writer，也不添加业务版本头。 */
        byte[] encode() { return output.toByteArray(); }
    }
    /** 单条记录的字段读取器，构造时完成编号与长度校验，业务方法再决定哪些字段必填及其内容类型。 */
    static final class Reader {
        private final Map<Integer, byte[]> fields = new HashMap<>();
        /** 读取整条记录，逐项检查头部完整、正数编号、长度边界、字段数上限与重复编号。把每个字段内容复制到本记录的表中，字段顺序和未知编号不在这里作业务判断。 */
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
        /** 取得必填编号的内容，缺失时报 ProtocolException。 */
        byte[] bytes(int id) {
            byte[] value = fields.get(id);
            if (value == null) throw new ProtocolException("required snapshot field missing: " + id);
            return value;
        }
        /** 要求指定必填字段恰好八字节，再按小端读取 long。 */
        long number(int id) {
            byte[] value = bytes(id);
            if (value.length != 8) throw new ProtocolException("invalid numeric snapshot field: " + id);
            return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getLong();
        }
        /** 读取 long 后精确转 int，越界由 Math.toIntExact 抛 ArithmeticException。 */
        int integer(int id) { return Math.toIntExact(number(id)); }
        /** 读取数值字段，只接受零或一，其他值按协议错误拒绝。 */
        boolean bool(int id) {
            long value = number(id);
            if (value != 0 && value != 1) throw new ProtocolException("invalid boolean snapshot field: " + id);
            return value == 1;
        }
        /** 限制字段内容最多两千零四十八字节，并严格解码 UTF-8。无效字节转换为带原异常的 ProtocolException，不使用替换文本继续恢复。 */
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
        /** 要求内容恰好十六字节，按小端读取高位和低位并构造 UUID。 */
        UUID uuid(int id) {
            byte[] value = bytes(id);
            if (value.length != 16) throw new ProtocolException("invalid UUID snapshot field: " + id);
            var buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
        /** 读取由长度与条目内容串联的必填字段，要求每项长度为正且不越界，按原顺序调用业务解码器并返回只读列表。 */
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
