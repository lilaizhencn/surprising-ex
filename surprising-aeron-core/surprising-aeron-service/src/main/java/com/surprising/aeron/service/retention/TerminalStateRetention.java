package com.surprising.aeron.service.retention;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.change.LaneCommitDelta;
import com.surprising.aeron.service.result.FundsCommandIndex;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 保存当前产品线的近期终态身份及长期资金命令指纹。终态订单从已提交 Lane 回执直接进入有界墓碑 FIFO，资金索引由 Ledger 执行 I/O；候选表只保留恢复输入中的实体身份，并参与订单编号占用检查，不负责候选发现或批量清理。
 * 构造约定：接管已恢复的候选、墓碑和资金索引引用，不复制，也不在此启动工作线程。
 * 参数 candidates：已恢复的有序候选表
 * 参数 tombstones：已恢复的近期墓碑 FIFO
 * 参数 fundsCommands：已重建的长期资金身份索引
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class TerminalStateRetention implements TradingAccountRuntime.TerminalOrderSink, AutoCloseable {

    // 内存终态身份由当前产品 Owner 管理；资金 I/O 单独交给 Ledger，快照输出为独立字节。

    /** 近期四类终态身份共用的六万五千五百三十六项保留上限。 */
    static final int MAX_TOMBSTONES = 65_536;
    /** 终态保留段快照版本二。 */
    private static final int VERSION = 2;
    /** 快照客户号的最大 UTF-8 字节数二百五十六。 */
    private static final int MAX_CLIENT_ID_BYTES = 256;
    /** 从恢复输入接管的有序候选表，当前终态接收入口不向它新增。 */
    private final LinkedHashMap<EntityKey, RetainedEntity> candidates;
    /** Owner 持有的近期终态身份 FIFO，客户号与实体索引引用同一槽。 */
    private final TerminalTombstoneStore tombstones;
    /** 长期资金命令身份索引，活动 I/O 由 Ledger 执行。 */
    private final FundsCommandIndex fundsCommands;
    /** 只用于 Owner 查询的复用键，绝不作为稳定 Map 键保存。 */
    private final EntityKey candidateLookupKey = new EntityKey(EntityType.ORDER, 1);

    /**
     * 创建空候选表、终态 FIFO 和尚未开库的资金身份索引。
     */
    public TerminalStateRetention() {
        this(com.surprising.aeron.config.CoreRuntimeConfiguration.standalone().fundsIndexDirectory());
    }

    public TerminalStateRetention(java.nio.file.Path fundsIndexDirectory) {
        this(new LinkedHashMap<>(), new TerminalTombstoneStore(), new FundsCommandIndex(fundsIndexDirectory));
    }

    /**
     * 将一条已提交终态订单身份写入墓碑，客户号按字节上限规范化；已有同类型同 ID 不重复插入，不在每条写入时裁剪窗口。
     * @param orderId 正普通订单 ID
     * @param userId 实体所属用户，客户号查询与它组合定位
     * @param clientOrderId 可空普通订单客户号
     * @param coreSequence 这批终态事实对应的正提交序号
     */
    @Override
    public void accept(long orderId, long userId, String clientOrderId, long coreSequence) {
        tombstones.putIfAbsent(EntityType.ORDER.code, orderId, userId,
                normalizeClientId(clientOrderId), coreSequence);
    }

    /**
     * 按 Lane 回执的终态槽顺序逐项接收订单身份，使用当前统一提交序号。
     * @param delta 已经完成的 Lane 回执，读取其终态订单槽
     * @param coreSequence 这批终态事实对应的正提交序号
     */
    @Override
    public void acceptBatch(LaneCommitDelta delta, long coreSequence) {
        for (/* 当前回执槽或快照记录的遍历下标 */ int index = 0; index < delta.terminalOrderCount(); index++) {
            accept(delta.terminalOrderId(index), delta.terminalOrderUser(index),
                    delta.terminalOrderClient(index), coreSequence);
        }
    }

    /**
     * 本次序号的终态身份接收结束后，一次裁剪全部超出保留窗口的最旧墓碑。
     */
    @Override
    public void completeSequence() {
        trimTombstones();
    }

    /**
     * 让终态 FIFO 只保留最近六万五千五百三十六项，不裁剪长期资金身份。
     */
    private void trimTombstones() {
        tombstones.trim(MAX_TOMBSTONES);
    }

    /**
     * 按订单 ID 或同用户客户订单号查近期墓碑，不读取候选表或活动订单。
     * @param orderId 正普通订单 ID
     * @param userId 实体所属用户，客户号查询与它组合定位
     * @param clientOrderId 可空普通订单客户号
     */
    public boolean containsOrder(long orderId, long userId, String clientOrderId) {
        return contains(EntityType.ORDER, orderId, userId, clientOrderId);
    }

    /**
     * 校验正订单 ID，复用临时查询键检查候选表，再查订单墓碑；查询键不作为 Map 存储键。
     * @param orderId 正普通订单 ID
     */
    public boolean orderIdInUse(long orderId) {
        if (orderId <= 0) throw new IllegalArgumentException("invalid terminal order key");
        candidateLookupKey.reset(EntityType.ORDER, orderId);
        return candidates.containsKey(candidateLookupKey) || tombstones.contains(EntityType.ORDER.code, orderId);
    }

    /**
     * 按算法单 ID 或同用户客户算法号查近期墓碑。
     * @param algoOrderId 正算法父单 ID
     * @param userId 实体所属用户，客户号查询与它组合定位
     * @param clientAlgoOrderId 可空客户算法号
     */
    public boolean containsAlgo(long algoOrderId, long userId, String clientAlgoOrderId) {
        return contains(EntityType.ALGO, algoOrderId, userId, clientAlgoOrderId);
    }

    /**
     * 按触发单 ID 或同用户客户触发号查近期墓碑。
     * @param triggerOrderId 正触发单 ID
     * @param userId 实体所属用户，客户号查询与它组合定位
     * @param clientTriggerOrderId 可空客户触发号
     */
    public boolean containsTrigger(long triggerOrderId, long userId, String clientTriggerOrderId) {
        return contains(EntityType.TRIGGER, triggerOrderId, userId, clientTriggerOrderId);
    }

    /**
     * 返回当前恢复候选数量，不包括墓碑和活动订单。
     */
    public int candidateCount() {
        return candidates.size();
    }

    /**
     * 返回当前 FIFO 终态身份数量，不包括长期资金命令。
     */
    public int tombstoneCount() {
        return tombstones.size();
    }

    /**
     * 把两段 UUID 查找交给资金索引，活动阶段须由 Ledger 线程调用。
     * @param msb 资金命令 UUID 高位
     * @param lsb 资金命令 UUID 低位
     */
    public CommandFingerprint fundsCommand(long msb, long lsb) {
        return fundsCommands.get(msb, lsb);
    }

    /**
     * 空 UUID 直接返回空，否则拆分 UUID 查长期资金指纹。
     * @param commandId 本次资金命令 UUID，具体入口按规定处理空值
     */
    public CommandFingerprint fundsCommand(UUID commandId) {
        if (commandId == null) return null;
        return fundsCommand(commandId.getMostSignificantBits(), commandId.getLeastSignificantBits());
    }

    /**
     * 把已提交资金命令 UUID 和指纹交给长期索引，同内容重复不新增，冲突由索引报错。
     * @param msb 资金命令 UUID 高位
     * @param lsb 资金命令 UUID 低位
     * @param fingerprint 本次写入借用的非空命令指纹
     */
    public void retainFundsCommand(long msb, long lsb, CommandFingerprint fingerprint) {
        fundsCommands.put(msb, lsb, fingerprint);
    }

    /**
     * 拒绝空 UUID，拆分后保存资金指纹，沿用原索引线程归属。
     * @param commandId 本次资金命令 UUID，具体入口按规定处理空值
     * @param fingerprint 本次写入借用的非空命令指纹
     */
    public void retainFundsCommand(UUID commandId, CommandFingerprint fingerprint) {
        if (commandId == null) throw new IllegalArgumentException("commandId is required");
        retainFundsCommand(commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(), fingerprint);
    }

    /**
     * 直接控制命令复用的资金索引 I/O 任务。Owner 准备和轮询，Ledger 读写实际索引，通过完成位交回结果；冷阶段可在当前线程执行，不自建线程或另一份资金历史。
     */
    public static final class FundsIndexWork implements com.surprising.aeron.service.treasury.SystemLedgerLane.Entry {
        /** 当前任务借用的终态保留器，Ledger 经它访问资金索引。 */
        private TerminalStateRetention retention;
        /** 已排队任务要通知的运行时，完成发布前先捕获为局部引用。 */
        private TradingAccountRuntime completionRuntime;
        /** 准备时可空的原 UUID 引用，实际 I/O 使用两段数值。 */
        private UUID commandId;
        /** 本次资金 UUID 高位。 */
        private long commandIdMsb;
        /** 本次资金 UUID 低位。 */
        private long commandIdLsb;
        /**
         * fingerprint：写任务借用的待保存指纹，使用方须保持至完成。
         * retained：查任务执行后返回的可空稳定指纹。
         */
        private CommandFingerprint fingerprint, retained;
        /**
         * write：本次是否写入，假表示查找。
         * prepared：当前请求是否仍在使用，clear 前禁止再次准备。
         * queued：是否已成功交给活动 Ledger，避免重复入队。
         */
        private boolean write, prepared, queued;
        /** 跨线程完成发布位，初始及 clear 后为真。 */
        private volatile boolean completed = true;

        /**
         * 以两段 UUID 准备一次查找，不创建额外 UUID 对象，结果待 Ledger 或冷阶段执行。
         * @param retention 实际拥有资金索引的非空保留器
         * @param msb 资金命令 UUID 高位
         * @param lsb 资金命令 UUID 低位
         */
        public void prepareLookup(TerminalStateRetention retention, long msb, long lsb) {
            prepare(retention, null, msb, lsb, null, false);
        }

        /**
         * 校验非空 UUID，保存原引用和两段数值后准备查找。
         * @param retention 实际拥有资金索引的非空保留器
         * @param commandId 本次资金命令 UUID，具体入口按规定处理空值
         */
        public void prepareLookup(TerminalStateRetention retention, UUID commandId) {
            java.util.Objects.requireNonNull(commandId, "commandId");
            prepare(retention, commandId, commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(), null, false);
        }

        /**
         * 要求指纹非空，用两段 UUID 准备一次写入，调用方保持输入指纹在任务完成前有效。
         * @param retention 实际拥有资金索引的非空保留器
         * @param msb 资金命令 UUID 高位
         * @param lsb 资金命令 UUID 低位
         * @param fingerprint 本次写入借用的非空命令指纹
         */
        public void prepareWrite(TerminalStateRetention retention, long msb, long lsb, CommandFingerprint fingerprint) {
            prepare(retention, null, msb, lsb, java.util.Objects.requireNonNull(fingerprint), true);
        }

        /**
         * 要求 UUID 和指纹非空，准备保存该资金命令身份。
         * @param retention 实际拥有资金索引的非空保留器
         * @param commandId 本次资金命令 UUID，具体入口按规定处理空值
         * @param fingerprint 本次写入借用的非空命令指纹
         */
        public void prepareWrite(TerminalStateRetention retention, UUID commandId, CommandFingerprint fingerprint) {
            java.util.Objects.requireNonNull(commandId, "commandId");
            prepare(retention, commandId, commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(), java.util.Objects.requireNonNull(fingerprint), true);
        }

        /**
         * 上一次准备尚未 clear 就拒绝复用；接入保留器及请求字段，清旧结果并把完成位改为未完成，不在此执行 I/O。
         * @param retention 实际拥有资金索引的非空保留器
         * @param commandId 本次资金命令 UUID，具体入口按规定处理空值
         * @param msb 资金命令 UUID 高位
         * @param lsb 资金命令 UUID 低位
         * @param fingerprint 本次写入借用的非空命令指纹
         * @param write 是否准备写任务，假为查任务
         */
        private void prepare(TerminalStateRetention retention, UUID commandId, long msb, long lsb,
                             CommandFingerprint fingerprint, boolean write) {
            if (prepared) throw new IllegalStateException("funds index request is still retained");
            this.retention = java.util.Objects.requireNonNull(retention);
            this.commandId = commandId;
            this.commandIdMsb = msb;
            this.commandIdLsb = lsb;
            this.fingerprint = fingerprint;
            this.write = write;
            retained = null;
            queued = false;
            completed = false;
            prepared = true;
        }

        /**
         * 先检查 Core 健康与已准备状态；账本未启动时当前线程直接执行，活动阶段只投递一次 Ledger 查询。邮箱满返回未完成以便重试，已投递后读取完成位。
         * @param owner 当前命令所属的健康 Core
         */
        public boolean poll(TradingCoreRuntime owner) {
            owner.assertHealthy();
            if (!prepared) throw new IllegalStateException("funds index request is not prepared");
            if (!owner.runtimeState().systemLedgerStarted()) {
                apply(null);
                return true;
            }
            if (!queued) {
                completionRuntime = owner.runtimeState();
                if (!owner.runtimeState().dispatchLedgerQuery(this)) return false;
                queued = true;
            }
            return completed;
        }

        /**
         * 由实际执行线程绑定索引 I/O 并读或写；先捕获完成通知所需运行时，再公布完成位，最后只用局部引用通知 Owner，防止任务已被 clear 或复用后读错字段。
         * @param ignored 账本任务契约附带的 Treasury，本索引 I/O 不读取它
         */
        @Override public void apply(com.surprising.aeron.service.treasury.TreasuryRuntime ignored) {
            retention.fundsCommands.bindIoOwner();
            if (write) retention.fundsCommands.put(commandIdMsb, commandIdLsb, fingerprint);
            else retained = retention.fundsCommands.get(commandIdMsb, commandIdLsb);
            // 发布完成位前捕获的通知运行时，任务复用后只访问这个局部引用
            TradingAccountRuntime completion = completionRuntime;
            completed = true;
            if (completion != null) completion.signalOwnerCompletion();
        }

        /**
         * 只有完成位已发布才返回查得指纹；没有命中、写任务或已清空任务返回空，本入口不额外检查 prepared。
         */
        public CommandFingerprint retained() {
            if (!completed) throw new IllegalStateException("funds index lookup is incomplete");
            return retained;
        }

        /**
         * 正常完成后或 Ledger 已停止的关闭阶段清任务引用及标记，恢复空闲完成状态；不在执行线程仍可能访问时提前调用。
         */
        public void clear() {
            retention = null; completionRuntime = null; commandId = null;
            commandIdMsb = commandIdLsb = 0;
            fingerprint = retained = null;
            write = prepared = queued = false;
            completed = true;
        }
    }

    /**
     * 检查长期资金索引是否故障或已关闭，不查询业务状态。
     */
    public void assertHealthy() { fundsCommands.assertHealthy(); }

    /**
     * 关闭本实例资金索引和它的本地目录，终态内存结构随实例释放。
     */
    @Override
    public void close() { fundsCommands.close(); }

    /**
     * 在既有快照阶段按版本、候选、墓碑和资金指纹顺序编码独立字节；本方法不额外取得并发状态屏障，输出 I/O 异常包装为编码故障。
     */
    public byte[] encode() {
        try {
            // 编码终态保留段的独立字节输出
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            // 写当前快照段的二进制流
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(VERSION);
            write(output, candidates);
            tombstones.write(output);
            fundsCommands.write(output);
            output.flush();
            return bytes.toByteArray();
        } catch (/* 编码或解码的 I/O 异常，按所在边界包装 */ IOException exception) {
            throw new IllegalStateException("failed to encode terminal retention", exception);
        }
    }

    /**
     * 校验版本和各段边界，恢复有序候选及墓碑，再重建独立资金索引；拒绝资金条数与剩余字节不符、重复 UUID 或尾部字节。恢复失败时尝试关闭新索引，关闭异常仍可能覆盖原错误。
     * @param encoded 完整终态保留快照段
     */
    public static TerminalStateRetention decode(byte[] encoded) {
        return decode(encoded, com.surprising.aeron.config.CoreRuntimeConfiguration.standalone().fundsIndexDirectory());
    }

    public static TerminalStateRetention decode(byte[] encoded, java.nio.file.Path fundsIndexDirectory) {
        if (encoded == null) throw new IllegalArgumentException("terminal retention payload is required");
        try {
            // 读取本次独立快照字节的二进制流
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
            if (input.readInt() != VERSION) throw new IllegalArgumentException("unsupported terminal retention version");
            // 从快照解码出的有序待清理候选
            LinkedHashMap<EntityKey, RetainedEntity> candidates = read(input, Integer.MAX_VALUE);
            // 先按相同实体格式解码的有序墓碑值
            var decodedTombstones = read(input, MAX_TOMBSTONES);
            // 重新建立索引的终态 FIFO
            TerminalTombstoneStore tombstones = new TerminalTombstoneStore();
            for (var /* 当前从快照读出或准备写出的实体元数据 */ value : decodedTombstones.values()) tombstones.put(value.key().type().code,
                    value.key().id(), value.userId(), value.clientId(), value.exportSequence());
            // 资金身份条数，必须精确匹配剩余字节
            int fundsCommandCount = input.readInt();
            if (fundsCommandCount < 0 || (long) fundsCommandCount * (16 + CommandFingerprint.LENGTH) != input.available()) {
                throw new IllegalArgumentException("invalid funds command retention count");
            }
            // 本次恢复新建的资金索引，失败时尝试关闭
            FundsCommandIndex fundsCommands = new FundsCommandIndex(fundsIndexDirectory);
            try {
                for (/* 当前回执槽或快照记录的遍历下标 */ int index = 0; index < fundsCommandCount; index++) {
                    // 当前资金快照记录的完整 UUID
                    UUID commandId = new UUID(input.readLong(), input.readLong());
                    // 当前资金快照记录的原始定长指纹字节
                    byte[] fingerprint = input.readNBytes(CommandFingerprint.LENGTH);
                    if (fingerprint.length != CommandFingerprint.LENGTH
                            || fundsCommands.get(commandId) != null) {
                        throw new IllegalArgumentException("invalid retained funds command");
                    }
                    fundsCommands.put(commandId, CommandFingerprint.fromBytes(fingerprint));
                }
                if (input.available() != 0) throw new IllegalArgumentException("trailing terminal retention bytes");
                return new TerminalStateRetention(candidates, tombstones, fundsCommands);
            } catch (/* 恢复新资金索引时的运行或 I/O 故障，关闭后继续抛出 */ RuntimeException | IOException failure) {
                fundsCommands.close();
                throw failure;
            }
        } catch (/* 编码或解码的 I/O 异常，按所在边界包装 */ IOException exception) {
            throw new IllegalArgumentException("invalid terminal retention payload", exception);
        }
    }

    /**
     * 正实体 ID 已在墓碑中就直接命中；否则把客户号规范化后按实体类型、用户和非空客户号查找，不把实体 ID 命中当作用户归属校验。
     * @param type 四类终态实体之一
     * @param id 当前类别的正实体 ID
     * @param userId 实体所属用户，客户号查询与它组合定位
     * @param clientId 可空客户号，按入口规则规范化
     */
    private boolean contains(EntityType type, long id, long userId, String clientId) {
        if (id <= 0) throw new IllegalArgumentException("invalid terminal entity key");
        if (tombstones.contains(type.code, id)) return true;
        // 把空客户号规范为空字符串后的原文本
        String normalized = normalizeClientId(clientId);
        if (normalized.isEmpty()) return false;
        return tombstones.containsClient(type.code, userId, normalized);
    }

    /**
     * 按候选迭代顺序编码类型、ID、用户、导出序号和 UTF-8 客户号，顺序由外层快照边界固定。
     * @param output 当前快照输出流
     * @param values 要按顺序编码的候选表
     */
    private static void write(DataOutputStream output, LinkedHashMap<EntityKey, RetainedEntity> values)
            throws IOException {
        output.writeInt(values.size());
        for (RetainedEntity /* 当前从快照读出或准备写出的实体元数据 */ value : values.values()) {
            output.writeByte(value.key().type().code);
            output.writeLong(value.key().id());
            output.writeLong(value.userId());
            output.writeLong(value.exportSequence());
            // 当前输出客户号的 UTF-8 字节
            byte[] clientId = value.clientId().getBytes(StandardCharsets.UTF_8);
            output.writeInt(clientId.length);
            output.write(clientId);
        }
    }

    /**
     * 检查条数上限、实体类型、正身份与导出序号、客户号字节长度及重复实体，返回按输入顺序建立的候选 Map。
     * @param input 当前快照输入流
     * @param maximum 本段最多允许的非负实体条数
     */
    private static LinkedHashMap<EntityKey, RetainedEntity> read(DataInputStream input, int maximum)
            throws IOException {
        // 本快照段声称的实体条数
        int count = input.readInt();
        if (count < 0 || count > maximum) throw new IllegalArgumentException("invalid terminal retention count");
        // 按输入顺序建立的实体 Map
        LinkedHashMap<EntityKey, RetainedEntity> values = new LinkedHashMap<>();
        for (/* 当前回执槽或快照记录的遍历下标 */ int index = 0; index < count; index++) {
            // 快照记录的无符号实体类别代码
            int typeCode = input.readUnsignedByte();
            // 当前构造、解码或比较的实体组合键
            EntityKey key = new EntityKey(EntityType.fromCode(typeCode), input.readLong());
            // 本实体快照中的正用户 ID
            long userId = input.readLong();
            // 本实体快照中的正导出序号
            long exportSequence = input.readLong();
            // 当前客户号在快照中的 UTF-8 字节数
            int textLength = input.readInt();
            if (textLength < 0 || textLength > MAX_CLIENT_ID_BYTES) {
                throw new IllegalArgumentException("invalid terminal client id length");
            }
            // 实际读出的客户号字节，必须与长度完全一致
            byte[] text = input.readNBytes(textLength);
            if (text.length != textLength) throw new IllegalArgumentException("truncated terminal client id");
            // 当前从快照读出或准备写出的实体元数据
            RetainedEntity value = new RetainedEntity(key, userId,
                    new String(text, StandardCharsets.UTF_8), exportSequence);
            if (values.put(key, value) != null) throw new IllegalArgumentException("duplicate terminal entity");
        }
        return values;
    }

    /**
     * 空客户号规范为空串；长度不超过六十四个 UTF-16 单元时无需扫描字节，其余精确核对 UTF-8 最多二百五十六字节，不裁剪或去空白。
     * @param clientId 可空客户号，按入口规则规范化
     */
    private static String normalizeClientId(String clientId) {
        // 把空客户号规范为空字符串后的原文本
        String normalized = clientId == null ? "" : clientId;
        // 六十四个以内 UTF-16 单元一定能装入二百五十六字节上限，短客户号直接保留。
        // 较长的恢复或内部文本仍执行完整 UTF-8 长度校验，不截断原身份。
        if (normalized.length() <= 64) return normalized;
        if (com.surprising.aeron.protocol.CoreStateQueryCodec.utf8Length(normalized) > MAX_CLIENT_ID_BYTES) {
            throw new IllegalArgumentException("terminal client id is too long");
        }
        return normalized;
    }

    /** 终态实体的四种持久化类别，同一个数值 ID 在不同类别中分别索引。 构造约定：固定快照与墓碑使用的实体类型代码。 参数 code：四种已定义的实体代码 */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private enum EntityType {
        /**
         * 普通订单终态身份，快照代码零。
         */
        ORDER(0), /** 算法父单终态身份，快照代码一。 */ ALGO(1), /** 触发条件单终态身份，快照代码二。 */ TRIGGER(2), /** 强平记录终态身份，快照代码三。 */ LIQUIDATION(3);
        /** 实体类别的固定持久化代码。 */
        final int code;
        /**
         * 把四种持久化代码转为实体类型，其他值明确拒绝。
         * @param code 四种已定义的实体代码
         */
        static EntityType fromCode(int code) {
            return switch (code) {
                case 0 -> ORDER;
                case 1 -> ALGO;
                case 2 -> TRIGGER;
                case 3 -> LIQUIDATION;
                default -> throw new IllegalArgumentException("invalid entity type");
            };
        }
    }

    /**
     * 类型与正 ID 的组合键。候选 Map 内的键保持稳定，单独的 candidateLookupKey 才允许重复 reset。
     */
    private static final class EntityKey {
        /**  当前实体键的类型，存入 Map 后不得改写。 读取该键的实体类型。 */
        @Accessors(fluent = true, chain = false)
        @Getter(value = AccessLevel.PACKAGE)
        private EntityType type;
        /**  当前实体键的正 ID。 读取该键的正实体 ID。 */
        @Accessors(fluent = true, chain = false)
        @Getter(value = AccessLevel.PACKAGE)
        private long id;
        /**
         * 校验并建立类型与正实体 ID 的组合键。
         * @param type 四类终态实体之一
         * @param id 当前类别的正实体 ID
         */
        private EntityKey(EntityType type, long id) { reset(type, id); }
        /**
         * 复用查询键时更换类型和 ID；已经放入候选 Map 的键不能这样改写。
         * @param type 四类终态实体之一
         * @param id 当前类别的正实体 ID
         */
        private EntityKey reset(EntityType type, long id) {
            if (type == null || id <= 0) throw new IllegalArgumentException("invalid terminal entity key");
            this.type = type; this.id = id; return this;
        }
        /**
         * 按类型与 ID 计算内存 Map 查询哈希，不作为快照内容。
         */
        @Override public int hashCode() { return 31 * type.hashCode() + Long.hashCode(id); }
        /**
         * 只有同类型同 ID 的实体键相等。
         * @param other 参与实体键相等比较的对象
         */
        @Override public boolean equals(Object other) { return other instanceof EntityKey /* 当前构造、解码或比较的实体组合键 */ key && type == key.type && id == key.id; }
    }

    /**
     * 已导出、等待确认后清理的候选元数据，不复制订单、资金或持仓；当前候选来自恢复输入。
     */
    private static final class RetainedEntity {
        /**  候选的稳定实体键，不与可变查询键共用。 借出该候选稳定存储键。 */
        @Accessors(fluent = true, chain = false)
        @Getter(value = AccessLevel.PACKAGE)
        private final EntityKey key;
        /**  候选所属正用户 ID。 读取候选所属用户。 */
        @Accessors(fluent = true, chain = false)
        @Getter(value = AccessLevel.PACKAGE)
        private final long userId;
        /**  候选原客户号，可为空字符串但不能是 null。 读取已保存的客户号文本。 */
        @Accessors(fluent = true, chain = false)
        @Getter(value = AccessLevel.PACKAGE)
        private final String clientId;
        /**  候选已经导出的正序号，确认水位达到它后才可清理。 读取导出确认所需的原序号。 */
        @Accessors(fluent = true, chain = false)
        @Getter(value = AccessLevel.PACKAGE)
        private final long exportSequence;

        /**
         * 要求键、正用户、非空客户号和正导出序号，保存恢复候选元数据；不在这里查实际业务实体。
         * @param key 该候选持有的稳定实体键
         * @param userId 实体所属用户，客户号查询与它组合定位
         * @param clientId 可空客户号，按入口规则规范化
         * @param exportSequence 已经导出的正序号
         */
        private RetainedEntity(EntityKey key, long userId, String clientId, long exportSequence) {
            if (key == null || userId <= 0 || clientId == null || exportSequence <= 0) {
                throw new IllegalArgumentException("invalid retained terminal entity");
            }
            this.key = key;
            this.userId = userId;
            this.clientId = clientId;
            this.exportSequence = exportSequence;
        }

    }
}
