package com.surprising.aeron.service.execution;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.state.TradingDependencyMask;
import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import io.aeron.cluster.service.ClientSession;

/** Owner-only bounded in-flight response contexts. Dependency ownership lasts until a log-driven drain. */
final class ClusterCommandWindow {
    static final int CAPACITY = 64;
    private final Entry[] entries = new Entry[CAPACITY];
    private int head, size;
    /** 掩码每一位对应哪些物理窗口槽；仅跳过不相交项，精确依赖规则不变。 */
    private final long[] accountSlots = new long[64], symbolSlots = new long[64];
    /** 精确订单ID到物理窗口槽位；仅Owner写入，移出窗口立即删除，不保存业务状态。 */
    private final org.agrona.collections.Long2LongHashMap orderSlots =
            new org.agrona.collections.Long2LongHashMap(0);

    private static long intersectingSlots(long[] slots, long mask) {
        long result = 0;
        while (mask != 0) { int bit = Long.numberOfTrailingZeros(mask); mask &= mask - 1; result |= slots[bit]; }
        return result;
    }
    private static void indexSlots(long[] slots, long mask, long slot, boolean add) {
        while (mask != 0) {
            int bit = Long.numberOfTrailingZeros(mask); mask &= mask - 1;
            if (add) slots[bit] |= slot; else slots[bit] &= ~slot;
        }
    }
    private final long[] candidateOrders = new long[20];
    private final String[] candidateOrderSymbols = new String[20];
    private final CoreOrderSide[] candidateSides = new CoreOrderSide[20];
    private final long[] candidatePrices = new long[20];
    /** 对应范围的成交可能改变全币对持仓量，后续衍生品准入须读取完成后的值。 */
    private final boolean[] candidateOpenInterestChanges = new boolean[20];
    private long candidateUser;
    private ActiveOrderIndex participants;
    private CoreMessage decodedSource;
    private DecodedMatchingCommand decoded;
    private int candidateOrderCount, candidateScopeCount;
    long candidateAccounts, candidateSymbols;
    /** 所有潜在成交账户的物理分区，用于异分片结算派发而非改变资金归属。 */
    long candidateLanes;

    void resetCandidate(long userId) {
        java.util.Arrays.fill(candidateOrderSymbols, 0, candidateScopeCount, null);
        java.util.Arrays.fill(candidateSides, 0, candidateScopeCount, null);
        candidateUser = userId;
        candidateAccounts = userId == 0 ? 0 : TradingDependencyMask.account(userId);
        candidateSymbols = 0;
        candidateLanes = 0;
        candidateOrderCount = 0;
        candidateScopeCount = 0;
    }

    void candidateOrder(long orderId) {
        candidateOrder(orderId, null, null, 0);
    }

    void participants(ActiveOrderIndex index) { participants = index; }

    DecodedMatchingCommand decoded(CoreMessage request) {
        if (decodedSource != request) { decodedSource = request; decoded = null; }
        if (decoded == null) decoded = DecodedMatchingCommand.decode(request);
        return decoded;
    }

    DecodedMatchingCommand decodedIfPresent(CoreMessage request) {
        return decodedSource == request ? decoded : null;
    }

    void releaseDecoded() { decodedSource = null; decoded = null; }

    /** 只保留仍在入口队首等待的不可变命令解码结果，出队即释放。 */
    void retainDecoded(CoreMessage pendingHead) {
        if (pendingHead == null || decodedSource != pendingHead) releaseDecoded();
    }

    /** 已通过账户及撮合依赖检查的最大序号；结算可提前派发，提交仍逐条进行。 */
    long lastMatchingSequence() {
        for (int i = size - 1; i >= 0; i--) {
            long sequence = get(i).sequence;
            if (sequence != 0) return sequence;
        }
        return 0;
    }

    void candidateOrder(long orderId, String symbol, CoreOrderSide side, long price) {
        candidateOrder(orderId, symbol, side, price, false);
    }

    void candidateOrder(long orderId, String symbol, CoreOrderSide side, long price, boolean changesOpenInterest) {
        candidateOrders[candidateOrderCount++] = orderId;
        if (symbol == null) return;
        // Keep every order identity, but visit an identical matching range only once.
        for (int i = 0; i < candidateScopeCount; i++)
            if (side == candidateSides[i] && price == candidatePrices[i]
                    && symbol.equals(candidateOrderSymbols[i])) return;
        candidateOrderSymbols[candidateScopeCount] = symbol;
        candidateSides[candidateScopeCount] = side;
        candidateOpenInterestChanges[candidateScopeCount] = changesOpenInterest;
        candidatePrices[candidateScopeCount++] = price;
        candidateSymbols |= TradingDependencyMask.account(symbol.hashCode());
    }

    ClusterCommandWindow() {
        for (int i = 0; i < entries.length; i++) entries[i] = new Entry();
    }

    boolean conflicts() {
        return conflictingPrefixSize() != 0;
    }

    int conflictingPrefixSize() {
        long exactOrders = 0;
        for (int a = 0; a < candidateOrderCount; a++) exactOrders |= orderSlots.get(candidateOrders[a]);
        long candidates = Long.rotateRight(intersectingSlots(accountSlots, candidateAccounts)
                | intersectingSlots(symbolSlots, candidateSymbols) | exactOrders, head);
        exactOrders = Long.rotateRight(exactOrders, head);
        while (candidates != 0) {
            int i = 63 - Long.numberOfLeadingZeros(candidates);
            candidates &= ~(1L << i);
            Entry entry = get(i);
            if ((entry.symbols & candidateSymbols) != 0) {
                for (int a = 0; a < candidateScopeCount; a++)
                    for (int b = 0; b < entry.scopeCount; b++)
                        if (candidateOrderSymbols[a] != null
                                && candidateOrderSymbols[a].equals(entry.orderSymbols[b])
                                && (entry.openInterestChanges[b] || matchingRangesConflict(candidateSides[a], candidatePrices[a],
                                        entry.sides[b], entry.prices[b]))) return i + 1;
            }
            if ((entry.accounts & candidateAccounts) != 0 && accountsConflict(entry)) return i + 1;
            if ((exactOrders & (1L << i)) != 0) return i + 1;
        }
        return 0;
    }

    private static boolean matchingRangesConflict(CoreOrderSide incomingSide, long incomingPrice,
                                                  CoreOrderSide pendingSide, long pendingPrice) {
        // Cancel scopes do not describe their removed liquidity; keep their symbol fence.
        if (incomingSide == null || pendingSide == null) return true;
        if (incomingSide == pendingSide) return false;
        // A market order has no limiting price and can consume the preceding provisional order.
        if (incomingPrice <= 0 || pendingPrice <= 0) return true;
        return incomingSide == CoreOrderSide.BUY
                ? incomingPrice >= pendingPrice : pendingPrice >= incomingPrice;
    }

    private boolean accountsConflict(Entry entry) {
        if (candidateUser != 0 && candidateUser == entry.userId) return true;
        // Pending orders that can match each other fence above. Resting counterparties
        // remain in the owner index until commit, including for independent same-symbol orders.
        for (int a = 0; a < candidateScopeCount; a++) {
            if (candidateSides[a] == null) continue;
            if (participants.hasCounterparty(candidateOrderSymbols[a], candidateSides[a], candidatePrices[a], entry.userId))
                return true;
            for (int b = 0; b < entry.scopeCount; b++) {
                if (entry.sides[b] != null && participants.counterpartiesOverlap(
                        candidateOrderSymbols[a], candidateSides[a], candidatePrices[a],
                        entry.orderSymbols[b], entry.sides[b], entry.prices[b])) return true;
            }
        }
        for (int b = 0; b < entry.scopeCount; b++)
            if (entry.sides[b] != null && participants.hasCounterparty(
                    entry.orderSymbols[b], entry.sides[b], entry.prices[b], candidateUser)) return true;
        return false;
    }

    Entry add(ClientSession session, CoreMessage request, long timestamp, long position) {
        if (size == CAPACITY) throw new IllegalStateException("cluster command window is full");
        Entry entry = get(size++);
        entry.session = session;
        entry.request = request;
        entry.timestamp = timestamp;
        entry.position = position;
        entry.accounts = candidateAccounts;
        entry.userId = candidateUser;
        entry.symbols = candidateSymbols;
        entry.scopeCount = candidateScopeCount;
        long windowSlot = 1L << ((head + size - 1) & 63);
        indexSlots(accountSlots, entry.accounts, windowSlot, true);
        indexSlots(symbolSlots, entry.symbols, windowSlot, true);
        entry.orderCount = candidateOrderCount;
        for (int i = 0; i < candidateOrderCount; i++) {
            long orderId = candidateOrders[i];
            entry.orders[i] = orderId;
            orderSlots.put(orderId, orderSlots.get(orderId) | windowSlot);
        }
        System.arraycopy(candidateOrderSymbols, 0, entry.orderSymbols, 0, candidateScopeCount);
        System.arraycopy(candidateSides, 0, entry.sides, 0, candidateScopeCount);
        System.arraycopy(candidatePrices, 0, entry.prices, 0, candidateScopeCount);
        System.arraycopy(candidateOpenInterestChanges, 0, entry.openInterestChanges, 0, candidateScopeCount);
        return entry;
    }

    void complete(long sequence, CoreResponse response) {
        boolean found = false;
        for (int i = 0; i < size; i++) {
            Entry entry = get(i);
            if (entry.sequence == sequence) {
                if (entry.response != null) throw new IllegalStateException("duplicate pipeline completion");
                entry.response = response;
                found = true;
            }
        }
        if (!found) throw new IllegalStateException("pipeline completion has no request context");
    }

    int size() { return size; }
    Entry get(int index) { return entries[(head + index) & (CAPACITY - 1)]; }

    void clear() {
        removePrefix(size);
        resetCandidate(0);
        releaseDecoded();
    }

    void removePrefix(int count) {
        if (count < 0 || count > size) throw new IllegalArgumentException("invalid window prefix");
        for (int i = 0; i < count; i++) {
            Entry entry = get(i);
            long slot = 1L << ((head + i) & 63);
            indexSlots(accountSlots, entry.accounts, slot, false);
            indexSlots(symbolSlots, entry.symbols, slot, false);
            for (int k = 0; k < entry.orderCount; k++) {
                long id = entry.orders[k];
                long remaining = orderSlots.get(id) & ~slot;
                if (remaining == 0) orderSlots.remove(id); else orderSlots.put(id, remaining);
            }
            entry.orderCount = 0;
            entry.session = null;
            entry.request = null;
            entry.response = null;
            entry.sequence = entry.timestamp = entry.position = 0;
            entry.accounts = entry.symbols = 0;
            entry.userId = 0;
            java.util.Arrays.fill(entry.orderSymbols, 0, entry.scopeCount, null);
            java.util.Arrays.fill(entry.sides, 0, entry.scopeCount, null);
            entry.scopeCount = 0;
        }
        head = (head + count) & (CAPACITY - 1);
        size -= count;
    }

    static final class Entry {
        /** 本槽最多20个订单ID，只用于退窗时移除精确索引；[0,orderCount)有效。 */
        final long[] orders = new long[20];
        int orderCount;
        final String[] orderSymbols = new String[20];
        final CoreOrderSide[] sides = new CoreOrderSide[20];
        final long[] prices = new long[20];
        /** 每个在途范围是否可能改变后续准入所依赖的币对持仓量。 */
        final boolean[] openInterestChanges = new boolean[20];
        long userId;
        int scopeCount;
        long accounts, symbols;
        ClientSession session;
        CoreMessage request;
        CoreResponse response;
        long sequence, timestamp, position;
    }
}
