package com.surprising.aeron.service.execution;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreResponse;
import io.aeron.cluster.service.ClientSession;

/** Owner-only bounded in-flight response contexts. Dependency ownership lasts until a log-driven drain. */
final class ClusterCommandWindow {
    static final int CAPACITY = 64;
    private final Entry[] entries = new Entry[CAPACITY];
    private int head, size;
    private final long[] candidateOrders = new long[20];
    private int candidateOrderCount;
    private long candidateOrderMask;
    long candidateAccounts, candidateSymbols;

    void resetCandidate(long accountMask) {
        candidateAccounts = accountMask;
        candidateSymbols = 0;
        candidateOrderCount = 0;
        candidateOrderMask = 0;
    }

    void candidateOrder(long orderId) {
        candidateOrders[candidateOrderCount++] = orderId;
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
            if ((entry.accounts & candidateAccounts) != 0 || (entry.symbols & candidateSymbols) != 0) return i + 1;
            if ((entry.orderMask & candidateOrderMask) == 0) continue;
            for (int a = 0; a < candidateOrderCount; a++)
                for (int b = 0; b < entry.orderCount; b++)
                    if (candidateOrders[a] == entry.orders[b]) return i + 1;
        }
        return 0;
    }

    Entry add(ClientSession session, CoreMessage request, long timestamp, long position) {
        if (size == CAPACITY) throw new IllegalStateException("cluster command window is full");
        Entry entry = get(size++);
        entry.session = session;
        entry.request = request;
        entry.timestamp = timestamp;
        entry.position = position;
        entry.accounts = candidateAccounts;
        entry.symbols = candidateSymbols;
        entry.orderCount = candidateOrderCount;
        entry.orderMask = candidateOrderMask;
        System.arraycopy(candidateOrders, 0, entry.orders, 0, candidateOrderCount);
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
            entry.orderCount = 0;
        }
        head = (head + count) & (CAPACITY - 1);
        size -= count;
    }

    static final class Entry {
        final long[] orders = new long[20];
        int orderCount;
        long accounts, symbols, orderMask;
        ClientSession session;
        CoreMessage request;
        CoreResponse response;
        long sequence, timestamp, position;
    }
}
