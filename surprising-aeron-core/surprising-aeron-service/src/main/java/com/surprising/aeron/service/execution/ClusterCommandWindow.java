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
    private long candidateUser;
    private ActiveOrderIndex participants;
    private CoreMessage decodedSource;
    private DecodedMatchingCommand decoded;
    private int candidateOrderCount;
    private long candidateOrderMask;
    long candidateAccounts, candidateSymbols;

    void resetCandidate(long userId) {
        java.util.Arrays.fill(candidateOrderSymbols, 0, candidateOrderCount, null);
        java.util.Arrays.fill(candidateSides, 0, candidateOrderCount, null);
        candidateUser = userId;
        candidateAccounts = userId == 0 ? 0 : TradingDependencyMask.account(userId);
        candidateSymbols = 0;
        candidateOrderCount = 0;
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
        candidateOrders[candidateOrderCount] = orderId;
        candidateOrderSymbols[candidateOrderCount] = symbol;
        candidateSides[candidateOrderCount] = side;
        candidatePrices[candidateOrderCount++] = price;
        if (symbol != null) candidateSymbols |= TradingDependencyMask.account(symbol.hashCode());
        candidateOrderMask |= com.surprising.aeron.service.state.TradingDependencyMask.account(orderId);
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
                for (int a = 0; a < candidateOrderCount; a++)
                    for (int b = 0; b < entry.orderCount; b++)
                        if (candidateOrderSymbols[a] != null
                                && candidateOrderSymbols[a].equals(entry.orderSymbols[b])) return i + 1;
            }
            if ((entry.accounts & candidateAccounts) != 0 && accountsConflict(entry)) return i + 1;
            if ((entry.orderMask & candidateOrderMask) == 0) continue;
            for (int a = 0; a < candidateOrderCount; a++)
                for (int b = 0; b < entry.orderCount; b++)
                    if (candidateOrders[a] == entry.orders[b]) return i + 1;
        }
        return 0;
    }

    private boolean accountsConflict(Entry entry) {
        if (candidateUser != 0 && candidateUser == entry.userId) return true;
        // Same-symbol commands already fence above. Until a prefix commits, its resting
        // counterparties remain in the owner index; no order-book copy is needed here.
        for (int a = 0; a < candidateOrderCount; a++) {
            if (candidateSides[a] == null) continue;
            if (participants.hasCounterparty(candidateOrderSymbols[a], candidateSides[a], candidatePrices[a], entry.userId))
                return true;
            for (int b = 0; b < entry.orderCount; b++) {
                if (entry.sides[b] != null && participants.counterpartiesOverlap(
                        candidateOrderSymbols[a], candidateSides[a], candidatePrices[a],
                        entry.orderSymbols[b], entry.sides[b], entry.prices[b])) return true;
            }
        }
        for (int b = 0; b < entry.orderCount; b++)
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
        entry.orderCount = candidateOrderCount;
        entry.orderMask = candidateOrderMask;
        System.arraycopy(candidateOrders, 0, entry.orders, 0, candidateOrderCount);
        System.arraycopy(candidateOrderSymbols, 0, entry.orderSymbols, 0, candidateOrderCount);
        System.arraycopy(candidateSides, 0, entry.sides, 0, candidateOrderCount);
        System.arraycopy(candidatePrices, 0, entry.prices, 0, candidateOrderCount);
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
            java.util.Arrays.fill(entry.orderSymbols, 0, entry.orderCount, null);
            java.util.Arrays.fill(entry.sides, 0, entry.orderCount, null);
            entry.orderCount = 0;
        }
        head = (head + count) & (CAPACITY - 1);
        size -= count;
    }

    static final class Entry {
        final long[] orders = new long[20];
        final String[] orderSymbols = new String[20];
        final CoreOrderSide[] sides = new CoreOrderSide[20];
        final long[] prices = new long[20];
        long userId;
        int orderCount;
        long accounts, symbols, orderMask;
        ClientSession session;
        CoreMessage request;
        CoreResponse response;
        long sequence, timestamp, position;
    }
}
