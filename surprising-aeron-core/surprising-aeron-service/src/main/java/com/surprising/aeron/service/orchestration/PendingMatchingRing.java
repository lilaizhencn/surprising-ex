package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.service.command.order.DecodedMatchingCommand;
import com.surprising.aeron.service.command.order.ResolvedMatchingAdmission;
import com.surprising.aeron.protocol.CoreMessage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;

final class PendingMatchingRing {
    private final int[] nextSlots;
    private final int[] previousSlots;
    private final int[] submissionShards;
    /** 已准入命令的稳定撮合分区；提交队列出队后仍保留至全局终态。 */
    private final int[] settlementShards;
    /** 分区已经派发结算的槽位，防止全局提交前重复执行。 */
    private final boolean[] settlementDispatched;
    private final int[] nextSubmissionSlots;
    private final int[] previousSubmissionSlots;
    private final int[] submissionHeads;
    private final int[] submissionTails;
    private final PendingCommandIdIndex entriesByCommandId;
    private final LongIntHashMap pendingByUser;
    private final CommandSlotRing contexts;
    private final int mask;
    private int head = -1;
    private int tail = -1;
    private int dispatchHead = -1;
    private int size;
    /** Owner 派发顺序的失效标记：队列、分区或已派发前缀改变后必须重新检查依赖。 */
    private long dispatchRevision;
    /** Ready partition cursor: one primitive slot per matcher shard plus a ready bitmask. */
    private final int[] readyDispatchSlots;
    private long readyPartitionMask;
    private long readyPartitionRevision = Long.MIN_VALUE;
    private long readyPartitionThrough = Long.MIN_VALUE;

    long dispatchRevision() { return dispatchRevision; }

    void partitionDependenciesChanged() { dispatchRevision++; }

    /** Completion and admission notifications share the same owner-side wake-up revision. */
    void progressChanged() { dispatchRevision++; }

    PendingMatchingRing(int requestedCapacity, int matcherShardCount, int laneCount) {
        if (requestedCapacity <= 0 || requestedCapacity > 1 << 30) {
            throw new IllegalArgumentException("pending matching capacity must be positive");
        }
        if (matcherShardCount <= 0) throw new IllegalArgumentException("matcher shard count must be positive");
        int capacity = 1;
        while (capacity < requestedCapacity) capacity <<= 1;
        nextSlots = new int[capacity];
        previousSlots = new int[capacity];
        submissionShards = new int[capacity];
        settlementShards = new int[capacity];
        settlementDispatched = new boolean[capacity];
        java.util.Arrays.fill(settlementShards, -1);
        nextSubmissionSlots = new int[capacity];
        previousSubmissionSlots = new int[capacity];
        submissionHeads = new int[matcherShardCount];
        submissionTails = new int[matcherShardCount];
        readyDispatchSlots = new int[matcherShardCount];
        java.util.Arrays.fill(nextSlots, -1);
        java.util.Arrays.fill(previousSlots, -1);
        java.util.Arrays.fill(submissionShards, -1);
        java.util.Arrays.fill(nextSubmissionSlots, -1);
        java.util.Arrays.fill(previousSubmissionSlots, -1);
        java.util.Arrays.fill(submissionHeads, -1);
        java.util.Arrays.fill(submissionTails, -1);
        java.util.Arrays.fill(readyDispatchSlots, -1);
        mask = capacity - 1;
        contexts = new CommandSlotRing(capacity, laneCount);
        entriesByCommandId = new PendingCommandIdIndex(capacity);
        pendingByUser = new LongIntHashMap(capacity);
    }

    void put(CommandSlot pending) {
        if (pending == null) throw new IllegalArgumentException("pending matching is required");
        int index = slot(pending.sequence());
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
            dispatchRevision++;
            return;
        }
        if (size == contexts.capacity()) {
            throw new IllegalStateException("pending matching ring is full");
        }
        requireAvailableCommandId(pending, null);
        CommandSlot context = contexts.claimed(pending.sequence())
                ? contexts.required(pending.sequence()) : contexts.claim(pending.sequence());
        if (context != pending) throw new IllegalStateException("command must use its claimed slot");
        previousSlots[index] = tail;
        nextSlots[index] = -1;
        if (tail == -1) head = index;
        else nextSlots[tail] = index;
        tail = index;
        if (dispatchHead == -1) dispatchHead = index;
        addIndexes(pending);
        size++;
        dispatchRevision++;
    }

    CommandSlot acquire(long sequence, CommandSlot.Operation operation, CoreMessage command,
                            com.surprising.aeron.protocol.CommandFingerprint fingerprint,
                            java.util.List<Long> preMatchingCancellationOrderIds,
                            com.surprising.aeron.service.state.RuntimeProjectionPoint beforeProjection,
                            long beforeBusinessStateHash, long beforeFundsStateHash,
                            com.surprising.aeron.service.state.RuntimeFundsDelta fundsDelta,
                            DecodedMatchingCommand decodedCommand, ResolvedMatchingAdmission admission) {
        CommandSlot context = contexts.claim(sequence);
        CommandSlot pending = context.initialize(sequence, operation, command, fingerprint,
                preMatchingCancellationOrderIds, beforeProjection, beforeBusinessStateHash, beforeFundsStateHash,
                fundsDelta, decodedCommand, admission);
        if (context != pending) throw new IllegalStateException("command must use its claimed slot");
        return pending;
    }

    CommandSlot get(long sequence) {
        int index = slot(sequence);
        if (!linked(index)) return null;
        CommandSlot pending = pendingAt(index);
        return pending != null && pending.sequence() == sequence ? pending : null;
    }

    void discardPrepared(long sequence) {
        int index = slot(sequence);
        if (linked(index)) throw new IllegalStateException("cannot discard linked pending matching");
        if (contexts.claimed(sequence)) contexts.discard(sequence);
    }

    boolean contains(long sequence) {
        return get(sequence) != null;
    }

    CommandSlot remove(long sequence) {
        int entryIndex = indexOf(sequence);
        if (entryIndex < 0) return null;
        CommandSlot removed = pendingAt(entryIndex);
        if (removed == null || removed.sequence() != sequence) {
            throw new IllegalStateException("pending matching order is corrupted");
        }
        int previous = previousSlots[entryIndex];
        int next = nextSlots[entryIndex];
        if (dispatchHead == entryIndex) dispatchHead = next;
        if (previous == -1) head = next;
        else nextSlots[previous] = next;
        if (next == -1) tail = previous;
        else previousSlots[next] = previous;
        previousSlots[entryIndex] = -1;
        nextSlots[entryIndex] = -1;
        removeFromSubmissionOrder(entryIndex);
        settlementShards[entryIndex] = -1;
        settlementDispatched[entryIndex] = false;
        advanceDispatchedHead();
        removeIndexes(removed);
        size--;
        dispatchRevision++;
        if (contexts.claimed(sequence)) {
            CommandSlot context = contexts.required(sequence);
            if (context.complete()) contexts.release(sequence);
            else contexts.discard(sequence);
        }
        return removed;
    }

    long firstSequence() {
        return head == -1 ? 0 : pendingAt(head).sequence();
    }

    /** 返回有序队首；完成状态由提交器直接读取 slot，不再维护第二个 ready 位。 */
    CommandSlot head() { return head < 0 ? null : pendingAt(head); }

    CommandSlot dispatchHead() {
        return dispatchHead < 0 ? null : pendingAt(dispatchHead);
    }

    void completeDispatch(long sequence) {
        int index = indexOf(sequence);
        if (index < 0 || dispatchHead != index) {
            throw new IllegalStateException("matcher settlement dispatch is out of order");
        }
        settlementDispatched[index] = true;
        advanceDispatchedHead();
        dispatchRevision++;
    }

    private void advanceDispatchedHead() {
        while (dispatchHead >= 0 && settlementDispatched[dispatchHead]) {
            dispatchHead = nextSlots[dispatchHead];
        }
    }

    /**
     * Computes dispatchable partitions once per queue revision.  The Owner consumes the
     * resulting bitmask and primitive cursor slots; it no longer performs an all-partition scan
     * or allocates a candidate array on every completion pump.
     */
    long readyPartitionMask(long throughSequence) {
        if (readyPartitionRevision == dispatchRevision && readyPartitionThrough == throughSequence) {
            return readyPartitionMask;
        }
        java.util.Arrays.fill(readyDispatchSlots, -1);
        long candidates = 0;
        long earlierLanes = 0;
        for (int index = dispatchHead; index >= 0; index = nextSlots[index]) {
            if (settlementDispatched[index]) continue;
            CommandSlot pending = pendingAt(index);
            if (pending == null || pending.sequence() > throughSequence) break;
            int shard = settlementShards[index];
            long laneMask = pending.partitionLaneMask;
            boolean first = index == dispatchHead;
            if (first && shard >= 0) {
                if (readyDispatchSlots[shard] < 0) {
                    readyDispatchSlots[shard] = index;
                    candidates |= 1L << shard;
                }
            } else if (pending.clusterIndependent && shard >= 0 && laneMask != 0) {
                if (readyDispatchSlots[shard] < 0 && (laneMask & earlierLanes) == 0) {
                    readyDispatchSlots[shard] = index;
                    candidates |= 1L << shard;
                }
            } else {
                break;
            }
            if (!pending.clusterIndependent || shard < 0 || laneMask == 0) break;
            earlierLanes |= laneMask;
        }
        readyPartitionMask = candidates;
        readyPartitionRevision = dispatchRevision;
        readyPartitionThrough = throughSequence;
        return candidates;
    }

    CommandSlot readyPartitionHead(int shard) {
        if (shard < 0 || shard >= readyDispatchSlots.length) return null;
        int index = readyDispatchSlots[shard];
        return index < 0 ? null : pendingAt(index);
    }

    /**
     * Computes all currently dispatchable partition heads in one pass over the global
     * dispatch prefix.  The old caller scanned that prefix once per matcher shard, which
     * multiplied Owner work by the number of shards while the window was full.
     *
     * <p>The returned array is caller-owned scratch storage and is cleared before use.  A
     * non-independent head remains the only candidate for its own partition and blocks all
     * later partitions.</p>
     */
    void collectPartitionDispatchHeads(long throughSequence, CommandSlot[] heads) {
        if (heads == null || heads.length != submissionHeads.length) {
            throw new IllegalArgumentException("partition dispatch head storage is invalid");
        }
        readyPartitionMask(throughSequence);
        for (int shard = 0; shard < heads.length; shard++) heads[shard] = readyPartitionHead(shard);
    }

    /** Marks a head already validated by collectPartitionDispatchHeads as dispatched. */
    void completePartitionDispatchKnown(long sequence, int shard) {
        int index = indexOf(sequence);
        if (index < 0 || settlementShards[index] != shard || settlementDispatched[index]) {
            throw new IllegalStateException("matcher settlement dispatch candidate is invalid");
        }
        settlementDispatched[index] = true;
        advanceDispatchedHead();
        dispatchRevision++;
    }

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
        settlementShards[index] = matcherShard;
        previousSubmissionSlots[index] = tailSlot;
        nextSubmissionSlots[index] = -1;
        if (tailSlot < 0) submissionHeads[matcherShard] = index;
        else nextSubmissionSlots[tailSlot] = index;
        submissionTails[matcherShard] = index;
        dispatchRevision++;
    }

    boolean isSubmissionHead(long sequence, int matcherShard) {
        int index = indexOf(sequence);
        return index >= 0 && submissionHeads[matcherShard] == index;
    }

    int submissionShard(long sequence) {
        int index = indexOf(sequence);
        return index < 0 ? -1 : submissionShards[index];
    }

    CommandSlot submissionHead(int matcherShard) {
        int index = submissionHeads[matcherShard];
        return index < 0 ? null : pendingAt(index);
    }

    /**
     * Returns the oldest command held behind a batch boundary.  Deferred metadata lives in the
     * reusable command slot, so this bounded ring walk replaces the old boxed LinkedHashMap.
     */
    CommandSlot firstDeferred() {
        for (int index = head; index >= 0; index = nextSlots[index]) {
            CommandSlot pending = pendingAt(index);
            if (pending != null && pending.deferredMatching()) return pending;
        }
        return null;
    }

    boolean hasDeferred() {
        return firstDeferred() != null;
    }

    int deferredCount() {
        int count = 0;
        for (int index = head; index >= 0; index = nextSlots[index]) {
            CommandSlot pending = pendingAt(index);
            if (pending != null && pending.deferredMatching()) count++;
        }
        return count;
    }

    void completeSubmission(long sequence) {
        int index = indexOf(sequence);
        if (index >= 0) removeFromSubmissionOrder(index);
    }

    CommandSlot findByCommandId(UUID commandId) {
        return commandId == null ? null : entriesByCommandId.get(commandId);
    }

    boolean hasUser(long userId) {
        return userId > 0 && pendingByUser.get(userId) > 0;
    }

    boolean hasEarlierUser(long sequence, long userId) {
        if (sequence <= 0 || userId <= 0 || pendingByUser.get(userId) <= 1) return false;
        for (int slot = head; slot != -1; slot = nextSlots[slot]) {
            CommandSlot pending = pendingAt(slot);
            if (pending == null || pending.sequence() >= sequence) return false;
            if (pending.command().header().userId() == userId) return true;
        }
        return false;
    }

    void forEach(Consumer<CommandSlot> consumer) {
        for (int slot = head; slot != -1; slot = nextSlots[slot]) {
            CommandSlot pending = pendingAt(slot);
            if (pending != null) consumer.accept(pending);
        }
    }

    Map<Long, CommandSlot> snapshot() {
        LinkedHashMap<Long, CommandSlot> snapshot = new LinkedHashMap<>(size);
        forEach(pending -> snapshot.put(pending.sequence(), pending));
        return Collections.unmodifiableMap(snapshot);
    }

    void clear() {
        while (size != 0) remove(firstSequence());
    }

    private void addIndexes(CommandSlot pending) {
        UUID commandId = pending.command().header().commandId();
        entriesByCommandId.put(commandId, pending);
        long userId = pending.command().header().userId();
        if (userId > 0) pendingByUser.addToValue(userId, 1);
    }

    private void removeIndexes(CommandSlot pending) {
        entriesByCommandId.remove(pending.command().header().commandId(), pending);
        long userId = pending.command().header().userId();
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

    private void requireAvailableCommandId(CommandSlot pending, CommandSlot replaced) {
        CommandSlot duplicate = entriesByCommandId.get(pending.command().header().commandId());
        if (duplicate != null && duplicate != replaced) {
            throw new IllegalStateException("duplicate pending matching commandId");
        }
    }

    private int indexOf(long sequence) {
        int index = slot(sequence);
        if (!linked(index)) return -1;
        CommandSlot pending = pendingAt(index);
        return pending != null && pending.sequence() == sequence ? index : -1;
    }

    private int slot(long sequence) {
        return (int) sequence & mask;
    }

    private boolean linked(int index) {
        return head == index || previousSlots[index] != -1 || nextSlots[index] != -1;
    }

    private CommandSlot pendingAt(int index) {
        CommandSlot context = contexts.contextAt(index);
        return context.coreSequence() == 0 || context.command() == null ? null : context;
    }

    int size() { return size; }
    boolean isEmpty() { return size == 0; }
    int capacity() { return contexts.capacity(); }
    CommandSlotRing contexts() { return contexts; }
}
