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

/** 快照持久化边界的字段容器，每个字段写正数编号、四字节长度和内容，数值头按小端编码。同一记录字段不能重复，单记录最多一千零二十四项；字段出现顺序不影响读取，未知合法字段可以保留而由业务读取器不使用。这里不定义具体业务版本或必填字段表，也不进入撮合、结算及账户热路径。 构造约定：工具容器类不创建实例，使用独立 Writer 或 Reader。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class SnapshotFields {
    /** 每一条字段记录允许的最大字段数，不是整个快照或列表条目总数上限。 */
    private static final int MAX_FIELDS = 1024;
    /**
     * 仅本次编码使用的字段写入器，持有输出缓冲和已用编号；反复 encode 会复制当前全部内容，不自动重置。
     */
    static final class Writer {
        /** 当前 Writer 私有的累计字节缓冲，encode 返回其副本。 */
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        /** 本 Writer 已用的字段编号集合，用于发现重复及检查字段数。 */
        private final Set<Integer> ids = new HashSet<>();
        /**
         * 检查正数编号、当前记录内唯一性和字段数上限，再追加编号、内容长度及原始内容；内容被写入输出缓冲，允许空字节字段，返回同一 Writer 便于继续追加。
         * @param id 当前记录中的正数业务字段编号
         * @param value 要复制写入的原始字段内容
         */
        Writer bytes(int id, byte[] value) {
            if (id <= 0 || !ids.add(id) || ids.size() > MAX_FIELDS)
                throw new IllegalArgumentException("duplicate/invalid snapshot field: " + id);
            output.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(id).putInt(value.length).array());
            output.writeBytes(value);
            return this;
        }
        /**
         * 把 long 写成八字节小端内容，再按字段编号追加。
         * @param id 当前记录中的正数业务字段编号
         * @param value 要写成八字节的整数
         */
        Writer number(int id, long value) {
            return bytes(id, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
        }
        /**
         * 把布尔值转换为零或一，沿用八字节数值字段编码。
         * @param id 当前记录中的正数业务字段编号
         * @param value 要写成零或一的布尔值
         */
        Writer bool(int id, boolean value) { return number(id, value ? 1 : 0); }
        /**
         * 把字符串按 UTF-8 编码为字段内容；本写入入口不另限制两千零四十八字节，字符串读取入口才检查该限制。
         * @param id 当前记录中的正数业务字段编号
         * @param value 要按 UTF-8 编码的字符串
         */
        Writer text(int id, String value) { return bytes(id, value.getBytes(StandardCharsets.UTF_8)); }
        /**
         * 依次写 UUID 的高六十四位和低六十四位，每部分使用小端编码，共十六字节。
         * @param id 当前记录中的正数业务字段编号
         * @param value 要保存高低六十四位的 UUID
         */
        Writer uuid(int id, UUID value) {
            return bytes(id, ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array());
        }
        /**
         * 按集合当前迭代顺序编码各条目，每项前写四字节长度，最后整体作为一个字段追加；没有额外列表项数限制，条目内容及排序由调用方编码器负责。
         * @param <T> 列表中一条业务值的类型
         * @param id 当前记录中的正数业务字段编号
         * @param values 按当前迭代顺序编码的条目集合
         * @param encode 把一条业务值转换为字节的函数
         */
        <T> Writer list(int id, Collection<T> values, Function<T, byte[]> encode) {
            // 当前列表字段内部的条目串联缓冲，不是外层 Writer 输出。
            var out = new ByteArrayOutputStream();
            for (T /* 集合中当前待编码的一条业务值。 */ value : values) {
                // 该条目编码后的内容，前面另写四字节长度。
                byte[] data = encode.apply(value);
                out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length).array());
                out.writeBytes(data);
            }
            return bytes(id, out.toByteArray());
        }
        /**
         * 复制并返回当前已写字段字节，不清空 Writer，也不添加业务版本头。
         */
        byte[] encode() { return output.toByteArray(); }
    }
    /**
     * 单条记录的字段读取器，构造时完成编号与长度校验，业务方法再决定哪些字段必填及其内容类型。
     */
    static final class Reader {
        /** 本条 Reader 按字段编号保存的内容副本，bytes 会直接借出其中数组。 */
        private final Map<Integer, byte[]> fields = new HashMap<>();
        /**
         * 读取整条记录，逐项检查头部完整、正数编号、长度边界、字段数上限与重复编号；把每个字段内容复制到本记录的表中，字段顺序和未知编号不在这里作业务判断。
         * @param encoded 不包含外层业务版本头的整条字段记录
         */
        Reader(byte[] encoded) {
            if (encoded == null) throw new ProtocolException("missing snapshot record");
            // 按小端读取整条字段记录或列表内容的字节视图。
            var input = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
            while (input.hasRemaining()) {
                if (input.remaining() < 8) throw new ProtocolException("truncated snapshot field header");
                // 当前头部声明的字段编号，必须为正且不重复。
                // 当前字段或列表条目声明的字节长度，使用前检查剩余空间。
                int id = input.getInt(), length = input.getInt();
                if (id <= 0 || length < 0 || length > input.remaining() || fields.size() >= MAX_FIELDS)
                    throw new ProtocolException("invalid snapshot field length/id: " + id);
                // 当前字段或列表条目的字节内容，按对应读取方法检查格式。
                byte[] value = new byte[length]; input.get(value);
                if (fields.put(id, value) != null) throw new ProtocolException("duplicate snapshot field: " + id);
            }
        }
        /**
         * 取得必填编号的内容，缺失时报 ProtocolException；返回内部字节数组而非新副本，调用方应只读使用。
         * @param id 当前记录中的正数业务字段编号
         */
        byte[] bytes(int id) {
            // 当前字段或列表条目的字节内容，按对应读取方法检查格式。
            byte[] value = fields.get(id);
            if (value == null) throw new ProtocolException("required snapshot field missing: " + id);
            return value;
        }
        /**
         * 要求指定必填字段恰好八字节，再按小端读取 long。
         * @param id 当前记录中的正数业务字段编号
         */
        long number(int id) {
            // 当前字段或列表条目的字节内容，按对应读取方法检查格式。
            byte[] value = bytes(id);
            if (value.length != 8) throw new ProtocolException("invalid numeric snapshot field: " + id);
            return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getLong();
        }
        /**
         * 字段缺失时使用调用方明确给出的历史默认值；字段存在仍按八字节数值严格读取，不吞掉错误类型。
         * @param id 当前记录中的正数业务字段编号
         * @param defaultValue 字段缺失时明确采用的数值默认值
         */
        long numberOr(int id, long defaultValue) { return fields.containsKey(id) ? number(id) : defaultValue; }
        /**
         * 读取 long 后精确转 int，越界由 Math.toIntExact 抛 ArithmeticException。
         * @param id 当前记录中的正数业务字段编号
         */
        int integer(int id) { return Math.toIntExact(number(id)); }
        /**
         * 读取数值字段，只接受零或一，其他值按协议错误拒绝。
         * @param id 当前记录中的正数业务字段编号
         */
        boolean bool(int id) {
            // 从八字节数值字段读出的布尔候选，只接受零或一。
            long value = number(id);
            if (value != 0 && value != 1) throw new ProtocolException("invalid boolean snapshot field: " + id);
            return value == 1;
        }
        /**
         * 限制字段内容最多两千零四十八字节，并严格解码 UTF-8；无效字节转换为带原异常的 ProtocolException，不使用替换文本继续恢复。
         * @param id 当前记录中的正数业务字段编号
         */
        String text(int id) {
            // 当前字段或列表条目的字节内容，按对应读取方法检查格式。
            byte[] value = bytes(id);
            if (value.length > 2048) throw new ProtocolException("snapshot text too long: " + id);
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(value)).toString();
            } catch (/* 严格 UTF-8 解码发现的字符编码异常，作为协议异常原因保留。 */ java.nio.charset.CharacterCodingException invalid) {
                throw new ProtocolException("invalid UTF-8 snapshot field: " + id, invalid);
            }
        }
        /**
         * 要求内容恰好十六字节，按小端读取高位和低位并构造 UUID。
         * @param id 当前记录中的正数业务字段编号
         */
        UUID uuid(int id) {
            // 当前字段或列表条目的字节内容，按对应读取方法检查格式。
            byte[] value = bytes(id);
            if (value.length != 16) throw new ProtocolException("invalid UUID snapshot field: " + id);
            // 用于按小端读取 UUID 两个长整型部分的字节视图。
            var buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
        /**
         * 读取由长度与条目内容串联的必填字段，要求每项长度为正且不越界，按原顺序调用业务解码器并返回只读列表；空字段表示空列表。
         * @param <T> 列表中一条业务值的类型
         * @param id 当前记录中的正数业务字段编号
         * @param decode 把一个非空条目字节转换成业务值的函数
         */
        <T> List<T> list(int id, Function<byte[], T> decode) {
            // 按小端读取整条字段记录或列表内容的字节视图。
            var input = ByteBuffer.wrap(bytes(id)).order(ByteOrder.LITTLE_ENDIAN);
            // 按原列表顺序累计的已解码业务值，返回前固定为只读列表。
            List<T> values = new ArrayList<>();
            while (input.hasRemaining()) {
                if (input.remaining() < 4) throw new ProtocolException("truncated snapshot list");
                // 当前字段或列表条目声明的字节长度，使用前检查剩余空间。
                int length = input.getInt();
                if (length <= 0 || length > input.remaining()) throw new ProtocolException("invalid snapshot list item");
                // 当前字段或列表条目的字节内容，按对应读取方法检查格式。
                byte[] value = new byte[length]; input.get(value);
                values.add(decode.apply(value));
            }
            return List.copyOf(values);
        }
    }
}
