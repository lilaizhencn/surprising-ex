package com.surprising.aeron.service.core;

import com.surprising.aeron.model.CoreAlgoOrderState;
import com.surprising.aeron.model.CoreCancelAllAfterState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.CoreCancelAllAfterStatus;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.service.core.index.AlgoOrderIndex;
import com.surprising.aeron.service.core.index.CancelAllAfterIndex;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 直接验证算法调度和延时全撤索引的筛选、排序、改期、移除及清空重建，不执行算法切片下单或实际全撤。
 */
class ConditionalOrderSchedulingIndexTest {
    /**
     * 布置不同用户、标的及时间的三条算法单，验证交集筛选、时间优先与并列 ID 顺序；换标的和改期后旧键移除，删除清客户端身份，空重建清候选。
     */
    @Test
    void algoFiltersIntersectInDueOrderAndUpdatesRemoveAllOldKeys() {
        // 本用例独立的算法或全撤调度索引
        var index = new AlgoOrderIndex(TradingCoreState.empty(ProductLine.SPOT));
        index.apply(1, algo(1, 7, "10000", 100));
        index.apply(2, algo(2, 8, "10000", 50));
        index.apply(3, algo(3, 7, "2147483647", 100));
        assertThat(index.query(0, "", 0, 10)).containsExactly(2L, 1L, 3L);
        assertThat(index.query(7, "10000", 100, 10)).containsExactly(1L);
        assertThat(index.query(8, "2147483647", 0, 10)).isEmpty();
        assertThat(index.query(0, "10000", 50, 1)).containsExactly(2L);
        assertThat(index.value(3).instrumentId()).isEqualTo(Integer.MAX_VALUE);
        index.apply(1, algo(1, 7, "2147483647", 25));
        assertThat(index.query(7, "10000", 0, 10)).isEmpty();
        assertThat(index.query(7, "2147483647", 0, 10)).containsExactly(1L, 3L);
        index.apply(3, null);
        assertThat(index.containsClient(7, "algo-3")).isFalse();
        index.rebuild(TradingCoreState.empty(ProductLine.SPOT));
        assertThat(index.query(0, "", 0, 10)).isEmpty();
    }

    /**
     * 三个计时器按时间、用户和范围文本排序，同时间十排在二前；改期后新时间生效，删除和空重建不留旧候选。
     */
    @Test
    void cancelTimersRetainTimeUserAndScopeOrderingAfterRescheduleAndRemoval() {
        // 本用例独立的算法或全撤调度索引
        var index = new CancelAllAfterIndex(TradingCoreState.empty(ProductLine.SPOT));
        // 和计时器索引同步更新的当前正文映射，供查询 lookup 读取
        var values = new java.util.HashMap<com.surprising.aeron.model.CoreCancelAllAfterKey,
                CoreCancelAllAfterState>();
        for (var /* 本轮需要加入索引和正文映射的计时器值 */ timer : List.of(timer(7, "2", 100), timer(7, "10", 100), timer(8, "*", 50))) {
            values.put(timer.key(), timer); index.apply(timer.key(), timer);
        }
        assertThat(index.query(0, "", 100, 10, values)).containsExactly(
                timer(8, "*", 50).key(), timer(7, "10", 100).key(), timer(7, "2", 100).key());
        assertThat(index.query(7, "2", 100, 10, values)).containsExactly(timer(7, "2", 100).key());
        // 把用户七、标的二改期到二十五的新计时器值
        var changed = timer(7, "2", 25);
        values.put(changed.key(), changed); index.apply(changed.key(), changed);
        assertThat(index.query(0, "", 25, 1, values)).containsExactly(changed.key());
        values.remove(changed.key()); index.apply(changed.key(), null);
        assertThat(index.query(7, "2", 0, 10, values)).isEmpty();
        index.rebuild(TradingCoreState.empty(ProductLine.SPOT));
        assertThat(index.query(0, "", 0, 10, values)).isEmpty();
    }

    /**
     * 构造指定 ID、用户、标的及下一切片时间的非终态算法单，其余用固定买单及持续期参数，供调度索引测试。
     * @param id 算法单业务 ID
     * @param user 算法或计时器所属用户
     * @param symbol 算法单规范数字标的文本
     * @param due 算法下一切片或计时器触发毫秒时间
     */
    private static CoreAlgoOrderState algo(long id, long user, String symbol, long due) {
        return new CoreAlgoOrderState(id, user, "algo-" + id, symbol, 0, CoreOrderSide.BUY, 100, 100, 10,
                1, 10, CoreMarginMode.CROSS, CorePositionSide.NET, false, false, CoreTimeInForce.GTC, 1,
                0, "", 1, due, 0, 1, 1, 1, List.of());
    }

    /**
     * 构造指定用户、范围和触发时间的 ACTIVE 全撤计时器，其他版本和统计使用固定值。
     * @param user 算法或计时器所属用户
     * @param scope 计时器标的范围文本，星号也是具体存储文本
     * @param due 算法下一切片或计时器触发毫秒时间
     */
    private static CoreCancelAllAfterState timer(long user, String scope, long due) {
        return new CoreCancelAllAfterState(user, scope, 100, CoreCancelAllAfterStatus.ACTIVE, due, 1, 0, 0, 1);
    }
}
