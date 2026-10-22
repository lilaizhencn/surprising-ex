package com.surprising.aeron.service.account;

import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核对订单客户别名反向索引的零键、重复添加、多别名升降级及整单退休，并检查指定原语表数组复用。
 */
class OrderClientKeyIndexTest {
    /**
     * 保留零别名关联并反复周转其他订单，要求单别名表底层数组对象不变且零别名仍能读出。
     */
    @Test void churnDoesNotRebuildTheSingleAliasTableOrLoseZeroAliases() throws Exception {
        // 当前测试独立创建的订单到客户别名反向索引。
        var index = new OrderClientKeyIndex();
        index.add(1, 0);
        // 读取单别名原语表的反射字段。
        var field = OrderClientKeyIndex.class.getDeclaredField("single");
        field.setAccessible(true);
        // 索引内部实际使用的单别名表，缺失值为 -1。
        var map = (org.agrona.collections.Long2LongHashMap) field.get(index);
        // 该原语映射底层 entries 数组的反射字段。
        var entries = map.getClass().getDeclaredField("entries");
        entries.setAccessible(true);
        // 周转前单别名表的底层数组对象，后续按对象身份检查复用。
        Object storage = entries.get(map);
        for (/* 本轮短暂新增又删除的订单编号和同值别名。 */ long id = 2; id < 20_000; id++) {
            index.add(id, id);
            index.remove(id, id);
        }
        assertThat(entries.get(map)).isSameAs(storage);
        assertThat(map.size()).isOne();
        assertThat(keys(index, 1)).containsExactly(0);
    }

    /**
     * 依次重复添加、扩为多别名、忽略不存在别名、降回单别名、整单删除及重新创建，核对其他订单不受影响。
     */
    @Test
    void handlesSingleAliasPromotionDemotionAndCompleteRetirement() {
        // 当前测试独立创建的订单到客户别名反向索引。
        OrderClientKeyIndex index = new OrderClientKeyIndex();
        index.add(11, 0);
        index.add(11, 0);
        index.add(12, 99);
        assertThat(keys(index, 11)).containsExactly(0);
        index.add(11, 91);
        index.add(11, 92);
        index.remove(11, 777);
        assertThat(keys(index, 11)).containsExactlyInAnyOrder(0, 91, 92);
        index.remove(11, 0);
        index.remove(11, 91);
        assertThat(keys(index, 11)).containsExactly(92);
        index.add(11, 93);
        index.remove(11);
        assertThat(keys(index, 11)).isEmpty();
        assertThat(keys(index, 12)).containsExactly(99);
        index.add(11, 94);
        index.remove(11, 93);
        assertThat(keys(index, 11)).containsExactly(94);
        index.remove(11, 94);
        assertThat(keys(index, 11)).isEmpty();
    }

    /**
     * 把一个订单当前所有别名收集成独立原语数组，供测试用有序或无序断言比较。
     * @param index 要遍历客户别名的反向索引
     * @param orderId 需要收集别名的订单编号
     */
    private static long[] keys(OrderClientKeyIndex index, long orderId) {
        // 单次收集当前订单别名的独立原语集合，避免暴露索引内部集合。
        LongHashSet keys = new LongHashSet();
        index.forEach(orderId, keys::add);
        return keys.toArray();
    }
}
