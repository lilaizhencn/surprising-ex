package com.surprising.aeron.service.core.change;

import com.surprising.aeron.model.*;
import com.surprising.aeron.model.CoreAlgoOrderState;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.service.core.TradingRuntimeState;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.risk.LiquidationRuntime;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 检查直接变化与已接管 Lane 的 null 删除项都进入已提交索引访问器；重复访问保持原顺序，提交清理只释放已接管结果，不触碰已交还源缓冲的新命令。
 */
class CommittedIndexVisitTest {
    /**
     * 先访问直接订单持仓删除项，再反向接管两条 Lane 的变化和其他业务删除，要求访问顺序稳定、null 不被跳过，清理后源中新命令仍在。
     */
    @Test void directAndBorrowedLaneTombstonesKeepPublicationOrderUntilTheCommitClearsThem() {
        try (/* 当前场景独立运行时，直接构造已提交变化，不启动真实集群。 */ var state = new TradingRuntimeState()) {
            state.setMetadata(ProductLine.SPOT, 1);
            // 按实际访问顺序收集业务类型及编号的断言列表。
            var visited = new ArrayList<String>();
            // 测试专用变化访问器，即使前后值都为空也记录删除通知。
            var consumer = /* 测试专用的已提交变化访问器，逐业务类型核对 null 删除通知并记录访问顺序。 */ new TradingRuntimeState.ChangedIndexConsumer() {
                /**
                 * 核对本场景订单前后值均为 null，仍记录一次订单删除访问。
                 * @param id 被访问的业务实体编号
                 * @param before 本场景期望为空的命令前值
                 * @param after 本场景明确记录为空的删除后值
                 */
                @Override public void order(long id, OrderRuntime before, OrderRuntime after) {
                    assertThat(before).isNull(); assertThat(after).isNull(); visited.add("order:" + id);
                }
                /**
                 * 核对持仓删除项的前后值并记录访问顺序。
                 * @param id 被访问的业务实体编号
                 * @param before 本场景期望为空的命令前值
                 * @param after 本场景明确记录为空的删除后值
                 */
                @Override public void position(long id, PositionRuntime before, PositionRuntime after) {
                    assertThat(before).isNull(); assertThat(after).isNull(); visited.add("position:" + id);
                }
                /**
                 * 核对强平删除项的前后值并记录访问顺序。
                 * @param id 被访问的业务实体编号
                 * @param before 本场景期望为空的命令前值
                 * @param after 本场景明确记录为空的删除后值
                 */
                @Override public void liquidation(long id, LiquidationRuntime before, LiquidationRuntime after) {
                    assertThat(before).isNull(); assertThat(after).isNull(); visited.add("liquidation:" + id);
                }
                /**
                 * 核对算法单删除项的前后值并记录访问顺序。
                 * @param id 被访问的业务实体编号
                 * @param before 本场景期望为空的命令前值
                 * @param after 本场景明确记录为空的删除后值
                 */
                @Override public void algoOrder(long id, CoreAlgoOrderState before, CoreAlgoOrderState after) {
                    assertThat(before).isNull(); assertThat(after).isNull(); visited.add("algo:" + id);
                }
                /**
                 * 核对触发单删除项的前后值并记录访问顺序。
                 * @param id 被访问的业务实体编号
                 * @param before 本场景期望为空的命令前值
                 * @param after 本场景明确记录为空的删除后值
                 */
                @Override public void triggerOrder(long id, CoreTriggerOrderState before, CoreTriggerOrderState after) {
                    assertThat(before).isNull(); assertThat(after).isNull(); visited.add("trigger:" + id);
                }
            };
            state.orderChanges().put(11, null);
            state.positionChanges().put(21, null);
            state.visitChangedIndexes(consumer);
            assertThat(visited).containsExactly("order:11", "position:21");
            state.clearChangedKeys(); visited.clear();

            // 稍后按 Lane 零接管的订单删除变化。
            var firstOrders = new RuntimeOrderChangeBuffer();
            // 先按 Lane 三接管的订单删除变化，用于核对按保留 Lane 顺序访问。
            var lastOrders = new RuntimeOrderChangeBuffer();
            // Lane 零的持仓删除及已准备空索引变化。
            var firstPositions = new RuntimePositionChangeBuffer();
            // Lane 三的持仓删除及已准备空索引变化。
            var lastPositions = new RuntimePositionChangeBuffer();
            firstOrders.put(30, null); lastOrders.put(33, null);
            firstPositions.putIndexed(40, null, true, null);
            lastPositions.putIndexed(43, null, true, null);
            state.orderChanges().adopt(3, lastOrders); state.orderChanges().adopt(0, firstOrders);
            state.positionChanges().adopt(3, lastPositions); state.positionChanges().adopt(0, firstPositions);
            firstOrders.put(999, null); firstPositions.put(998, null);
            state.changedLiquidations().put(50, null);
            state.changedAlgoOrders().put(60, null);
            state.changedTriggerOrders().put(70, null);
            for (/* 清理之前重复访问同一已提交变化集合的轮次。 */ int repeat = 0; repeat < 2; repeat++) {
                visited.clear();
                state.visitChangedIndexes(consumer);
                assertThat(visited).containsExactly("order:30", "order:33", "position:40", "position:43",
                        "liquidation:50", "algo:60", "trigger:70");
            }
            state.clearChangedKeys(); visited.clear(); state.visitChangedIndexes(consumer);
            assertThat(visited).isEmpty();
            assertThat(firstOrders.containsKey(999)).isTrue();
            assertThat(firstPositions.containsKey(998)).isTrue();
        }
    }
}
