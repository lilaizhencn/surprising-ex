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
    private long candidateOrderMask;
    long candidateAccounts, candidateSymbols;

    void resetCandidate(long userId) {
        java.util.Arrays.fill(candidateOrderSymbols, 0, candidateScopeCount, null);
        java.util.Arrays.fill(candidateSides, 0, candidateScopeCount, null);
        candidateUser = userId;
        candidateAccounts = userId == 0 ? 0 : TradingDependencyMask.account(userId);
        candidateSymbols = 0;
        candidateOrderCount = 0;
        candidateScopeCount = 0;
        candidateOrderMask = 0;
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

    void candidateOrder(long orderId, String symbol, CoreOrderSide side, long price) {
        candidateOrder(orderId, symbol, side, price, false);
    }

    void candidateOrder(long orderId, String symbol, CoreOrderSide side, long price, boolean changesOpenInterest) {
        candidateOrders[candidateOrderCount++] = orderId;
        candidateOrderMask |= TradingDependencyMask.account(orderId);
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
        for (int i = size - 1; i >= 0; i--) {
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
            if ((entry.orderMask & candidateOrderMask) == 0) continue;
            for (int a = 0; a < candidateOrderCount; a++) {
                long orderId = candidateOrders[a];
                int slot = TradingDependencyMask.partition(orderId);
                while (entry.orders[slot] != 0) {
                    if (entry.orders[slot] == orderId) return i + 1;
                    slot = (slot + 1) & 63;
                }
            }
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
        entry.orderMask = candidateOrderMask;
        for (int i = 0; i < candidateOrderCount; i++) {
            long orderId = candidateOrders[i];
            int slot = TradingDependencyMask.partition(orderId);
            while (entry.orders[slot] != 0 && entry.orders[slot] != orderId) slot = (slot + 1) & 63;
            entry.orders[slot] = orderId;
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
            entry.session = null;
            entry.request = null;
            entry.response = null;
            entry.sequence = entry.timestamp = entry.position = 0;
            entry.accounts = entry.symbols = entry.orderMask = 0;
            entry.userId = 0;
            java.util.Arrays.fill(entry.orders, 0);
            java.util.Arrays.fill(entry.orderSymbols, 0, entry.scopeCount, null);
            java.util.Arrays.fill(entry.sides, 0, entry.scopeCount, null);
            entry.scopeCount = 0;
        }
        head = (head + count) & (CAPACITY - 1);
        size -= count;
    }

    static final class Entry {
        // Protocol order IDs are positive. A 64-slot table holds at most 20 identities;
        // zero is empty, and the entire owner-owned table is reused with its window entry.
        final long[] orders = new long[64];
        final String[] orderSymbols = new String[20];
        final CoreOrderSide[] sides = new CoreOrderSide[20];
        final long[] prices = new long[20];
        /** 每个在途范围是否可能改变后续准入所依赖的币对持仓量。 */
        final boolean[] openInterestChanges = new boolean[20];
        long userId;
        int scopeCount;
        long accounts, symbols, orderMask;
        ClientSession session;
        CoreMessage request;
        CoreResponse response;
        long sequence, timestamp, position;
    }
}
