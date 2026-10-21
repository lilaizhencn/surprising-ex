package com.surprising.aeron.service.state.account;

import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.eclipse.collections.api.block.procedure.primitive.LongProcedure;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/**
 * 账户 Lane 拥有的订单到客户身份别名反向索引。一个别名直接保存在原语表，出现多个不同别名才创建集合；调用方保证串行访问及键非负，零仍是合法别名。
 */
public final class OrderClientKeyIndex {
    // 客户别名键由调用方保证非负，零仍是有效别名。
    /** 订单到唯一别名的原语映射，-1 表示缺失，因此客户别名零无需特殊排除。 */
    private final Long2LongHashMap single = new Long2LongHashMap(-1);
    /** 仅为具有多个别名的订单保存集合；降为一个别名时释放该集合关联。 */
    private final Long2ObjectHashMap<LongHashSet> multiple = new Long2ObjectHashMap<>();

    /**
     * 给订单加入别名，重复加入不增加成员；第一次直接写单别名表，第二个不同别名到来时提升为集合。
     * @param orderId 需要维护或遍历客户别名的订单编号
     * @param key 由调用方保证非负的客户别名键，零合法，-1 被单别名表保留为缺失值
     */
    public void add(long orderId, long key) {
        // 该订单已有的多别名集合；不存在时查看单别名表。
        LongHashSet keys = multiple.get(orderId);
        if (keys != null) {
            keys.add(key);
            return;
        }
        // 单别名表用 -1 表示缺失，其他非负键含零都合法，一次查找即可判断是否已有别名。
        // 单别名表中的原别名，-1 表示尚未登记此订单。
        long previous = single.get(orderId);
        if (previous == -1) {
            single.put(orderId, key);
        } else if (previous != key) {
            keys = new LongHashSet();
            keys.add(previous);
            single.remove(orderId);
            keys.add(key);
            multiple.put(orderId, keys);
        }
    }

    /**
     * 只移除指定别名；多别名集合剩一个时降回单别名表，不匹配或不存在时不删除其他关联。
     * @param orderId 需要维护或遍历客户别名的订单编号
     * @param key 由调用方保证非负的客户别名键，零合法，-1 被单别名表保留为缺失值
     */
    public void remove(long orderId, long key) {
        // 该订单已有的多别名集合；不存在时查看单别名表。
        LongHashSet keys = multiple.get(orderId);
        if (keys == null) {
            if (single.get(orderId) == key) single.remove(orderId);
        } else if (keys.remove(key) && keys.size() == 1) {
            single.put(orderId, keys.longIterator().next());
            multiple.remove(orderId);
        }
    }

    /**
     * 把该订单当前的全部别名交给回调，零别名也正常输出；遍历顺序沿用容器，不保证排序。
     * @param orderId 需要维护或遍历客户别名的订单编号
     * @param consumer 在当前 Lane 访问范围内逐项处理别名的回调
     */
    public void forEach(long orderId, LongProcedure consumer) {
        // 该订单已有的多别名集合；不存在时查看单别名表。
        LongHashSet keys = multiple.get(orderId);
        if (keys != null) keys.forEach(consumer);
        else {
            // 从单别名表取出的唯一别名，只有 -1 才表示不存在。
            long key = single.get(orderId);
            if (key != -1) consumer.value(key);
        }
    }

    /**
     * 删除订单的所有反向别名记录；身份字典引用和用户客户号映射需由外层另行清理。
     * @param orderId 需要维护或遍历客户别名的订单编号
     */
    public void remove(long orderId) {
        single.remove(orderId);
        multiple.remove(orderId);
    }
}
