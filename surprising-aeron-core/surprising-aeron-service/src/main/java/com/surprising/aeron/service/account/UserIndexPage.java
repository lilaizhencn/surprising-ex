package com.surprising.aeron.service.account;

import com.surprising.aeron.support.collection.ImmutableLongArrayList;
import java.util.NavigableSet;

/**
 * 资金费和到期结算共用的账户索引页。读取账本拥有的递增索引，不复制账户状态。
 * @param userIds 按全局用户编号递增的只读用户列表
 * @param nextCursorUserId 还有后页时为本页最后编号，完成时为零
 * @param complete 索引是否已到末尾
 */
public record UserIndexPage(ImmutableLongArrayList userIds,
                            long nextCursorUserId, boolean complete) {
    /**
     * 按全局用户编号选择排他游标之后的一页，并额外观察一条记录判断是否还有后页。NavigableSet 先取 tailSet 避免从头迭代；普通 Iterable 由调用方保证递增且不重复，本方法只跳过空值和不大于游标的值，不排序或去重。非正数上限返回已完成空页。
     * @param indexedUserIds 非空、按全局用户编号递增且不重复的索引来源
     * @param startCursorUserId 全局用户编号的排他下界
     * @param limit 最多返回的用户数量，非正数返回完成空页
     */
    public static UserIndexPage select(Iterable<Long> indexedUserIds, long startCursorUserId, int limit) {
        if (limit <= 0) return new UserIndexPage(ImmutableLongArrayList.empty(), 0, true);
        // 本次用户页的原语列表构建器，满页后还观察下一条以判断是否结束。
        ImmutableLongArrayList.Builder selected = new ImmutableLongArrayList.Builder(Math.min(limit, 64));
        // 实际遍历来源，有序集合先缩小为排他游标之后的尾部视图。
        Iterable<Long> usersAfterCursor = indexedUserIds;
        if (indexedUserIds instanceof NavigableSet<?> /* 确认实现 NavigableSet 的用户索引，可直接定位游标尾部。 */ indexedSet) {
            // 转换成 Long 类型后可调用 tailSet 的同一个有序集合。
            @SuppressWarnings("unchecked")
            NavigableSet<Long> userIds = (NavigableSet<Long>) indexedSet;
            usersAfterCursor = userIds.tailSet(startCursorUserId, false);
        }
        for (Long /* 当前正在计算或登记余额发布变化的用户编号。 */ userId : usersAfterCursor) {
            if (userId == null || userId <= startCursorUserId) continue;
            if (selected.size() == limit) {
                return new UserIndexPage(selected.freeze(), selected.last(), false);
            }
            selected.add(userId.longValue());
        }
        return new UserIndexPage(selected.freeze(), 0, true);
    }

}
