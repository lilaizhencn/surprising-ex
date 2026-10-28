package com.surprising.aeron.service.retention;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.change.LaneCommitDelta;
import com.surprising.aeron.service.result.FundsCommandIndex;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/** 保存当前产品线近期终态身份和长期资金命令指纹；Owner 管理墓碑 FIFO 并同步执行资金索引 I/O。 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class TerminalStateRetention implements TradingAccountRuntime.TerminalOrderSink, AutoCloseable {

    // 内存终态身份由当前产品 Owner 管理；资金 I/O 由同一 Owner 执行，快照输出为独立字节。

    /** 近期订单终态身份的六万五千五百三十六项保留上限。 */
    static final int MAX_TOMBSTONES = 65_536;
    /** 订单终态保留段快照版本四。 */
    private static final int VERSION = 4;
    /** 快照客户号的最大 UTF-8 字节数二百五十六。 */
    private static final int MAX_CLIENT_ID_BYTES = 256;
    /** Owner 持有的近期终态身份 FIFO，客户号与实体索引引用同一槽。 */
    private final TerminalTombstoneStore tombstones;
    /** 长期资金命令身份索引，活动 I/O 由 Owner 执行。 */
    private final FundsCommandIndex fundsCommands;

    /**
     * 创建空终态 FIFO 和尚未开库的资金身份索引。
     */
    public TerminalStateRetention() {
        this(com.surprising.aeron.config.CoreRuntimeConfiguration.standalone().fundsIndexDirectory());
    }

    public TerminalStateRetention(java.nio.file.Path fundsIndexDirectory) {
        this(new TerminalTombstoneStore(), new FundsCommandIndex(fundsIndexDirectory));
    }

    /** 冷阶段导入结束后，将活动资金索引 I/O 绑定到当前 Core Owner。 */
    public void activate() {
        fundsCommands.bindIoOwner();
    }

    /**
     * 将一条已提交终态订单身份写入墓碑，客户号按字节上限规范化；已有同订单 ID 不重复插入，不在每条写入时裁剪窗口。
     * @param orderId 正普通订单 ID
     * @param userId 实体所属用户，客户号查询与它组合定位
     * @param clientOrderId 可空普通订单客户号
     */
    @Override
    public void accept(long orderId, long userId, String clientOrderId) {
        tombstones.putIfAbsent(orderId, userId,
                normalizeClientId(clientOrderId));
    }

    /**
     * 按 Lane 回执的终态槽顺序逐项接收订单身份。
     * @param delta 已经完成的 Lane 回执，读取其终态订单槽
     */
    @Override
    public void acceptBatch(LaneCommitDelta delta) {
        for (/* 当前回执槽或快照记录的遍历下标 */ int index = 0; index < delta.terminalOrderCount(); index++) {
            accept(delta.terminalOrderId(index), delta.terminalOrderUser(index),
                    delta.terminalOrderClient(index));
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
     * 按订单 ID 或同用户客户订单号查近期墓碑，不读取活动订单。
     * @param orderId 正普通订单 ID
     * @param userId 实体所属用户，客户号查询与它组合定位
     * @param clientOrderId 可空普通订单客户号
     */
    public boolean containsOrder(long orderId, long userId, String clientOrderId) {
        if (orderId <= 0) throw new IllegalArgumentException("invalid terminal order key");
        if (tombstones.contains(orderId)) return true;
        String client = normalizeClientId(clientOrderId);
        return !client.isEmpty() && tombstones.containsClient(userId, client);
    }

    /**
     * 校验正订单 ID，查询近期订单墓碑。
     * @param orderId 正普通订单 ID
     */
    public boolean orderIdInUse(long orderId) {
        if (orderId <= 0) throw new IllegalArgumentException("invalid terminal order key");
        return tombstones.contains(orderId);
    }

    /**
     * 返回当前 FIFO 终态身份数量，不包括长期资金命令。
     */
    public int tombstoneCount() {
        return tombstones.size();
    }

    /**
     * 把两段 UUID 查找交给资金索引，活动阶段须由 Owner 线程调用。
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
     * 检查长期资金索引是否故障或已关闭，不查询业务状态。
     */
    public void assertHealthy() { fundsCommands.assertHealthy(); }

    /**
     * 关闭本实例资金索引和它的本地目录，终态内存结构随实例释放。
     */
    @Override
    public void close() { fundsCommands.close(); }

    /**
     * 在既有快照阶段按版本、墓碑和资金指纹顺序编码独立字节；本方法不额外取得并发状态屏障，输出 I/O 异常包装为编码故障。
     */
    public byte[] encode() {
        try {
            // 编码终态保留段的独立字节输出
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            // 写当前快照段的二进制流
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(VERSION);
            tombstones.write(output);
            fundsCommands.write(output);
            output.flush();
            return bytes.toByteArray();
        } catch (/* 编码或解码的 I/O 异常，按所在边界包装 */ IOException exception) {
            throw new IllegalStateException("failed to encode terminal retention", exception);
        }
    }

    /**
     * 校验版本和各段边界，恢复有序墓碑，再重建独立资金索引；拒绝资金条数与剩余字节不符、重复 UUID 或尾部字节。恢复失败时尝试关闭新索引，关闭异常仍可能覆盖原错误。
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
            TerminalTombstoneStore tombstones = readTombstones(input);
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
                return new TerminalStateRetention(tombstones, fundsCommands);
            } catch (/* 恢复新资金索引时的运行或 I/O 故障，关闭后继续抛出 */ RuntimeException | IOException failure) {
                fundsCommands.close();
                throw failure;
            }
        } catch (/* 编码或解码的 I/O 异常，按所在边界包装 */ IOException exception) {
            throw new IllegalArgumentException("invalid terminal retention payload", exception);
        }
    }

    /** 直接按 FIFO 顺序恢复近期墓碑，拒绝越界条数、非法身份、重复实体及截断文本。 */
    private static TerminalTombstoneStore readTombstones(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_TOMBSTONES) throw new IllegalArgumentException("invalid terminal retention count");
        TerminalTombstoneStore tombstones = new TerminalTombstoneStore();
        for (int index = 0; index < count; index++) {
            long id = input.readLong();
            long userId = input.readLong();
            int length = input.readInt();
            if (length < 0 || length > MAX_CLIENT_ID_BYTES) {
                throw new IllegalArgumentException("invalid terminal client id length");
            }
            byte[] text = input.readNBytes(length);
            if (text.length != length) throw new IllegalArgumentException("truncated terminal client id");
            if (!tombstones.putIfAbsent(id, userId, new String(text, StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("duplicate terminal entity");
            }
        }
        return tombstones;
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

}
