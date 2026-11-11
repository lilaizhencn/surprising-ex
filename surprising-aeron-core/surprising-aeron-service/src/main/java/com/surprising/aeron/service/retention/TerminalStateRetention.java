package com.surprising.aeron.service.retention;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.change.AccountPartitionCommit;
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

/** 保存当前产品线近期终态身份和长期资金命令指纹。Owner 顺序维护两份内存索引。 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class TerminalStateRetention implements TradingAccountRuntime.TerminalOrderSink, AutoCloseable {

    // 两份内存身份由当前产品 Owner 管理，快照输出为独立字节。

    static final int MAX_COMPLETED_ORDERS = 65_536;
    /** 订单终态保留段快照版本四。 */
    private static final int VERSION = 4;
    /** 快照客户号的最大 UTF-8 字节数二百五十六。 */
    private static final int MAX_CLIENT_ID_BYTES = 256;
    /** Owner 持有的近期终态身份 FIFO，客户号与实体索引引用同一槽。 */
    private final CompletedOrderStore completedOrders;
    /** 长期资金命令身份索引，由 Owner 在内存中顺序维护。 */
    private final FundsCommandIndex fundsCommands;

    /** 创建空终态 FIFO 和资金身份索引。 */
    public TerminalStateRetention() {
        this(new CompletedOrderStore(), new FundsCommandIndex());
    }

    /** 冷阶段导入结束后，将活动资金索引绑定到当前 Core Owner。 */
    public void activate() {
        fundsCommands.bindOwner();
    }

    /** 将一条已提交终态订单身份写入墓碑，客户号按字节上限规范化。 */
    @Override
    public void accept(long orderId, long userId, String clientOrderId) {
        completedOrders.putIfAbsent(orderId, userId,
                normalizeClientId(clientOrderId));
    }

    /** 按 Lane 回执的终态槽顺序逐项接收订单身份。 */
    @Override
    public void acceptBatch(AccountPartitionCommit.Delta delta) {
        for (int index = 0; index < delta.terminalOrderCount(); index++) {
            accept(delta.terminalOrderId(index), delta.terminalOrderUser(index),
                    delta.terminalOrderClient(index));
        }
    }

    /** 本次序号的终态身份接收结束后，一次裁剪全部超出保留窗口的最旧墓碑。 */
    @Override
    public void completeSequence() {
        trimCompletedOrders();
    }

    private void trimCompletedOrders() {
        completedOrders.trim(MAX_COMPLETED_ORDERS);
    }

    /** 按订单 ID 或同用户客户订单号查近期墓碑，不读取活动订单。 */
    public boolean containsOrder(long orderId, long userId, String clientOrderId) {
        if (orderId <= 0) throw new IllegalArgumentException("invalid terminal order key");
        if (completedOrders.contains(orderId)) return true;
        String client = normalizeClientId(clientOrderId);
        return !client.isEmpty() && completedOrders.containsClient(userId, client);
    }

    /** 校验正订单 ID，查询近期订单墓碑。 */
    public boolean orderIdInUse(long orderId) {
        if (orderId <= 0) throw new IllegalArgumentException("invalid terminal order key");
        return completedOrders.contains(orderId);
    }

    /** 返回当前 FIFO 终态身份数量，不包括长期资金命令。 */
    public int completedCount() {
        return completedOrders.size();
    }

    /** 把两段 UUID 查找交给资金索引，活动阶段须由 Owner 线程调用。 */
    public CommandFingerprint fundsCommand(long msb, long lsb) {
        return fundsCommands.get(msb, lsb);
    }

    /** 资金幂等入口直接比较已保留的原生指纹列。 */
    public FundsCommandIndex.Match fundsCommandMatch(long msb, long lsb, CommandFingerprint fingerprint) {
        return fundsCommands.match(msb, lsb, fingerprint);
    }

    /** 空 UUID 直接返回空，否则拆分 UUID 查长期资金指纹。 */
    public CommandFingerprint fundsCommand(UUID commandId) {
        if (commandId == null) return null;
        return fundsCommand(commandId.getMostSignificantBits(), commandId.getLeastSignificantBits());
    }

    /** 把已提交资金命令 UUID 和指纹交给长期索引，同内容重复不新增，冲突由索引报错。 */
    public void retainFundsCommand(long msb, long lsb, CommandFingerprint fingerprint) {
        fundsCommands.put(msb, lsb, fingerprint);
    }

    /** 在进入资金业务计算前完成身份表扩容，避免扩容失败发生在账本提交之后。 */
    public void reserveFundsCommandIdentity() { fundsCommands.reserveNext(); }

    /** 拒绝空 UUID，拆分后保存资金指纹，沿用原索引线程归属。 */
    public void retainFundsCommand(UUID commandId, CommandFingerprint fingerprint) {
        if (commandId == null) throw new IllegalArgumentException("commandId is required");
        retainFundsCommand(commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(), fingerprint);
    }

    /** 检查长期资金索引是否故障或已关闭，不查询业务状态。 */
    public void assertHealthy() { fundsCommands.assertHealthy(); }

    /** 关闭本实例资金索引，终态内存结构随实例释放。 */
    @Override
    public void close() { fundsCommands.close(); }

    /** 在既有快照阶段按版本、墓碑和资金指纹顺序编码独立字节。本方法不额外取得并发状态屏障，输出 I/O 异常包装为编码故障。 */
    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(VERSION);
            completedOrders.write(output);
            fundsCommands.write(output);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to encode terminal retention", exception);
        }
    }

    /** 校验版本和各段边界，恢复有序墓碑，再重建独立资金索引。恢复失败时尝试关闭新索引，关闭异常仍可能覆盖原错误。 */
    public static TerminalStateRetention decode(byte[] encoded) {
        if (encoded == null) throw new IllegalArgumentException("terminal retention payload is required");
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
            if (input.readInt() != VERSION) throw new IllegalArgumentException("unsupported terminal retention version");
            CompletedOrderStore completedOrders = readCompletedOrders(input);
            int fundsCommandCount = input.readInt();
            if (fundsCommandCount < 0 || (long) fundsCommandCount * (16 + CommandFingerprint.LENGTH) != input.available()) {
                throw new IllegalArgumentException("invalid funds command retention count");
            }
            FundsCommandIndex fundsCommands = new FundsCommandIndex();
            try {
                for (int index = 0; index < fundsCommandCount; index++) {
                    UUID commandId = new UUID(input.readLong(), input.readLong());
                    byte[] fingerprint = input.readNBytes(CommandFingerprint.LENGTH);
                    if (fingerprint.length != CommandFingerprint.LENGTH
                            || fundsCommands.get(commandId) != null) {
                        throw new IllegalArgumentException("invalid retained funds command");
                    }
                    fundsCommands.put(commandId, CommandFingerprint.fromBytes(fingerprint));
                }
                if (input.available() != 0) throw new IllegalArgumentException("trailing terminal retention bytes");
                return new TerminalStateRetention(completedOrders, fundsCommands);
            } catch (RuntimeException | IOException failure) {
                fundsCommands.close();
                throw failure;
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid terminal retention payload", exception);
        }
    }

    /** 直接按 FIFO 顺序恢复近期墓碑，拒绝越界条数、非法身份、重复实体及截断文本。 */
    private static CompletedOrderStore readCompletedOrders(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_COMPLETED_ORDERS) throw new IllegalArgumentException("invalid terminal retention count");
        CompletedOrderStore completedOrders = new CompletedOrderStore();
        for (int index = 0; index < count; index++) {
            long id = input.readLong();
            long userId = input.readLong();
            int length = input.readInt();
            if (length < 0 || length > MAX_CLIENT_ID_BYTES) {
                throw new IllegalArgumentException("invalid terminal client id length");
            }
            byte[] text = input.readNBytes(length);
            if (text.length != length) throw new IllegalArgumentException("truncated terminal client id");
            if (!completedOrders.putIfAbsent(id, userId, new String(text, StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("duplicate terminal entity");
            }
        }
        return completedOrders;
    }

    private static String normalizeClientId(String clientId) {
        String normalized = clientId == null ? "" : clientId;
        // 较长的恢复或内部文本仍执行完整 UTF-8 长度校验，不截断原身份。
        if (normalized.length() <= 64) return normalized;
        if (com.surprising.aeron.protocol.CoreStateQueryCodec.utf8Length(normalized) > MAX_CLIENT_ID_BYTES) {
            throw new IllegalArgumentException("terminal client id is too long");
        }
        return normalized;
    }

}
