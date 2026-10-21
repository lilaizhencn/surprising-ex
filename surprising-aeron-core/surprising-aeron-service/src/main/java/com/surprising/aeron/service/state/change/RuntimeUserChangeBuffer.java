package com.surprising.aeron.service.state.change;

/**
 * 命令独占的用户前后值缓冲。每个有效槽复制用户修订和模式到可复用命令槽，不让后续 Lane 原位更新覆盖较早回执；用于前值捕获时，第二列另外保留原权威对象身份。
 */
public final class RuntimeUserChangeBuffer extends RuntimeIndexedChangeBuffer<com.surprising.aeron.service.state.account.UserRuntime, com.surprising.aeron.service.state.account.UserRuntime> {
    /** 跟随本用户缓冲保存的命令值槽数组，各槽可复用但不能被未完成命令共享。 */
    private com.surprising.aeron.service.state.account.UserRuntime[] copies = new com.surprising.aeron.service.state.account.UserRuntime[8];

    /**
     * 按键定位有效槽，把非空用户复制到该槽自己的命令值，再覆盖业务值列；不会直接把活跃用户引用作为回执值。
     * @param key 用户编号原语键
     * @param value 要复制进本命令槽的用户值，null 表示不存在
     */
    public @Override int put(long key, com.surprising.aeron.service.state.account.UserRuntime value) {
        // 当前用户在连续变化缓冲中的槽位，首次写入先登记空业务值以取得位置。
        int slot = indexOf(key);
        if (slot < 0) slot = super.put(key, null);
        if (slot >= copies.length)
            copies = java.util.Arrays.copyOf(copies, Math.max(slot + 1, copies.length * 2));
        if (value != null) value = copies[slot] = value.copyIntoCommandSlot(copies[slot]);
        return super.put(key, value);
    }

    /**
     * 只在本命令首次捕获用户时复制独立前值，并把原权威引用保存在第二列供回滚恢复身份；原用户不存在也保留捕获标记。
     * @param key 用户编号原语键
     * @param authority 首次修改前的原 Lane 权威用户，允许 null 表示原用户不存在
     */
    public void captureBefore(long key, com.surprising.aeron.service.state.account.UserRuntime authority) {
        if (containsKey(key)) return;
        put(key, authority);
        putPrepared(key, authority);
    }

    /**
     * 逐有效命令槽清除用户字段，再清基础值及原权威引用，保留命令槽对象和数组供下一命令使用。
     */
    public @Override void clear() {
        for (/* 当前用户在连续变化缓冲中的槽位，首次写入先登记空业务值以取得位置。 */ int slot = 0; slot < size; slot++) if (copies[slot] != null) copies[slot].clearCommandSlot();
        super.clear();
    }
}
