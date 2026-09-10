package com.surprising.aeron.service.execution;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;

/** Owner独占的终态FIFO；实体及客户号索引直接引用槽位，稳定负载插入/淘汰不创建键或节点。 */
final class TerminalTombstoneStore {
    /** 按实体类型隔离ID空间，值为FIFO物理槽位加一。 */
    private final LongIntHashMap[] entities = {new LongIntHashMap(), new LongIntHashMap(),
            new LongIntHashMap(), new LongIntHashMap()};
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
    boolean contains(int type, long id) { return entities[type].containsKey(id); }
    boolean containsClient(int type, long user, String client) { return clientSlot(type, user, client) >= 0; }

    void put(int type, long id, long user, String client, long sequence) {
        if (type < 0 || type >= entities.length || id <= 0 || user <= 0 || sequence <= 0 || client == null)
            throw new IllegalArgumentException("invalid retained terminal entity");
        int existing = entities[type].get(id) - 1;
        if (existing < 0 && size == ids.length) grow();
        int slot = existing >= 0 ? existing : (head + size++) & (ids.length - 1);
        if (existing >= 0) unlinkClient(types[slot], users[slot], clients[slot]);
        ids[slot] = id; users[slot] = user; sequences[slot] = sequence; types[slot] = type; clients[slot] = client;
        entities[type].put(id, slot + 1);
        indexClient(slot);
    }

    void trim(int maximum) {
        while (size > maximum) {
            entities[types[head]].removeKey(ids[head]);
            unlinkClient(types[head], users[head], clients[head]);
            clients[head] = null;
            indexed[head] = false;
            next[head] = 0;
            head = (head + 1) & (ids.length - 1);
            size--;
        }
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
        for (var index : entities) index.clear();
        for (int i = 0; i < size; i++) {
            entities[types[i]].put(ids[i], i + 1);
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
