package com.surprising.aeron.service.order;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.service.order.command.DecodedMatchingCommand;
import com.surprising.aeron.service.order.command.ResolvedMatchingAdmission;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;

/**
 * Owner 持有的待撮合命令顺序与索引，所有业务进度仍放在复用 CommandSlot。全局链用于按 Core 顺序提交/回收，各 Matcher 分片另有提交链，财库派发另有游标；UUID 和用户计数只是派生索引。acquire 先占槽，put 才链接入队，两者之间的预备槽仍计入池的在途数。
 */
public final class PendingMatchingRing {
    /** 以物理槽为键的全局后继数组，负一表示没有后继。 */
    private final int[] nextSlots;
    /** 以物理槽为键的全局前驱数组。 */
    private final int[] previousSlots;
    /** 各物理项登记的 Matcher 分片，负一表示未登记。 */
    private final int[] submissionShards;
    /** 各物理项在其分片提交链中的后继。 */
    private final int[] nextSubmissionSlots;
    /** 各物理项在其分片提交链中的前驱。 */
    private final int[] previousSubmissionSlots;
    /** 每个 Matcher 分片的提交链头物理下标。 */
    private final int[] submissionHeads;
    /** 每个 Matcher 分片的提交链尾物理下标。 */
    private final int[] submissionTails;
    /** 当前已链接命令的 UUID 派生索引。 */
    private final PendingCommandIdIndex entriesByCommandId;
    /** 当前已链接命令按正用户号计数的派生索引。 */
    private final LongIntHashMap pendingByUser;
    /**  真正拥有可复用命令槽的固定池。 返回同一个底层命令槽池，供受约束的 Owner/Matcher 协作。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final CommandSlotRing contexts;
    /** 槽池容量减一的物理寻址掩码。 */
    private final int mask;
    /** 全局 FIFO 队首物理位置，空队列负一。 */
    private int head = -1;
    /** 全局 FIFO 队尾物理位置，空队列负一。 */
    private int tail = -1;
    /** 尚需按顺序派发资金事实的物理游标，独立于响应退休队首。 */
    private int ledgerDispatchHead = -1;
    /**  已经链接进入全局队列的项数。 返回已经 put 链接的命令数。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int size;
    /**  Owner 调度状态变化时递增的本地修订号，不是通知对象或业务序号。 返回调度进度标记，供调用方判断是否值得重新派发，不是 Core 序号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long dispatchRevision;

    /**
     * 只递增 Owner 本地调度修订号，本方法本身不发送通知或唤醒线程。
     */
    public void progressChanged() { dispatchRevision++; }

    /**
     * 校验请求容量及正 Matcher 分片数，容量向上取二次幂，创建命令槽池、全局/分片链数组、UUID 和用户计数索引，负一下标表示无链接。
     * @param requestedCapacity 正的请求容量，上限二的三十次方，向上取二次幂
     * @param matcherShardCount 正的 Matcher 分片数量
     */
    public PendingMatchingRing(int requestedCapacity, int matcherShardCount) {
        if (requestedCapacity <= 0 || requestedCapacity > 1 << 30) {
            throw new IllegalArgumentException("pending matching capacity must be positive");
        }
        if (matcherShardCount <= 0) throw new IllegalArgumentException("matcher shard count must be positive");
        // 向上取二次幂后同时用于槽池及链接数组的容量。
        int capacity = 1;
        while (capacity < requestedCapacity) capacity <<= 1;
        nextSlots = new int[capacity];
        previousSlots = new int[capacity];
        submissionShards = new int[capacity];
        nextSubmissionSlots = new int[capacity];
        previousSubmissionSlots = new int[capacity];
        submissionHeads = new int[matcherShardCount];
        submissionTails = new int[matcherShardCount];
        java.util.Arrays.fill(nextSlots, -1);
        java.util.Arrays.fill(previousSlots, -1);
        java.util.Arrays.fill(submissionShards, -1);
        java.util.Arrays.fill(nextSubmissionSlots, -1);
        java.util.Arrays.fill(previousSubmissionSlots, -1);
        java.util.Arrays.fill(submissionHeads, -1);
        java.util.Arrays.fill(submissionTails, -1);
        mask = capacity - 1;
        contexts = new CommandSlotRing(capacity);
        entriesByCommandId = new PendingCommandIdIndex(capacity);
        pendingByUser = new LongIntHashMap(capacity);
    }

    /**
     * 只接受本池已占用的同一槽对象，检查物理窗口及重复 UUID 后链接到全局尾部并登记索引；已链接的同序号槽只更新索引、不改顺序。若财库游标为空则从此项开始，成功后增加调度修订；中途异常不做事务回滚。
     * @param pending 必须属于本池的已初始化命令槽
     */
    void put(CommandSlot pending) {
        if (pending == null) throw new IllegalArgumentException("pending matching is required");
        // 当前序号对应或遍历中的物理槽位置。
        int index = slot(pending.sequence());
        // 该物理位置现有的有效槽，用来判定同序号重登记或窗口冲突。
        CommandSlot existing = pendingAt(index);
        if (existing != null && linked(index)) {
            if (existing.sequence() != pending.sequence()) {
                throw new IllegalStateException("pending matching sequence window is full");
            }
            requireAvailableCommandId(pending, existing);
            removeIndexes(existing);
            if (contexts.required(pending.sequence()) != pending)
                throw new IllegalStateException("command must use its claimed slot");
            addIndexes(pending);
            progressChanged();
            return;
        }
        if (size == contexts.capacity()) {
            throw new IllegalStateException("pending matching ring is full");
        }
        requireAvailableCommandId(pending, null);
        // 底层池中当前完整序号对应的占用槽。
        CommandSlot context = contexts.claimed(pending.sequence())
                ? contexts.required(pending.sequence()) : contexts.claim(pending.sequence());
        if (context != pending) throw new IllegalStateException("command must use its claimed slot");
        previousSlots[index] = tail;
        nextSlots[index] = -1;
        if (tail == -1) head = index;
        else nextSlots[tail] = index;
        tail = index;
        if (ledgerDispatchHead == -1) ledgerDispatchHead = index;
        addIndexes(pending);
        size++;
        progressChanged();
    }

    /**
     * 先占槽再初始化；尚未入队，失败也不自动撤销已经占用的池槽。
     * @param sequence 当前命令的完整 Core 序号
     * @param operation 该槽承载的业务操作类别
     * @param command 该槽对应的完整 Core 命令
     * @param fingerprint 原命令指纹
     * @param preMatchingCancellationOrderIds 已确定要先撤的订单号集合
     * @param decodedCommand 当前命令已经解码的只读输入
     * @param admission 可空的已确定准入结果
     */
    CommandSlot acquire(long sequence, CommandSlot.Operation operation, CoreMessage command,
                        com.surprising.aeron.protocol.CommandFingerprint fingerprint,
                        java.util.List<Long> preMatchingCancellationOrderIds,
                        DecodedMatchingCommand decodedCommand, ResolvedMatchingAdmission admission) {
        // 底层池中当前完整序号对应的占用槽。
        CommandSlot context = contexts.claim(sequence);
        // 正在初始化、查询、遍历或观察的原命令槽引用。
        CommandSlot pending = context.initialize(sequence, operation, command, fingerprint,
                preMatchingCancellationOrderIds, decodedCommand, admission);
        if (context != pending) throw new IllegalStateException("command must use its claimed slot");
        return pending;
    }

    /**
     * 按低位定位，要求已链接且完整 Core 序号相同才返回；只有预占但未 put 的槽不会作为待撮合项返回。
     * @param sequence 当前命令的完整 Core 序号
     */
    public CommandSlot get(long sequence) {
        // 当前序号对应或遍历中的物理槽位置。
        int index = slot(sequence);
        if (!linked(index)) return null;
        // 正在初始化、查询、遍历或观察的原命令槽引用。
        CommandSlot pending = pendingAt(index);
        return pending != null && pending.sequence() == sequence ? pending : null;
    }

    /**
     * 只允许丢弃尚未链接的预备槽，已入队项必须经过正常 remove 路径。
     * @param sequence 当前命令的完整 Core 序号
     */
    void discardPrepared(long sequence) {
        // 当前序号对应或遍历中的物理槽位置。
        int index = slot(sequence);
        if (linked(index)) throw new IllegalStateException("cannot discard linked pending matching");
        if (contexts.claimed(sequence)) contexts.discard(sequence);
    }

    /**
     * 返回指定完整序号是否已链接在待撮合队列中。
     * @param sequence 当前命令的完整 Core 序号
     */
    public boolean contains(long sequence) {
        return get(sequence) != null;
    }

    /**
     * 按完整序号定位后从全局链、分片提交链与派生索引移除，推进命中该项的财库游标并更新修订；完整项经池 release 校验，未完整项走 discard。返回的是已清生命周期的同一槽，不是保留结果的永久快照。
     * @param sequence 当前命令的完整 Core 序号
     */
    CommandSlot remove(long sequence) {
        // 准备移除的完整序号所在物理位置。
        int entryIndex = indexOf(sequence);
        if (entryIndex < 0) return null;
        // 当前被移除的原槽，池回收后其生命周期会被清掉。
        CommandSlot removed = pendingAt(entryIndex);
        if (removed == null || removed.sequence() != sequence) {
            throw new IllegalStateException("pending matching order is corrupted");
        }
        // 当前项的前驱物理位置。
        int previous = previousSlots[entryIndex];
        // 当前项的后继物理位置。
        int next = nextSlots[entryIndex];
        if (ledgerDispatchHead == entryIndex) ledgerDispatchHead = next;
        if (previous == -1) head = next;
        else nextSlots[previous] = next;
        if (next == -1) tail = previous;
        else previousSlots[next] = previous;
        previousSlots[entryIndex] = -1;
        nextSlots[entryIndex] = -1;
        removeFromSubmissionOrder(entryIndex);
        removeIndexes(removed);
        size--;
        progressChanged();
        if (contexts.claimed(sequence)) {
            // 底层池中当前完整序号对应的占用槽。
            CommandSlot context = contexts.required(sequence);
            if (context.complete()) contexts.release(sequence);
            else contexts.discard(sequence);
        }
        return removed;
    }

    /**
     * 返回全局队首 Core 序号，空队列返回零。
     */
    public long firstSequence() {
        return head == -1 ? 0 : pendingAt(head).sequence();
    }

    /**
     * 返回全局队首槽，完成状态由槽里的事实和掩码直接判断。
     */
    public CommandSlot head() { return head < 0 ? null : pendingAt(head); }

    /**
     * 返回下一条尚需按序提供资金事实的槽，可能与响应回收队首不同。
     */
    public CommandSlot ledgerDispatchHead() {
        return ledgerDispatchHead < 0 ? null : pendingAt(ledgerDispatchHead);
    }

    /**
     * 要求序号正好对应当前资金派发游标，再推进到下一全局项；调用方须先确认财库邮箱已接收整条资金事实，本方法不自行检查邮箱或金额。
     * @param sequence 当前命令的完整 Core 序号
     */
    public void completeLedgerDispatch(long sequence) {
        if (ledgerDispatchHead < 0 || pendingAt(ledgerDispatchHead).sequence() != sequence)
            throw new IllegalStateException("Ledger settlement dispatch is out of order");
        ledgerDispatchHead = nextSlots[ledgerDispatchHead];
    }

    /**
     * 把已入队命令追加到指定 Matcher 分片提交链，同分片重复登记不做事，换分片拒绝；登记只更新 Owner 顺序索引，不实际向 Matcher 队列提交。
     * @param sequence 当前命令的完整 Core 序号
     * @param matcherShard 当前命令应使用的合法 Matcher 分片号
     */
    void registerSubmission(long sequence, int matcherShard) {
        if (matcherShard < 0 || matcherShard >= submissionHeads.length) {
            throw new IllegalArgumentException("invalid matcher shard");
        }
        // 当前序号对应或遍历中的物理槽位置。
        int index = indexOf(sequence);
        if (index < 0) throw new IllegalStateException("pending matching sequence is missing");
        // 当前项已经登记的分片，负一表示尚未提交登记。
        int registeredShard = submissionShards[index];
        if (registeredShard == matcherShard) return;
        if (registeredShard >= 0) throw new IllegalStateException("matching submission shard changed");
        // 指定分片当前尾项位置。
        int tailSlot = submissionTails[matcherShard];
        submissionShards[index] = matcherShard;
        previousSubmissionSlots[index] = tailSlot;
        nextSubmissionSlots[index] = -1;
        if (tailSlot < 0) submissionHeads[matcherShard] = index;
        else nextSubmissionSlots[tailSlot] = index;
        submissionTails[matcherShard] = index;
        progressChanged();
    }

    /**
     * 判断命令是否位于指定分片提交链头，调用方先保证分片下标合法。
     * @param sequence 当前命令的完整 Core 序号
     * @param matcherShard 当前命令应使用的合法 Matcher 分片号
     */
    boolean isSubmissionHead(long sequence, int matcherShard) {
        // 当前序号对应或遍历中的物理槽位置。
        int index = indexOf(sequence);
        return index >= 0 && submissionHeads[matcherShard] == index;
    }

    /**
     * 返回此已入队命令登记的分片，未找到或尚未登记时负一。
     * @param sequence 当前命令的完整 Core 序号
     */
    int submissionShard(long sequence) {
        // 当前序号对应或遍历中的物理槽位置。
        int index = indexOf(sequence);
        return index < 0 ? -1 : submissionShards[index];
    }

    /**
     * 返回指定合法分片的提交链头槽，空链返回空。
     * @param matcherShard 当前命令应使用的合法 Matcher 分片号
     */
    CommandSlot submissionHead(int matcherShard) {
        // 当前序号对应或遍历中的物理槽位置。
        int index = submissionHeads[matcherShard];
        return index < 0 ? null : pendingAt(index);
    }

    /**
     * 按全局 FIFO 有界遍历，返回最早标为延后撮合的槽，不再维护另一张延后命令表。
     */
    CommandSlot firstDeferred() {
        for (/* 当前序号对应或遍历中的物理槽位置。 */ int index = head; index >= 0; index = nextSlots[index]) {
            // 正在初始化、查询、遍历或观察的原命令槽引用。
            CommandSlot pending = pendingAt(index);
            if (pending != null && pending.deferredMatching()) return pending;
        }
        return null;
    }

    /**
     * 检查是否还存在延后撮合项。
     */
    public boolean hasDeferred() {
        return firstDeferred() != null;
    }

    /**
     * 按当前全局链统计延后撮合项数，用于冷态观察或边界判断。
     */
    int deferredCount() {
        // 当前扫描累计的延后撮合项数。
        int count = 0;
        for (/* 当前序号对应或遍历中的物理槽位置。 */ int index = head; index >= 0; index = nextSlots[index]) {
            // 正在初始化、查询、遍历或观察的原命令槽引用。
            CommandSlot pending = pendingAt(index);
            if (pending != null && pending.deferredMatching()) count++;
        }
        return count;
    }

    /**
     * 命令仍在队列时移走它的分片提交链接，不从全局链移除，也不代表账户结算或财库完成。
     * @param sequence 当前命令的完整 Core 序号
     */
    void completeSubmission(long sequence) {
        // 当前序号对应或遍历中的物理槽位置。
        int index = indexOf(sequence);
        if (index >= 0) removeFromSubmissionOrder(index);
    }

    /**
     * 按 UUID 两个 primitive 半部查当前待撮合槽。
     * @param msb UUID 高六十四位
     * @param lsb UUID 低六十四位
     */
    public CommandSlot findByCommandId(long msb, long lsb) {
        return entriesByCommandId.get(msb, lsb);
    }

    /**
     * 非空 UUID 查询待撮合索引，空值直接返回空。
     * @param commandId 当前命令 UUID
     */
    public CommandSlot findByCommandId(UUID commandId) {
        return commandId == null ? null : entriesByCommandId.get(commandId);
    }

    /**
     * 对正用户号检查当前已链接命令计数是否大于零。
     * @param userId 需要查询的正用户号
     */
    public boolean hasUser(long userId) {
        return userId > 0 && pendingByUser.get(userId) > 0;
    }

    /**
     * 用于已入队命令判断更早的同用户项：计数至多一时直接否定，否则按全局链扫描到目标序号前。依赖命令按 Core 序号递增链接，目标本身已计入用户数量。
     * @param sequence 当前命令的完整 Core 序号
     * @param userId 需要查询的正用户号
     */
    boolean hasEarlierUser(long sequence, long userId) {
        if (sequence <= 0 || userId <= 0 || pendingByUser.get(userId) <= 1) return false;
        for (/* 按全局链前进的物理位置。 */ int slot = head; slot != -1; slot = nextSlots[slot]) {
            // 正在初始化、查询、遍历或观察的原命令槽引用。
            CommandSlot pending = pendingAt(slot);
            if (pending == null || pending.sequence() >= sequence) return false;
            if (pending.command().header().userId() == userId) return true;
        }
        return false;
    }

    /**
     * 由 Owner 按全局链接顺序对现有槽执行回调，不复制内容，也不提供遍历期间并发修改保证。
     * @param consumer 由 Owner 按全局顺序执行的槽回调
     */
    void forEach(Consumer<CommandSlot> consumer) {
        for (/* 按全局链前进的物理位置。 */ int slot = head; slot != -1; slot = nextSlots[slot]) {
            // 正在初始化、查询、遍历或观察的原命令槽引用。
            CommandSlot pending = pendingAt(slot);
            if (pending != null) consumer.accept(pending);
        }
    }

    /**
     * 按全局顺序复制外层 Map 并固定它，值仍是会被回收复用的原槽引用，只能作当前观察，不能当作持久化状态快照。
     */
    public Map<Long, CommandSlot> snapshot() {
        // 复制出来的外层有序观察 Map，值仍借用原槽。
        LinkedHashMap<Long, CommandSlot> snapshot = new LinkedHashMap<>(size);
        forEach(/* 正在初始化、查询、遍历或观察的原命令槽引用。 */ pending -> snapshot.put(pending.sequence(), pending));
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * 反复按全局队首 remove 清掉所有已链接项；未链接预备槽不由这个 size 循环自动回收。
     */
    public void clear() {
        while (size != 0) remove(firstSequence());
    }

    /**
     * 按当前命令头登记 UUID 到槽的映射，并为正用户号增加在队计数。
     * @param pending 必须属于本池的已初始化命令槽
     */
    private void addIndexes(CommandSlot pending) {
        // 当前槽原命令的头部，用来更新 UUID 与用户索引。
        var header = pending.command().header();
        entriesByCommandId.put(header.commandIdMsb(), header.commandIdLsb(), pending);
        // 当前命令所属用户，系统用户零不参与计数。
        long userId = header.userId();
        if (userId > 0) pendingByUser.addToValue(userId, 1);
    }

    /**
     * 按当前 UUID 及预期槽身份移除索引，再递减正用户计数；归零删除键，负数明确报错，不自动回滚前面的索引动作。
     * @param pending 必须属于本池的已初始化命令槽
     */
    private void removeIndexes(CommandSlot pending) {
        // 当前槽原命令的头部，用来更新 UUID 与用户索引。
        var header = pending.command().header();
        entriesByCommandId.remove(header.commandIdMsb(), header.commandIdLsb(), pending);
        // 当前命令所属用户，系统用户零不参与计数。
        long userId = header.userId();
        if (userId <= 0) return;
        // 移除一条后该用户的在队命令计数。
        int remaining = pendingByUser.addToValue(userId, -1);
        if (remaining == 0) pendingByUser.removeKey(userId);
        else if (remaining < 0) throw new IllegalStateException("pending matching user count underflow");
    }

    /**
     * 若该物理项已登记分片，就修补前后项和分片头尾，并清本项三种分片链接标记。
     * @param index 当前物理槽下标
     */
    private void removeFromSubmissionOrder(int index) {
        // 当前物理项登记的分片，负一表示不在分片链。
        int shard = submissionShards[index];
        if (shard < 0) return;
        // 当前项的前驱物理位置。
        int previous = previousSubmissionSlots[index];
        // 当前项的后继物理位置。
        int next = nextSubmissionSlots[index];
        if (previous < 0) submissionHeads[shard] = next;
        else nextSubmissionSlots[previous] = next;
        if (next < 0) submissionTails[shard] = previous;
        else previousSubmissionSlots[next] = previous;
        submissionShards[index] = -1;
        previousSubmissionSlots[index] = -1;
        nextSubmissionSlots[index] = -1;
    }

    /**
     * 拒绝同 UUID 已被另一槽占用；允许显式传入的 replaced 与现有索引值为同一对象。
     * @param pending 必须属于本池的已初始化命令槽
     * @param replaced 允许已占用同 UUID 的旧槽引用，空表示不允许其他项已存在
     */
    private void requireAvailableCommandId(CommandSlot pending, CommandSlot replaced) {
        // 相同命令身份现有的槽引用，用原语 MSB/LSB 查询避免热路径分配 UUID 对象，用来拒绝重复链接。
        var header = pending.command().header();
        CommandSlot duplicate = entriesByCommandId.get(header.commandIdMsb(), header.commandIdLsb());
        if (duplicate != null && duplicate != replaced) {
            throw new IllegalStateException("duplicate pending matching commandId");
        }
    }

    /**
     * 只返回已链接且完整 Core 序号匹配的物理位置，否则负一。
     * @param sequence 当前命令的完整 Core 序号
     */
    private int indexOf(long sequence) {
        // 当前序号对应或遍历中的物理槽位置。
        int index = slot(sequence);
        if (!linked(index)) return -1;
        // 正在初始化、查询、遍历或观察的原命令槽引用。
        CommandSlot pending = pendingAt(index);
        return pending != null && pending.sequence() == sequence ? index : -1;
    }

    /**
     * 只用序号低位与掩码换算物理位置，不在此检查序号正数。
     * @param sequence 当前命令的完整 Core 序号
     */
    private int slot(long sequence) {
        return (int) sequence & mask;
    }

    /**
     * 按全局队首或前后链接判断物理项是否在队，单项队列由 head 命中识别。
     * @param index 当前物理槽下标
     */
    private boolean linked(int index) {
        return head == index || previousSlots[index] != -1 || nextSlots[index] != -1;
    }

    /**
     * 从槽池读取物理项，只有仍被占用且命令引用非空才作为有效槽返回；不另验证全局链接。
     * @param index 当前物理槽下标
     */
    private CommandSlot pendingAt(int index) {
        // 底层池中当前完整序号对应的占用槽。
        CommandSlot context = contexts.contextAt(index);
        return context.coreSequence() == 0 || context.command() == null ? null : context;
    }

    /**
     * 返回已链接队列是否为空，不等于槽池没有预备占用。
     */
    public boolean isEmpty() { return size == 0; }
    /**
     * 返回槽池向上取整后的容量。
     */
    public int capacity() { return contexts.capacity(); }

    /**
     * 按完整序号从槽池严格取占用槽，不要求它已经 put 链接。
     * @param sequence 当前命令的完整 Core 序号
     */
    public CommandSlot required(long sequence) { return contexts.required(sequence); }
    /**
     * 检查槽池是否已占用该完整序号。
     * @param sequence 当前命令的完整 Core 序号
     */
    public boolean claimed(long sequence) { return contexts.claimed(sequence); }
    /**
     * 返回底层池全部占用数，包含未链接预备项。
     */
    public int inFlight() { return contexts.inFlight(); }
    /**
     * 返回底层池历史占用峰值，不只是已链接队列峰值。
     */
    public int highWaterMark() { return contexts.highWaterMark(); }
}
