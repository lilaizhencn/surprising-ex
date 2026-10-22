package com.surprising.aeron.service.core;

import java.lang.reflect.Field;
import org.agrona.collections.Long2ObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/**
 * 测试用的账户访问哨兵：临时替换账户表，在独立 Lane 模式下拦住 Owner 对表的 get 调用，并拦住其他线程向 Owner 变更集合写入。
 * FUSED 模式由 Owner 自己持有所有 Lane，因此允许它读取账户表；关闭哨兵时恢复原容器并保留期间产生的数据变化。
 */
public final class OwnerAccountReadGuard implements AutoCloseable {
    /** 通过反射取得的账户 Lane 数组，后续按 Lane 替换及恢复表。 */
    private final Object[] lanes;
    /** 需要检查 Owner 读取权限的五种账户表字段名。 */
    private final String[] names = {"orders", "reservations", "users", "positions", "balances"};
    /** 替换前的原始表引用，第一维为 Lane，第二维与 names 对应。 */
    private final Object[][] originals;
    /** 创建哨兵的测试线程，代表当前 Owner；以线程对象身份判断访问方。 */
    private final Thread owner = Thread.currentThread();
    /** 当前受检查的运行时，用于访问账户 Lane 和判断是否为 FUSED 模式。 */
    private final TradingAccountRuntime runtime;
    /** 只能由 Owner 写入的用户和冻结委托变更集合字段名。 */
    private final String[] changeNames = {"changedUsers", "changedReservations"};
    /** 安装哨兵前的变更集合引用，关闭时把临时集合内容写回这些原集合。 */
    private final LongHashSet[] originalChanges = new LongHashSet[changeNames.length];

    /**
     * 保存账户表和变更集合引用，复制原内容并安装带线程检查的替代容器；仅供测试验证状态所有权。
     * @param runtime 需要检查 Owner 与账户 Lane 访问边界的运行时
     */
    public OwnerAccountReadGuard(TradingAccountRuntime runtime) throws ReflectiveOperationException {
        this.runtime = runtime;
        // 运行时内部账户 Lane 数组的反射字段，需要绕过可见性以安装测试哨兵。
        Field field = runtime.getClass().getDeclaredField("accountLanes");
        field.setAccessible(true);
        lanes = (Object[]) field.get(runtime);
        originals = new Object[lanes.length][names.length];
        for (/* 本轮要安装哨兵的账户 Lane 下标。 */ int lane = 0; lane < lanes.length; lane++) {
            for (/* 当前 Lane 内要替换的账户表种类下标。 */ int index = 0; index < names.length; index++) {
                // 该 Lane 上当前表的反射字段，先保存引用再换成带检查的表。
                Field table = table(lane, index);
                originals[lane][index] = table.get(lanes[lane]);
                table.set(lanes[lane], guard(originals[lane][index], names[index]));
            }
        }
        for (/* 本轮要安装写入检查的 Owner 变更集合下标。 */ int index = 0; index < changeNames.length; index++) {
            // 当前变更集合在运行时上的反射字段。
            Field changes = changeField(index);
            // 替换前的变更集合，原有内容也要复制进哨兵集合。
            var source = (LongHashSet) changes.get(runtime);
            originalChanges[index] = source;
            // 当前变更集合的字段名，线程违规时带入断言消息便于定位。
            String name = changeNames[index];
            // 临时集合只允许 Owner 线程新增编号，防止账户 Lane 直接写 Owner 的变更索引。
            var guard = new LongHashSet() {
                /**
                 * 每次增加变更编号前检查线程身份，非 Owner 写入立即让测试失败。
                 * @param key 要写入 Owner 变更集合的业务编号
                 */
                @Override public boolean add(long key) {
                    if (Thread.currentThread() != owner) throw new AssertionError("Lane wrote Owner " + name);
                    return super.add(key);
                }
            };
            source.forEach(guard::add);
            changes.set(runtime, guard);
        }
    }

    /**
     * 找到并开放指定 Owner 变更集合的反射访问权限。
     * @param index changeNames 中要访问的集合下标
     */
    private Field changeField(int index) throws ReflectiveOperationException {
        // 按固定字段名定位的变更集合字段，供构造器替换及 close 恢复。
        Field field = runtime.getClass().getDeclaredField(changeNames[index]);
        field.setAccessible(true);
        return field;
    }

    /**
     * 找到指定 Lane 的指定账户表，并开放反射访问权限。
     * @param lane 账户 Lane 数组下标
     * @param index names 中的账户表种类下标
     */
    private Field table(int lane, int index) throws ReflectiveOperationException {
        // 指定 Lane 上的账户表字段，后续用它读取、替换或恢复容器引用。
        Field table = lanes[lane].getClass().getDeclaredField(names[index]);
        table.setAccessible(true);
        return table;
    }

    /** 判断当前是否为独立 Lane 模式下的 Owner 线程读表；FUSED 模式允许 Owner 读自己的账户状态。 */
    private boolean forbiddenOwnerRead() {
        return Thread.currentThread() == owner
                && !runtime.isFusedExecution();
    }

    /**
     * 按原始表的具体集合类型创建替代表，复制数据并在 get 时检查 Owner 读取权限。
     * @param original 安装哨兵前的账户表，不改变原表引用
     * @param name 账户表字段名，违规时写入断言消息
     */
    @SuppressWarnings("unchecked")
    private Object guard(Object original, String name) {
        if (original instanceof Long2ObjectHashMap<?> /* 原表属于 Agrona 类型时，使用相同类型的读表哨兵。 */ source) {
            // Agrona 账户表的临时副本，拦截 Owner 的 get，但保持原有键值查询行为。
            var guard = new Long2ObjectHashMap<Object>() {
                /**
                 * 独立 Lane 模式下拒绝 Owner 查询账户表，其他合法线程仍按原集合规则读取。
                 * @param key 要读取的账户、订单、持仓或余额整数键
                 */
                @Override public Object get(long key) {
                    if (forbiddenOwnerRead()) throw new AssertionError("Owner read Lane " + name);
                    return super.get(key);
                }
            };
            guard.putAll((Long2ObjectHashMap<Object>) source);
            return guard;
        }
        // Eclipse Collections 账户表的临时副本，检查规则与上面的 Agrona 表一致。
        var guard = new LongObjectHashMap<Object>() {
            /**
             * 查询前检查 Owner 是否越过账户 Lane 的状态所有权边界。
             * @param key 要读取的账户、订单、持仓或余额整数键
             */
            @Override public Object get(long key) {
                if (forbiddenOwnerRead()) throw new AssertionError("Owner read Lane " + name);
                return super.get(key);
            }
        };
        guard.putAll((LongObjectHashMap<Object>) original);
        return guard;
    }

    /** 把临时表及变更集合的最新内容写回原容器，再恢复所有原始引用，让其他测试能继续使用同一运行时。 */
    @SuppressWarnings("unchecked")
    @Override public void close() throws ReflectiveOperationException {
        for (/* 本轮需要恢复账户表的 Lane 下标。 */ int lane = 0; lane < lanes.length; lane++) {
            for (/* 该 Lane 内当前需要恢复的账户表种类下标。 */ int index = 0; index < names.length; index++) {
                // 当前账户表的反射字段，最后将其重新指向原容器。
                Field table = table(lane, index);
                // 安装哨兵前的原始容器，恢复引用前先同步最新内容。
                Object original = originals[lane][index];
                // 当前带哨兵的表，合法 Lane 在测试期间可能已经修改它。
                Object current = table.get(lanes[lane]);
                if (original instanceof Long2ObjectHashMap<?>) {
                    // Agrona 原表，清空旧内容后接收哨兵表中的最新数据。
                    var target = (Long2ObjectHashMap<Object>) original;
                    target.clear(); target.putAll((Long2ObjectHashMap<Object>) current);
                } else {
                    // Eclipse Collections 原表，采用与 Agrona 分支相同的恢复口径。
                    var target = (LongObjectHashMap<Object>) original;
                    target.clear(); target.putAll((LongObjectHashMap<Object>) current);
                }
                table.set(lanes[lane], original);
            }
        }
        for (/* 本轮需要恢复的 Owner 变更集合下标。 */ int index = 0; index < changeNames.length; index++) {
            // 当前带写入检查的变更集合字段。
            Field changes = changeField(index);
            // 测试期间累计的最新变更编号，写回原集合后才能恢复引用。
            var source = (LongHashSet) changes.get(runtime);
            originalChanges[index].clear();
            originalChanges[index].addAll(source);
            changes.set(runtime, originalChanges[index]);
        }
    }
}
