package com.surprising.aeron.service.retention;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Owner 独占的近期终态订单身份 FIFO。 */
final class CompletedOrderStore {
    private long maximumIndexedId;
    static final int DEFAULT_INITIAL_CAPACITY = 8192;
    private int[] entityBuckets, entityNext, entityPrevious;
    private int[] entityBucketBySlot;
    private int head, size;
    private long[] ids, users;
    private String[] clients;
    private int[] buckets, next;
    private int[] clientBucketBySlot, clientPrevious;

    /** 使用默认八千一百九十二槽建立终态存储。 */
    CompletedOrderStore() {
        this(DEFAULT_INITIAL_CAPACITY);
    }

    /** 从至少一百二十八槽向上取二次幂容量，建立 FIFO 数组与两套索引，空链接用零、未入桶位置用负一。 */
    CompletedOrderStore(int initialCapacity) {
        int capacity = 128;
        while (capacity < initialCapacity) capacity = Math.multiplyExact(capacity, 2);
        entityBuckets = new int[capacity * 2];
        entityNext = new int[capacity];
        entityPrevious = new int[capacity];
        entityBucketBySlot = new int[capacity];
        java.util.Arrays.fill(entityBucketBySlot, -1);
        ids = new long[capacity];
        users = new long[capacity];

        clients = new String[capacity];
        buckets = new int[capacity * 2];
        next = new int[capacity];
        clientBucketBySlot = new int[capacity];
        java.util.Arrays.fill(clientBucketBySlot, -1);
        clientPrevious = new int[capacity];
    }

    /** 读取当前保留的终态实体数，不是已分配槽数。 */
    int size() { return size; }
    /** 按订单 ID 查精确身份索引，不读取客户号或用户归属。 */
    boolean contains(long id) { return entitySlot(id) >= 0; }
    /** 按用户及客户号查客户索引链，多个匹配项存在时仍只返回是否命中。 */
    boolean containsClient(long user, String client) { return clientSlot(user, client) >= 0; }

    /** 只追加新订单身份。 */
    boolean putIfAbsent(long id, long user, String client) {
        if (entitySlot(id) >= 0) return false;
        if (id <= 0 || user <= 0 || client == null)
            throw new IllegalArgumentException("invalid retained terminal order");
        if (size == ids.length) grow();
        int slot = (head + size++) & (ids.length - 1);
        ids[slot] = id;
        users[slot] = user;
        clients[slot] = client;
        maximumIndexedId = Math.max(maximumIndexedId, id);
        indexEntity(slot);
        indexClient(slot);
        return true;
    }

    /** 调用方提供非负上限，逐个移除最早 FIFO 实体及客户索引并清客户号引用，直到数量满足。 */
    void trim(int maximum) {
        while (size > maximum) {
            unlinkEntity(head);
            unlinkClientSlot(head);
            clients[head] = null;

            next[head] = 0;
            head = (head + 1) & (ids.length - 1);
            size--;
        }
    }

    private int entityBucket(long id) {
        return org.agrona.collections.Hashing.hash(id, entityBuckets.length - 1);
    }

    /** 保守上界不要求 ID 按顺序到达。 */
    private int entitySlot(long id) {
        if (id > maximumIndexedId) return -1;
        for (int link = entityBuckets[entityBucket(id)]; link != 0; link = entityNext[link - 1]) {
            int slot = link - 1;
            if (ids[slot] == id) return slot;
        }
        return -1;
    }

    private void indexEntity(int slot) {
        int bucket = entityBucket(ids[slot]);
        entityBucketBySlot[slot] = bucket;
        int nextSlot = entityBuckets[bucket];
        entityPrevious[slot] = 0;
        entityNext[slot] = nextSlot;
        if (nextSlot != 0) entityPrevious[nextSlot - 1] = slot + 1;
        entityBuckets[bucket] = slot + 1;
    }

    private void unlinkEntity(int slot) {
        int previous = entityPrevious[slot], following = entityNext[slot];
        if (previous == 0) entityBuckets[entityBucketBySlot[slot]] = following;
        else entityNext[previous - 1] = following;
        if (following != 0) entityPrevious[following - 1] = previous;
        entityPrevious[slot] = entityNext[slot] = 0;
        entityBucketBySlot[slot] = -1;
    }

    private int bucket(long user, String client) {
        int hash = 31 * Long.hashCode(user) + client.hashCode();
        hash ^= hash >>> 16;
        return hash & (buckets.length - 1);
    }
    private int clientSlot(long user, String client) {
        for (int link = buckets[bucket(user, client)]; link != 0; link = next[link - 1]) {
            int slot = link - 1;
            if (users[slot] == user && clients[slot].equals(client)) return slot;
        }
        return -1;
    }
    private void unlinkClientSlot(int slot) {
        if (clientBucketBySlot[slot] < 0) return;
        int bucket = clientBucketBySlot[slot];
        int previous = clientPrevious[slot];
        int following = next[slot];
        if (previous == 0) buckets[bucket] = following;
        else next[previous - 1] = following;
        if (following != 0) clientPrevious[following - 1] = previous;
        next[slot] = 0;
        clientPrevious[slot] = 0;
        clientBucketBySlot[slot] = -1;
    }
    private void indexClient(int slot) {
        if (clients[slot].isEmpty()) {
            clientBucketBySlot[slot] = -1;
            clientPrevious[slot] = 0;

            return;
        }
        int bucket = bucket(users[slot], clients[slot]);
        int following = buckets[bucket];
        clientBucketBySlot[slot] = bucket;
        clientPrevious[slot] = 0;
        next[slot] = following;
        if (following != 0) clientPrevious[following - 1] = slot + 1;
        buckets[bucket] = slot + 1;
    }
    /** FIFO 达到历史容量时翻倍，把当前有效项按原 FIFO 顺序放到新数组开头并重建两套索引。 */
    private void grow() {
        int capacity = Math.multiplyExact(ids.length, 2);
        long[] newIds = new long[capacity], newUsers = new long[capacity];
        String[] newClients = new String[capacity];
        for (int i = 0; i < size; i++) {
            int slot = (head + i) & (ids.length - 1);
            newIds[i] = ids[slot]; newUsers[i] = users[slot];
            newClients[i] = clients[slot];
        }
        ids = newIds; users = newUsers; clients = newClients;
        next = new int[capacity];
        clientBucketBySlot = new int[capacity];
        java.util.Arrays.fill(clientBucketBySlot, -1);
        clientPrevious = new int[capacity];
        buckets = new int[capacity * 2]; head = 0;
        entityBuckets = new int[capacity * 2];
        entityNext = new int[capacity];
        entityPrevious = new int[capacity];
        entityBucketBySlot = new int[capacity];
        java.util.Arrays.fill(entityBucketBySlot, -1);
        for (int i = 0; i < size; i++) {
            indexEntity(i);
            indexClient(i);
        }
    }

    /** 按当前 FIFO 顺序写条数、订单身份、用户和 UTF-8 客户号。 */
    void write(DataOutputStream out) throws IOException {
        out.writeInt(size);
        for (int i = 0; i < size; i++) {
            int slot = (head + i) & (ids.length - 1);
            out.writeLong(ids[slot]); out.writeLong(users[slot]);
            byte[] text = clients[slot].getBytes(StandardCharsets.UTF_8);
            out.writeInt(text.length); out.write(text);
        }
    }
}
