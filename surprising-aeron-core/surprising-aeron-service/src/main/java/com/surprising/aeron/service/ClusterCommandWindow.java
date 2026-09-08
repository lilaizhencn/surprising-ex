package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreResponse;
import io.aeron.cluster.service.ClientSession;

/** Owner-only bounded in-flight response contexts. Dependency ownership lasts until a log-driven drain. */
final class ClusterCommandWindow {
    static final int CAPACITY = 64;
    private final Entry[] entries = new Entry[CAPACITY];
    private int size;
    private long accounts, symbols, orders;
    long candidateAccounts, candidateSymbols, candidateOrders;

    ClusterCommandWindow() {
        for (int i = 0; i < entries.length; i++) entries[i] = new Entry();
    }

    boolean conflicts() {
        return (accounts & candidateAccounts) != 0 || (symbols & candidateSymbols) != 0
                || (orders & candidateOrders) != 0;
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
        orders |= candidateOrders;
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
        accounts = symbols = orders = 0;
    }

    static final class Entry {
        ClientSession session;
        CoreMessage request;
        CoreResponse response;
        long sequence, timestamp, position;
    }
}
