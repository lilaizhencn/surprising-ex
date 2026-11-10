package com.surprising.aeron.service.order;

import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.support.ingress.OwnerIngressRing;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import static com.surprising.aeron.support.egress.ClusterServiceEgress.NO_SESSION_ID;

public final class OrderCommandQueue {
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
    private final CommandIdIndex entriesByCommandId;
    /** 当前已链接命令按正用户号计数的派生索引。 */
    private final LongIntHashMap pendingByUser;
    /**  真正拥有可复用命令槽的固定池。 返回同一个底层命令槽池，供受约束的 Owner/Matcher 协作。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final OrderCommandQueue.Pool contexts;
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
    public OrderCommandQueue(int requestedCapacity, int matcherShardCount) {
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
        contexts = new OrderCommandQueue.Pool(capacity);
        entriesByCommandId = new CommandIdIndex(capacity);
        pendingByUser = new LongIntHashMap(capacity);
    }

    /**
     * 只接受本池已占用的同一槽对象，检查物理窗口及重复 UUID 后链接到全局尾部并登记索引；已链接的同序号槽只更新索引、不改顺序。若财库游标为空则从此项开始，成功后增加调度修订；中途异常不做事务回滚。
     * @param pending 必须属于本池的已初始化命令槽
     */
    void put(OrderCommand pending) {
        if (pending == null) throw new IllegalArgumentException("pending matching is required");
        // 当前序号对应或遍历中的物理槽位置。
        int index = slot(pending.sequence());
        // 该物理位置现有的有效槽，用来判定同序号重登记或窗口冲突。
        OrderCommand existing = pendingAt(index);
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
        OrderCommand context = contexts.claimed(pending.sequence())
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
    OrderCommand acquire(long sequence, OrderCommand.Operation operation, CoreMessage command,
                        com.surprising.aeron.protocol.CommandFingerprint fingerprint,
                        java.util.List<Long> preMatchingCancellationOrderIds,
                        OrderCommand.Decoded decodedCommand, OrderCommand.ReplacementAdmission admission) {
        // 底层池中当前完整序号对应的占用槽。
        OrderCommand context = contexts.claim(sequence);
        // 正在初始化、查询、遍历或观察的原命令槽引用。
        OrderCommand pending = context.initialize(sequence, operation, command, fingerprint,
                preMatchingCancellationOrderIds, decodedCommand, admission);
        if (context != pending) throw new IllegalStateException("command must use its claimed slot");
        return pending;
    }

    /**
     * 按低位定位，要求已链接且完整 Core 序号相同才返回；只有预占但未 put 的槽不会作为待撮合项返回。
     * @param sequence 当前命令的完整 Core 序号
     */
    public OrderCommand get(long sequence) {
        // 当前序号对应或遍历中的物理槽位置。
        int index = slot(sequence);
        if (!linked(index)) return null;
        // 正在初始化、查询、遍历或观察的原命令槽引用。
        OrderCommand pending = pendingAt(index);
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
    OrderCommand remove(long sequence) {
        // 准备移除的完整序号所在物理位置。
        int entryIndex = indexOf(sequence);
        if (entryIndex < 0) return null;
        // 当前被移除的原槽，池回收后其生命周期会被清掉。
        OrderCommand removed = pendingAt(entryIndex);
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
            OrderCommand context = contexts.required(sequence);
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
    public OrderCommand head() { return head < 0 ? null : pendingAt(head); }

    /**
     * 返回下一条尚需按序提供资金事实的槽，可能与响应回收队首不同。
     */
    public OrderCommand ledgerDispatchHead() {
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
    OrderCommand submissionHead(int matcherShard) {
        // 当前序号对应或遍历中的物理槽位置。
        int index = submissionHeads[matcherShard];
        return index < 0 ? null : pendingAt(index);
    }

    /**
     * 按全局 FIFO 有界遍历，返回最早标为延后撮合的槽，不再维护另一张延后命令表。
     */
    OrderCommand firstDeferred() {
        for (/* 当前序号对应或遍历中的物理槽位置。 */ int index = head; index >= 0; index = nextSlots[index]) {
            // 正在初始化、查询、遍历或观察的原命令槽引用。
            OrderCommand pending = pendingAt(index);
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
            OrderCommand pending = pendingAt(index);
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
    public OrderCommand findByCommandId(long msb, long lsb) {
        return entriesByCommandId.get(msb, lsb);
    }

    /**
     * 非空 UUID 查询待撮合索引，空值直接返回空。
     * @param commandId 当前命令 UUID
     */
    public OrderCommand findByCommandId(UUID commandId) {
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
            OrderCommand pending = pendingAt(slot);
            if (pending == null || pending.sequence() >= sequence) return false;
            if (pending.command().header().userId() == userId) return true;
        }
        return false;
    }

    /**
     * 由 Owner 按全局链接顺序对现有槽执行回调，不复制内容，也不提供遍历期间并发修改保证。
     * @param consumer 由 Owner 按全局顺序执行的槽回调
     */
    void forEach(Consumer<OrderCommand> consumer) {
        for (/* 按全局链前进的物理位置。 */ int slot = head; slot != -1; slot = nextSlots[slot]) {
            // 正在初始化、查询、遍历或观察的原命令槽引用。
            OrderCommand pending = pendingAt(slot);
            if (pending != null) consumer.accept(pending);
        }
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
    private void addIndexes(OrderCommand pending) {
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
    private void removeIndexes(OrderCommand pending) {
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
    private void requireAvailableCommandId(OrderCommand pending, OrderCommand replaced) {
        // 相同命令身份现有的槽引用，用原语 MSB/LSB 查询避免热路径分配 UUID 对象，用来拒绝重复链接。
        var header = pending.command().header();
        OrderCommand duplicate = entriesByCommandId.get(header.commandIdMsb(), header.commandIdLsb());
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
        OrderCommand pending = pendingAt(index);
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
    private OrderCommand pendingAt(int index) {
        // 底层池中当前完整序号对应的占用槽。
        OrderCommand context = contexts.contextAt(index);
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
    public OrderCommand required(long sequence) { return contexts.required(sequence); }
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


public static final class Pool {
    /** 按序号低位寻址的预分配命令槽数组。 */
    private final OrderCommand[] contexts;
    /** 容量减一的环形寻址掩码。 */
    private final int mask;
    /**  当前 claimed 槽位数量。 返回池中已占用槽数，包含尚未链接到待撮合队列的预备槽。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private int inFlight;
    /**  历史 claimed 数量峰值。 返回该槽池历史占用峰值。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private int highWaterMark;

    /**
     * 校验正的二次幂容量，预分配空 OrderCommand。
     * @param capacity 正的二次幂槽位数
     */
    public Pool(int capacity) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("lane command context capacity must be a power of two");
        }
        contexts = new OrderCommand[capacity];
        for (/* 当前预分配的槽下标。 */ int index = 0; index < capacity; index++) contexts[index] = new OrderCommand();
        mask = capacity - 1;
    }

    /**
     * 要求正 Core 序号，按掩码取得未占用槽，写入身份、标记占用并更新在途/峰值；只占槽，不完整初始化业务字段。物理位置冲突立即拒绝。
     * @param coreSequence 需占用、查找或释放的完整 Core 序号
     */
    OrderCommand claim(long coreSequence) {
        if (coreSequence <= 0) throw new IllegalArgumentException("coreSequence must be positive");
        // 当前完整序号对应的命令槽引用。
        OrderCommand context = contexts[(int) coreSequence & mask];
        if (context.claimed) throw new IllegalStateException("lane command context ring is full");
        context.coreSequence = coreSequence;
        context.claimed = true;
        inFlight++;
        highWaterMark = Math.max(highWaterMark, inFlight);
        return context;
    }

    /**
     * 按低位寻址并同时检查 claimed 和完整序号匹配，否则抛出带槽位诊断的异常。
     * @param coreSequence 需占用、查找或释放的完整 Core 序号
     */
    public OrderCommand required(long coreSequence) {
        // 物理槽下标或该位置的槽对象，具体由声明类型区分。
        int slot = (int) coreSequence & mask;
        // 当前完整序号对应的命令槽引用。
        OrderCommand context = contexts[slot];
        if (!context.claimed || context.coreSequence != coreSequence) {
            throw new IllegalStateException("unknown lane command context sequence=" + coreSequence
                    + " slot=" + slot + " actual=" + context.coreSequence
                    + " inFlight=" + inFlight + " capacity=" + contexts.length);
        }
        return context;
    }

    /**
     * 按物理数组下标取槽，不检查它是否已占用；数组自己约束下标。
     * @param slot 物理槽下标
     */
    OrderCommand contextAt(int slot) { return contexts[slot]; }

    /**
     * 要求已绑定撮合结果且全部预期 Lane 完成，并且 Matcher 提交路由已释放，随后清生命周期并减在途计数；不逐个清掉所有业务引用。
     * @param coreSequence 需占用、查找或释放的完整 Core 序号
     */
    void release(long coreSequence) {
        // 当前完整序号对应的命令槽引用。
        OrderCommand context = required(coreSequence);
        if (!context.complete() || context.submittedMatcherShard() != -1)
            throw new IllegalStateException("incomplete lane command context complete=" + context.complete()
                    + " submittedMatcherShard=" + context.submittedMatcherShard()
                    + " matchingResult=" + (context.matchingResult() != null)
                    + " completedLaneMask=" + context.completedLaneMask()
                    + " expectedLaneMask=" + context.expectedLaneMask());
        context.clear();
        inFlight--;
    }

    /**
     * 检查目标物理槽已占用且完整 Core 序号相同，不仅比较低位下标。
     * @param coreSequence 需占用、查找或释放的完整 Core 序号
     */
    public boolean claimed(long coreSequence) {
        // 物理槽下标或该位置的槽对象，具体由声明类型区分。
        OrderCommand slot = contexts[(int) coreSequence & mask];
        return slot.claimed && slot.coreSequence == coreSequence;
    }

    /**
     * 直接清已占用槽的生命周期并减计数，不要求撮合、Lane 或 Matcher 路由完成；仅用于调用方确认可撤销或终止的路径。
     * @param coreSequence 需占用、查找或释放的完整 Core 序号
     */
    void discard(long coreSequence) {
        // 当前完整序号对应的命令槽引用。
        OrderCommand context = required(coreSequence);
        context.clear();
        inFlight--;
    }

    /**
     * 返回预分配槽位总数。
     */
    int capacity() { return contexts.length; }

}


static final class CommandIdIndex {
    /** 各物理条目 UUID 高六十四位。 */
    private final long[] mostSignificantBits;
    /** 各物理条目 UUID 低六十四位。 */
    private final long[] leastSignificantBits;
    /** 各物理位置的命令槽引用，空引用表示未使用。 */
    private final OrderCommand[] values;
    /** 固定数组容量减一的探测环绕掩码。 */
    private final int mask;
    /** 当前索引中非空条目数量。 */
    private int size;

    /**
     * 要求预期条目数为正，按其两倍向上取二次幂建立数组，使正常窗口最多半满；本构造未单独检查倍增的 int 溢出。
     * @param expectedEntries 正常有界窗口最多需要索引的命令数
     */
    CommandIdIndex(int expectedEntries) {
        if (expectedEntries <= 0) throw new IllegalArgumentException("expected entries must be positive");
        // 按预期条目数两倍向上取二次幂的数组容量。
        int capacity = 1;
        while (capacity < expectedEntries * 2) capacity <<= 1;
        mostSignificantBits = new long[capacity];
        leastSignificantBits = new long[capacity];
        values = new OrderCommand[capacity];
        mask = capacity - 1;
    }

    /**
     * 按 UUID 两半混合后的起点连续探测，找到相同位值返回原槽引用，遇到空位返回空。
     * @param most UUID 高六十四位
     * @param least UUID 低六十四位
     */
    OrderCommand get(long most, long least) {
        // 当前线性探测的物理位置，越界按掩码环绕。
        int index = index(most, least);
        while (values[index] != null) {
            if (mostSignificantBits[index] == most && leastSignificantBits[index] == least) return values[index];
            index = (index + 1) & mask;
        }
        return null;
    }

    /**
     * 空 UUID 直接返回空，否则拆成两半查询，不创建新的键对象。
     * @param commandId 要定位的命令 UUID
     */
    OrderCommand get(UUID commandId) {
        if (commandId == null) return null;
        return get(commandId.getMostSignificantBits(), commandId.getLeastSignificantBits());
    }

    /**
     * 同 UUID 已有条目就替换引用，否则写入首空位并增加数量；半满检查发生在插入之后，超限异常不会回滚刚写入的条目。
     * @param most UUID 高六十四位
     * @param least UUID 低六十四位
     * @param value 需要登记或重插的非空命令槽引用
     */
    void put(long most, long least, OrderCommand value) {
        if (value == null) throw new IllegalArgumentException("command value is required");
        // 当前线性探测的物理位置，越界按掩码环绕。
        int index = index(most, least);
        while (values[index] != null) {
            if (mostSignificantBits[index] == most && leastSignificantBits[index] == least) {
                values[index] = value;
                return;
            }
            index = (index + 1) & mask;
        }
        mostSignificantBits[index] = most;
        leastSignificantBits[index] = least;
        values[index] = value;
        size++;
        if (size * 2 > values.length) throw new IllegalStateException("pending command-id index is full");
    }

    /**
     * 找到 UUID 后，仅在 expected 为空或与当前值按对象身份相同时删除；不匹配的旧请求不会移走另一个值。
     * @param most UUID 高六十四位
     * @param least UUID 低六十四位
     * @param expected 删除时可选的预期对象引用，空值表示只按 UUID 删除
     */
    void remove(long most, long least, OrderCommand expected) {
        // 当前线性探测的物理位置，越界按掩码环绕。
        int index = index(most, least);
        while (values[index] != null) {
            if (mostSignificantBits[index] == most && leastSignificantBits[index] == least) {
                if (expected != null && values[index] != expected) return;
                removeAt(index);
                return;
            }
            index = (index + 1) & mask;
        }
    }

    /**
     * 移走目标槽后，沿后续连续探测簇逐个取出并重新插入，修复查找链，直到遇到原空位。
     * @param removed 准备移除的物理条目下标
     */
    private void removeAt(int removed) {
        values[removed] = null;
        mostSignificantBits[removed] = 0;
        leastSignificantBits[removed] = 0;
        size--;
        // 当前线性探测的物理位置，越界按掩码环绕。
        int index = (removed + 1) & mask;
        while (values[index] != null) {
            // 删除修复时临时取出的后续槽引用。
            OrderCommand value = values[index];
            // 删除修复条目的 UUID 高半部。
            long most = mostSignificantBits[index];
            // 删除修复条目的 UUID 低半部。
            long least = leastSignificantBits[index];
            values[index] = null;
            mostSignificantBits[index] = 0;
            leastSignificantBits[index] = 0;
            size--;
            insert(most, least, value);
            index = (index + 1) & mask;
        }
    }

    /**
     * 把已移出的有效条目重新放到当前哈希起点之后的首空位，只用于删除修复，不再次执行半满检查。
     * @param most UUID 高六十四位
     * @param least UUID 低六十四位
     * @param value 需要登记或重插的非空命令槽引用
     */
    private void insert(long most, long least, OrderCommand value) {
        // 当前线性探测的物理位置，越界按掩码环绕。
        int index = index(most, least);
        while (values[index] != null) index = (index + 1) & mask;
        mostSignificantBits[index] = most;
        leastSignificantBits[index] = least;
        values[index] = value;
        size++;
    }

    /**
     * 对 UUID 两半执行固定混合，再用容量掩码得到起点，不是密码摘要。
     * @param most UUID 高六十四位
     * @param least UUID 低六十四位
     */
    private int index(long most, long least) {
        // 由两半 UUID 混合出的固定 long 哈希累加值。
        long hash = most ^ Long.rotateLeft(least, 23);
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdl;
        hash ^= hash >>> 33;
        return (int) hash & mask;
    }
}


public static final class IngressWindow {
    /** 未配置系统属性时的入口窗口容量二百五十六。 */
    public static final int DEFAULT_CAPACITY = 256;
    /** 一条准入命令允许登记的最大精确订单数，取两类批量命令上限较大值。 */
    private static final int MAX_TRACKED_ORDER_IDS = Math.max(
            PlaceOrderBatchCommand.MAX_ORDERS, CancelOrderBatchCommand.MAX_ORDERS);


    /** 固定环形条目数组，各条目持有可交换复用的解码槽。 */
    private final Entry[] entries;
    /** 容量减一，用于物理下标环绕。 */
    private final int indexMask;
    /** 当前 FIFO 队首物理下标。 */
    private int head;
    /**  尚未退休的条目数。 返回当前 FIFO 尚未退休的条目数量。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int size;
    /** 当前有效队首的绝对位置水位，初始一，小于它的索引记录视为过期。 */
    private long headAbsSeq = 1L;
    /** 每次 add 增加的尾部绝对位置，不是 Core 命令序号。 */
    private long tailAbsSeq;
    /**  最后一次非零绑定且尚未退休的 Core 序号。 返回窗口中最后一次绑定非零 Core 序号且尚未退休的记录，不计算数值最大值。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long lastMatchingSequence;
    /** 最后非零绑定条目对应的窗口绝对位置，用于退休后失效。 */
    private long lastMatchingAbsSeq;

    /** 用户号到最近窗口绝对位置的派生索引，只用于同用户依赖判断。 */
    private final org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap userLastAbsSeq;
    /** 当前代订单号到最近准入位置的派生索引。 */
    private org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap activeOrderLastAbsSeq;
    /** 上一代仍可能含有效旧命令的订单位置索引。 */
    private org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap previousOrderLastAbsSeq;
    /** 由订单 Map 初始容量确定的轮换写入次数阈值。 */
    private final int orderIndexRotationThreshold;
    /** 上一代可能包含的最大绝对位置，队首越过后才可清掉轮换。 */
    private long previousOrderMaxAbsSeq;
    /** 当前代订单登记写入次数，重复键写入也累计。 */
    private int activeOrderInserts;

    /** 下一入口准备使用的用户号。 */
    private long candidateUser;
    /** 候选提交用户的 Lane 位值，仅作为路由保存。 */
    private long candidateUserLaneBit;
    /** 候选静态 Matcher 分片，未路由时负一。 */
    private int candidateMatcherShard = -1;
    /** 候选静态路由是否已保存。 */
    private boolean candidateRouted;
    /** 产生候选路由时的解码来源请求引用，防止旧解码被误用于新请求。 */
    private CoreMessage routedSource;
    /** 当前解码缓存对应的请求对象引用。 */
    private CoreMessage decodedSource;
    /** 当前可用解码值，可能已交换给窗口条目。 */
    private OrderCommand.Decoded decoded;
    /** Owner 准备下一入口时使用的可复用解码槽，准入时与 Entry 备用槽交换。 */
    private OrderCommand.Decoded candidateDecoder = OrderCommand.Decoded.commandSlot();

    /**
     * 从系统属性读取窗口容量，未配置默认二百五十六，再按显式容量构造；非法数字直接抛解析异常。
     */
    public IngressWindow() {
        this(com.surprising.aeron.config.CoreRuntimeConfiguration.standalone().ownerCommandWindow());
    }

    /**
     * 要求容量是六十四至四千零九十六之间的二次幂，预分配条目及解码槽，按容量配置用户/双代订单索引初始容量；这些 Map 可自行扩容，不等于固定数组上限。
     * @param capacity 六十四至四千零九十六之间的二次幂容量
     */
    public IngressWindow(int capacity) {
        if (capacity < 64 || capacity > 4096 || Integer.bitCount(capacity) != 1)
            throw new IllegalArgumentException("owner command window must be a power of two in [64,4096]");
        entries = new Entry[capacity];
        indexMask = capacity - 1;
        // 按窗口容量确定的用户依赖索引初始容量。
        int userMapCapacity = Math.max(512, capacity << 1);
        // 按窗口容量确定的订单索引初始容量及轮换阈值。
        int orderMapCapacity = Math.max(4096, capacity << 4);
        userLastAbsSeq = new org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap(userMapCapacity);
        activeOrderLastAbsSeq = new org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap(orderMapCapacity);
        previousOrderLastAbsSeq = new org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap(orderMapCapacity);
        orderIndexRotationThreshold = orderMapCapacity;
        for (/* 当前预分配、批量订单遍历或退休前缀的下标。 */ int i = 0; i < entries.length; i++) entries[i] = new Entry();
    }

    /**
     * 设置下一入口用户并清掉候选路由；保留已解码请求，便于阻塞队首重试不重复解码。
     * @param userId 下一候选提交用户号
     */
    public void resetCandidate(long userId) {
        candidateUser = userId;
        candidateUserLaneBit = 0;
        candidateMatcherShard = -1;
        candidateRouted = false;
        routedSource = null;
    }

    /**
     * 保存当前用户的静态 Matcher 分片和非零 Lane 位，并关联当时 decodedSource；只检查用户为正、分片非负及位值非零，不核验拓扑上界或恰好一位。
     * @param matcherShard 调用方确定的非负静态 Matcher 分片号
     * @param userLaneBit 调用方提供的非零用户 Lane 位值
     */
    public void route(int matcherShard, long userLaneBit) {
        if (candidateUser <= 0 || matcherShard < 0 || userLaneBit == 0)
            throw new IllegalArgumentException("invalid ingress route");
        routedSource = decodedSource;
        candidateMatcherShard = matcherShard;
        candidateUserLaneBit = userLaneBit;
        candidateRouted = true;
    }

    /**
     * 已路由时返回候选 Matcher 分片，否则返回负一。
     */
    public int routeMatcherShard() { return candidateRouted ? candidateMatcherShard : -1; }
    /**
     * 已路由时返回调用方保存的用户 Lane 位值，否则返回零。
     */
    public long routeUserLaneBit() { return candidateRouted ? candidateUserLaneBit : 0; }

    /**
     * 计算撤单精确订单依赖前缀，再取与同 userId 最近命令前缀的较大值。不同用户即使路由同一 Lane，也不会仅凭 Lane 位在这里互斥。
     */
    public int conflictingPrefixSize() {
        if (size == 0) return 0;
        // 由撤单精确订单依赖确定的前缀长度。
        int exactPrefix = exactOrderPrefix(0);
        // 有静态路由时的候选用户号，否则零，不检查同 Lane 的其他用户。
        long candidateUserId = candidateRouted ? candidateUser : 0;
        if (candidateUserId != 0) {
            // 该用户最近准入位置，低于队首水位时已过期。
            long userAbsSeq = userLastAbsSeq.get(candidateUserId);
            if (userAbsSeq >= headAbsSeq) {
                // 覆盖该用户旧命令所需退休的前缀长度。
                int userPrefix = (int) (userAbsSeq - headAbsSeq + 1L);
                if (userPrefix > exactPrefix) {
                    return userPrefix;
                }
            }
        }
        return exactPrefix;
    }

    /**
     * 只在路由与当前解码请求仍按对象身份相同时，检查 CANCEL_ORDER 或 CANCEL_ORDER_BATCH 引用的订单；其他类型不增加精确订单依赖。
     * @param prefix 已找到的依赖前缀长度
     */
    private int exactOrderPrefix(int prefix) {
        if (routedSource == null || routedSource != decodedSource || decoded == null || size == 0) return prefix;
        // 当前解码请求的协议消息类型。
        CoreMessageType type = decodedSource.header().messageType();
        switch (type) {
            case CANCEL_ORDER -> prefix = maxOrderPrefix(prefix, decoded.cancelOrder().orderId());
            case CANCEL_ORDER_BATCH -> {
                // 当前批量下单或撤单中已经解码的订单项。
                var orders = decoded.cancelOrderBatch().orders();
                for (/* 当前批量订单的固定数量，循环内不重复读取。 */ /* 当前预分配、批量订单遍历或退休前缀的下标。 */ int i = 0, n = orders.size(); i < n; i++) {
                    prefix = maxOrderPrefix(prefix, orders.get(i).orderId());
                }
            }
            default -> { }
        }
        return prefix;
    }

    /**
     * 查当前和仍有效的上一代订单索引，若该订单旧命令尚在窗口，返回覆盖它的更长 FIFO 前缀；非正订单号直接跳过。
     * @param prefix 已找到的依赖前缀长度
     * @param orderId 需要查找或登记的精确订单编号
     */
    private int maxOrderPrefix(int prefix, long orderId) {
        if (orderId <= 0) return prefix;
        // 从当前代或仍有效上一代索引读到的旧订单命令位置。
        long orderAbsSeq = activeOrderLastAbsSeq.get(orderId);
        if (orderAbsSeq < headAbsSeq && previousOrderMaxAbsSeq >= headAbsSeq) {
            orderAbsSeq = previousOrderLastAbsSeq.get(orderId);
        }
        if (orderAbsSeq >= headAbsSeq) {
            return Math.max(prefix, (int) (orderAbsSeq - headAbsSeq + 1L));
        }
        return prefix;
    }

    /**
     * 按请求对象身份缓存解码，换请求时使用候选预分配槽 decodeInto，同一请求重试直接返回；不按消息内容比较是否相同。
     * @param request 需要解码或加入窗口的完整 Core 请求
     */
    public OrderCommand.Decoded decoded(CoreMessage request) {
        if (decodedSource != request) {
            decodedSource = request;
            decoded = null;
        }
        if (decoded == null) {
            candidateDecoder.decodeInto(request);
            decoded = candidateDecoder;
        }
        return decoded;
    }

    /**
     * 只有请求对象与缓存来源相同才返回已解码引用，不触发新解码。
     * @param request 需要解码或加入窗口的完整 Core 请求
     */
    public OrderCommand.Decoded decodedIfPresent(CoreMessage request) {
        return decodedSource == request ? decoded : null;
    }

    /**
     * 若解码器仍归候选就清它，若已交换给条目则保留条目内容；清除当前来源和路由来源关联。
     */
    public void releaseDecoded() {
        if (decoded == candidateDecoder) candidateDecoder.clearCommandSlot();
        if (routedSource == decodedSource) routedSource = null;
        decodedSource = null;
        decoded = null;
    }

    /**
     * 仅当当前阻塞队首仍是同一请求对象时保留缓存，否则释放候选解码关联。
     * @param pendingHead 当前仍被阻塞的入口队首对象，空表示不再保留
     */
    public void retainDecoded(CoreMessage pendingHead) {
        if (pendingHead == null || decodedSource != pendingHead) releaseDecoded();
    }

    /**
     * 检查队满和候选路由存在后，填入尾条目、登记用户及当前解码中的订单索引，并把候选解码槽与条目备用槽交换。调用方保证 request 与准备的路由/解码对应；内部后续异常不自动回滚已写字段。
     * @param sessionId 最终响应的目标 Cluster 会话号
     * @param request 需要解码或加入窗口的完整 Core 请求
     * @param timestamp 该命令在 Cluster 中的业务时间
     * @param position 该命令的 Cluster 日志位置
     */
    public Entry add(long sessionId, CoreMessage request, long timestamp, long position) {
        if (size == entries.length) throw new IllegalStateException("cluster command window is full");
        if (!candidateRouted) throw new IllegalStateException("ingress route is missing");
        // 当前填充或清理的已有窗口条目。
        Entry entry = get(size++);
        // 新尾条目分配的绝对位置。
        long absSeq = ++tailAbsSeq;
        entry.absSeq = absSeq;
        entry.sessionId = sessionId;
        entry.request = request;
        entry.timestamp = timestamp;
        entry.position = position;
        // 新尾条目对应的环形数组物理下标。
        int physical = (head + size - 1) & indexMask;
        userLastAbsSeq.put(candidateUser, absSeq);
        indexTrackedOrderIds(absSeq);
        if (decodedSource == request && decoded == candidateDecoder) {
            // 条目原来的备用解码槽，交换后交还候选使用。
            var spare = entry.decodedSlot;
            entry.decodedSlot = candidateDecoder;
            candidateDecoder = spare;
        }
        entry.physicalSlot = physical;
        return entry;
    }

    /**
     * 对当前解码的单笔/批量下单或撤单逐订单登记；写入次数达到阈值且上一代已全失效时轮换两张索引表，不给每条命令另存订单号数组。其他命令类型不登记精确订单键。
     * @param absSeq 当前准入条目的窗口绝对位置
     */
    private void indexTrackedOrderIds(long absSeq) {
        if (routedSource == null || routedSource != decodedSource || decoded == null) return;
        if (activeOrderInserts >= orderIndexRotationThreshold && headAbsSeq > previousOrderMaxAbsSeq) {
            previousOrderLastAbsSeq.clear();
            // 已清空的上一代订单索引，轮换后作为新当前代复用。
            var swap = previousOrderLastAbsSeq;
            previousOrderLastAbsSeq = activeOrderLastAbsSeq;
            activeOrderLastAbsSeq = swap;
            previousOrderMaxAbsSeq = absSeq - 1L;
            activeOrderInserts = 0;
        }
        // 本次命令已登记的精确订单数量。
        int count = 0;
        switch (decodedSource.header().messageType()) {
            case PLACE_ORDER -> count = trackOrderId(absSeq, count, decoded.placeOrder().orderId());
            case CANCEL_ORDER -> count = trackOrderId(absSeq, count, decoded.cancelOrder().orderId());
            case PLACE_ORDER_BATCH -> {
                // 当前批量下单或撤单中已经解码的订单项。
                var orders = decoded.placeOrderBatch().orders();
                for (/* 当前批量订单的固定数量，循环内不重复读取。 */ /* 当前预分配、批量订单遍历或退休前缀的下标。 */ int i = 0, n = orders.size(); i < n; i++) {
                    count = trackOrderId(absSeq, count, orders.get(i).orderId());
                }
            }
            case CANCEL_ORDER_BATCH -> {
                // 当前批量下单或撤单中已经解码的订单项。
                var orders = decoded.cancelOrderBatch().orders();
                for (/* 当前批量订单的固定数量，循环内不重复读取。 */ /* 当前预分配、批量订单遍历或退休前缀的下标。 */ int i = 0, n = orders.size(); i < n; i++) {
                    count = trackOrderId(absSeq, count, orders.get(i).orderId());
                }
            }
            default -> { }
        }
    }

    /**
     * 跳过非正订单号，按本命令累计数量检查上限后记录该订单最新绝对位置；重复键也会增加插入计数。
     * @param absSeq 当前准入条目的窗口绝对位置
     * @param count 本条命令已经登记的订单身份数量，退休入口则为待移除前缀数量
     * @param orderId 需要查找或登记的精确订单编号
     */
    private int trackOrderId(long absSeq, int count, long orderId) {
        if (orderId <= 0) return count;
        if (count >= MAX_TRACKED_ORDER_IDS) throw new IllegalArgumentException("too many tracked order IDs");
        activeOrderLastAbsSeq.put(orderId, absSeq);
        activeOrderInserts++;
        return count + 1;
    }

    /**
     * 给已有物理条目绑定非负 Core 序号，零表示无需记录异步撮合水位；非零同时更新最后一次撮合记录。不在此核对条目是否属于本窗口或仍是当前请求。
     * @param entry 已经加入窗口的条目对象
     * @param sequence 要绑定的非负 Core 序号，零不更新最近撮合记录
     */
    public void bindSequence(Entry entry, long sequence) {
        if (entry == null || entry.physicalSlot < 0 || sequence < 0)
            throw new IllegalArgumentException("invalid command window sequence binding");
        entry.sequence = sequence;
        if (sequence == 0) return;
        lastMatchingSequence = sequence;
        lastMatchingAbsSeq = entry.absSeq;
    }

    /**
     * 返回固定条目数组容量。
     */
    public int capacity() { return entries.length; }
    /**
     * 按队首相对偏移与掩码取得条目，不检查 index 是否在当前 size 范围内；调用方只传有效相对下标。
     * @param index 相对 FIFO 队首的有效条目偏移
     */
    public Entry get(int index) { return entries[(head + index) & indexMask]; }

    /**
     * 退休当前全部条目，清候选路由与解码关联；绝对序号继续累计，索引按水位及既有清理条件失效，不重置所有历史字段。
     */
    public void clear() {
        removePrefix(size);
        resetCandidate(0);
        releaseDecoded();
    }

    /**
     * 退休给定数量队首：清条目引用、归还请求解码槽并推进绝对水位；最近撮合记录已出队时清除。用户索引只在窗口空且条目数超过阈值时清表，订单索引不逐键删除。
     * @param count 本条命令已经登记的订单身份数量，退休入口则为待移除前缀数量
     */
    public void removePrefix(int count) {
        if (count < 0 || count > size) throw new IllegalArgumentException("invalid window prefix");
        for (/* 当前预分配、批量订单遍历或退休前缀的下标。 */ int i = 0; i < count; i++) {
            // 当前填充或清理的已有窗口条目。
            Entry entry = get(i);
            entry.decodedSlot.clearCommandSlot();
            entry.sessionId = NO_SESSION_ID;
            if (decodedSource == entry.request) releaseDecoded();
            if (entry.request != null) entry.request.releaseDecoderSlot();
            entry.request = null;
            entry.response = null;
            entry.sequence = entry.timestamp = entry.position = entry.absSeq = 0;
            entry.physicalSlot = -1;
        }
        head = (head + count) & indexMask;
        size -= count;
        headAbsSeq += count;
        if (lastMatchingAbsSeq < headAbsSeq) {
            lastMatchingSequence = 0;
            lastMatchingAbsSeq = 0;
        }
        if (size == 0 && userLastAbsSeq.size() > orderIndexRotationThreshold) {
            userLastAbsSeq.clear();
        }
    }

    /**
     * 固定响应窗口条目，保存该请求的会话、序号、日志元数据和解码租约，退休后原位清理并复用，不是永久命令历史。
     */
    public static final class Entry {
        /** 当前条目拥有的解码槽，退休时清理，下一次准入可与候选交换。 */
        private OrderCommand.Decoded decodedSlot = OrderCommand.Decoded.commandSlot();
        /** 该条目此次准入的窗口绝对位置。 */
        private long absSeq;
        /** 响应要发回的 Cluster 会话编号，清理后为无会话标记。 */
        public long sessionId = NO_SESSION_ID;
        /** 条目对应的完整入口请求引用。 */
        public CoreMessage request;
        /** 等待按 FIFO 发出的终态响应引用。 */
        public CoreResponse response;
        /**
         * sequence：绑定的 Core 命令序号，零可表示没有异步撮合。
         * timestamp：该条目记录的 Cluster 业务时间。
         * position：该条目记录的 Cluster 日志位置。
         */
        public long sequence, timestamp, position;
        /** 该条目在数组中的物理位置，未使用或退休后负一。 */
        public int physicalSlot = -1;
    }
}


public static final class PipelineState {
    /** Owner 每轮完成收集使用的批次上限六十四，实际循环由调用方控制。 */
    public static final int COMPLETION_BATCH_SIZE = 64;
    /** 异步命令截止点使用的三十秒纳秒间隔，本类只保存截止点。 */
    static final long COMMAND_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);

    /**  当前 Owner 的响应 FIFO。 返回当前响应 FIFO。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final OrderCommandQueue.IngressWindow commandWindow;

    /**
     * 创建使用默认窗口容量的 Owner 调度状态。
     */
    public PipelineState() {
        this(com.surprising.aeron.config.CoreRuntimeConfiguration.standalone().ownerCommandWindow());
    }

    /**
     * 创建指定窗口容量的 Owner 调度状态，其他字段使用初始值。
     * @param commandWindowCapacity 命令响应窗口容量
     */
    public PipelineState(int commandWindowCapacity) {
        commandWindow = new OrderCommandQueue.IngressWindow(commandWindowCapacity);
    }
    /**  正在等待直接业务或查询完成的入口条目。 返回当前控制命令的入口条目引用。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private OwnerIngressRing.Entry activeControl;
    /**
     *  当前控制命令暂存的可空响应候选。
     * 返回当前控制命令暂存的响应候选。
     * 保存可空响应候选，实际发送时机由 Owner 主流程决定。
     * @param controlResponse 需要暂存或匹配保存的可空/非空响应，具体要求见方法
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    private CoreResponse controlResponse;
    /** 控制命令等待的 Matcher 序号，零表示无等待。 */
    private long matchingResponseSequence;
    /**  已经匹配等待序号的控制撮合终态响应。 返回已经匹配保存的控制撮合终态响应。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private CoreResponse matchingResponse;

    /**  当前正在提交的窗口队首引用，退休前固定。 返回已绑定队首引用，其日志位置与时间保留在条目中。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private OrderCommandQueue.IngressWindow.Entry committingHead;
    /**  当前异步命令保存的 nanoTime 截止点。 返回上次保存的单调时钟截止点，未设置时为零。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long progressDeadline;

    /** 被现有依赖挡住的入口请求对象。 */
    private CoreMessage blockedIngress;
    /** 必须先退休的依赖前缀最后条目，引用既有窗口对象。 */
    private OrderCommandQueue.IngressWindow.Entry blockedThrough;

    /**  Owner 已记录的命令阶段推进次数。 返回 Owner 已记录的推进次数，不是已应用 Core 序号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long commandProgress;

    /**  准入记录时观测到的窗口最大占用。 返回记录准入时观测到的窗口峰值。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int commandWindowHighWaterMark;

    /**
     * 清当前窗口及全部控制、依赖和观测字段，供启动或测试复用；不会重置交易运行时的业务状态。
     */
    public void reset() {
        commandWindow.clear();
        activeControl = null;
        controlResponse = null;
        matchingResponseSequence = 0;
        matchingResponse = null;
        committingHead = null;
        progressDeadline = 0;
        blockedIngress = null;
        blockedThrough = null;
        commandProgress = 0;
        commandWindowHighWaterMark = 0;
    }

    /**
     * 终止时清窗口和活动引用，保留进展/峰值/边界计数，便于随后输出摘要。
     */
    public void clearCommandContainers() {
        commandWindow.clear();
        activeControl = null;
        controlResponse = null;
        matchingResponseSequence = 0;
        matchingResponse = null;
        committingHead = null;
        progressDeadline = 0;
        blockedIngress = null;
        blockedThrough = null;
    }

    /**
     * 返回是否已登记活动控制入口。
     */
    public boolean hasActiveControl() {
        return activeControl != null;
    }

    /**
     * 保存控制入口并清上一控制响应及等待 Matcher 水位，不校验 entry 非空。
     * @param entry 当前控制命令的入口条目引用
     */
    public void beginControl(OwnerIngressRing.Entry entry) {
        activeControl = entry;
        controlResponse = null;
        matchingResponseSequence = 0;
        matchingResponse = null;
    }

    /**
     * 记录当前控制命令等待的序号并清旧响应；本方法不检查序号正数。
     * @param sequence 控制命令等待或报告完成的 Matcher 序号
     */
    public void rememberMatchingResponseSequence(long sequence) {
        matchingResponseSequence = sequence;
        matchingResponse = null;
    }

    /**
     * 返回等待序号是否非零，不等于异步子任务已经完成。
     */
    public boolean hasMatchingResponseSequence() {
        return matchingResponseSequence != 0;
    }

    /**
     * 清当前控制入口、响应候选和等待序号，不移动普通命令窗口。
     */
    public void finishControl() {
        activeControl = null;
        controlResponse = null;
        matchingResponseSequence = 0;
        matchingResponse = null;
    }

    /**
     * 要求依赖前缀在当前窗口内，记录请求对象和该前缀最后一个现有条目；不复制一份依赖集合。
     * @param request 需要判定或记录依赖的入口请求对象
     * @param prefixSize 当前必须退休的正依赖前缀长度
     */
    public void rememberBlockedIngress(CoreMessage request, int prefixSize) {
        if (prefixSize <= 0 || prefixSize > commandWindow.size())
            throw new IllegalArgumentException("invalid ingress dependency prefix");
        blockedIngress = request;
        blockedThrough = commandWindow.get(prefixSize - 1);
    }

    /**
     * 清掉阻塞请求和依赖末项的引用，允许入口重新准备。
     */
    public void clearBlockedIngress() {
        blockedIngress = null;
        blockedThrough = null;
    }

    /**
     * 按请求对象身份及已记录末项引用判断是否仍阻塞，不在这里扫描窗口或自行退休依赖。
     * @param request 需要判定或记录依赖的入口请求对象
     */
    public boolean isIngressBlocked(CoreMessage request) {
        return request == blockedIngress && blockedThrough != null;
    }

    /**
     * 只为仍在入口队首的同一请求保留解码，换请求或队首为空时也清旧阻塞引用。
     * @param pending 当前仍在入口队首的请求，空表示没有待准备请求
     */
    public void retainPendingPreparation(CoreMessage pending) {
        commandWindow.retainDecoded(pending);
        if (pending != blockedIngress || pending == null) clearBlockedIngress();
    }

    /**
     * 要求窗口非空且还没有提交队首，保存当前首项并增加进入提交边界的次数；不在此判断其业务是否已完成。
     */
    public void beginHeadCommit() {
        if (commandWindow.size() == 0 || committingHead != null) {
            throw new IllegalStateException("invalid command drain boundary");
        }
        committingHead = commandWindow.get(0);
    }

    /**
     * 返回是否已绑定正在提交的窗口队首。
     */
    public boolean hasCommittingHead() {
        return committingHead != null;
    }

    /**
     * 保存当前 nanoTime 加三十秒的截止点，超时判断由调用方后续执行，不在这里启动计时任务。
     */
    public void startProgressDeadline() {
        progressDeadline = System.nanoTime() + COMMAND_TIMEOUT_NANOS;
    }

    /**
     * 调用方确认队首终态后退休一项；若它正是阻塞依赖末项先清引用，随后移窗口、清提交状态并增加进展计数。
     */
    public void finishHeadCommit() {
        if (committingHead == null) throw new IllegalStateException("no command commit boundary");
        if (committingHead == blockedThrough) clearBlockedIngress();
        commandWindow.removePrefix(1);
        committingHead = null;
        commandProgress++;
    }

    /**
     * 先要求普通窗口空和响应非空，再仅接收与等待序号相同的结果，不匹配的内部子命令结果忽略；同序号重复响应拒绝。本方法不另校验活动控制存在或序号为正。
     * @param sequence 控制命令等待或报告完成的 Matcher 序号
     * @param response 需要暂存或匹配保存的可空/非空响应，具体要求见方法
     */
    public void completeControlMatching(long sequence, CoreResponse response) {
        if (commandWindow.size() != 0 || response == null)
            throw new IllegalStateException("control completion requires an empty trading window and a response");
        if (sequence != matchingResponseSequence) return;
        if (matchingResponse != null) throw new IllegalStateException("duplicate control matching completion");
        matchingResponse = response;
    }

    /**
     * 记录一次入口准入进展，并按当前窗口大小更新观测峰值。
     */
    public void recordAdmission() {
        commandProgress++;
        commandWindowHighWaterMark = Math.max(commandWindowHighWaterMark, commandWindow.size());
    }

    /**
     * 记录一次控制命令推进。
     */
    public void recordControlProgress() {
        commandProgress++;
    }

    /**
     * 组装终止日志摘要：窗口峰值与剩余未完成项。
     */
    public String terminationSummary() {
        return "highWaterMark=" + commandWindowHighWaterMark
                + " pending=" + commandWindow.size();
    }
}

}
