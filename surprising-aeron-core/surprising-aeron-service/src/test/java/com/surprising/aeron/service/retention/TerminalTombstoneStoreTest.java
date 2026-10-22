package com.surprising.aeron.service.retention;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 对实际数组 FIFO 检查连续退窗、原位覆写、乱序 ID、类型隔离和哈希文本碰撞，并与有序 Map 的编码字节对照。测试名称中的扩容和探测链措辞按实际场景说明，不把名称当作覆盖证据。
 */
class TerminalTombstoneStoreTest {
    /**
     * 顺序写十万条四类身份并每次保留最新一千二百八十条，检查刚淘汰项不存在、当前项和最终区间可查；实现通过槽链解链，并非删除探测链压紧。
     */
    @Test void boundedEvictionRetainsOnlyLatestIdsAcrossManyProbeChainCompactions() {
        // 本场景实际数组终态存储
        var store = new TerminalTombstoneStore();
        for (/* 当前插入或核对的原始顺序计数 */ int i = 1; i <= 100000; i++) {
            assertThat(store.contains(i % 4, i)).isFalse();
            assertThat(store.putIfAbsent(i % 4, i, 7, "client-" + i, i)).isTrue();
            store.trim(1280);
            if (i > 1280) assertThat(store.contains((i - 1280) % 4, i - 1280)).isFalse();
            assertThat(store.contains(i % 4, i)).isTrue();
        }
        for (/* 当前插入或核对的原始顺序计数 */ long i = 98721; i <= 100000; i++) assertThat(store.contains((int)(i % 4), i)).isTrue();
        assertThat(store.size()).isEqualTo(1280);
    }

    /**
     * 覆写已有实体后旧客户号失效、新客户号可查，但 FIFO 位置不后移；裁剪后原实体被淘汰，数值相同的其他类型实体仍可独立使用。
     */
    @Test void overwritingAnEntityKeepsItsFifoPositionAndReplacesItsClientIndex() {
        // 本场景实际数组终态存储
        var store = new TerminalTombstoneStore();
        store.put(0, 7, 11, "old", 1);
        store.put(0, 8, 11, "next", 2);
        store.put(0, 7, 11, "replacement", 3);
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.containsClient(0, 11, "old")).isFalse();
        assertThat(store.containsClient(0, 11, "replacement")).isTrue();
        store.trim(1);
        assertThat(store.contains(0, 7)).isFalse();
        assertThat(store.contains(0, 8)).isTrue();
        assertThat(store.containsClient(0, 11, "replacement")).isFalse();
        store.trim(0);
        store.put(3, 7, 12, "reused", 4);
        assertThat(store.contains(3, 7)).isTrue();
        assertThat(store.contains(0, 7)).isFalse();
    }

    /**
     * 先写最大 long ID 再写较小乱序 ID，淘汰最大项；原实例与复制实例都须精确查到剩余项，且覆写和类型隔离不受保守上界影响。
     */
    @Test void upperBoundNeverHidesOutOfOrderIdsOrDuplicatesAfterEvictionAndCopy() {
        // 本场景实际数组终态存储
        var store = new TerminalTombstoneStore();
        store.put(0, Long.MAX_VALUE, 1, "highest", 1);
        store.put(0, 7, 1, "late", 2);
        store.put(3, 7, 1, "other-type", 3);
        store.trim(2);
        for (var /* 当前检查的原存储或独立副本 */ current : new TerminalTombstoneStore[]{store, store.copy()}) {
            assertThat(current.contains(0, Long.MAX_VALUE)).isFalse();
            assertThat(current.contains(0, 7)).isTrue();
            assertThat(current.contains(3, 7)).isTrue();
            current.put(0, 7, 1, "overwritten", 4);
            assertThat(current.size()).isEqualTo(2);
            current.put(0, 8, 1, "new", 5);
            assertThat(current.contains(0, 8)).isTrue();
            assertThat(current.contains(1, 8)).isFalse();
        }
    }

    /**
     * 参照 Map 的实体类型与 ID 组合键。
     * @param type 零到三的终态实体类别
     * @param id 该类别实体 ID
     */
    record Key(int type, long id) {}
    /**
     * 参照 Map 保存的用户、客户号和原提交序号。
     * @param user 当前终态用户 ID
     * @param client 完整客户号文本，碰撞场景仍须精确比较
     * @param sequence 该条终态原提交序号
     */
    record Value(long user, String client, long sequence) {}
    /**
     * 参照客户号映射使用的类型、用户和完整文本组合键。
     * @param type 零到三的终态实体类别
     * @param user 当前终态用户 ID
     * @param client 完整客户号文本，碰撞场景仍须精确比较
     */
    record Client(int type, long user, String client) {}

    /**
     * 以相同哈希的 Aa/BB 前缀制造客户文本碰撞，反复写入、覆写和退窗，核对有序编码及参照仍保留客户号的可查性；副本字节不随原实例清空改变。默认八千一百九十二槽与一千项裁剪预算并未强制触发扩容。
     */
    @Test void fifoGrowthWrapOverwriteAndClientCollisionsMatchOriginalMapBytes() throws Exception {
        // 本场景实际数组终态存储
        var store = new TerminalTombstoneStore();
        // 保留原插入顺序的实体参照 Map
        var reference = new LinkedHashMap<Key, Value>();
        // 参照场景记录的客户键映射，只核对其中仍保留键的存在性
        var clients = new HashMap<Client, Key>();
        for (/* 当前插入或核对的原始顺序计数 */ int i = 1; i < 6000; i++) {
            // 当前循环实体类别
            int type = i % 4; /* 循环产生的重复实体 ID，用来覆盖原位覆写 */ /* 循环生成的所属用户 ID */ long id = i % 913 + 1, user = i % 11 + 1;
            // Aa 与 BB 的 String 哈希相同，客户索引仍须按完整文本区分。
            // 含相同 String 哈希前缀的客户文本，内容仍不同
            String client = (i % 2 == 0 ? "Aa" : "BB") + i % 37;
            // 当前参照实体组合键
            var key = new Key(type, id); /* 当前写入参照 Map 的终态值 */ var value = new Value(user, client, i);
            store.put(type, id, user, client, i);
            reference.put(key, value); clients.put(new Client(type, user, client), key);
            if (i % 29 == 0) {
                // 当前退窗保留条数一千
                int maximum = 1000;
                store.trim(maximum);
                while (reference.size() > maximum) {
                    // 当前移除或核对的参照键值项
                    var entry = reference.pollFirstEntry();
                    clients.remove(new Client(entry.getKey().type(), entry.getValue().user(), entry.getValue().client()));
                }
                assertThat(bytes(store)).isEqualTo(bytes(reference));
                for (var /* 当前移除或核对的参照键值项 */ entry : reference.entrySet()) assertThat(store.contains(entry.getKey().type(), entry.getKey().id())).isTrue();
                for (var /* 当前移除或核对的参照键值项 */ entry : clients.keySet()) assertThat(store.containsClient(entry.type(), entry.user(), entry.client())).isTrue();
            }
        }
        // 清原存储前取得的独立 FIFO 副本
        var copy = store.copy(); /* 副本编码后的独立字节，原实例清空不能改变它 */ byte[] snapshot = bytes(copy);
        store.trim(0);
        assertThat(store.size()).isZero();
        assertThat(bytes(copy)).isEqualTo(snapshot);
        assertThat(snapshot).isEqualTo(bytes(reference));
    }
    /**
     * 调用真实存储的二进制输出，返回独立编码字节。
     * @param store 需要按真实格式编码的终态 FIFO
     */
    private static byte[] bytes(TerminalTombstoneStore store) throws Exception {
        // 收集当前二进制输出的独立字节流
        var bytes = new ByteArrayOutputStream(); store.write(new DataOutputStream(bytes)); return bytes.toByteArray();
    }
    /**
     * 按相同字段顺序编码有序 Map 参照，供逐字节比较，不调用实际存储编码。
     * @param values 按插入顺序编码的参照实体 Map
     */
    private static byte[] bytes(LinkedHashMap<Key,Value> values) throws Exception {
        // 收集当前二进制输出的独立字节流
        var bytes = new ByteArrayOutputStream(); /* 参照 Map 按协议字段顺序写入的二进制输出 */ var out = new DataOutputStream(bytes); out.writeInt(values.size());
        for (var /* 当前序列化的参照实体项 */ e : values.entrySet()) {
            out.writeByte(e.getKey().type()); out.writeLong(e.getKey().id()); out.writeLong(e.getValue().user()); out.writeLong(e.getValue().sequence());
            // 当前客户号的 UTF-8 编码字节
            byte[] text = e.getValue().client().getBytes(StandardCharsets.UTF_8); out.writeInt(text.length); out.write(text);
        }
        return bytes.toByteArray();
    }
}
