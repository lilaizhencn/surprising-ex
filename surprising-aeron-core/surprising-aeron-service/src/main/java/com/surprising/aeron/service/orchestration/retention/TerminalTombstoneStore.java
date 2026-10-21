package com.surprising.aeron.service.orchestration.retention;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Owner 独占的近期终态身份 FIFO。实体及客户号哈希链直接链接同一组数组槽，覆写实体保留原 FIFO 位置，淘汰按已知槽解链；同一客户号可由多个实体保留，查找优先命中最近挂入的匹配项。
 */
final class TerminalTombstoneStore {
    /** 快照协议四类终态身份的数量。 */
    private static final int ENTITY_TYPE_COUNT = 4;
    /** 每类曾新增 ID 的保守最大值，淘汰不降低，仅用于快速排除更大 ID。 */
    private final long[] maximumIndexedIds = new long[ENTITY_TYPE_COUNT];
    /** 默认初始 FIFO 槽数八千一百九十二。 */
    static final int DEFAULT_INITIAL_CAPACITY = 8192;
    /**
     * entityBuckets：实体哈希桶头，链接值为真实槽号加一，零为空。
     * entityNext：实体桶链中各槽的后继链接值。
     * entityPrevious：实体桶链中各槽的前驱链接值。
     */
    private int[] entityBuckets, entityNext, entityPrevious;
    /** 各实体槽所属桶号，解链后为负一。 */
    private int[] entityBucketBySlot;
    /**
     * head：有效 FIFO 的环形头下标。
     * size：当前有效终态实体数。
     */
    private int head, size;
    /**
     * ids：各 FIFO 槽的实体 ID。
     * users：各 FIFO 槽的用户 ID。
     * sequences：各 FIFO 槽保存的原提交或导出序号。
     */
    private long[] ids, users, sequences;
    /** 各 FIFO 槽的四类实体代码。 */
    private int[] types;
    /** 各有效槽保存的客户号原文本，淘汰清引用，不做文本归一化。 */
    private String[] clients;
    /**
     * buckets：客户号哈希桶头，零为空。
     * next：客户链中各槽后继的槽号加一。
     */
    private int[] buckets, next;
    /**
     * clientBucketBySlot：已索引客户槽的桶号，未入链为负一。
     * clientPrevious：客户链中各槽前驱的槽号加一，零为无前驱。
     */
    private int[] clientBucketBySlot, clientPrevious;
    /** 当前槽是否挂在客户索引中，非空客户号的多个实体可以同时为真。 */
    private boolean[] indexed;

    /**
     * 使用默认八千一百九十二槽建立终态存储。
     */
    TerminalTombstoneStore() {
        this(DEFAULT_INITIAL_CAPACITY);
    }

    /**
     * 从至少一百二十八槽向上取二次幂容量，建立 FIFO 数组与两套索引，空链接用零、未入桶位置用负一；本入口不另行拒绝非正初始建议值。
     * @param initialCapacity 初始建议槽数，实际至少一百二十八并向上取二次幂
     */
    TerminalTombstoneStore(int initialCapacity) {
        // 本次构造或扩容采用的二次幂 FIFO 容量
        int capacity = 128;
        while (capacity < initialCapacity) capacity = Math.multiplyExact(capacity, 2);
        entityBuckets = new int[capacity * 2];
        entityNext = new int[capacity];
        entityPrevious = new int[capacity];
        entityBucketBySlot = new int[capacity];
        java.util.Arrays.fill(entityBucketBySlot, -1);
        ids = new long[capacity];
        users = new long[capacity];
        sequences = new long[capacity];
        types = new int[capacity];
        clients = new String[capacity];
        buckets = new int[capacity * 2];
        next = new int[capacity];
        clientBucketBySlot = new int[capacity];
        java.util.Arrays.fill(clientBucketBySlot, -1);
        clientPrevious = new int[capacity];
        indexed = new boolean[capacity];
    }

    /**
     * 读取当前保留的终态实体数，不是已分配槽数。
     */
    int size() { return size; }
    /**
     * 按合法实体类型和 ID 查精确实体索引，不读取客户号或用户归属。
     * @param type 零到三的合法实体类别，查询入口依赖调用方保证
     * @param id 当前类别的实体 ID，写入必须为正
     */
    boolean contains(int type, long id) { return entitySlot(type, id) >= 0; }
    /**
     * 按类型、用户及客户号查客户索引链，多个匹配项存在时仍只返回是否命中。
     * @param type 零到三的合法实体类别，查询入口依赖调用方保证
     * @param user 客户号所属用户，写入必须为正
     * @param client 非空客户号文本，空字符串表示不建立客户索引
     */
    boolean containsClient(int type, long user, String client) { return clientSlot(type, user, client) >= 0; }

    /**
     * 校验类型后探测已有实体；有则原位改元数据并重新挂客户链，无则追加 FIFO。覆写不把实体移到队尾。
     * @param type 零到三的合法实体类别，查询入口依赖调用方保证
     * @param id 当前类别的实体 ID，写入必须为正
     * @param user 客户号所属用户，写入必须为正
     * @param client 非空客户号文本，空字符串表示不建立客户索引
     * @param sequence 写入终态身份的正提交或导出序号
     */
    void put(int type, long id, long user, String client, long sequence) {
        if (type < 0 || type >= ENTITY_TYPE_COUNT)
            throw new IllegalArgumentException("invalid retained terminal entity");
        putAt(entitySlot(type, id), type, id, user, client, sequence);
    }

    /**
     * 校验类型并只探测一次；已有同类型 ID 立即返回假，不改原元数据，也不继续校验新用户、客户号和序号。新实体才进入完整字段校验并返回真。
     * @param type 零到三的合法实体类别，查询入口依赖调用方保证
     * @param id 当前类别的实体 ID，写入必须为正
     * @param user 客户号所属用户，写入必须为正
     * @param client 非空客户号文本，空字符串表示不建立客户索引
     * @param sequence 写入终态身份的正提交或导出序号
     */
    boolean putIfAbsent(int type, long id, long user, String client, long sequence) {
        if (type < 0 || type >= ENTITY_TYPE_COUNT)
            throw new IllegalArgumentException("invalid retained terminal entity");
        // 单次探测到的可空实体位置，负一表示尚未保留
        int existing = entitySlot(type, id);
        if (existing >= 0) return false;
        putAt(-1, type, id, user, client, sequence);
        return true;
    }

    /**
     * 校验实体、用户、序号及非空客户文本，容量满且新增时扩容；已有槽先脱旧客户链，写字段后接新客户链，新增实体另更新保守最大 ID 和实体链。
     * @param existing 已有实体槽号，负一表示新增
     * @param type 零到三的合法实体类别，查询入口依赖调用方保证
     * @param id 当前类别的实体 ID，写入必须为正
     * @param user 客户号所属用户，写入必须为正
     * @param client 非空客户号文本，空字符串表示不建立客户索引
     * @param sequence 写入终态身份的正提交或导出序号
     */
    private void putAt(int existing, int type, long id, long user, String client, long sequence) {
        if (type < 0 || type >= ENTITY_TYPE_COUNT || id <= 0 || user <= 0 || sequence <= 0 || client == null)
            throw new IllegalArgumentException("invalid retained terminal entity");
        if (existing < 0 && size == ids.length) grow();
        // 当前需要写入、查询、复制或输出的真实 FIFO 槽下标
        int slot = existing >= 0 ? existing : (head + size++) & (ids.length - 1);
        if (existing >= 0) unlinkClientSlot(slot);
        ids[slot] = id; users[slot] = user; sequences[slot] = sequence; types[slot] = type; clients[slot] = client;
        if (existing < 0) {
            maximumIndexedIds[type] = Math.max(maximumIndexedIds[type], id);
            indexEntity(slot);
        }
        indexClient(slot);
    }

    /**
     * 调用方提供非负上限，逐个移除最早 FIFO 实体及客户索引并清客户号引用，直到数量满足；不缩数组、不降低最大 ID 上界，也不另校验负上限。
     * @param maximum 调用方保证非负的目标保留条数
     */
    void trim(int maximum) {
        while (size > maximum) {
            unlinkEntity(head);
            unlinkClientSlot(head);
            clients[head] = null;
            indexed[head] = false;
            next[head] = 0;
            head = (head + 1) & (ids.length - 1);
            size--;
        }
    }

    /**
     * 混合实体类别与 ID，计算实体哈希桶号。
     * @param type 零到三的合法实体类别，查询入口依赖调用方保证
     * @param id 当前类别的实体 ID，写入必须为正
     */
    private int entityBucket(int type, long id) {
        return org.agrona.collections.Hashing.hash(id ^ (type * 0x9e3779b97f4a7c15L), entityBuckets.length - 1);
    }

    /**
     * 大于该类历史最大 ID 直接未命中，其余走精确桶链；保守上界不要求 ID 按顺序到达。
     * @param type 零到三的合法实体类别，查询入口依赖调用方保证
     * @param id 当前类别的实体 ID，写入必须为正
     */
    private int entitySlot(int type, long id) {
        if (id > maximumIndexedIds[type]) return -1;
        for (/* 桶链当前链接值，真实槽号等于它减一，零表示链尾 */ int link = entityBuckets[entityBucket(type, id)]; link != 0; link = entityNext[link - 1]) {
            // 当前需要写入、查询、复制或输出的真实 FIFO 槽下标
            int slot = link - 1;
            if (types[slot] == type && ids[slot] == id) return slot;
        }
        return -1;
    }

    /**
     * 把新实体槽挂在实体桶头，同时记录桶号和前后链接，链接值为真实槽号加一。
     * @param slot 当前已知且有效的 FIFO 数组槽下标
     */
    private void indexEntity(int slot) {
        // 当前实体或客户号对应的哈希桶号
        int bucket = entityBucket(types[slot], ids[slot]);
        entityBucketBySlot[slot] = bucket;
        // 原实体桶头的链接值，不是未加一的真实数组下标
        int nextSlot = entityBuckets[bucket];
        entityPrevious[slot] = 0;
        entityNext[slot] = nextSlot;
        if (nextSlot != 0) entityPrevious[nextSlot - 1] = slot + 1;
        entityBuckets[bucket] = slot + 1;
    }

    /**
     * 对已知实体槽直接修改前后链接和桶头，再清其索引元数据，不搬移其他 FIFO 值。
     * @param slot 当前已知且有效的 FIFO 数组槽下标
     */
    private void unlinkEntity(int slot) {
        // 当前待移除槽的前驱链接值，零表示桶头
        // 当前槽后继链接值，零表示链尾
        int previous = entityPrevious[slot], following = entityNext[slot];
        if (previous == 0) entityBuckets[entityBucketBySlot[slot]] = following;
        else entityNext[previous - 1] = following;
        if (following != 0) entityPrevious[following - 1] = previous;
        entityPrevious[slot] = entityNext[slot] = 0;
        entityBucketBySlot[slot] = -1;
    }

    /**
     * 按类型、用户与客户号完整文本计算客户桶，不把哈希相同当作身份相同。
     * @param type 零到三的合法实体类别，查询入口依赖调用方保证
     * @param user 客户号所属用户，写入必须为正
     * @param client 非空客户号文本，空字符串表示不建立客户索引
     */
    private int bucket(int type, long user, String client) {
        // 客户身份完整字段混合得到的中间哈希值
        int hash = 31 * (31 * type + Long.hashCode(user)) + client.hashCode();
        hash ^= hash >>> 16;
        return hash & (buckets.length - 1);
    }
    /**
     * 遍历客户桶链并逐项精确比较类型、用户和文本，返回第一个匹配槽或负一。
     * @param type 零到三的合法实体类别，查询入口依赖调用方保证
     * @param user 客户号所属用户，写入必须为正
     * @param client 非空客户号文本，空字符串表示不建立客户索引
     */
    private int clientSlot(int type, long user, String client) {
        for (/* 桶链当前链接值，真实槽号等于它减一，零表示链尾 */ int link = buckets[bucket(type, user, client)]; link != 0; link = next[link - 1]) {
            // 当前需要写入、查询、复制或输出的真实 FIFO 槽下标
            int slot = link - 1;
            if (types[slot] == type && users[slot] == user && clients[slot].equals(client)) return slot;
        }
        return -1;
    }
    /**
     * 该槽确实在客户链中才解链；利用已保存桶和前驱直接移除，不误删其他相同客户号实体。
     * @param slot 当前已知且有效的 FIFO 数组槽下标
     */
    private void unlinkClientSlot(int slot) {
        if (!indexed[slot]) return;
        // 当前实体或客户号对应的哈希桶号
        int bucket = clientBucketBySlot[slot];
        // 当前待移除槽的前驱链接值，零表示桶头
        int previous = clientPrevious[slot];
        // 当前槽后继链接值，零表示链尾
        int following = next[slot];
        if (previous == 0) buckets[bucket] = following;
        else next[previous - 1] = following;
        if (following != 0) clientPrevious[following - 1] = previous;
        next[slot] = 0;
        clientPrevious[slot] = 0;
        clientBucketBySlot[slot] = -1;
        indexed[slot] = false;
    }
    /**
     * 空客户号不入客户索引；非空直接挂到桶头并标记已索引。新槽尚未挂链，覆写路径已经先解链，因此无需再次查找或删除其他同客户号项。
     * @param slot 当前已知且有效的 FIFO 数组槽下标
     */
    private void indexClient(int slot) {
        if (clients[slot].isEmpty()) {
            clientBucketBySlot[slot] = -1;
            clientPrevious[slot] = 0;
            indexed[slot] = false;
            return;
        }
        // 当前实体或客户号对应的哈希桶号
        int bucket = bucket(types[slot], users[slot], clients[slot]);
        // 新 FIFO 槽尚未在客户链中，覆写路径也已经先解链。
        // 这里直接挂桶头，不为每个新增终态再次遍历客户桶。
        // 当前槽后继链接值，零表示链尾
        int following = buckets[bucket];
        clientBucketBySlot[slot] = bucket;
        clientPrevious[slot] = 0;
        next[slot] = following;
        if (following != 0) clientPrevious[following - 1] = slot + 1;
        buckets[bucket] = slot + 1;
        indexed[slot] = true;
    }
    /**
     * FIFO 达到历史容量时翻倍，把当前有效项按原 FIFO 顺序放到新数组开头并重建两套索引；保留计数与最大 ID 上界，实际保留上限仍由调用方 trim 控制。
     */
    private void grow() {
        // 本次构造或扩容采用的二次幂 FIFO 容量
        int capacity = Math.multiplyExact(ids.length, 2);
        // 扩容后的原提交序号数组
        // 扩容后的实体 ID 数组
        // 扩容后的用户 ID 数组
        long[] newIds = new long[capacity], newUsers = new long[capacity], newSequences = new long[capacity];
        // 扩容后的实体类别数组
        int[] newTypes = new int[capacity]; /* 扩容后按 FIFO 重排的客户号引用数组 */ String[] newClients = new String[capacity];
        // 扩容后仍需重挂客户链的标记数组
        boolean[] newIndexed = new boolean[capacity];
        for (/* 当前有效 FIFO 项的相对顺序下标 */ int i = 0; i < size; i++) {
            // 当前需要写入、查询、复制或输出的真实 FIFO 槽下标
            int slot = (head + i) & (ids.length - 1);
            newIds[i] = ids[slot]; newUsers[i] = users[slot]; newSequences[i] = sequences[slot];
            newTypes[i] = types[slot]; newClients[i] = clients[slot]; newIndexed[i] = indexed[slot];
        }
        ids = newIds; users = newUsers; sequences = newSequences; types = newTypes; clients = newClients;
        indexed = newIndexed; next = new int[capacity];
        clientBucketBySlot = new int[capacity];
        java.util.Arrays.fill(clientBucketBySlot, -1);
        clientPrevious = new int[capacity];
        buckets = new int[capacity * 2]; head = 0;
        entityBuckets = new int[capacity * 2];
        entityNext = new int[capacity];
        entityPrevious = new int[capacity];
        entityBucketBySlot = new int[capacity];
        java.util.Arrays.fill(entityBucketBySlot, -1);
        for (/* 当前有效 FIFO 项的相对顺序下标 */ int i = 0; i < size; i++) {
            indexEntity(i);
            if (indexed[i]) indexClient(i);
        }
    }
    /**
     * 按当前 FIFO 顺序创建独立数组和索引的副本；客户号字符串可共享，历史空槽、数组容量与最大 ID 上界不要求逐值复制。
     */
    TerminalTombstoneStore copy() {
        // 按当前有效内容独立构建的终态存储副本
        TerminalTombstoneStore copy = new TerminalTombstoneStore(Math.max(DEFAULT_INITIAL_CAPACITY, size));
        for (/* 当前有效 FIFO 项的相对顺序下标 */ int i = 0; i < size; i++) {
            // 当前需要写入、查询、复制或输出的真实 FIFO 槽下标
            int slot = (head + i) & (ids.length - 1);
            copy.put(types[slot], ids[slot], users[slot], clients[slot], sequences[slot]);
        }
        return copy;
    }
    /**
     * 按当前 FIFO 顺序写条数、实体类别、身份、用户、提交序号和 UTF-8 客户号；不在这里写版本或另外验证客户号字节上限。
     * @param out 当前终态段二进制输出流
     */
    void write(DataOutputStream out) throws IOException {
        out.writeInt(size);
        for (/* 当前有效 FIFO 项的相对顺序下标 */ int i = 0; i < size; i++) {
            // 当前需要写入、查询、复制或输出的真实 FIFO 槽下标
            int slot = (head + i) & (ids.length - 1);
            out.writeByte(types[slot]); out.writeLong(ids[slot]); out.writeLong(users[slot]); out.writeLong(sequences[slot]);
            // 当前客户号编码后的 UTF-8 字节数组
            byte[] text = clients[slot].getBytes(StandardCharsets.UTF_8);
            out.writeInt(text.length); out.write(text);
        }
    }
}
