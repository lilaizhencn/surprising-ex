package com.surprising.aeron.service.state;

import java.util.Arrays;
import java.util.Map;
import org.agrona.collections.Long2LongHashMap;

/** Client identities and their references belong to one Account Lane; deleted rows are reusable. */
final class LaneClientIdentities {
    private final AccountLaneState lane;
    private final Long2LongHashMap rowsByKey = new Long2LongHashMap(16, 0.65f, 0);
    private long[] keys = new long[16];
    private long[] users = new long[16];
    private long[] references = new long[16];
    private String[] names = new String[16];
    private int[] freeRows = new int[16];
    private int freeCount;
    private int nextRow;
    /** Monitoring only: Owner never reads the primitive map or any identity row. */
    private volatile int publishedCount;

    LaneClientIdentities(AccountLaneState lane) { this.lane = lane; }

    private void assertOwner() { if (lane != null) lane.assertOwner(); }

    int publishedCount() { return publishedCount; }

    long prepare(long key, long userId, String name, boolean retain) {
        assertOwner();
        int row = row(key);
        if (row >= 0) {
            requireIdentity(row, userId, name);
            if (retain) references[row] = Math.incrementExact(references[row]);
            return key;
        }
        insert(key, userId, name, 1);
        return -key;
    }

    long find(long key, long userId, String name) {
        assertOwner();
        int row = row(key);
        return row >= 0 && users[row] == userId && names[row].equals(name) ? key : 0;
    }

    String name(long key, long userId) {
        assertOwner();
        int row = row(key);
        if (row < 0 || users[row] != userId)
            throw new IllegalArgumentException("unknown runtime client key: " + userId + '/' + key);
        return names[row];
    }

    void rollback(long key, long userId, String name) {
        assertOwner();
        int row = row(key);
        if (row < 0) throw new IllegalStateException("deterministic client identity collision");
        requireIdentity(row, userId, name);
        releaseRow(key, row);
    }

    void release(long key, long userId) {
        assertOwner();
        int row = row(key);
        if (row >= 0 && users[row] == userId) releaseRow(key, row);
    }

    private void requireIdentity(int row, long userId, String name) {
        if (users[row] != userId || !names[row].equals(name))
            throw new IllegalStateException("deterministic client identity collision");
    }

    private int row(long key) { return (int) rowsByKey.get(key) - 1; }

    private void insert(long key, long userId, String name, long count) {
        if (key <= 0 || userId <= 0 || name == null || name.isBlank() || count <= 0)
            throw new IllegalArgumentException("invalid client identity");
        if (rowsByKey.containsKey(key)) throw new IllegalStateException("duplicate imported client identity");
        int row = freeCount == 0 ? nextRow : freeRows[freeCount - 1];
        if (row == keys.length) {
            int capacity = Math.multiplyExact(keys.length, 2);
            keys = Arrays.copyOf(keys, capacity);
            users = Arrays.copyOf(users, capacity);
            references = Arrays.copyOf(references, capacity);
            names = Arrays.copyOf(names, capacity);
            freeRows = Arrays.copyOf(freeRows, capacity);
        }
        rowsByKey.put(key, row + 1L);
        if (freeCount == 0) nextRow++; else freeCount--;
        keys[row] = key;
        users[row] = userId;
        names[row] = name;
        references[row] = count;
        publishedCount++;
    }

    private void releaseRow(long key, int row) {
        if (references[row] <= 0) throw new IllegalStateException("client identity released twice");
        if (--references[row] != 0) return;
        rowsByKey.remove(key);
        keys[row] = users[row] = 0;
        names[row] = null;
        freeRows[freeCount++] = row;
        publishedCount--;
    }

    /** Cold import keeps the stored deterministic key; no rehash or new dictionary allocation. */
    void importIdentity(long key, long userId, String name) {
        assertOwner();
        insert(key, userId, name, 1);
    }

    /** Cold rebind copies into unpublished target tables; the registry clears sources only after all imports succeed. */
    void copyTo(TradingRuntimeState target) {
        assertOwner();
        for (int row = 0; row < nextRow; row++) {
            if (references[row] == 0) continue;
            AccountLaneState destination = target.accountLanes[target.topology().accountLaneId(users[row])];
            destination.assertOwner();
            destination.clientIdentities.insert(keys[row], users[row], names[row], references[row]);
        }
    }

    void clear() {
        assertOwner();
        rowsByKey.clear();
        Arrays.fill(keys, 0, nextRow, 0);
        Arrays.fill(users, 0, nextRow, 0);
        Arrays.fill(references, 0, nextRow, 0);
        Arrays.fill(names, 0, nextRow, null);
        nextRow = freeCount = publishedCount = 0;
    }

    void exportTo(Map<RuntimeIdentityRegistry.ClientIdentity, Long> target) {
        for (int row = 0; row < nextRow; row++)
            if (references[row] != 0)
                target.put(new RuntimeIdentityRegistry.ClientIdentity(users[row], names[row]), keys[row]);
    }
}
