package com.surprising.aeron.service.matcher;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.processors.journaling.ISerializationProcessor.SerializedModuleType;
import exchange.core2.core.processors.journaling.InMemorySerializationProcessor.SerializedModule;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32C;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 原生 Matcher 快照清单版本八的二进制格式。使用 DataInput/DataOutput 的大端整数顺序，逐模块和整条正文分别校验 CRC32C，保存拓扑、配对水位/哈希、注册表及原生模块；与交易状态快照三十七的小端字段格式不同，不能混用读取器。 构造约定：工具类不创建实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MatcherSnapshotCodec {

    /** 此原生 Matcher 清单的固定格式识别数。 */
    private static final int MAGIC = 0x4d534e50;
    /** 本 Codec 唯一写入和接受的清单版本八。 */
    private static final int VERSION = 8;
    /** 沿用分段 Core 快照规定的最大区段字节数，包含末尾八字节校验值。 */
    private static final int HARD_MAX_SNAPSHOT_BYTES = SectionedCoreSnapshotCodec.HARD_MAX_SECTION_BYTES;
    /** 解码每类注册表、进度或模块区段允许的通用百万条目粗上限。 */
    private static final int MAX_REGISTRY_ENTRIES = 1_000_000;
    /** 单个原生模块载荷上限，在总快照限额内预留一百二十八字节空间。 */
    private static final int MAX_MODULE_BYTES = HARD_MAX_SNAPSHOT_BYTES - 128;

    /**
     * 用限长正文缓冲写版本八清单，按清单现有迭代顺序保存注册表、进度及模块，最后追加八字节无符号 CRC32C 值。模块类型沿当前实现写 ordinal，单模块与总字节限长在这里检查；不额外执行 decode 的百万条目数上限。
     * @param snapshot 已经完成清单构造校验、输入引用在编码期间稳定的 MatcherSnapshot
     */
    public static byte[] encode(MatcherSnapshot snapshot) {
        int deploymentLimit = com.surprising.aeron.config.CoreRuntimeConfiguration.standalone().snapshot().maxBytes()
                - SectionedCoreSnapshotCodec.ENVELOPE_LENGTH
                - SectionedCoreSnapshotCodec.MAX_SECTION_COUNT * SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH;
        return encode(snapshot, Math.min(deploymentLimit, HARD_MAX_SNAPSHOT_BYTES));
    }

    /** 在调用方已经扣除外层封套/段头和已编码部分的预算内写入，扩容前拒绝超限。 */
    public static byte[] encode(MatcherSnapshot snapshot, int maximumSnapshotBytes) {
        if (maximumSnapshotBytes <= Long.BYTES || maximumSnapshotBytes > HARD_MAX_SNAPSHOT_BYTES)
            throw new IllegalArgumentException("invalid matcher snapshot byte limit");
        try {
            // 只容纳正文、不包含末尾八字节 CRC 的有上限输出缓冲。
            BoundedByteArrayOutputStream body =
                    new BoundedByteArrayOutputStream(maximumSnapshotBytes - Long.BYTES);
            try (/* 当前正文大端写入流，关闭后保留底层已写字节。 */ DataOutputStream output = new DataOutputStream(body)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(ProductLineWireCode.encode(snapshot.productLine()));
                output.writeInt(snapshot.topology().matchingEngineCount());
                output.writeInt(snapshot.topology().riskEngineCount());
                output.writeInt(snapshot.topology().matcherShardMask());
                output.writeInt(snapshot.topology().accountLaneCount());
                output.writeLong(snapshot.topology().accountLaneSeed());
                output.writeInt(snapshot.topology().matcherWindowSize());
                output.writeInt(snapshot.topology().matchingCompletionCapacity());
                output.writeInt(snapshot.topology().accountLaneQueueCapacity());
                output.writeLong(snapshot.snapshotId());
                output.writeLong(snapshot.coreSequence());
                output.writeLong(snapshot.matcherSequence());
                output.writeInt(snapshot.matcherShardProgress().size());
                for (MatcherShardProgress /* 本清单当前分片编号及其业务证据进度。 */ progress : snapshot.matcherShardProgress()) {
                    output.writeInt(progress.matcherShardId());
                    output.writeLong(progress.matcherSequence());
                }
                output.writeLong(snapshot.coreBusinessStateHash());
                output.writeInt(snapshot.engineStateHash());
                output.writeInt(snapshot.symbols().size());
                for (Map.Entry<String, Integer> /* 当前币对名字及原生引擎符号编号条目。 */ entry : snapshot.symbols().entrySet()) {
                    writeText(output, entry.getKey());
                    output.writeInt(entry.getValue());
                }
                output.writeInt(snapshot.users().size());
                for (Long /* 当前要写入或从清单恢复的原生正数用户编号。 */ userId : snapshot.users()) output.writeLong(userId);
                output.writeInt(snapshot.modules().size());
                for (SerializedModule /* 当前要写入类型、实例、水位、时间和载荷的原生模块。 */ module : snapshot.modules()) {
                    // 当前模块的序列化载荷字节，解码时还要核对其 CRC32C。
                    byte[] data = module.data();
                    if (data.length == 0 || data.length > MAX_MODULE_BYTES) {
                        throw new IllegalArgumentException("matcher module exceeds maximum size");
                    }
                    output.writeInt(module.type().ordinal());
                    output.writeInt(module.instanceId());
                    output.writeLong(module.sequence());
                    output.writeLong(module.timestampNs());
                    output.writeInt(data.length);
                    output.writeLong(module.checksum());
                    output.write(data);
                }
            }
            // 正文已经写入的实际字节数，最终再加八字节校验尾部。
            int bodyLength = body.size();
            if (bodyLength > maximumSnapshotBytes - Long.BYTES) {
                throw new IllegalArgumentException("matcher snapshot exceeds maximum size");
            }
            // 本次新分配的最终数组，前部为正文，尾部保存无符号 CRC32C 的 long。
            byte[] encoded = new byte[bodyLength + Long.BYTES];
            body.copyTo(encoded);
            ByteBuffer.wrap(encoded, bodyLength, Long.BYTES)
                    .putLong(Integer.toUnsignedLong(checksum(encoded, 0, bodyLength)));
            return encoded;
        } catch (/* 编码 IO、解码 EOF 或参数/IO 异常，按当前分支转换错误说明。 */ IOException exception) {
            throw new IllegalStateException("unable to encode matcher snapshot", exception);
        }
    }

    /**
     * 先检查总长度与正文 CRC32C，再按版本八读取拓扑和清单，检查注册表重复/数量、模块字节长度及各模块 CRC32C，最终要求没有尾部字节并构造 MatcherSnapshot 核对清单不变量。截断和其他 IO/参数异常按原代码转为协议异常，转换时保留消息而不附原 cause，已有协议异常原样抛出。
     * @param encoded 包含正文及末尾八字节 CRC32C 的完整快照字节
     */
    public static MatcherSnapshot decode(byte[] encoded) {
        if (encoded != null && encoded.length > HARD_MAX_SNAPSHOT_BYTES) {
            throw new ProtocolException("matcher snapshot exceeds maximum size");
        }
        if (encoded == null || encoded.length < 64) {
            throw new ProtocolException("matcher snapshot is truncated");
        }
        // 最终八字节保存的正文 CRC32C 无符号数值。
        long storedChecksum = ByteBuffer.wrap(encoded, encoded.length - Long.BYTES, Long.BYTES).getLong();
        // 重新计算正文得到的 CRC32C 三十二位位模式。
        int actualChecksum = checksum(encoded, 0, encoded.length - Long.BYTES);
        if (storedChecksum != Integer.toUnsignedLong(actualChecksum)) {
            throw new ProtocolException("matcher snapshot checksum mismatch");
        }
        try (/* 仅覆盖正文部分的大端读取流，不把校验尾部当作业务字节。 */ DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(encoded, 0, encoded.length - Long.BYTES))) {
            if (input.readInt() != MAGIC) throw new ProtocolException("invalid matcher snapshot magic");
            // 魔数之后保存的清单版本，当前只接受八。
            int version = input.readInt();
            if (version != VERSION) throw new ProtocolException("unsupported matcher snapshot version: " + version);
            // 从固定产品线编码恢复的独立产品线。
            ProductLine productLine = ProductLineWireCode.decode(input.readInt());
            // 从清单保存的引擎、账户路由和队列容量重建的拓扑。
            LaneTopology topology = new LaneTopology(input.readInt(), input.readInt(), input.readInt(),
                    input.readInt(), input.readLong(), input.readInt(), input.readInt(), input.readInt());
            // 所有模块必须共同归属的快照编号。
            long snapshotId = input.readLong();
            // 与 Core 状态配对的命令水位。
            long coreSequence = input.readLong();
            // 模块序号的清单上界，最大模块必须达到此值。
            long matcherSequence = input.readLong();
            // 清单保存的证据分片条目数，应为撮合引擎数加一。
            int progressCount = readCount(input, "matcher shard progress");
            if (progressCount != topology.matchingEngineCount() + 1) {
                throw new ProtocolException("invalid matcher shard progress count");
            }
            // 按字节原顺序收集的分片进度，构造清单时再检查规定编号顺序。
            List<MatcherShardProgress> matcherShardProgress = new ArrayList<>(progressCount);
            for (/* 当前进度、注册表或模块区段中的零起点条目下标。 */ int index = 0; index < progressCount; index++) {
                matcherShardProgress.add(new MatcherShardProgress(input.readInt(), input.readLong()));
            }
            // 清单保存的配对 Core 业务哈希。
            long businessHash = input.readLong();
            // 清单保存的原生引擎状态哈希。
            int engineHash = input.readInt();
            // 当前币对注册条目数。
            int symbolCount = readCount(input, "instrumentId registry");
            // 保留载荷迭代顺序的币对名字到符号编号恢复表。
            Map<String, Integer> symbols = new LinkedHashMap<>();
            // 已经出现的内部符号编号，检查跨币对重复。
            Set<Integer> symbolIds = new LinkedHashSet<>();
            for (/* 当前进度、注册表或模块区段中的零起点条目下标。 */ int index = 0; index < symbolCount; index++) {
                // 当前注册的币对业务名称。
                String instrumentId = readText(input);
                // 当前币对对应的正数原生符号编号。
                int symbolId = input.readInt();
                if (symbolId <= 0 || symbols.put(instrumentId, symbolId) != null || !symbolIds.add(symbolId)) {
                    throw new ProtocolException("duplicate matcher instrumentId");
                }
            }
            // 清单用户注册条目数。
            int userCount = readCount(input, "user registry");
            // 按载荷顺序收集、同时查重的原生用户集合。
            Set<Long> users = new LinkedHashSet<>();
            for (/* 当前进度、注册表或模块区段中的零起点条目下标。 */ int index = 0; index < userCount; index++) {
                // 当前要写入或从清单恢复的原生正数用户编号。
                long userId = input.readLong();
                if (userId <= 0 || !users.add(userId)) throw new ProtocolException("invalid matcher user registry");
            }
            // 保存的原生模块数量，必须等于撮合和风险引擎数之和。
            int moduleCount = readCount(input, "module");
            if (moduleCount != topology.matchingEngineCount() + topology.riskEngineCount()) {
                throw new ProtocolException("invalid matcher module count");
            }
            // 当前已校验 CRC 并恢复出的模块列表。
            List<SerializedModule> modules = new ArrayList<>(moduleCount);
            for (/* 当前进度、注册表或模块区段中的零起点条目下标。 */ int index = 0; index < moduleCount; index++) {
                // 原生模块类型枚举在本格式中的 ordinal，读取沿当前枚举排列解释。
                int typeOrdinal = input.readInt();
                if (typeOrdinal < 0 || typeOrdinal >= SerializedModuleType.values().length) {
                    throw new ProtocolException("invalid matcher module type");
                }
                // 当前模块在其类型内的实例编号。
                int instanceId = input.readInt();
                // 当前模块保存的原生序号。
                long sequence = input.readLong();
                // 模块记录的纳秒时间戳。
                long timestampNs = input.readLong();
                // 当前模块或文本声明的载荷字节数。
                int length = input.readInt();
                // 当前模块头保存的 CRC32C 无符号 long 数值。
                long moduleChecksum = input.readLong();
                if (length <= 0 || length > MAX_MODULE_BYTES || length > input.available()) {
                    throw new ProtocolException("invalid matcher module length");
                }
                // 当前模块的序列化载荷字节，解码时还要核对其 CRC32C。
                byte[] data = input.readNBytes(length);
                if (moduleChecksum != Integer.toUnsignedLong(checksum(data))) {
                    throw new ProtocolException("matcher module checksum mismatch");
                }
                modules.add(new SerializedModule(snapshotId, sequence, timestampNs,
                        SerializedModuleType.values()[typeOrdinal], instanceId, data));
            }
            if (input.available() != 0) throw new ProtocolException("trailing matcher snapshot bytes");
            return new MatcherSnapshot(productLine, topology, snapshotId, coreSequence,
                    matcherSequence, matcherShardProgress, businessHash, engineHash, symbols, users, modules);
        } catch (/* 编码 IO、解码 EOF 或参数/IO 异常，按当前分支转换错误说明。 */ EOFException exception) {
            throw new ProtocolException("matcher snapshot is truncated: " + exception.getMessage());
        } catch (/* 编码 IO、解码 EOF 或参数/IO 异常，按当前分支转换错误说明。 */ IOException | IllegalArgumentException exception) {
            if (exception instanceof ProtocolException /* 已经是协议错误的异常对象，原样向调用方传递。 */ protocolException) throw protocolException;
            throw new ProtocolException("invalid matcher snapshot: " + exception.getMessage());
        }
    }

    /**
     * 读取非负且不超过一百万的条目数，错误中包含当前区段名称；具体拓扑和模块数量还在解码主流程进一步核对。
     * @param input 当前大端清单内容读取流
     * @param field 数量异常中标明的业务区段名称
     */
    private static int readCount(DataInputStream input, String field) throws IOException {
        // 当前区段声明的条目数，先做通用非负/百万上限检查。
        int count = input.readInt();
        if (count < 0 || count > MAX_REGISTRY_ENTRIES) throw new ProtocolException("invalid " + field + " count");
        return count;
    }

    /**
     * 把非空且最多二百五十六字节的 UTF-8 内容写成四字节长度加内容；不在此规范化币对业务名称。
     * @param output 当前大端清单正文写入流
     * @param value 要写入的业务文本，或单字节写方法中的整数低八位
     */
    private static void writeText(DataOutputStream output, String value) throws IOException {
        // 当前业务文本按 UTF-8 编码后的字节。
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > 256) throw new IllegalArgumentException("invalid matcher text");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    /**
     * 检查文本长度为一到二百五十六且未越过剩余内容，再按普通 UTF-8 String 构造读取，不额外做严格非法字节校验。
     * @param input 当前大端清单内容读取流
     */
    private static String readText(DataInputStream input) throws IOException {
        // 当前模块或文本声明的载荷字节数。
        int length = input.readInt();
        if (length <= 0 || length > 256 || length > input.available()) {
            throw new ProtocolException("invalid matcher text length");
        }
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    /**
     * 计算整段输入的 CRC32C，返回其三十二位有符号位模式，写入清单时再转无符号 long。
     * @param data 要计算校验或写入片段的原始字节数组
     */
    private static int checksum(byte[] data) {
        return checksum(data, 0, data.length);
    }

    /**
     * 只对指定偏移和长度计算 CRC32C，不复制该段内容。
     * @param data 要计算校验或写入片段的原始字节数组
     * @param offset 本次校验或写片段的起始字节位置
     * @param length 本次校验或写片段的字节长度
     */
    private static int checksum(byte[] data, int offset, int length) {
        // 本次指定字节范围的 CRC32C 累加器。
        CRC32C checksum = new CRC32C();
        checksum.update(data, offset, length);
        return (int) checksum.getValue();
    }

    /**
     * 专用于构建快照正文的限长字节缓冲，复用父类数组增长与同步写入规则，在追加前阻止超出格式总限额。
     */
    private static final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
        /** 此正文缓冲允许的最大已写字节数，末尾清单校验值另写到最终数组。 */
        private final int maximumBytes;

        /**
         * 保存最大正文容量，初始数组取八 KiB 与该上限的较小值；调用方传入合法的正上限。
         * @param maximumBytes 缓冲正文总字节上限
         */
        private BoundedByteArrayOutputStream(int maximumBytes) {
            super(Math.min(8 * 1024, maximumBytes));
            this.maximumBytes = maximumBytes;
        }

        /**
         * 同步检查增加一字节后不超限，再由父类写入整数低八位。
         * @param value 要写入的业务文本，或单字节写方法中的整数低八位
         */
        @Override
        public synchronized void write(int value) {
            ensureCapacityFor(1);
            super.write(value);
        }

        /**
         * 同步检查本次长度不会使正文超限，再由父类写指定数组片段；数组及偏移边界仍由父类检查。
         * @param data 要计算校验或写入片段的原始字节数组
         * @param offset 本次校验或写片段的起始字节位置
         * @param length 本次校验或写片段的字节长度
         */
        @Override
        public synchronized void write(byte[] data, int offset, int length) {
            ensureCapacityFor(length);
            super.write(data, offset, length);
        }

        /**
         * 拒绝负追加长度，并用已用量与最大值减追加量比较，避免用 count 加长度判断时溢出；这里只检查，不提前分配数组。
         * @param additionalBytes 即将增加的字节数
         */
        private void ensureCapacityFor(int additionalBytes) {
            if (additionalBytes < 0 || count > maximumBytes - additionalBytes) {
                throw new IllegalArgumentException("matcher snapshot exceeds maximum size");
            }
        }

        /**
         * 把已用正文直接复制到目标数组开头，不复制空闲容量，也不清空输出缓冲；目标容量由调用者准备。
         * @param destination 已按正文加校验尾部预分配的最终目标数组
         */
        private void copyTo(byte[] destination) {
            System.arraycopy(buf, 0, destination, 0, count);
        }
    }
}
