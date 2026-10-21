package com.surprising.aeron.service.orchestration.trade;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.service.command.order.DecodedMatchingCommand;
import com.surprising.aeron.service.command.order.ResolvedMatchingAdmission;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;

public final class PendingMatchingRing {
    private final int[] nextSlots;
    private final int[] previousSlots;
    private final int[] submissionShards;
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
    /** Owner-only FIFO cursor for money not yet offered; response retirement keeps its own head. */
    private int ledgerDispatchHead = -1;
    private int size;
    /** Owner 调度进度标记：队列变化或异步完成后重新尝试派发。 */
    private long dispatchRevision;
    public long dispatchRevision() { return dispatchRevision; }

    /** Completion and admission notifications wake the Owner to resume the ordered command and financial cursors. */
    public void progressChanged() { dispatchRevision++; }

    public PendingMatchingRing(int requestedCapacity, int matcherShardCount, int laneCount) {
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
            progressChanged();
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
        if (ledgerDispatchHead == -1) ledgerDispatchHead = index;
        addIndexes(pending);
        size++;
        progressChanged();
    }

    CommandSlot acquire(long sequence, CommandSlot.Operation operation, CoreMessage command,
                            com.surprising.aeron.protocol.CommandFingerprint fingerprint,
                            java.util.List<Long> preMatchingCancellationOrderIds,
                            com.surprising.aeron.service.state.ledger.RuntimeFundsDelta fundsDelta,
                            DecodedMatchingCommand decodedCommand, ResolvedMatchingAdmission admission) {
        CommandSlot context = contexts.claim(sequence);
        CommandSlot pending = context.initialize(sequence, operation, command, fingerprint,
                preMatchingCancellationOrderIds,
                fundsDelta, decodedCommand, admission);
        if (context != pending) throw new IllegalStateException("command must use its claimed slot");
        return pending;
    }

    CommandSlot acquire(long sequence, CommandSlot.Operation operation, CoreMessage command,
                        com.surprising.aeron.protocol.CommandFingerprint fingerprint,
                        java.util.List<Long> preMatchingCancellationOrderIds,
                        com.surprising.aeron.service.state.ledger.RuntimeFundsAccumulator fundsAccumulator,
                        DecodedMatchingCommand decodedCommand, ResolvedMatchingAdmission admission) {
        CommandSlot context = contexts.claim(sequence);
        CommandSlot pending = context.initialize(sequence, operation, command, fingerprint,
                preMatchingCancellationOrderIds, fundsAccumulator, decodedCommand, admission);
        if (context != pending) throw new IllegalStateException("command must use its claimed slot");
        return pending;
    }

    public CommandSlot get(long sequence) {
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

    public boolean contains(long sequence) {
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
            CommandSlot context = contexts.required(sequence);
            if (context.complete()) contexts.release(sequence);
            else contexts.discard(sequence);
        }
        return removed;
    }

    public long firstSequence() {
        return head == -1 ? 0 : pendingAt(head).sequence();
    }

    /** 返回有序队首；完成状态由提交器直接读取 slot，不再维护第二个 ready 位。 */
    public CommandSlot head() { return head < 0 ? null : pendingAt(head); }

    public CommandSlot ledgerDispatchHead() {
        return ledgerDispatchHead < 0 ? null : pendingAt(ledgerDispatchHead);
    }

    /** Move only after the existing Ledger mailbox accepted this command's entire money fact. */
    public void completeLedgerDispatch(long sequence) {
        if (ledgerDispatchHead < 0 || pendingAt(ledgerDispatchHead).sequence() != sequence)
            throw new IllegalStateException("Ledger settlement dispatch is out of order");
        ledgerDispatchHead = nextSlots[ledgerDispatchHead];
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
        previousSubmissionSlots[index] = tailSlot;
        nextSubmissionSlots[index] = -1;
        if (tailSlot < 0) submissionHeads[matcherShard] = index;
        else nextSubmissionSlots[tailSlot] = index;
        submissionTails[matcherShard] = index;
        progressChanged();
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

    public boolean hasDeferred() {
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

    public CommandSlot findByCommandId(long msb, long lsb) {
        return entriesByCommandId.get(msb, lsb);
    }

    public CommandSlot findByCommandId(UUID commandId) {
        return commandId == null ? null : entriesByCommandId.get(commandId);
    }

    public boolean hasUser(long userId) {
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

    public Map<Long, CommandSlot> snapshot() {
        LinkedHashMap<Long, CommandSlot> snapshot = new LinkedHashMap<>(size);
        forEach(pending -> snapshot.put(pending.sequence(), pending));
        return Collections.unmodifiableMap(snapshot);
    }

    public void clear() {
        while (size != 0) remove(firstSequence());
    }

    private void addIndexes(CommandSlot pending) {
        var header = pending.command().header();
        entriesByCommandId.put(header.commandIdMsb(), header.commandIdLsb(), pending);
        long userId = header.userId();
        if (userId > 0) pendingByUser.addToValue(userId, 1);
    }

    private void removeIndexes(CommandSlot pending) {
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

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }
    public int capacity() { return contexts.capacity(); }
    public CommandSlotRing contexts() { return contexts; }

    public CommandSlot required(long sequence) { return contexts.required(sequence); }
    public boolean claimed(long sequence) { return contexts.claimed(sequence); }
    CommandSlot claim(long sequence) { return contexts.claim(sequence); }
    void release(long sequence) { contexts.release(sequence); }
    void discard(long sequence) { contexts.discard(sequence); }
    public int inFlight() { return contexts.inFlight(); }
    public int highWaterMark() { return contexts.highWaterMark(); }
    CommandSlot contextAt(int slot) { return contexts.contextAt(slot); }
}
