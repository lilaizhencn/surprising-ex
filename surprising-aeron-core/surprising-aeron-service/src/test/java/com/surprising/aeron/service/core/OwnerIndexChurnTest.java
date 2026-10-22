package com.surprising.aeron.service.core;

import org.agrona.collections.Long2LongHashMap;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通过反射检查 pendingReservationUsers 一个 Owner 待预留用户映射，反复插删后是否复用原底层数组；不覆盖全部 Owner 索引，也不是性能压测。
 */
class OwnerIndexChurnTest {
    /**
     * 对待预留用户映射每轮插入再删除三十二个键，共遍历八千一百九十二个键，核对删除值、缺键零值和最终空表，并比较底层 entries 数组对象仍为原引用。反射异常直接使测试失败。
     */
    @Test
    void ownerIndexesKeepTheirStorageAcrossBoundedInsertDeleteChurn() throws Exception {
        try (/* 未启动账户或账本工作线程的默认测试运行时，离开资源作用域关闭 */ var state = new TradingAccountRuntime()) {
            for (String /* 当前被检查字段名，原数组只包含 pendingReservationUsers 这一项 */ name : new String[]{"pendingReservationUsers"}) {
                // 字段所在对象，本用例实际为运行时的 pendingReservations 容器
                Object target = name.equals("pendingReservationUsers") ? state.pendingReservations : state;
                // 通过反射取得的待预留用户映射字段，设置可访问后读取
                var field = target.getClass().getDeclaredField(name);
                field.setAccessible(true);
                // 原 Agrona long 到 long 待预留用户映射，直接插删以检查存储复用
                var map = (Long2LongHashMap) field.get(target);
                // 该映射底层 entries 数组的反射字段
                var storage = Long2LongHashMap.class.getDeclaredField("entries");
                storage.setAccessible(true);
                // 插删前保存的原数组引用，测试末比较对象身份
                Object before = storage.get(map);
                for (/* 本轮三十二个新键的起始值，每轮前移三十二 */ long base = 1; base < 8192; base += 32) {
                    for (/* 当前插入或删除的 long 键，对应存入值为 key 加一 */ long key = base; key < base + 32; key++) map.put(key, key + 1);
                    for (/* 当前插入或删除的 long 键，对应存入值为 key 加一 */ long key = base; key < base + 32; key++) {
                        assertThat(map.remove(key)).isEqualTo(key + 1);
                        assertThat(map.get(key)).isZero();
                    }
                }
                assertThat(map.isEmpty()).isTrue();
                assertThat(storage.get(map)).as(name + " backing storage").isSameAs(before);
            }
        }
    }
}
