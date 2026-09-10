package com.surprising.aeron.service.execution;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Owner独占的终态FIFO；实体及客户号索引直接引用槽位，稳定负载插入/淘汰不创建键或节点。 */
final class TerminalTombstoneStore {
    /** 快照协议中的四类终态实体，编号与原独立索引一致。 */
    private static final int ENTITY_TYPE_COUNT = 4;
    /** 实体索引直接链接 FIFO 槽，避免复制订单 ID 或删除时移动哈希探测链。 */
    private int[] entityBuckets = new int[256], entityNext = new int[128], entityPrevious = new int[128];
    /** FIFO有效区间；容量不足仅在高水位增长，完成序号后由调用方裁剪保留窗口。 */
    private int head, size;
    private long[] ids = new long[128], users = new long[128], sequences = new long[128];
    private int[] types = new int[128];
    /** 原始客户号引用；淘汰即释放，快照复制不与运行实例共享数组。 */
    private String[] clients = new String[128];
    /** 客户号哈希桶及槽位链，下标加一为链接值，零为空。 */
    private int[] buckets = new int[256], next = new int[128];
    /** 同客户号覆盖时只索引最后一项，保持原Map put/remove语义。 */
    private boolean[] indexed = new boolean[128];

    int size() { return size; }
    boolean contains(int type, long id) { return entitySlot(type, id) >= 0; }
    boolean containsClient(int type, long user, String client) { return clientSlot(type, user, client) >= 0; }

    void put(int type, long id, long user, String client, long sequence) {
        if (type < 0 || type >= ENTITY_TYPE_COUNT || id <= 0 || user <= 0 || sequence <= 0 || client == null)
            throw new IllegalArgumentException("invalid retained terminal entity");
        int existing = entitySlot(type, id);
        if (existing < 0 && size == ids.length) grow();
        int slot = existing >= 0 ? existing : (head + size++) & (ids.length - 1);
        if (existing >= 0) unlinkClient(types[slot], users[slot], clients[slot]);
        ids[slot] = id; users[slot] = user; sequences[slot] = sequence; types[slot] = type; clients[slot] = client;
        if (existing < 0) indexEntity(slot);
        indexClient(slot);
    }

    void trim(int maximum) {
        while (size > maximum) {
            unlinkEntity(head);
            unlinkClient(types[head], users[head], clients[head]);
            clients[head] = null;
            indexed[head] = false;
            next[head] = 0;
            head = (head + 1) & (ids.length - 1);
            size--;
        }
    }

    private int entityBucket(int type, long id) {
        return org.agrona.collections.Hashing.hash(id ^ (type * 0x9e3779b97f4a7c15L), entityBuckets.length - 1);
    }

    private int entitySlot(int type, long id) {
        for (int link = entityBuckets[entityBucket(type, id)]; link != 0; link = entityNext[link - 1]) {
            int slot = link - 1;
            if (types[slot] == type && ids[slot] == id) return slot;
        }
        return -1;
    }

    private void indexEntity(int slot) {
        int bucket = entityBucket(types[slot], ids[slot]);
        int nextSlot = entityBuckets[bucket];
        entityPrevious[slot] = 0;
        entityNext[slot] = nextSlot;
        if (nextSlot != 0) entityPrevious[nextSlot - 1] = slot + 1;
        entityBuckets[bucket] = slot + 1;
    }

    /** 淘汰已经知道 FIFO 槽位，直接解链，不再查找实体或搬动其他实体。 */
    private void unlinkEntity(int slot) {
        int previous = entityPrevious[slot], following = entityNext[slot];
        if (previous == 0) entityBuckets[entityBucket(types[slot], ids[slot])] = following;
        else entityNext[previous - 1] = following;
        if (following != 0) entityPrevious[following - 1] = previous;
        entityPrevious[slot] = entityNext[slot] = 0;
    }

    private int bucket(int type, long user, String client) {
        int hash = 31 * (31 * type + Long.hashCode(user)) + client.hashCode();
        hash ^= hash >>> 16;
        return hash & (buckets.length - 1);
    }
    private int clientSlot(int type, long user, String client) {
        for (int link = buckets[bucket(type, user, client)]; link != 0; link = next[link - 1]) {
            int slot = link - 1;
            if (types[slot] == type && users[slot] == user && clients[slot].equals(client)) return slot;
        }
        return -1;
    }
    private void unlinkClient(int type, long user, String client) {
        if (client == null || client.isEmpty()) return;
        int bucket = bucket(type, user, client), previous = 0;
        for (int link = buckets[bucket]; link != 0; link = next[link - 1]) {
            int slot = link - 1;
            if (types[slot] == type && users[slot] == user && clients[slot].equals(client)) {
                if (previous == 0) buckets[bucket] = next[slot]; else next[previous - 1] = next[slot];
                next[slot] = 0; indexed[slot] = false;
                return;
            }
            previous = link;
        }
    }
    private void indexClient(int slot) {
        if (clients[slot].isEmpty()) return;
        unlinkClient(types[slot], users[slot], clients[slot]);
        int bucket = bucket(types[slot], users[slot], clients[slot]);
        next[slot] = buckets[bucket]; buckets[bucket] = slot + 1; indexed[slot] = true;
    }
    private void grow() {
        int capacity = Math.multiplyExact(ids.length, 2);
        long[] newIds = new long[capacity], newUsers = new long[capacity], newSequences = new long[capacity];
        int[] newTypes = new int[capacity]; String[] newClients = new String[capacity];
        boolean[] newIndexed = new boolean[capacity];
        for (int i = 0; i < size; i++) {
            int slot = (head + i) & (ids.length - 1);
            newIds[i] = ids[slot]; newUsers[i] = users[slot]; newSequences[i] = sequences[slot];
            newTypes[i] = types[slot]; newClients[i] = clients[slot]; newIndexed[i] = indexed[slot];
        }
        ids = newIds; users = newUsers; sequences = newSequences; types = newTypes; clients = newClients;
        indexed = newIndexed; next = new int[capacity]; buckets = new int[capacity * 2]; head = 0;
        entityBuckets = new int[capacity * 2];
        entityNext = new int[capacity];
        entityPrevious = new int[capacity];
        for (int i = 0; i < size; i++) {
            indexEntity(i);
            if (indexed[i]) { int b = bucket(types[i], users[i], clients[i]); next[i] = buckets[b]; buckets[b] = i + 1; }
        }
    }
    TerminalTombstoneStore copy() {
        TerminalTombstoneStore copy = new TerminalTombstoneStore();
        for (int i = 0; i < size; i++) {
            int slot = (head + i) & (ids.length - 1);
            copy.put(types[slot], ids[slot], users[slot], clients[slot], sequences[slot]);
        }
        return copy;
    }
    void write(DataOutputStream out) throws IOException {
        out.writeInt(size);
        for (int i = 0; i < size; i++) {
            int slot = (head + i) & (ids.length - 1);
            out.writeByte(types[slot]); out.writeLong(ids[slot]); out.writeLong(users[slot]); out.writeLong(sequences[slot]);
            byte[] text = clients[slot].getBytes(StandardCharsets.UTF_8);
            out.writeInt(text.length); out.write(text);
        }
    }
}
