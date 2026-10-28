package com.surprising.aeron.service.core.change;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核对变化缓冲的首次插入顺序、覆盖、null 删除、极值键、扩容及跨命令清理；反射只用于验证引用释放，不是全链路分配采样。
 */
class RuntimeChangeBufferTest {
    /**
     * 以长短交替前缀登记 null 预留删除，部分轮次冻结、部分不物化，要求清理都不越过发布槽长度并移除旧索引。
     */
    @Test
    void largeInlineReservationDeletesClearAcrossFrozenAndLiveReuse() {
        // 当前测试独立使用的普通变化缓冲或预留专用缓冲。
        var buffer = new RuntimeReservationChangeBuffer();
        for (int /* 本轮长短变化的有效条目数量，部分数量超过初始发布槽容量。 */ count : new int[]{0, 1, 8, 9, 256, 1024, 3}) {
            for (/* 本轮加入或核对的连续变化下标。 */ int index = 0; index < count; index++) buffer.putLive(index + 1, null);
            assertThat(buffer.size()).isEqualTo(count);
            if (count == 8 || count == 256) buffer.freezeValues();
            buffer.clear();
            assertThat(buffer.isEmpty()).isTrue();
            for (/* 本轮加入或核对的连续变化下标。 */ int index = 0; index < count; index++)
                assertThat(buffer.containsKey(index + 1)).isFalse();
        }
    }

    /**
     * 通过消费者接收每个键一次，要求 null 删除通知不丢失、源索引和引用清理完整，并保留零键的存在性语义。
     */
    @Test
    void drainPreservesNullDeletesAndOverwritesAndReleasesReferencesAcrossReuse() throws Exception {
        // 当前测试独立使用的普通变化缓冲或预留专用缓冲。
        var buffer = new RuntimeChangeBuffer<String>();
        // 记录消费者收到的键和值，包含显式 null 删除通知。
        var published = new java.util.HashMap<Long, String>();
        for (/* 当前清理和复用的测试轮次。 */ int round = 0; round < 64; round++) {
            for (/* 本轮随机、极值或连续变化使用的 long 业务键。 */ long key = -64; key < 64; key++) buffer.put(key, "value" + round);
            buffer.put(0, "updated");
            buffer.put(1, null);
            // 回调可修改的访问次数槽，重复键覆盖后仍只访问一次。
            int[] visits = {0};
            buffer.drainTo((/* 本轮随机、极值或连续变化使用的 long 业务键。 */ key, /* 当前随机生成或消费者收到的业务值，允许 null。 */ value) -> { visits[0]++; published.put(key, value); });
            assertThat(visits[0]).isEqualTo(128);
            assertThat(published).containsEntry(0L, "updated").containsEntry(1L, null);
            assertThat(buffer.isEmpty()).isTrue();
            assertThat(buffer.indexOf(0)).isEqualTo(-1);
            // 读取基础值引用数组的反射字段。
            var field = RuntimeChangeBuffer.class.getDeclaredField("values");
            field.setAccessible(true);
            assertThat((Object[]) field.get(buffer)).containsOnlyNulls();
        }
        buffer.put(0, null);
        assertThat(buffer.indexOf(0)).isGreaterThanOrEqualTo(0);
        assertThat(buffer.valueAt(buffer.indexOf(0))).isNull();
        buffer.clear();
        assertThat(buffer.containsKey(0)).isFalse();
    }
    /**
     * 用固定随机种子与 LinkedHashMap 对照多轮扩容和覆盖，检查零、负值、long 极值键及连续槽首次插入顺序一致。
     */
    @Test
    void matchesInsertionOrderedMapAcrossGrowthOverwriteNullAndReuse() {
        // 正在与插入有序标准 Map 对照的变化缓冲。
        var actual = new RuntimeChangeBuffer<String>();
        // 保持首次插入顺序的标准 Map 参照。
        var expected = new java.util.LinkedHashMap<Long, String>();
        // 使用固定种子创建的伪随机键生成器，使测试可重复。
        var random = new java.util.Random(25620);
        for (/* 当前清理和复用的测试轮次。 */ int round = 0; round < 4; round++) {
            for (long /* 本轮随机、极值或连续变化使用的 long 业务键。 */ key : new long[]{0, Long.MIN_VALUE, Long.MAX_VALUE, -1}) {
                actual.put(key, null);
                expected.put(key, null);
            }
            for (/* 本轮随机更新的顺序下标。 */ int i = 0; i < 4096; i++) {
                // 本轮随机、极值或连续变化使用的 long 业务键。
                long key = random.nextInt(2048) - 1024;
                // 当前随机生成或消费者收到的业务值，允许 null。
                String value = i % 3 == 0 ? null : "value" + i;
                // put 返回的连续槽或当前参照遍历期望的下标。
                int slot = actual.put(key, value);
                expected.put(key, value);
                assertThat(actual.keyAt(slot)).isEqualTo(key);
                assertThat(actual.valueAt(slot)).isEqualTo(value);
            }
            assertThat(actual.size()).isEqualTo(expected.size());
            // put 返回的连续槽或当前参照遍历期望的下标。
            int slot = 0;
            for (var /* 参照 Map 按首次插入顺序遍历到的键值项。 */ entry : expected.entrySet()) {
                assertThat(actual.indexOf(entry.getKey())).isEqualTo(slot);
                assertThat(actual.keyAt(slot)).isEqualTo(entry.getKey());
                assertThat(actual.valueAt(slot++)).isEqualTo(entry.getValue());
            }
            actual.clear();
            for (long /* 本轮随机、极值或连续变化使用的 long 业务键。 */ key : expected.keySet()) assertThat(actual.containsKey(key)).isFalse();
            expected.clear();
        }
    }

}
