package com.surprising.aeron.service.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

/** 检查订单身份 FIFO、重复 ID、乱序 ID、扩容和客户号哈希碰撞。 */
class TerminalTombstoneStoreTest {
    @Test
    void boundedEvictionRetainsOnlyLatestOrders() {
        var store = new TerminalTombstoneStore();
        for (int i = 1; i <= 100_000; i++) {
            assertThat(store.contains(i)).isFalse();
            assertThat(store.putIfAbsent(i, 7, "client-" + i)).isTrue();
            store.trim(1280);
            if (i > 1280) assertThat(store.contains(i - 1280)).isFalse();
            assertThat(store.contains(i)).isTrue();
        }
        for (long i = 98_721; i <= 100_000; i++) assertThat(store.contains(i)).isTrue();
        assertThat(store.size()).isEqualTo(1280);
    }

    @Test
    void duplicateOrderKeepsOriginalIdentityAndFifoPosition() {
        var store = new TerminalTombstoneStore();
        store.putIfAbsent(7, 11, "original");
        store.putIfAbsent(8, 11, "next");
        assertThat(store.putIfAbsent(7, 12, "replacement")).isFalse();
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.containsClient(11, "original")).isTrue();
        assertThat(store.containsClient(12, "replacement")).isFalse();
        store.trim(1);
        assertThat(store.contains(7)).isFalse();
        assertThat(store.contains(8)).isTrue();
        assertThat(store.containsClient(11, "original")).isFalse();
        store.trim(0);
        assertThat(store.putIfAbsent(7, 12, "reused")).isTrue();
        assertThat(store.containsClient(12, "reused")).isTrue();
    }

    @Test
    void upperBoundNeverHidesOutOfOrderIdsAfterEviction() {
        var store = new TerminalTombstoneStore();
        store.putIfAbsent(Long.MAX_VALUE, 1, "highest");
        store.putIfAbsent(7, 1, "late");
        store.trim(1);
        assertThat(store.contains(Long.MAX_VALUE)).isFalse();
        assertThat(store.contains(7)).isTrue();
        assertThat(store.putIfAbsent(7, 2, "replacement")).isFalse();
        store.putIfAbsent(8, 1, "new");
        assertThat(store.contains(8)).isTrue();
        assertThat(store.size()).isEqualTo(2);
    }

    private record Value(long user, String client) { }

    @Test
    void growthWrapAndClientCollisionsMatchFifoReferenceBytes() throws Exception {
        var store = new TerminalTombstoneStore(128);
        var reference = new LinkedHashMap<Long, Value>();
        // 先超过初始容量触发扩容，再重复退窗，检查同用户、同客户号由多笔订单保留。
        for (long id = 1; id < 6000; id++) {
            long user = id % 11 + 1;
            String client = (id % 2 == 0 ? "Aa" : "BB") + id % 37;
            assertThat(store.putIfAbsent(id, user, client)).isTrue();
            reference.put(id, new Value(user, client));
            if (id % 29 == 0) {
                store.trim(1000);
                while (reference.size() > 1000) reference.pollFirstEntry();
                assertThat(bytes(store)).isEqualTo(bytes(reference));
                for (var entry : reference.entrySet()) {
                    assertThat(store.contains(entry.getKey())).isTrue();
                    assertThat(store.containsClient(entry.getValue().user(), entry.getValue().client())).isTrue();
                }
            }
        }
        byte[] snapshot = bytes(store);
        assertThat(snapshot).isEqualTo(bytes(reference));
        store.trim(0);
        assertThat(store.size()).isZero();
        for (var value : reference.values()) assertThat(store.containsClient(value.user(), value.client())).isFalse();
    }

    private static byte[] bytes(TerminalTombstoneStore store) throws Exception {
        var bytes = new ByteArrayOutputStream();
        store.write(new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    /** 按订单身份格式独立编码，避免用被测写入器生成预期值。 */
    private static byte[] bytes(LinkedHashMap<Long, Value> values) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(values.size());
        for (var entry : values.entrySet()) {
            out.writeLong(entry.getKey());
            out.writeLong(entry.getValue().user());
            byte[] text = entry.getValue().client().getBytes(StandardCharsets.UTF_8);
            out.writeInt(text.length);
            out.write(text);
        }
        return bytes.toByteArray();
    }
}
