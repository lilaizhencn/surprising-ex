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
    private final int[] nextSlots;
    private final int[] previousSlots;
    private final int[] submissionShards;
    private final int[] nextSubmissionSlots;
    private final int[] previousSubmissionSlots;
    private final int[] submissionHeads;
    private final int[] submissionTails;
    private final CommandIdIndex entriesByCommandId;
    private final LongIntHashMap pendingByUser;
    /** 返回同一个底层命令槽池，供受约束的 Owner/Matcher 协作。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final OrderCommandQueue.Pool contexts;
    private final int mask;
    private int head = -1;
    private int tail = -1;
    /** 尚需按顺序派发资金事实的物理游标，独立于响应退休队首。 */
    private int ledgerDispatchHead = -1;
    @Accessors(fluent = true, chain = false)
    @Getter
    private int size;
    /** Owner 调度状态变化时递增的本地修订号，不是通知对象或业务序号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long dispatchRevision;

    /** 只递增 Owner 本地调度修订号，本方法本身不发送通知或唤醒线程。 */
    public void progressChanged() { dispatchRevision++; }

    /** 校验请求容量及正 Matcher 分片数，容量向上取二次幂，创建命令槽池、全局/分片链数组、UUID 和用户计数索引，负一下标表示无链接。 */
    public OrderCommandQueue(int requestedCapacity, int matcherShardCount) {
        if (requestedCapacity <= 0 || requestedCapacity > 1 << 30) {
            throw new IllegalArgumentException("pending matching capacity must be positive");
        }
        if (matcherShardCount <= 0) throw new IllegalArgumentException("matcher shard count must be positive");
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

    /** 只接受本池已占用的同一槽对象，检查物理窗口及重复 UUID 后链接到全局尾部并登记索引。已链接的同序号槽只更新索引、不改顺序。中途异常不做事务回滚。 */
    void put(OrderCommand pending) {
        if (pending == null) throw new IllegalArgumentException("pending matching is required");
        int index = slot(pending.sequence());
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

    /** 先占槽再初始化。 */
    OrderCommand acquire(long sequence, OrderCommand.Operation operation, CoreMessage command,
                        com.surprising.aeron.protocol.CommandFingerprint fingerprint,
                        java.util.List<Long> preMatchingCancellationOrderIds,
                        OrderCommand.Decoded decodedCommand, OrderCommand.ReplaceOrderCheck orderCheck) {
        OrderCommand context = contexts.claim(sequence);
        OrderCommand pending = context.initialize(sequence, operation, command, fingerprint,
                preMatchingCancellationOrderIds, decodedCommand, orderCheck);
        if (context != pending) throw new IllegalStateException("command must use its claimed slot");
        return pending;
    }

    /** 按低位定位，要求已链接且完整 Core 序号相同才返回。 */
    public OrderCommand get(long sequence) {
        int index = slot(sequence);
        if (!linked(index)) return null;
        OrderCommand pending = pendingAt(index);
        return pending != null && pending.sequence() == sequence ? pending : null;
    }

    /** 只允许丢弃尚未链接的预备槽，已入队项必须经过正常 remove 路径。 */
    void discardPrepared(long sequence) {
        int index = slot(sequence);
        if (linked(index)) throw new IllegalStateException("cannot discard linked pending matching");
        if (contexts.claimed(sequence)) contexts.discard(sequence);
    }

    /** 返回指定完整序号是否已链接在待撮合队列中。 */
    public boolean contains(long sequence) {
        return get(sequence) != null;
    }

    /** 按完整序号定位后从全局链、分片提交链与派生索引移除，推进命中该项的财库游标并更新修订。完整项经池 release 校验，未完整项走 discard。 */
    OrderCommand remove(long sequence) {
        int entryIndex = indexOf(sequence);
        if (entryIndex < 0) return null;
        OrderCommand removed = pendingAt(entryIndex);
        if (removed == null || removed.sequence() != sequence) {
            throw new IllegalStateException("pending matching order is corrupted");
        }
        int previous = previousSlots[entryIndex];
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
            OrderCommand context = contexts.required(sequence);
            if (context.complete()) contexts.release(sequence);
            else contexts.discard(sequence);
        }
        return removed;
    }

    /** 返回全局队首 Core 序号，空队列返回零。 */
    public long firstSequence() {
        return head == -1 ? 0 : pendingAt(head).sequence();
    }

    /** 返回全局队首槽，完成状态由槽里的事实和掩码直接判断。 */
    public OrderCommand head() { return head < 0 ? null : pendingAt(head); }

    /** 返回下一条尚需按序提供资金事实的槽，可能与响应回收队首不同。 */
    public OrderCommand ledgerDispatchHead() {
        return ledgerDispatchHead < 0 ? null : pendingAt(ledgerDispatchHead);
    }

    /** 要求序号正好对应当前资金派发游标，再推进到下一全局项。 */
    public void completeLedgerDispatch(long sequence) {
        if (ledgerDispatchHead < 0 || pendingAt(ledgerDispatchHead).sequence() != sequence)
            throw new IllegalStateException("Ledger settlement dispatch is out of order");
        ledgerDispatchHead = nextSlots[ledgerDispatchHead];
    }

    /** 把已入队命令追加到指定 Matcher 分片提交链，同分片重复登记不做事，换分片拒绝。登记只更新 Owner 顺序索引，不实际向 Matcher 队列提交。 */
    void registerSubmission(long sequence, int matcherShard) {
        if (matcherShard < 0 || matcherShard >= submissionHeads.length) {
            throw new IllegalArgumentException("invalid matcher shard");
        }
        int index = indexOf(sequence);
        if (index < 0) throw new IllegalStateException("pending matching sequence is missing");
        int registeredShard = submissionShards[index];
        if (registeredShard == matcherShard) return;
        if (registeredShard >= 0) throw new IllegalStateException("matching submission shard changed");
        int tailSlot = submissionTails[matcherShard];
        submissionShards[index] = matcherShard;
        previousSubmissionSlots[index] = tailSlot;
        nextSubmissionSlots[index] = -1;
        if (tailSlot < 0) submissionHeads[matcherShard] = index;
        else nextSubmissionSlots[tailSlot] = index;
        submissionTails[matcherShard] = index;
        progressChanged();
    }

    /** 判断命令是否位于指定分片提交链头，调用方先保证分片下标合法。 */
    boolean isSubmissionHead(long sequence, int matcherShard) {
        int index = indexOf(sequence);
        return index >= 0 && submissionHeads[matcherShard] == index;
    }

    /** 返回此已入队命令登记的分片，未找到或尚未登记时负一。 */
    int submissionShard(long sequence) {
        int index = indexOf(sequence);
        return index < 0 ? -1 : submissionShards[index];
    }

    /** 返回指定合法分片的提交链头槽，空链返回空。 */
    OrderCommand submissionHead(int matcherShard) {
        int index = submissionHeads[matcherShard];
        return index < 0 ? null : pendingAt(index);
    }

    /** 按全局 FIFO 有界遍历，返回最早标为延后撮合的槽，不再维护另一张延后命令表。 */
    OrderCommand firstDeferred() {
        for (int index = head; index >= 0; index = nextSlots[index]) {
            OrderCommand pending = pendingAt(index);
            if (pending != null && pending.deferredMatching()) return pending;
        }
        return null;
    }

    /** 检查是否还存在延后撮合项。 */
    public boolean hasDeferred() {
        return firstDeferred() != null;
    }

    /** 按当前全局链统计延后撮合项数，用于冷态观察或边界判断。 */
    int deferredCount() {
        int count = 0;
        for (int index = head; index >= 0; index = nextSlots[index]) {
            OrderCommand pending = pendingAt(index);
            if (pending != null && pending.deferredMatching()) count++;
        }
        return count;
    }

    /** 命令仍在队列时移走它的分片提交链接，不从全局链移除，也不代表账户结算或财库完成。 */
    void completeSubmission(long sequence) {
        int index = indexOf(sequence);
        if (index >= 0) removeFromSubmissionOrder(index);
    }

    /** 按 UUID 两个 primitive 半部查当前待撮合槽。 */
    public OrderCommand findByCommandId(long msb, long lsb) {
        return entriesByCommandId.get(msb, lsb);
    }

    /** 非空 UUID 查询待撮合索引，空值直接返回空。 */
    public OrderCommand findByCommandId(UUID commandId) {
        return commandId == null ? null : entriesByCommandId.get(commandId);
    }

    /** 对正用户号检查当前已链接命令计数是否大于零。 */
    public boolean hasUser(long userId) {
        return userId > 0 && pendingByUser.get(userId) > 0;
    }

    /** 用于已入队命令判断更早的同用户项：计数至多一时直接否定，否则按全局链扫描到目标序号前。 */
    boolean hasEarlierUser(long sequence, long userId) {
        if (sequence <= 0 || userId <= 0 || pendingByUser.get(userId) <= 1) return false;
        for (int slot = head; slot != -1; slot = nextSlots[slot]) {
            OrderCommand pending = pendingAt(slot);
            if (pending == null || pending.sequence() >= sequence) return false;
            if (pending.command().header().userId() == userId) return true;
        }
        return false;
    }

    /** 由 Owner 按全局链接顺序对现有槽执行回调，不复制内容，也不提供遍历期间并发修改保证。 */
    void forEach(Consumer<OrderCommand> consumer) {
        for (int slot = head; slot != -1; slot = nextSlots[slot]) {
            OrderCommand pending = pendingAt(slot);
            if (pending != null) consumer.accept(pending);
        }
    }

    /** 反复按全局队首 remove 清掉所有已链接项。 */
    public void clear() {
        while (size != 0) remove(firstSequence());
    }

    private void addIndexes(OrderCommand pending) {
        var header = pending.command().header();
        entriesByCommandId.put(header.commandIdMsb(), header.commandIdLsb(), pending);
        long userId = header.userId();
        if (userId > 0) pendingByUser.addToValue(userId, 1);
    }

    /** 归零删除键，负数明确报错，不自动回滚前面的索引动作。 */
    private void removeIndexes(OrderCommand pending) {
        var header = pending.command().header();
        entriesByCommandId.remove(header.commandIdMsb(), header.commandIdLsb(), pending);
        long userId = header.userId();
        if (userId <= 0) return;
        int remaining = pendingByUser.addToValue(userId, -1);
        if (remaining == 0) pendingByUser.removeKey(userId);
        else if (remaining < 0) throw new IllegalStateException("pending matching user count underflow");
    }

    private void removeFromSubmissionOrder(int index) {
        int shard = submissionShards[index];
        if (shard < 0) return;
        int previous = previousSubmissionSlots[index];
        int next = nextSubmissionSlots[index];
        if (previous < 0) submissionHeads[shard] = next;
        else nextSubmissionSlots[previous] = next;
        if (next < 0) submissionTails[shard] = previous;
        else previousSubmissionSlots[next] = previous;
        submissionShards[index] = -1;
        previousSubmissionSlots[index] = -1;
        nextSubmissionSlots[index] = -1;
    }

    private void requireAvailableCommandId(OrderCommand pending, OrderCommand replaced) {
        var header = pending.command().header();
        OrderCommand duplicate = entriesByCommandId.get(header.commandIdMsb(), header.commandIdLsb());
        if (duplicate != null && duplicate != replaced) {
            throw new IllegalStateException("duplicate pending matching commandId");
        }
    }

    /** 只返回已链接且完整 Core 序号匹配的物理位置，否则负一。 */
    private int indexOf(long sequence) {
        int index = slot(sequence);
        if (!linked(index)) return -1;
        OrderCommand pending = pendingAt(index);
        return pending != null && pending.sequence() == sequence ? index : -1;
    }

    private int slot(long sequence) {
        return (int) sequence & mask;
    }

    private boolean linked(int index) {
        return head == index || previousSlots[index] != -1 || nextSlots[index] != -1;
    }

    private OrderCommand pendingAt(int index) {
        OrderCommand context = contexts.contextAt(index);
        return context.coreSequence() == 0 || context.command() == null ? null : context;
    }

    /** 返回已链接队列是否为空，不等于槽池没有预备占用。 */
    public boolean isEmpty() { return size == 0; }
    /** 返回槽池向上取整后的容量。 */
    public int capacity() { return contexts.capacity(); }

    /** 按完整序号从槽池严格取占用槽，不要求它已经 put 链接。 */
    public OrderCommand required(long sequence) { return contexts.required(sequence); }
    /** 检查槽池是否已占用该完整序号。 */
    public boolean claimed(long sequence) { return contexts.claimed(sequence); }
    /** 返回底层池全部占用数，包含未链接预备项。 */
    public int inFlight() { return contexts.inFlight(); }
    /** 返回底层池历史占用峰值，不只是已链接队列峰值。 */
    public int highWaterMark() { return contexts.highWaterMark(); }

public static final class Pool {
    private final OrderCommand[] contexts;
    private final int mask;
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private int inFlight;
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private int highWaterMark;

    /** 校验正的二次幂容量，预分配空 OrderCommand。 */
    public Pool(int capacity) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("lane command context capacity must be a power of two");
        }
        contexts = new OrderCommand[capacity];
        for (int index = 0; index < capacity; index++) contexts[index] = new OrderCommand();
        mask = capacity - 1;
    }

    /** 要求正 Core 序号，按掩码取得未占用槽，写入身份、标记占用并更新在途/峰值。 */
    OrderCommand claim(long coreSequence) {
        if (coreSequence <= 0) throw new IllegalArgumentException("coreSequence must be positive");
        OrderCommand context = contexts[(int) coreSequence & mask];
        if (context.claimed) throw new IllegalStateException("lane command context ring is full");
        context.coreSequence = coreSequence;
        context.claimed = true;
        inFlight++;
        highWaterMark = Math.max(highWaterMark, inFlight);
        return context;
    }

    /** 按低位寻址并同时检查 claimed 和完整序号匹配，否则抛出带槽位诊断的异常。 */
    public OrderCommand required(long coreSequence) {
        int slot = (int) coreSequence & mask;
        OrderCommand context = contexts[slot];
        if (!context.claimed || context.coreSequence != coreSequence) {
            throw new IllegalStateException("unknown lane command context sequence=" + coreSequence
                    + " slot=" + slot + " actual=" + context.coreSequence
                    + " inFlight=" + inFlight + " capacity=" + contexts.length);
        }
        return context;
    }

    /** 按物理数组下标取槽，不检查它是否已占用。 */
    OrderCommand contextAt(int slot) { return contexts[slot]; }

    /** 要求已绑定撮合结果且全部预期 Lane 完成，并且 Matcher 提交路由已释放，随后清生命周期并减在途计数。 */
    void release(long coreSequence) {
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

    /** 检查目标物理槽已占用且完整 Core 序号相同，不仅比较低位下标。 */
    public boolean claimed(long coreSequence) {
        OrderCommand slot = contexts[(int) coreSequence & mask];
        return slot.claimed && slot.coreSequence == coreSequence;
    }

    /** 直接清已占用槽的生命周期并减计数，不要求撮合、Lane 或 Matcher 路由完成。 */
    void discard(long coreSequence) {
        OrderCommand context = required(coreSequence);
        context.clear();
        inFlight--;
    }

    /** 返回预分配槽位总数。 */
    int capacity() { return contexts.length; }

}

static final class CommandIdIndex {
    private final long[] mostSignificantBits;
    private final long[] leastSignificantBits;
    private final OrderCommand[] values;
    private final int mask;
    private int size;

    /** 要求预期条目数为正，按其两倍向上取二次幂建立数组，使正常窗口最多半满。本构造未单独检查倍增的 int 溢出。 */
    CommandIdIndex(int expectedEntries) {
        if (expectedEntries <= 0) throw new IllegalArgumentException("expected entries must be positive");
        int capacity = 1;
        while (capacity < expectedEntries * 2) capacity <<= 1;
        mostSignificantBits = new long[capacity];
        leastSignificantBits = new long[capacity];
        values = new OrderCommand[capacity];
        mask = capacity - 1;
    }

    /** 按 UUID 两半混合后的起点连续探测，找到相同位值返回原槽引用，遇到空位返回空。 */
    OrderCommand get(long most, long least) {
        int index = index(most, least);
        while (values[index] != null) {
            if (mostSignificantBits[index] == most && leastSignificantBits[index] == least) return values[index];
            index = (index + 1) & mask;
        }
        return null;
    }

    /** 空 UUID 直接返回空，否则拆成两半查询，不创建新的键对象。 */
    OrderCommand get(UUID commandId) {
        if (commandId == null) return null;
        return get(commandId.getMostSignificantBits(), commandId.getLeastSignificantBits());
    }

    /** 同 UUID 已有条目就替换引用，否则写入首空位并增加数量。半满检查发生在插入之后，超限异常不会回滚刚写入的条目。 */
    void put(long most, long least, OrderCommand value) {
        if (value == null) throw new IllegalArgumentException("command value is required");
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

    /** 找到 UUID 后，仅在 expected 为空或与当前值按对象身份相同时删除。 */
    void remove(long most, long least, OrderCommand expected) {
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

    private void removeAt(int removed) {
        values[removed] = null;
        mostSignificantBits[removed] = 0;
        leastSignificantBits[removed] = 0;
        size--;
        int index = (removed + 1) & mask;
        while (values[index] != null) {
            OrderCommand value = values[index];
            long most = mostSignificantBits[index];
            long least = leastSignificantBits[index];
            values[index] = null;
            mostSignificantBits[index] = 0;
            leastSignificantBits[index] = 0;
            size--;
            insert(most, least, value);
            index = (index + 1) & mask;
        }
    }

    private void insert(long most, long least, OrderCommand value) {
        int index = index(most, least);
        while (values[index] != null) index = (index + 1) & mask;
        mostSignificantBits[index] = most;
        leastSignificantBits[index] = least;
        values[index] = value;
        size++;
    }

    private int index(long most, long least) {
        long hash = most ^ Long.rotateLeft(least, 23);
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdl;
        hash ^= hash >>> 33;
        return (int) hash & mask;
    }
}

public static final class IngressWindow {
    public static final int DEFAULT_CAPACITY = 256;
    private static final int MAX_TRACKED_ORDER_IDS = Math.max(
            PlaceOrderBatchCommand.MAX_ORDERS, CancelOrderBatchCommand.MAX_ORDERS);

    private final Entry[] entries;
    private final int indexMask;
    private int head;
    @Accessors(fluent = true, chain = false)
    @Getter
    private int size;
    private long headAbsSeq = 1L;
    private long tailAbsSeq;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long lastMatchingSequence;
    private long lastMatchingAbsSeq;

    private final org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap userLastAbsSeq;
    private org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap activeOrderLastAbsSeq;
    /** 上一代仍可能含有效旧命令的订单位置索引。 */
    private org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap previousOrderLastAbsSeq;
    private final int orderIndexRotationThreshold;
    private long previousOrderMaxAbsSeq;
    private int activeOrderInserts;

    private long candidateUser;
    private long candidateUserLaneBit;
    private int candidateMatcherShard = -1;
    private boolean candidateRouted;
    /** 产生候选路由时的解码来源请求引用，防止旧解码被误用于新请求。 */
    private CoreMessage routedSource;
    private CoreMessage decodedSource;
    private OrderCommand.Decoded decoded;
    /** Owner 准备下一入口时使用的可复用解码槽，准入时与 Entry 备用槽交换。 */
    private OrderCommand.Decoded candidateDecoder = OrderCommand.Decoded.commandSlot();

    /** 从系统属性读取窗口容量，未配置默认二百五十六，再按显式容量构造。 */
    public IngressWindow() {
        this(com.surprising.aeron.config.CoreRuntimeConfiguration.standalone().ownerCommandWindow());
    }

    /** 要求容量是六十四至四千零九十六之间的二次幂，预分配条目及解码槽，按容量配置用户/双代订单索引初始容量。 */
    public IngressWindow(int capacity) {
        if (capacity < 64 || capacity > 4096 || Integer.bitCount(capacity) != 1)
            throw new IllegalArgumentException("owner command window must be a power of two in [64,4096]");
        entries = new Entry[capacity];
        indexMask = capacity - 1;
        int userMapCapacity = Math.max(512, capacity << 1);
        int orderMapCapacity = Math.max(4096, capacity << 4);
        userLastAbsSeq = new org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap(userMapCapacity);
        activeOrderLastAbsSeq = new org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap(orderMapCapacity);
        previousOrderLastAbsSeq = new org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap(orderMapCapacity);
        orderIndexRotationThreshold = orderMapCapacity;
        for (int i = 0; i < entries.length; i++) entries[i] = new Entry();
    }

    /** 设置下一入口用户并清掉候选路由。 */
    public void resetCandidate(long userId) {
        candidateUser = userId;
        candidateUserLaneBit = 0;
        candidateMatcherShard = -1;
        candidateRouted = false;
        routedSource = null;
    }

    /** 保存当前用户的静态 Matcher 分片和非零 Lane 位，并关联当时 decodedSource。 */
    public void route(int matcherShard, long userLaneBit) {
        if (candidateUser <= 0 || matcherShard < 0 || userLaneBit == 0)
            throw new IllegalArgumentException("invalid ingress route");
        routedSource = decodedSource;
        candidateMatcherShard = matcherShard;
        candidateUserLaneBit = userLaneBit;
        candidateRouted = true;
    }

    /** 已路由时返回候选 Matcher 分片，否则返回负一。 */
    public int routeMatcherShard() { return candidateRouted ? candidateMatcherShard : -1; }
    /** 已路由时返回调用方保存的用户 Lane 位值，否则返回零。 */
    public long routeUserLaneBit() { return candidateRouted ? candidateUserLaneBit : 0; }

    /** 计算撤单精确订单依赖前缀，再取与同 userId 最近命令前缀的较大值。 */
    public int conflictingPrefixSize() {
        if (size == 0) return 0;
        int exactPrefix = exactOrderPrefix(0);
        long candidateUserId = candidateRouted ? candidateUser : 0;
        if (candidateUserId != 0) {
            long userAbsSeq = userLastAbsSeq.get(candidateUserId);
            if (userAbsSeq >= headAbsSeq) {
                int userPrefix = (int) (userAbsSeq - headAbsSeq + 1L);
                if (userPrefix > exactPrefix) {
                    return userPrefix;
                }
            }
        }
        return exactPrefix;
    }

    private int exactOrderPrefix(int prefix) {
        if (routedSource == null || routedSource != decodedSource || decoded == null || size == 0) return prefix;
        CoreMessageType type = decodedSource.header().messageType();
        switch (type) {
            case CANCEL_ORDER -> prefix = maxOrderPrefix(prefix, decoded.cancelOrder().orderId());
            case CANCEL_ORDER_BATCH -> {
                var orders = decoded.cancelOrderBatch().orders();
                for (int i = 0, n = orders.size(); i < n; i++) {
                    prefix = maxOrderPrefix(prefix, orders.get(i).orderId());
                }
            }
            default -> { }
        }
        return prefix;
    }

    private int maxOrderPrefix(int prefix, long orderId) {
        if (orderId <= 0) return prefix;
        long orderAbsSeq = activeOrderLastAbsSeq.get(orderId);
        if (orderAbsSeq < headAbsSeq && previousOrderMaxAbsSeq >= headAbsSeq) {
            orderAbsSeq = previousOrderLastAbsSeq.get(orderId);
        }
        if (orderAbsSeq >= headAbsSeq) {
            return Math.max(prefix, (int) (orderAbsSeq - headAbsSeq + 1L));
        }
        return prefix;
    }

    /** 按请求对象身份缓存解码，换请求时使用候选预分配槽 decodeInto，同一请求重试直接返回。 */
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

    /** 只有请求对象与缓存来源相同才返回已解码引用，不触发新解码。 */
    public OrderCommand.Decoded decodedIfPresent(CoreMessage request) {
        return decodedSource == request ? decoded : null;
    }

    /** 若解码器仍归候选就清它，若已交换给条目则保留条目内容。 */
    public void releaseDecoded() {
        if (decoded == candidateDecoder) candidateDecoder.clearCommandSlot();
        if (routedSource == decodedSource) routedSource = null;
        decodedSource = null;
        decoded = null;
    }

    /** 仅当当前阻塞队首仍是同一请求对象时保留缓存，否则释放候选解码关联。 */
    public void retainDecoded(CoreMessage pendingHead) {
        if (pendingHead == null || decodedSource != pendingHead) releaseDecoded();
    }

    /** 检查队满和候选路由存在后，填入尾条目、登记用户及当前解码中的订单索引，并把候选解码槽与条目备用槽交换。内部后续异常不自动回滚已写字段。 */
    public Entry add(long sessionId, CoreMessage request, long timestamp, long position) {
        if (size == entries.length) throw new IllegalStateException("cluster command window is full");
        if (!candidateRouted) throw new IllegalStateException("ingress route is missing");
        Entry entry = get(size++);
        long absSeq = ++tailAbsSeq;
        entry.absSeq = absSeq;
        entry.sessionId = sessionId;
        entry.request = request;
        entry.timestamp = timestamp;
        entry.position = position;
        int physical = (head + size - 1) & indexMask;
        userLastAbsSeq.put(candidateUser, absSeq);
        indexTrackedOrderIds(absSeq);
        if (decodedSource == request && decoded == candidateDecoder) {
            var spare = entry.decodedSlot;
            entry.decodedSlot = candidateDecoder;
            candidateDecoder = spare;
        }
        entry.physicalSlot = physical;
        return entry;
    }

    private void indexTrackedOrderIds(long absSeq) {
        if (routedSource == null || routedSource != decodedSource || decoded == null) return;
        if (activeOrderInserts >= orderIndexRotationThreshold && headAbsSeq > previousOrderMaxAbsSeq) {
            previousOrderLastAbsSeq.clear();
            var swap = previousOrderLastAbsSeq;
            previousOrderLastAbsSeq = activeOrderLastAbsSeq;
            activeOrderLastAbsSeq = swap;
            previousOrderMaxAbsSeq = absSeq - 1L;
            activeOrderInserts = 0;
        }
        int count = 0;
        switch (decodedSource.header().messageType()) {
            case PLACE_ORDER -> count = trackOrderId(absSeq, count, decoded.placeOrder().orderId());
            case CANCEL_ORDER -> count = trackOrderId(absSeq, count, decoded.cancelOrder().orderId());
            case PLACE_ORDER_BATCH -> {
                var orders = decoded.placeOrderBatch().orders();
                for (int i = 0, n = orders.size(); i < n; i++) {
                    count = trackOrderId(absSeq, count, orders.get(i).orderId());
                }
            }
            case CANCEL_ORDER_BATCH -> {
                var orders = decoded.cancelOrderBatch().orders();
                for (int i = 0, n = orders.size(); i < n; i++) {
                    count = trackOrderId(absSeq, count, orders.get(i).orderId());
                }
            }
            default -> { }
        }
    }

    private int trackOrderId(long absSeq, int count, long orderId) {
        if (orderId <= 0) return count;
        if (count >= MAX_TRACKED_ORDER_IDS) throw new IllegalArgumentException("too many tracked order IDs");
        activeOrderLastAbsSeq.put(orderId, absSeq);
        activeOrderInserts++;
        return count + 1;
    }

    /** 给已有物理条目绑定非负 Core 序号，零表示无需记录异步撮合水位。 */
    public void bindSequence(Entry entry, long sequence) {
        if (entry == null || entry.physicalSlot < 0 || sequence < 0)
            throw new IllegalArgumentException("invalid command window sequence binding");
        entry.sequence = sequence;
        if (sequence == 0) return;
        lastMatchingSequence = sequence;
        lastMatchingAbsSeq = entry.absSeq;
    }

    /** 返回固定条目数组容量。 */
    public int capacity() { return entries.length; }
    /** 按队首相对偏移与掩码取得条目，不检查 index 是否在当前 size 范围内。 */
    public Entry get(int index) { return entries[(head + index) & indexMask]; }

    /** 退休当前全部条目，清候选路由与解码关联。 */
    public void clear() {
        removePrefix(size);
        resetCandidate(0);
        releaseDecoded();
    }

    /** 退休给定数量队首：清条目引用、归还请求解码槽并推进绝对水位。 */
    public void removePrefix(int count) {
        if (count < 0 || count > size) throw new IllegalArgumentException("invalid window prefix");
        for (int i = 0; i < count; i++) {
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

    /** 固定响应窗口条目，保存该请求的会话、序号、日志元数据和解码租约，退休后原位清理并复用，不是永久命令历史。 */
    public static final class Entry {
        private OrderCommand.Decoded decodedSlot = OrderCommand.Decoded.commandSlot();
        private long absSeq;
        public long sessionId = NO_SESSION_ID;
        public CoreMessage request;
        public CoreResponse response;
        public long sequence, timestamp, position;
        public int physicalSlot = -1;
    }
}

public static final class PipelineState {
    /** Owner 每轮完成收集使用的批次上限六十四，实际循环由调用方控制。 */
    public static final int COMPLETION_BATCH_SIZE = 64;
    static final long COMMAND_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);

    /** 当前 Owner 的响应 FIFO。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final OrderCommandQueue.IngressWindow commandWindow;

    /** 创建使用默认窗口容量的 Owner 调度状态。 */
    public PipelineState() {
        this(com.surprising.aeron.config.CoreRuntimeConfiguration.standalone().ownerCommandWindow());
    }

    /** 创建指定窗口容量的 Owner 调度状态，其他字段使用初始值。 */
    public PipelineState(int commandWindowCapacity) {
        commandWindow = new OrderCommandQueue.IngressWindow(commandWindowCapacity);
    }
    @Accessors(fluent = true, chain = false)
    @Getter
    private OwnerIngressRing.Entry activeControl;
    /** 保存可空响应候选，实际发送时机由 Owner 主流程决定。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    private CoreResponse controlResponse;
    private long matchingResponseSequence;
    @Accessors(fluent = true, chain = false)
    @Getter
    private CoreResponse matchingResponse;

    @Accessors(fluent = true, chain = false)
    @Getter
    private OrderCommandQueue.IngressWindow.Entry committingHead;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long progressDeadline;

    private CoreMessage blockedIngress;
    /** 必须先退休的依赖前缀最后条目，引用既有窗口对象。 */
    private OrderCommandQueue.IngressWindow.Entry blockedThrough;

    /** Owner 已记录的命令阶段推进次数。返回 Owner 已记录的推进次数，不是已应用 Core 序号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long commandProgress;

    @Accessors(fluent = true, chain = false)
    @Getter
    private int commandWindowHighWaterMark;

    /** 清当前窗口及全部控制、依赖和观测字段，供启动或测试复用。 */
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

    /** 终止时清窗口和活动引用，保留进展/峰值/边界计数，便于随后输出摘要。 */
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

    /** 返回是否已登记活动控制入口。 */
    public boolean hasActiveControl() {
        return activeControl != null;
    }

    /** 保存控制入口并清上一控制响应及等待 Matcher 水位，不校验 entry 非空。 */
    public void beginControl(OwnerIngressRing.Entry entry) {
        activeControl = entry;
        controlResponse = null;
        matchingResponseSequence = 0;
        matchingResponse = null;
    }

    /** 记录当前控制命令等待的序号并清旧响应。 */
    public void rememberMatchingResponseSequence(long sequence) {
        matchingResponseSequence = sequence;
        matchingResponse = null;
    }

    /** 返回等待序号是否非零，不等于异步子任务已经完成。 */
    public boolean hasMatchingResponseSequence() {
        return matchingResponseSequence != 0;
    }

    /** 清当前控制入口、响应候选和等待序号，不移动普通命令窗口。 */
    public void finishControl() {
        activeControl = null;
        controlResponse = null;
        matchingResponseSequence = 0;
        matchingResponse = null;
    }

    /** 要求依赖前缀在当前窗口内，记录请求对象和该前缀最后一个现有条目。 */
    public void rememberBlockedIngress(CoreMessage request, int prefixSize) {
        if (prefixSize <= 0 || prefixSize > commandWindow.size())
            throw new IllegalArgumentException("invalid ingress dependency prefix");
        blockedIngress = request;
        blockedThrough = commandWindow.get(prefixSize - 1);
    }

    /** 清掉阻塞请求和依赖末项的引用，允许入口重新准备。 */
    public void clearBlockedIngress() {
        blockedIngress = null;
        blockedThrough = null;
    }

    /** 按请求对象身份及已记录末项引用判断是否仍阻塞，不在这里扫描窗口或自行退休依赖。 */
    public boolean isIngressBlocked(CoreMessage request) {
        return request == blockedIngress && blockedThrough != null;
    }

    /** 只为仍在入口队首的同一请求保留解码，换请求或队首为空时也清旧阻塞引用。 */
    public void retainPendingPreparation(CoreMessage pending) {
        commandWindow.retainDecoded(pending);
        if (pending != blockedIngress || pending == null) clearBlockedIngress();
    }

    /** 要求窗口非空且还没有提交队首，保存当前首项并增加进入提交边界的次数。 */
    public void beginHeadCommit() {
        if (commandWindow.size() == 0 || committingHead != null) {
            throw new IllegalStateException("invalid command drain boundary");
        }
        committingHead = commandWindow.get(0);
    }

    /** 返回是否已绑定正在提交的窗口队首。 */
    public boolean hasCommittingHead() {
        return committingHead != null;
    }

    /** 保存当前 nanoTime 加三十秒的截止点，超时判断由调用方后续执行，不在这里启动计时任务。 */
    public void startProgressDeadline() {
        progressDeadline = System.nanoTime() + COMMAND_TIMEOUT_NANOS;
    }

    /** 调用方确认队首终态后退休一项。 */
    public void finishHeadCommit() {
        if (committingHead == null) throw new IllegalStateException("no command commit boundary");
        if (committingHead == blockedThrough) clearBlockedIngress();
        commandWindow.removePrefix(1);
        committingHead = null;
        commandProgress++;
    }

    /** 先要求普通窗口空和响应非空，再仅接收与等待序号相同的结果，不匹配的内部子命令结果忽略。 */
    public void completeControlMatching(long sequence, CoreResponse response) {
        if (commandWindow.size() != 0 || response == null)
            throw new IllegalStateException("control completion requires an empty trading window and a response");
        if (sequence != matchingResponseSequence) return;
        if (matchingResponse != null) throw new IllegalStateException("duplicate control matching completion");
        matchingResponse = response;
    }

    /** 记录一次入口准入进展，并按当前窗口大小更新观测峰值。 */
    public void recordOrderCheck() {
        commandProgress++;
        commandWindowHighWaterMark = Math.max(commandWindowHighWaterMark, commandWindow.size());
    }

    /** 记录一次控制命令推进。 */
    public void recordControlProgress() {
        commandProgress++;
    }

    /** 组装终止日志摘要：窗口峰值与剩余未完成项。 */
    public String terminationSummary() {
        return "highWaterMark=" + commandWindowHighWaterMark
                + " pending=" + commandWindow.size();
    }
}

}
