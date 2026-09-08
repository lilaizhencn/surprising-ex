package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreResponse;
import io.aeron.cluster.service.ClientSession;

/** Owner-only bounded in-flight response contexts. Dependency ownership lasts until a log-driven drain. */
final class ClusterCommandWindow {
    static final int CAPACITY = 64;
    private final Entry[] entries = new Entry[CAPACITY];
    private int size;
    private long accounts, symbols;
    private final org.eclipse.collections.impl.set.mutable.primitive.LongHashSet orders =
            new org.eclipse.collections.impl.set.mutable.primitive.LongHashSet(CAPACITY * 20);
    private final long[] candidateOrders = new long[20];
    private int candidateOrderCount;
    long candidateAccounts, candidateSymbols;

    void resetCandidate(long accountMask) {
        candidateAccounts = accountMask;
        candidateSymbols = 0;
        candidateOrderCount = 0;
    }

    void candidateOrder(long orderId) { candidateOrders[candidateOrderCount++] = orderId; }

    ClusterCommandWindow() {
        for (int i = 0; i < entries.length; i++) entries[i] = new Entry();
    }

    boolean conflicts() {
        if ((accounts & candidateAccounts) != 0 || (symbols & candidateSymbols) != 0) return true;
        for (int i = 0; i < candidateOrderCount; i++) if (orders.contains(candidateOrders[i])) return true;
        return false;
    }

    Entry add(ClientSession session, CoreMessage request, long timestamp, long position) {
        if (size == CAPACITY) throw new IllegalStateException("cluster command window is full");
        Entry entry = entries[size++];
        entry.session = session;
        entry.request = request;
        entry.timestamp = timestamp;
        entry.position = position;
        accounts |= candidateAccounts;
        symbols |= candidateSymbols;
        for (int i = 0; i < candidateOrderCount; i++) orders.add(candidateOrders[i]);
        return entry;
    }

    void complete(long sequence, CoreResponse response) {
        boolean found = false;
        for (int i = 0; i < size; i++) {
            Entry entry = entries[i];
            if (entry.sequence == sequence) {
                if (entry.response != null) throw new IllegalStateException("duplicate pipeline completion");
                entry.response = response;
                found = true;
            }
        }
        if (!found) throw new IllegalStateException("pipeline completion has no request context");
    }

    int size() { return size; }
    Entry get(int index) { return entries[index]; }

    void clear() {
        for (int i = 0; i < size; i++) {
            Entry entry = entries[i];
            entry.session = null;
            entry.request = null;
            entry.response = null;
            entry.sequence = entry.timestamp = entry.position = 0;
        }
        size = 0;
        accounts = symbols = 0;
        orders.clear();
    }

    static final class Entry {
        ClientSession session;
        CoreMessage request;
        CoreResponse response;
        long sequence, timestamp, position;
    }
}
