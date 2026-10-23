package com.surprising.aeron.service.result;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;
import static com.surprising.aeron.service.core.TradingCoreRuntime.*;

/**
 * 交易步骤 5–6：Owner 独占的近期命令结果账本，按 UUID 保存指纹、原终态和响应字节，用于重复命令及结果查询。条数和协议字节都有上限，替换保留原顺序；它不是账户状态副本，资金命令的长期身份另由资金索引保留。
 */
public final class CommandResultLedger {
    /** 固定五百一十二个线性探测槽，给最多一百二十八条保留结果留出探测空间。 */
    private static final int TABLE_CAPACITY = 512;
    /** 结果表二次幂容量的下标掩码。 */
    private static final int TABLE_MASK = TABLE_CAPACITY - 1;
    /** 保留顺序队列的固定二百五十六槽容量。 */
    private static final int RETENTION_CAPACITY = 256;
    /** 每个占用表槽对应 UUID 的高六十四位。 */
    private final long[] commandIdMost = new long[TABLE_CAPACITY];
    /** 每个占用表槽对应 UUID 的低六十四位。 */
    private final long[] commandIdLeast = new long[TABLE_CAPACITY];
    /** 按 UUID 探测定位的当前保留记录，null 表示空槽。 */
    private final StoredResult[] results = new StoredResult[TABLE_CAPACITY];
    /** 启动时预建的结果记录池，运行期存入借用其中记录。 */
    private final StoredResult[] recordRing = new StoredResult[TABLE_CAPACITY];
    /** 可再借记录的池下标栈。 */
    private final int[] freeRecordSlots = new int[TABLE_CAPACITY];
    /** 当前空闲记录栈的有效条数。 */
    private int freeRecordCount;
    /** 按保留顺序排放哈希表槽号的循环队列，负一表示空洞。 */
    private final int[] retentionSlots = new int[RETENTION_CAPACITY];
    /** 表槽到保留队列绝对位置的反向索引，搬移表槽时同步更新。 */
    private final int[] retentionPositions = new int[TABLE_CAPACITY];
    /** 可空响应池，存在时分别维护账本和发送方引用。 */
    private final ResponseArena responseArena;
    /** 保留循环队列的绝对头游标。 */
    private int retentionHead;
    /** 保留循环队列的下一追加绝对位置。 */
    private int retentionTail;
    /**  当前哈希表内保留结果数。 返回当前保留结果数，不是系统累计执行命令数。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private int size;
    /** 当前保留结果的协议总字节，不按池数组容量累计。 */
    private long commandResultBytes;
    /** 下一条新增命令的保留序号，替换原结果时不前移。 */
    private long nextResultRetentionSequence;

    /**
     * 从已有有序结果建立账本，不接入响应池的引用计数。
     * @param results 按保留顺序提供的已有结果集合，验证入口检查其元数据
     */
    public CommandResultLedger(LinkedHashMap<UUID, StoredResult> results) {
        this(results, null);
    }

    /**
     * 预建结果记录和空闲槽，检查输入非空及条数后按迭代顺序恢复记录，再统计协议字节和下一保留序号；完整恢复元数据验证由 validateResultLedger 单独承担。
     * @param results 按保留顺序提供的已有结果集合，验证入口检查其元数据
     * @param responseArena 可空共享响应池，存在时参与字节引用管理
     */
    public CommandResultLedger(LinkedHashMap<UUID, StoredResult> results, ResponseArena responseArena) {
        this.responseArena = responseArena;
        for (/* 当前预建池槽号或有效响应字节的遍历位置 */ int index = 0; index < TABLE_CAPACITY; index++) {
            recordRing[index] = new StoredResult(index);
            freeRecordSlots[index] = index;
        }
        freeRecordCount = TABLE_CAPACITY;
        java.util.Arrays.fill(retentionPositions, -1);
        if (results == null || results.size() > MAX_IDEMPOTENCY_RESULTS) {
            throw new IllegalArgumentException("invalid result ledger");
        }
        for (Map.Entry<UUID, StoredResult> /* 当前输入有序结果集合中的键值项 */ entry : results.entrySet()) put(entry.getKey(), entry.getValue());
        commandResultBytes = resultLedgerBytes(results);
        nextResultRetentionSequence = nextRetentionSequence(results);
    }

    /**
     * 判断当前是否没有保留任何命令结果。
     */
    public boolean isEmpty() { return size == 0; }

    /**
     * 按 UUID 两段数值查找当前记录，空账本或未命中返回 null；返回记录仍归账本复用，不是长期独立快照。
     * @param most 要查找或混合的 UUID 高位
     * @param least 要查找或混合的 UUID 低位
     */
    public StoredResult get(long most, long least) {
        if (size == 0) return null;
        return results[locate(most, least)];
    }

    /**
     * UUID 为空直接返回 null，否则复用两段数值查找。
     * @param id 原命令 UUID，按具体入口处理可空情况
     */
    public StoredResult get(UUID id) {
        if (id == null) return null;
        return get(id.getMostSignificantBits(), id.getLeastSignificantBits());
    }

    /**
     * 使用已取得的非空保留记录核对原命令身份；内容冲突返回 IDEMPOTENCY_CONFLICT，匹配则保留原终态和应用计数、把本次响应标记 DUPLICATE，并为池内字节增加独立发送引用。
     * @param duplicate 已经查到的非空保留记录
     * @param message 本次重复请求的原消息，用于核对身份
     * @param fingerprint 当前结果或请求的命令指纹
     * @param appliedCommandCount 当前要保存的应用计数；冲突响应使用传入计数，重复成功响应沿用原记录计数
     */
    public CoreResponse duplicateResponse(StoredResult duplicate, com.surprising.aeron.protocol.CoreMessage message,
                                   CommandFingerprint fingerprint,
                                   long appliedCommandCount) {
        if (!duplicate.fingerprint.equals(fingerprint)) {
            return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                    CoreResultCode.IDEMPOTENCY_CONFLICT, appliedCommandCount, EMPTY_RESPONSE_DATA);
        }
        if (responseArena != null) responseArena.retain(duplicate.responseDataUnsafe());
        return CoreResponse.owned(ResponseStatus.DUPLICATE,
                duplicate.status(), duplicate.resultCode(), duplicate.appliedCommandCount(),
                duplicate.responseDataUnsafe(),
                duplicate.responseDataOffsetUnsafe(), duplicate.responseDataLength());
    }

    /**
     * 仅在快照或导出边界按保留序号物化只读有序 Map；Map 结构独立，但值仍是当前复用记录的借用引用，不能把它当作永久不变的深复制。
     */
    public Map<UUID, StoredResult> entries() {
        // 快照边界收集的有效表槽列表，随后按保留序号排序
        ArrayList<Integer> slots = new ArrayList<>(size);
        for (/* 本次查找、插入、淘汰或导出的结果表槽 */ int slot = 0; slot < TABLE_CAPACITY; slot++) if (results[slot] != null) slots.add(slot);
        slots.sort(Comparator.comparingLong(/* 本次查找、插入、淘汰或导出的结果表槽 */ slot -> results[slot].retentionSequence()));
        // 只读导出 Map 的可写构建容器，值仍借用现有记录
        LinkedHashMap<UUID, StoredResult> snapshot = new LinkedHashMap<>(Math.max(4, size * 2));
        for (int /* 本次查找、插入、淘汰或导出的结果表槽 */ slot : slots) snapshot.put(new UUID(commandIdMost[slot], commandIdLeast[slot]), results[slot]);
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * 逐项累加结果协议固定字段和有效载荷长度，数值溢出直接报错。
     * @param results 按保留顺序提供的已有结果集合，验证入口检查其元数据
     */
    static long resultLedgerBytes(Map<UUID, StoredResult> results) {
        // 按结果协议固定字段和有效载荷累计的总字节
        long bytes = 0;
        for (Map.Entry<UUID, StoredResult> /* 当前输入有序结果集合中的键值项 */ entry : results.entrySet()) {
            bytes = Math.addExact(bytes, resultEntryBytes(entry.getValue()));
        }
        return bytes;
    }

    /**
     * 计算一条保留结果在快照协议中的固定字段加有效载荷字节数，不按底层数组容量计费。
     * @param result 要计费或存入账本的完整结果描述
     */
    static long resultEntryBytes(StoredResult result) {
        return Math.addExact(SectionedCoreSnapshotCodec.RESULT_FIXED_LENGTH, result.responseDataLength());
    }

    /**
     * 从现有保留序号求下一序号，空账本从一开始；已有序号加一溢出直接报错。
     * @param results 按保留顺序提供的已有结果集合，验证入口检查其元数据
     */
    static long nextRetentionSequence(Map<UUID, StoredResult> results) {
        // 求得的下一保留序号
        long next = 1;
        for (StoredResult /* 当前参与统计或相等性比较的结果记录 */ result : results.values()) {
            next = Math.max(next, Math.incrementExact(result.retentionSequence()));
        }
        return next;
    }

    /**
     * 验证条数、总协议字节及按迭代顺序严格递增的正保留序号，并拒绝空键或空值；它不在这里验证业务资金状态。
     * @param results 按保留顺序提供的已有结果集合，验证入口检查其元数据
     */
    public static void validateResultLedger(Map<UUID, StoredResult> results) {
        if (results == null || results.size() > MAX_IDEMPOTENCY_RESULTS) {
            throw new IllegalArgumentException("invalid result ledger count");
        }
        // 按结果协议固定字段和有效载荷累计的总字节
        long bytes = resultLedgerBytes(results);
        // 验证迭代顺序时上一条已经通过的正保留序号
        long previousRetentionSequence = 0;
        for (Map.Entry<UUID, StoredResult> /* 当前输入有序结果集合中的键值项 */ entry : results.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || entry.getValue().retentionSequence() <= previousRetentionSequence) {
                throw new IllegalArgumentException("invalid result ledger retention metadata");
            }
            previousRetentionSequence = entry.getValue().retentionSequence();
        }
        if (bytes > MAX_RESULT_LEDGER_BYTES) {
            throw new IllegalArgumentException("result ledger exceeds byte bound");
        }
    }

    /**
     * 读取传入结果的指纹、状态和响应切片，交给统一存储入口；传入结果自身的保留序号不决定本账本的新增顺序。
     * @param commandId 本次存入的非空命令 UUID
     * @param result 要计费或存入账本的完整结果描述
     */
    public void storeResult(UUID commandId, StoredResult result) {
        storeOwnedResult(commandId, result.fingerprint, result.status, result.resultCode,
                result.appliedCommandCount,
                result.responseData, result.responseOffset, result.responseLength);
    }

    /**
     * 以整个响应数组作为有效载荷交给切片入口；空数组引用按空响应处理，不复制已交入的有效字节。
     * @param commandId 本次存入的非空命令 UUID
     * @param fingerprint 当前结果或请求的命令指纹
     * @param status 要保存的原执行状态
     * @param resultCode 要保存的原确定性结果码
     * @param appliedCommandCount 当前要保存的应用计数；冲突响应使用传入计数，重复成功响应沿用原记录计数
     * @param responseData 调用方交入的响应字节数组，空值按入口规则规范为空响应
     */
    public void storeOwnedResult(UUID commandId, CommandFingerprint fingerprint, ResponseStatus status,
                          CoreResultCode resultCode, long appliedCommandCount,
                          byte[] responseData) {
        storeOwnedResult(commandId, fingerprint, status, resultCode, appliedCommandCount,
                responseData, 0,
                responseData == null ? 0 : responseData.length);
    }

    /**
     * 要求命令 UUID 非空，拆成两段后交给实际存储入口，保留原响应切片范围。
     * @param commandId 本次存入的非空命令 UUID
     * @param fingerprint 当前结果或请求的命令指纹
     * @param status 要保存的原执行状态
     * @param resultCode 要保存的原确定性结果码
     * @param appliedCommandCount 当前要保存的应用计数；冲突响应使用传入计数，重复成功响应沿用原记录计数
     * @param responseData 调用方交入的响应字节数组，空值按入口规则规范为空响应
     * @param responseOffset 响应有效切片的起始偏移
     * @param responseLength 响应有效切片字节数
     */
    public void storeOwnedResult(UUID commandId, CommandFingerprint fingerprint, ResponseStatus status,
                          CoreResultCode resultCode, long appliedCommandCount,
                          byte[] responseData, int responseOffset, int responseLength) {
        java.util.Objects.requireNonNull(commandId, "command id");
        storeOwnedResult(commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(),
                fingerprint, status, resultCode, appliedCommandCount, responseData, responseOffset, responseLength);
    }

    /**
     * 校验响应切片和单项字节上限，借另一预建记录保存结果；新命令追加保留序号，替换沿用旧序号。分别维护账本与发送引用，超条数或总字节时淘汰最旧的其他命令，保护本次存入项。
     * @param commandIdMsb 本次存入 UUID 高位
     * @param commandIdLsb 本次存入 UUID 低位
     * @param fingerprint 当前结果或请求的命令指纹
     * @param status 要保存的原执行状态
     * @param resultCode 要保存的原确定性结果码
     * @param appliedCommandCount 当前要保存的应用计数；冲突响应使用传入计数，重复成功响应沿用原记录计数
     * @param responseData 调用方交入的响应字节数组，空值按入口规则规范为空响应
     * @param responseOffset 响应有效切片的起始偏移
     * @param responseLength 响应有效切片字节数
     */
    public void storeOwnedResult(long commandIdMsb, long commandIdLsb, CommandFingerprint fingerprint, ResponseStatus status,
                          CoreResultCode resultCode, long appliedCommandCount,
                          byte[] responseData, int responseOffset, int responseLength) {
        if (responseData == null) {
            responseData = TradingCoreRuntime.EMPTY_RESPONSE_DATA;
            responseOffset = 0;
            responseLength = 0;
        } else if (responseOffset < 0 || responseLength < 0
                || responseOffset > responseData.length - responseLength) {
            throw new IllegalArgumentException("invalid result response slice");
        }
        // 当前新结果占用的协议字节数
        long resultBytes = Math.addExact(SectionedCoreSnapshotCodec.RESULT_FIXED_LENGTH,
                responseLength);
        if (resultBytes > MAX_RESULT_LEDGER_BYTES) {
            throw new IllegalArgumentException("result ledger entry exceeds byte bound");
        }
        // 本次查找、插入、淘汰或导出的结果表槽
        int slot = locate(commandIdMsb, commandIdLsb);
        // 相同 UUID 此前保存的可空记录
        StoredResult previous = results[slot];
        // 本次沿用旧记录或新分配的实际保留序号
        long retentionSequence = previous == null ? nextResultRetentionSequence : previous.retentionSequence();
        // 旧记录的底层响应数组，用对象身份判断是否沿用同一池引用
        byte[] previousResponse = previous == null ? null : previous.responseDataUnsafe();
        // 旧结果已占的协议字节，无旧结果为零
        long previousBytes = previous == null ? 0 : resultEntryBytes(previous);
        if (freeRecordCount == 0) throw new IllegalStateException("result commit ring is full");
        // 方法返回后，Owner 命令或输出队列仍持有终态响应。
        // 账本另持一份池引用，避免淘汰结果时覆盖尚未完成发送的响应字节。
        if (responseArena != null) {
            if (previous != null && previousResponse == responseData) {
                // 相同命令继续使用同一底层数组时，沿用已有账本引用，另加本次发送引用。
                // 首次响应已经排出、随后再次查询保留结果时，也需要独立的发送借用期。
                responseArena.retain(responseData);
            } else {
                responseArena.retainLedger(responseData);
            }
        }
        // 本次替换使用另一预建记录，避免当场改写当前边界仍借看的旧记录。
        // 旧记录随后归池，可被后续命令复用；导出的记录引用不是永久深复制。
        // 从预建池取得并写好本次结果的另一记录
        StoredResult retained = recordRing[freeRecordSlots[--freeRecordCount]];
        retained.set(fingerprint, status, resultCode, appliedCommandCount,
                responseData, responseOffset, responseLength, retentionSequence, true);
        // 本次成功存入后应保存的下一新增保留序号
        long nextSequence = previous == null ? Math.incrementExact(nextResultRetentionSequence)
                : nextResultRetentionSequence;
        if (previous == null) {
            commandIdMost[slot] = commandIdMsb;
            commandIdLeast[slot] = commandIdLsb;
            enqueueRetentionSlot(slot);
            size++;
        } else if (responseArena != null && previousResponse != responseData) {
            responseArena.releaseLedger(previousResponse);
        }
        if (previous != null) freeRecordSlots[freeRecordCount++] = previous.ringSlot;
        results[slot] = retained;
        nextResultRetentionSequence = nextSequence;
        commandResultBytes = Math.addExact(commandResultBytes - previousBytes, resultBytes);
        while (size > MAX_IDEMPOTENCY_RESULTS
                || commandResultBytes > MAX_RESULT_LEDGER_BYTES) {
            // 最早可淘汰且不是本次保护项的表槽
            int oldest = oldestSlot(commandIdMsb, commandIdLsb);
            if (oldest < 0) throw new IllegalStateException("result ledger protected entry exceeds bound");
            commandResultBytes = Math.subtractExact(commandResultBytes, resultEntryBytes(results[oldest]));
            remove(oldest);
        }
    }

    /**
     * 构造恢复时将已有结果复制进预建记录并接入保留队列；不为响应池另建引用，也不执行运行期淘汰逻辑。
     * @param id 原命令 UUID，按具体入口处理可空情况
     * @param value 恢复构造时要复制进池的结果记录
     */
    private void put(UUID id, StoredResult value) {
        // 本次查找、插入、淘汰或导出的结果表槽
        int slot = locate(id.getMostSignificantBits(), id.getLeastSignificantBits());
        if (freeRecordCount == 0) throw new IllegalStateException("result commit ring is full");
        // 从预建池取得并写好本次结果的另一记录
        StoredResult retained = recordRing[freeRecordSlots[--freeRecordCount]];
        retained.copyFrom(value);
        commandIdMost[slot] = id.getMostSignificantBits();
        commandIdLeast[slot] = id.getLeastSignificantBits();
        results[slot] = retained;
        enqueueRetentionSlot(slot);
        size++;
    }

    /**
     * 按 UUID 哈希线性探测，一次找到现有记录或可插入空槽。
     * @param most 要查找或混合的 UUID 高位
     * @param least 要查找或混合的 UUID 低位
     */
    private int locate(long most, long least) {
        // 本次查找、插入、淘汰或导出的结果表槽
        int slot = hash(most, least);
        while (results[slot] != null) {
            if (commandIdMost[slot] == most && commandIdLeast[slot] == least) return slot;
            slot = (slot + 1) & TABLE_MASK;
        }
        return slot;
    }

    /**
     * 从保留队列头查最早有效记录，跳过已移除项和本次受保护 UUID；没有可淘汰项返回负一。
     * @param protectedMost 本次不能淘汰的命令 UUID 高位
     * @param protectedLeast 本次不能淘汰的命令 UUID 低位
     */
    private int oldestSlot(long protectedMost, long protectedLeast) {
        for (/* 从保留队列头开始向后检查的相对位置 */ int offset = 0; offset < RETENTION_CAPACITY && retentionHead + offset < retentionTail; offset++) {
            // 本次查找、插入、淘汰或导出的结果表槽
            int slot = retentionSlots[(retentionHead + offset) & (RETENTION_CAPACITY - 1)];
            if (slot < 0 || results[slot] == null) continue;
            if (commandIdMost[slot] == protectedMost && commandIdLeast[slot] == protectedLeast) continue;
            return slot;
        }
        return -1;
    }

    /**
     * 归还被淘汰项的账本字节引用，标记保留队列空洞，并搬移哈希探测链填洞；搬移时同步保留位置，最后把记录对象归还预建池。本方法不单独扣 commandResultBytes，调用方先扣。
     * @param slot 结果哈希表中的槽号
     */
    private void remove(int slot) {
        // 本次即将从表移除并归还池的记录
        StoredResult removed = results[slot];
        if (responseArena != null && removed != null) {
            responseArena.releaseLedger(removed.responseDataUnsafe());
        }
        // 当前表槽对应的保留队列绝对位置
        int position = retentionPositions[slot];
        retentionSlots[position & (RETENTION_CAPACITY - 1)] = -1;
        retentionPositions[slot] = -1;
        size--;
        // 搬移后继记录填补探测链空洞，不积累删除标记。
        // 保留队列位置随记录搬移，淘汰与导出的相对顺序不变。
        // 移除后待填补的哈希探测链空洞
        int hole = slot;
        for (/* 探测链上正在检查是否应向前搬移的后继槽 */ int next = (hole + 1) & TABLE_MASK; results[next] != null;
                next = (next + 1) & TABLE_MASK) {
            // 后继记录按 UUID 哈希得到的原始起点
            int home = hash(commandIdMost[next], commandIdLeast[next]);
            if (((hole - home) & TABLE_MASK) < ((next - home) & TABLE_MASK)) {
                commandIdMost[hole] = commandIdMost[next];
                commandIdLeast[hole] = commandIdLeast[next];
                results[hole] = results[next];
                // 被搬移记录的原保留队列位置，必须跟随它更新反向索引
                int movedPosition = retentionPositions[next];
                retentionPositions[hole] = movedPosition;
                retentionSlots[movedPosition & (RETENTION_CAPACITY - 1)] = hole;
                hole = next;
            }
        }
        results[hole] = null;
        retentionPositions[hole] = -1;
        freeRecordSlots[freeRecordCount++] = removed.ringSlot;
        advanceRetentionHead();
    }

    /**
     * 先跳过队头空洞，保留队列跨度满时压紧，再追加结果槽并记录反向位置。
     * @param slot 结果哈希表中的槽号
     */
    private void enqueueRetentionSlot(int slot) {
        advanceRetentionHead();
        if (retentionTail - retentionHead >= RETENTION_CAPACITY) compactRetentionQueue();
        // 当前表槽对应的保留队列绝对位置
        int position = retentionTail++;
        retentionSlots[position & (RETENTION_CAPACITY - 1)] = slot;
        retentionPositions[slot] = position;
    }

    /**
     * 仅跳过保留队列头已经失效的空洞，不改变剩余结果的相对顺序。
     */
    private void advanceRetentionHead() {
        while (retentionHead < retentionTail
                && retentionSlots[retentionHead & (RETENTION_CAPACITY - 1)] < 0) {
            retentionHead++;
        }
    }

    /**
     * 把有效保留槽按原顺序压紧，修正反向位置并缩短尾游标，清掉中间空洞占用的跨度。
     */
    private void compactRetentionQueue() {
        // 压紧时已保留的有效队列条数
        int count = 0;
        // 压紧开始时头尾之间的队列跨度
        int pending = retentionTail - retentionHead;
        for (/* 从保留队列头开始向后检查的相对位置 */ int offset = 0; offset < pending; offset++) {
            // 本次查找、插入、淘汰或导出的结果表槽
            int slot = retentionSlots[(retentionHead + offset) & (RETENTION_CAPACITY - 1)];
            if (slot < 0) continue;
            // 当前表槽对应的保留队列绝对位置
            int position = retentionHead + count++;
            retentionSlots[position & (RETENTION_CAPACITY - 1)] = slot;
            retentionPositions[slot] = position;
        }
        retentionTail = retentionHead + count;
    }

    /**
     * 混合 UUID 两段数值，返回固定结果表内的起始探测槽。
     * @param most 要查找或混合的 UUID 高位
     * @param least 要查找或混合的 UUID 低位
     */
    private static int hash(long most, long least) {
        // UUID 混合后的中间数值
        long value = most * 0x9E3779B97F4A7C15L + least;
        value ^= value >>> 33;
        value *= 0xC2B2AE3D27D4EB4FL;
        return (int) (value ^ (value >>> 32)) & TABLE_MASK;
    }

    /**
     * 一条命令的结果描述，记录指纹、原终态、有效响应切片及保留顺序。独立构造的记录可单独持有；预建池记录归账本复用，替换不会当次改写旧记录，但旧记录归池后仍可能被后续命令重用。
     */
    public static final class StoredResult {
        /** 该记录在预建池中的下标；独立记录为负一。 */
        private final int ringSlot;
        /** 本记录保存的稳定命令指纹，不随原输入租约归还而改变。 */
        private CommandFingerprint fingerprint;
        /** 本记录自有的可复用指纹字节槽，用于接收借用指纹的复制。 */
        private CommandFingerprint fingerprintSlot;
        /**  原命令保存的执行状态。 读取原命令的执行终态。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private ResponseStatus status;
        /**  原命令保存的确定性结果码。 读取原命令的确定性业务结果码。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private CoreResultCode resultCode;
        /**  原结果保存时的应用命令计数，可能高于结算完成水位。 读取记录保存时的原应用命令计数，不以当前运行时计数替换。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private long appliedCommandCount;
        /** 保留的响应底层数组，使用有效偏移和长度解释。 */
        private byte[] responseData;
        /** 有效响应在底层数组中的起始偏移。 */
        private int responseOffset;
        /** 有效响应字节数，池数组可比它更大。 */
        private int responseLength;
        /**  本记录的保留顺序号，替换内容时继续沿用。 读取本结果的保留顺序，内容替换不将它挪到队尾。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private long retentionSequence;
        /**
         * 为记录池预建一个尚未写入业务内容的槽，固定其池下标；以后由 set 写入实际结果。
         * @param ringSlot 当前预建结果记录的池下标
         */
        private StoredResult(int ringSlot) {
            this.ringSlot = ringSlot;
            fingerprint = null;
            status = null;
            resultCode = null;
            responseData = TradingCoreRuntime.EMPTY_RESPONSE_DATA;
        }

        /**
         * 创建独立结果记录，默认复制响应有效字节，避免调用方改原数组影响结果。
         * @param fingerprint 当前结果或请求的命令指纹
         * @param status 要保存的原执行状态
         * @param resultCode 要保存的原确定性结果码
         * @param appliedCommandCount 当前要保存的应用计数；冲突响应使用传入计数，重复成功响应沿用原记录计数
         * @param responseData 调用方交入的响应字节数组，空值按入口规则规范为空响应
         * @param retentionSequence 要保存在独立记录中的非负顺序号
         */
        public StoredResult(CommandFingerprint fingerprint, ResponseStatus status, CoreResultCode resultCode,
                     long appliedCommandCount,
                     byte[] responseData, long retentionSequence) {
            this(fingerprint, status, resultCode, appliedCommandCount,
                    responseData, retentionSequence, false);
        }

        /**
         * 以整个响应数组创建独立记录，由 ownedResponseData 决定直接接管数组还是复制有效字节。
         * @param fingerprint 当前结果或请求的命令指纹
         * @param status 要保存的原执行状态
         * @param resultCode 要保存的原确定性结果码
         * @param appliedCommandCount 当前要保存的应用计数；冲突响应使用传入计数，重复成功响应沿用原记录计数
         * @param responseData 调用方交入的响应字节数组，空值按入口规则规范为空响应
         * @param retentionSequence 要保存在独立记录中的非负顺序号
         * @param ownedResponseData 为真表示调用方交出字节所有权，不再复制有效切片；本标记不自行增加池引用
         */
        public StoredResult(CommandFingerprint fingerprint, ResponseStatus status, CoreResultCode resultCode,
                     long appliedCommandCount,
                     byte[] responseData, long retentionSequence, boolean ownedResponseData) {
            this(fingerprint, status, resultCode, appliedCommandCount,
                    responseData, 0, responseData == null ? 0 : responseData.length, retentionSequence,
                    ownedResponseData);
        }

        /**
         * 创建不属于预建池的记录，校验指纹、状态、非负计数及切片范围后保存；是否复制响应字节由显式所有权标记决定。
         * @param fingerprint 当前结果或请求的命令指纹
         * @param status 要保存的原执行状态
         * @param resultCode 要保存的原确定性结果码
         * @param appliedCommandCount 当前要保存的应用计数；冲突响应使用传入计数，重复成功响应沿用原记录计数
         * @param responseData 调用方交入的响应字节数组，空值按入口规则规范为空响应
         * @param responseOffset 响应有效切片的起始偏移
         * @param responseLength 响应有效切片字节数
         * @param retentionSequence 要保存在独立记录中的非负顺序号
         * @param ownedResponseData 为真表示调用方交出字节所有权，不再复制有效切片；本标记不自行增加池引用
         */
        public StoredResult(CommandFingerprint fingerprint, ResponseStatus status, CoreResultCode resultCode,
                     long appliedCommandCount,
                     byte[] responseData, int responseOffset, int responseLength,
                     long retentionSequence, boolean ownedResponseData) {
            this.ringSlot = -1;
            if (fingerprint == null || status == null || resultCode == null || appliedCommandCount < 0
                    || retentionSequence < 0 || responseOffset < 0
                    || responseLength < 0 || responseData == null
                    && (responseOffset != 0 || responseLength != 0) || responseData != null
                    && responseOffset > responseData.length - responseLength) {
                throw new IllegalArgumentException("invalid stored result");
            }
            set(fingerprint, status, resultCode, appliedCommandCount,
                    responseData, responseOffset, responseLength, retentionSequence, ownedResponseData);
        }

        /**
         * 校验并写入本记录；借用指纹复制到记录自有指纹槽，响应为空则规范为空数组，有所有权则保留切片，否则只复制有效范围。
         * @param fingerprint 当前结果或请求的命令指纹
         * @param status 要保存的原执行状态
         * @param resultCode 要保存的原确定性结果码
         * @param appliedCommandCount 当前要保存的应用计数；冲突响应使用传入计数，重复成功响应沿用原记录计数
         * @param responseData 调用方交入的响应字节数组，空值按入口规则规范为空响应
         * @param responseOffset 响应有效切片的起始偏移
         * @param responseLength 响应有效切片字节数
         * @param retentionSequence 要保存在独立记录中的非负顺序号
         * @param ownedResponseData 为真表示调用方交出字节所有权，不再复制有效切片；本标记不自行增加池引用
         */
        private void set(CommandFingerprint fingerprint, ResponseStatus status, CoreResultCode resultCode,
                          long appliedCommandCount,
                          byte[] responseData, int responseOffset, int responseLength,
                          long retentionSequence, boolean ownedResponseData) {
            if (fingerprint == null || status == null || resultCode == null || appliedCommandCount < 0
                    || retentionSequence < 0 || responseOffset < 0
                    || responseLength < 0 || responseData == null
                    && (responseOffset != 0 || responseLength != 0) || responseData != null
                    && responseOffset > responseData.length - responseLength) {
                throw new IllegalArgumentException("invalid stored result");
            }
            this.fingerprint = fingerprint.isSlotValue()
                    ? (fingerprintSlot = fingerprint.copyIntoSlot(fingerprintSlot)) : fingerprint;
            this.status = status;
            this.resultCode = resultCode;
            this.appliedCommandCount = appliedCommandCount;
            // 把空引用规范为共享空数组后的响应底层数组
            byte[] normalized = responseData == null ? TradingCoreRuntime.EMPTY_RESPONSE_DATA : responseData;
            if (normalized == TradingCoreRuntime.EMPTY_RESPONSE_DATA || responseLength == 0) {
                this.responseData = TradingCoreRuntime.EMPTY_RESPONSE_DATA;
                this.responseOffset = 0;
                this.responseLength = 0;
            } else if (ownedResponseData) {
                this.responseData = normalized;
                this.responseOffset = responseOffset;
                this.responseLength = responseLength;
            } else {
                this.responseData = java.util.Arrays.copyOfRange(normalized, responseOffset,
                        responseOffset + responseLength);
                this.responseOffset = 0;
                this.responseLength = responseLength;
            }
            this.retentionSequence = retentionSequence;
        }

        /**
         * 将另一记录的值写到本池记录，借用指纹按本记录规则复制，响应切片直接引用，不额外增加响应池引用。
         * @param other 待复制或比较的另一个结果对象
         */
        private void copyFrom(StoredResult other) {
            set(other.fingerprint, other.status, other.resultCode, other.appliedCommandCount,
                    other.responseData, other.responseOffset,
                    other.responseLength, other.retentionSequence, true);
        }

        /**
         * 创建仅更换保留序号的独立记录，共用原响应切片；不自行增加响应池引用，也不修改当前记录。
         * @param sequence 新记录要采用的保留顺序号
         */
        public StoredResult withRetentionSequence(long sequence) {
            return new StoredResult(fingerprint, status, resultCode, appliedCommandCount,
                    responseData, responseOffset, responseLength,
                    sequence, true);
        }

        /**
         * 返回指纹的不可变值，避免对外暴露会随记录复用改写的指纹槽。
         */
        public CommandFingerprint fingerprint() { return fingerprint.immutableValue(); }

        /**
         * 借出原响应底层数组，必须连同偏移和有效长度使用，不能读写容量尾部或越过借用期。
         */
        public byte[] responseDataUnsafe() {
            return responseData;
        }

        /**
         * 读取有效响应在底层数组中的起始偏移。
         */
        public int responseDataOffsetUnsafe() { return responseOffset; }
        /**
         * 读取有效响应字节数，不是底层数组容量。
         */
        public int responseDataLength() { return responseLength; }

        /**
         * 复制有效响应切片供外部持有；零长时返回共享空数组。
         */
        public byte[] responseData() {
            return responseLength == 0 ? TradingCoreRuntime.EMPTY_RESPONSE_DATA
                    : java.util.Arrays.copyOfRange(responseData, responseOffset, responseOffset + responseLength);
        }

        /**
         * 比较指纹、状态、计数、保留序号和有效响应字节；池下标、数组偏移和容量不参与业务相等性。
         * @param other 待复制或比较的另一个结果对象
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof StoredResult /* 当前参与统计或相等性比较的结果记录 */ result)) return false;
            return appliedCommandCount == result.appliedCommandCount
                    && retentionSequence == result.retentionSequence
                    && fingerprint.equals(result.fingerprint)
                    && status == result.status
                    && resultCode == result.resultCode
                    && responseBytesEqual(result);
        }

        /**
         * 按与相等性一致的元数据及有效字节计算哈希，不把底层数组空余容量算进去。
         */
        @Override
        public int hashCode() {
            // 当前元数据组成的基础哈希
            int hash = java.util.Objects.hash(fingerprint, status, resultCode, appliedCommandCount,
                    retentionSequence);
            // 只按有效响应字节累计的数组哈希
            int responseHash = 1;
            for (/* 当前预建池槽号或有效响应字节的遍历位置 */ int index = responseOffset; index < responseOffset + responseLength; index++)
                responseHash = 31 * responseHash + responseData[index];
            return 31 * hash + responseHash;
        }

        /**
         * 按各自切片偏移逐字节比较有效响应，先检查有效长度相同。
         * @param other 待复制或比较的另一个结果对象
         */
        private boolean responseBytesEqual(StoredResult other) {
            if (responseLength != other.responseLength) return false;
            for (/* 当前预建池槽号或有效响应字节的遍历位置 */ int index = 0; index < responseLength; index++) {
                if (responseData[responseOffset + index] != other.responseData[other.responseOffset + index]) return false;
            }
            return true;
        }
    }
}
