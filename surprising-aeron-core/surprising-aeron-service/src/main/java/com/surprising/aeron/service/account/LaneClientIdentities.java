package com.surprising.aeron.service.account;

import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.agrona.collections.Long2LongHashMap;

/** 一个账户 Lane 的客户订单身份行表。正数身份键定位用户与客户号文本，引用计数为零时清除文本并复用空行；跨线程监控只读发布数量，不直接读取行表。 构造约定：绑定所属 Lane，构造时不申请线程所有权。 参数 lane：唯一所属账户 Lane，不允许为空 */
@RequiredArgsConstructor
public final class LaneClientIdentities {
    /** 拥有这些客户身份的唯一账户 Lane。 */
    @lombok.NonNull
    private final AccountLaneState lane;
    /** 正数客户身份键到数组行号加一的映射，零表示不存在。 */
    private final Long2LongHashMap rowsByKey = new Long2LongHashMap(16, 0.65f, 0);
    /** 按行保存的确定性客户身份键，空行置零。 */
    private long[] keys = new long[16];
    /** 按行保存的用户编号，与身份文本一起校验键归属。 */
    private long[] users = new long[16];
    /** 按行保存仍持有此身份的引用次数，零表示可回收行。 */
    private long[] references = new long[16];
    /** 按行保存的客户号文本，最后一个引用释放时清空，避免回收行继续持有文本。 */
    private String[] names = new String[16];
    /** 已释放数组行的下标栈，优先复用已有空行。 */
    private int[] freeRows = new int[16];
    /** 空闲行下标栈的有效长度。 */
    private int freeCount;
    /** 尚未使用过的下一行下标，也是清理和冷导出遍历的上界。 */
    private int nextRow;
    /**  监控可见的存活身份行数，Owner 只观察此计数，不读取原语表和身份行。 返回用于监控的存活身份行数量；单独的 volatile 计数不能用于遍历权威行表。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private volatile int publishedCount;

    /**
     * 由所属 Lane 校验或认领当前线程。
     */
    private void assertOwner() { lane.assertOwner(); }

    /**
     * 已有键先核对用户和文本，retain 为 true 才增加已有引用；新键总以一个引用插入。返回正键表示已存在，负键表示本次新建。
     * @param key 由运行时字典计算的正数客户身份键
     * @param userId 此客户号身份所属的用户编号
     * @param name 用户提供的非空白客户订单号文本
     * @param retain 已有身份是否再持有一个引用；新身份不论此值都以一个引用插入
     */
    public long prepare(long key, long userId, String name, boolean retain) {
        assertOwner();
        // 本次查找、导入或遍历使用的身份数组下标，只有引用非零的行才存活。
        int row = row(key);
        if (row >= 0) {
            requireIdentity(row, userId, name);
            if (retain) references[row] = Math.incrementExact(references[row]);
            return key;
        }
        insert(key, userId, name, 1);
        return -key;
    }

    /**
     * 只在键、用户与客户号文本全部匹配时返回正键；未找到或身份不匹配返回零，不新建行或增加引用。
     * @param key 由运行时字典计算的正数客户身份键
     * @param userId 此客户号身份所属的用户编号
     * @param name 用户提供的非空白客户订单号文本
     */
    public long find(long key, long userId, String name) {
        assertOwner();
        // 本次查找、导入或遍历使用的身份数组下标，只有引用非零的行才存活。
        int row = row(key);
        return row >= 0 && users[row] == userId && names[row].equals(name) ? key : 0;
    }

    /**
     * 要求身份键存在且属于指定用户后返回客户号文本，不允许借其他用户的键读取文本。
     * @param key 由运行时字典计算的正数客户身份键
     * @param userId 此客户号身份所属的用户编号
     */
    public String name(long key, long userId) {
        assertOwner();
        // 本次查找、导入或遍历使用的身份数组下标，只有引用非零的行才存活。
        int row = row(key);
        if (row < 0 || users[row] != userId)
            throw new IllegalArgumentException("unknown runtime client key: " + userId + '/' + key);
        return names[row];
    }

    /**
     * 要求键及完整身份匹配后撤回一个引用；不是无条件删除整行，最后一个引用撤回后才回收行。
     * @param key 由运行时字典计算的正数客户身份键
     * @param userId 此客户号身份所属的用户编号
     * @param name 用户提供的非空白客户订单号文本
     */
    public void rollback(long key, long userId, String name) {
        assertOwner();
        // 本次查找、导入或遍历使用的身份数组下标，只有引用非零的行才存活。
        int row = row(key);
        if (row < 0) throw new IllegalStateException("deterministic client identity collision");
        requireIdentity(row, userId, name);
        releaseRow(key, row);
    }

    /**
     * 键存在且归属用户匹配时释放一个引用；缺失或归属不同直接跳过。
     * @param key 由运行时字典计算的正数客户身份键
     * @param userId 此客户号身份所属的用户编号
     */
    public void release(long key, long userId) {
        assertOwner();
        // 本次查找、导入或遍历使用的身份数组下标，只有引用非零的行才存活。
        int row = row(key);
        if (row >= 0 && users[row] == userId) releaseRow(key, row);
    }

    /**
     * 核对指定行的用户和客户号文本，防止确定性键碰撞后误用其他身份。
     * @param row 索引已经定位的身份数组行下标
     * @param userId 此客户号身份所属的用户编号
     * @param name 用户提供的非空白客户订单号文本
     */
    private void requireIdentity(int row, long userId, String name) {
        if (users[row] != userId || !names[row].equals(name))
            throw new IllegalStateException("deterministic client identity collision");
    }

    /**
     * 把索引保存的行号加一还原成数组下标；索引中的零表示缺失，返回 -1。
     * @param key 由运行时字典计算的正数客户身份键
     */
    private int row(long key) { return (int) rowsByKey.get(key) - 1; }

    /**
     * 校验正数键、用户、引用数及非空客户号，优先取空行，必要时成倍扩数组，最后填行并增加存活数量。
     * @param key 由运行时字典计算的正数客户身份键
     * @param userId 此客户号身份所属的用户编号
     * @param name 用户提供的非空白客户订单号文本
     * @param count 冷复制时要保留的正数引用次数
     */
    private void insert(long key, long userId, String name, long count) {
        if (key <= 0 || userId <= 0 || name == null || name.isBlank() || count <= 0)
            throw new IllegalArgumentException("invalid client identity");
        if (rowsByKey.containsKey(key)) throw new IllegalStateException("duplicate imported client identity");
        // 本次查找、导入或遍历使用的身份数组下标，只有引用非零的行才存活。
        int row = freeCount == 0 ? nextRow : freeRows[freeCount - 1];
        if (row == keys.length) {
            // 现有数组长度精确翻倍后的容量，所有按行数组同步扩容。
            int capacity = Math.multiplyExact(keys.length, 2);
            keys = Arrays.copyOf(keys, capacity);
            users = Arrays.copyOf(users, capacity);
            references = Arrays.copyOf(references, capacity);
            names = Arrays.copyOf(names, capacity);
            freeRows = Arrays.copyOf(freeRows, capacity);
        }
        rowsByKey.put(key, row + 1L);
        if (freeCount == 0) nextRow++; else freeCount--;
        keys[row] = key;
        users[row] = userId;
        names[row] = name;
        references[row] = count;
        publishedCount++;
    }

    /**
     * 递减引用；归零后从键表删除、清除键和用户及文本、把数组行压入空闲栈，并减少发布数量。
     * @param key 由运行时字典计算的正数客户身份键
     * @param row 索引已经定位的身份数组行下标
     */
    private void releaseRow(long key, int row) {
        if (references[row] <= 0) throw new IllegalStateException("client identity released twice");
        if (--references[row] != 0) return;
        rowsByKey.remove(key);
        keys[row] = users[row] = 0;
        names[row] = null;
        freeRows[freeCount++] = row;
        publishedCount--;
    }

    /**
     * 清空索引与已分配数组区间，并重置行计数和空闲栈指针；保留数组容量，旧空闲下标因指针归零不再使用。
     */
    public void clear() {
        assertOwner();
        rowsByKey.clear();
        Arrays.fill(keys, 0, nextRow, 0);
        Arrays.fill(users, 0, nextRow, 0);
        Arrays.fill(references, 0, nextRow, 0);
        Arrays.fill(names, 0, nextRow, null);
        nextRow = freeCount = publishedCount = 0;
    }

}
