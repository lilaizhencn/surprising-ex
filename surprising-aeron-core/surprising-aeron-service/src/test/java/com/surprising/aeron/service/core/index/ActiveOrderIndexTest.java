package com.surprising.aeron.service.core.index;

import com.surprising.aeron.protocol.CoreRiskLimitBracket;

import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检查 Owner 活跃订单路由、成员桶回收、用户标的交集和排他分页。账户准入汇总用例另外检查 Lane 自己的订单索引，不把该资金/风险职责归给 Owner 轻量路由；循环及反射断言属于正确性检查，不是性能压测。
 */
class ActiveOrderIndexTest {

    /**
     * 保留用户七的订单，同时让一千个其他用户订单反复新增、删除；检查空成员桶复用不泄漏旧身份，重复删除不影响常驻路由。
     */
    @Test void recycledMembershipDoesNotRetainPreviousUsersOrDisturbLiveRoutes() {
        // 本用例原始身份表，用于冷订单转运行时及反向标的查询
        var identities = new com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry();
        // 本用例 Owner 活跃订单路由索引
        var index = new ActiveOrderIndex(TradingCoreState.empty(ProductLine.SPOT));
        // 用户七标的一的常驻 OPEN 订单，用作桶复用隔离对照
        var held = new CoreOrderState(1, ProductLine.SPOT, 7, "1", CoreOrderSide.BUY,
                90, 10, 0, false, CoreOrderStatus.OPEN, 1);
        index.apply(1, com.surprising.aeron.service.snapshot.state.RuntimeStateProjector.toRuntimeOrder(held, identities, instrument("1")), identities);
        // 临时用户订单共用的标的二现货合约
        var otherInstrument = instrument("2");
        for (/* 当前构造、删除或遍历的订单号 */ long id = 1_000_000; id < 1_001_000; id++) {
            // 当前订单或交集查询的用户 ID
            long user = id + 1;
            // 当前构造、转为运行时或更新的测试订单
            var order = new CoreOrderState(id, ProductLine.SPOT, user, "2", CoreOrderSide.SELL,
                    100, 1, 0, false, CoreOrderStatus.OPEN, 1);
            index.apply(id, com.surprising.aeron.service.snapshot.state.RuntimeStateProjector.toRuntimeOrder(order, identities, otherInstrument), identities);
            assertThat(index.page(user, "2", 0, 10).orderIds()).containsExactly(id);
            index.apply(id, null, identities);
            index.apply(id, null, identities);
            assertThat(index.sortedIdsDescending(user)).isEmpty();
            assertThat(index.count("2")).isZero();
            assertThat(index.sortedIdsDescending(7)).containsExactly(1L);
            assertThat(index.activeOrderRoute(1).userId()).isEqualTo(7);
        }
        assertThat(index.count()).isOne();
        assertThat(index.page(0, null, 0, 10).orderIds()).containsExactly(1L);
    }

    /**
     * 建立并删除四千零九十七个订单，通过反射检查有效成员表为空、回收路由字段归零且成员桶容量与回收数量有界。
     */
    @Test void completedBurstLeavesBoundedEmptyCachesAndNoIdentityEntries() throws Exception {
        // 本用例原始身份表，用于冷订单转运行时及反向标的查询
        var identities = new com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry();
        // 本用例 Owner 活跃订单路由索引
        var index = new ActiveOrderIndex(TradingCoreState.empty(ProductLine.SPOT));
        // 本轮订单转运行时使用的现货合约
        var instrument = instrument("1");
        for (/* 当前构造、删除或遍历的订单号 */ long id = 1; id <= 4_097; id++) {
            // 当前构造、转为运行时或更新的测试订单
            var order = new CoreOrderState(id, ProductLine.SPOT, id, "1", CoreOrderSide.BUY,
                    90, 1, 0, false, CoreOrderStatus.OPEN, 1);
            index.apply(id, com.surprising.aeron.service.snapshot.state.RuntimeStateProjector.toRuntimeOrder(order, identities, instrument), identities);
        }
        for (/* 当前构造、删除或遍历的订单号 */ long id = 1; id <= 4_097; id++) index.apply(id, null, identities);
        assertThat(index.count()).isZero();
        for (String /* 需要反射检查为空的有效成员表字段名 */ name : new String[]{"idsByUser", "idsBySymbol"}) {
            // 当前反射字段，检查其值或类型以确认路由不含订单正文
            var field = ActiveOrderIndex.class.getDeclaredField(name); field.setAccessible(true);
            assertThat((Map<?, ?>) field.get(index)).isEmpty();
        }
        // 反射取得回收路由池的字段
        var routesField = ActiveOrderIndex.class.getDeclaredField("recycledOrders"); routesField.setAccessible(true);
        // 回收路由对象队列，逐项核对数字已清零、文本已清空
        var routes = (java.util.ArrayDeque<ActiveOrderIndex.IndexedOrder>) routesField.get(index);
        assertThat(routes).hasSizeLessThanOrEqualTo(4_096).allSatisfy(/* 当前借出的轻量路由，或回收池中逐项验证的空路由 */ route -> {
            assertThat(route.orderId()).isZero(); assertThat(route.userId()).isZero(); assertThat(route.instrumentId()).isNull();
        });
        // 反射取得空成员桶回收池的字段
        var setsField = ActiveOrderIndex.class.getDeclaredField("recycledMemberships"); setsField.setAccessible(true);
        // 回收成员桶队列，检查数量、空状态与容量上限
        var sets = (java.util.ArrayDeque<org.agrona.collections.LongHashSet>) setsField.get(index);
        assertThat(sets).hasSizeLessThanOrEqualTo(64).allSatisfy(/* 回收池中当前检查的 primitive 空成员桶 */ ids -> {
            assertThat(ids.size()).isZero(); assertThat(ids.capacity()).isLessThanOrEqualTo(4_096);
        });
    }

    /**
     * 遍历全部终态，重复应用终态及空删除后检查原订单消失、其他用户与同标的订单仍在，最后清空所有成员。
     */
    @Test
    void terminalRemovalIsIdempotentAndPreservesOtherAccountAndSymbolOrders() {
        for (var /* 当前要验证删除幂等性的订单终态枚举项 */ status : CoreOrderStatus.values()) {
            if (!status.terminal()) continue;
            // 本用例原始身份表，用于冷订单转运行时及反向标的查询
            var identities = new com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry();
            // 本用例 Owner 活跃订单路由索引
            var index = new ActiveOrderIndex(TradingCoreState.empty(ProductLine.SPOT));
            // 本用例订单集合，终态测试为运行时数组，冷重建测试为按订单号映射
            var orders = new com.surprising.aeron.service.order.state.OrderRuntime[3];
            for (/* 当前构造三份对照订单的数组下标 */ int i = 0; i < orders.length; i++) {
                // 当前构造、转为运行时或更新的测试订单
                var order = new CoreOrderState(i + 1, ProductLine.SPOT, i < 2 ? 7 : 8, "1", CoreOrderSide.BUY, 90, 10, 0, false, CoreOrderStatus.OPEN, 1);
                orders[i] = com.surprising.aeron.service.snapshot.state.RuntimeStateProjector.toRuntimeOrder(
                        order, identities, instrument(order.instrumentId()));
                index.apply(i + 1, orders[i], identities);
            }
            // 根据当前终态从首订单派生的结束订单状态
            var terminal = status == CoreOrderStatus.FILLED
                    ? orders[0].withFill(10, 0, 0, status, 2) : orders[0].withStatus(status, 2);
            index.apply(1, terminal, identities);
            index.apply(1, terminal, identities);
            index.apply(1, null, identities);
            index.apply(999, null, identities);
            assertThat(index.activeOrderRoute(1)).isNull();
            assertThat(index.sortedIdsDescending(7)).containsExactly(2L);
            assertThat(index.sortedIdsDescending(8)).containsExactly(3L);
            assertThat(index.sortedIdsDescending("1")).containsExactly(3L, 2L);
            index.apply(2, null, identities);
            index.apply(3, null, identities);
            assertThat(index.count()).isZero();
            assertThat(index.sortedIdsDescending(7)).isEmpty();
            assertThat(index.sortedIdsDescending(8)).isEmpty();
            assertThat(index.sortedIdsDescending("1")).isEmpty();
        }
    }

    /**
     * 保持三十二个活单滑动周转到四千零九十六号，用反射检查总表存储数组仍复用；检查用户标的交集及分页结果。
     */
    @Test
    void boundedTurnoverKeepsStorageAndIndependentQueryCursors() throws Exception {
        // 本用例原始身份表，用于冷订单转运行时及反向标的查询
        var identities = new com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry();
        // 本用例 Owner 活跃订单路由索引
        var index = new ActiveOrderIndex(TradingCoreState.empty(ProductLine.SPOT));
        // 反射取得活跃订单反向总表的字段
        var mapField = ActiveOrderIndex.class.getDeclaredField("ordersById");
        mapField.setAccessible(true);
        // 实际 primitive 订单总表，仅供检查内部存储是否仍复用
        var entries = (org.agrona.collections.Long2ObjectHashMap<?>) mapField.get(index);
        // Agrona 总表内部 values 数组的反射字段
        var valuesField = org.agrona.collections.Long2ObjectHashMap.class.getDeclaredField("values");
        valuesField.setAccessible(true);
        for (/* 当前构造、删除或遍历的订单号 */ long id = 1; id <= 32; id++) {
            // 当前构造、转为运行时或更新的测试订单
            var order = new CoreOrderState(id, ProductLine.SPOT, 7, "1", CoreOrderSide.BUY, 90, 10, 0, false, CoreOrderStatus.OPEN, 1);
            index.apply(id, com.surprising.aeron.service.snapshot.state.RuntimeStateProjector.toRuntimeOrder(
                    order, identities, instrument(order.instrumentId())), identities);
        }
        // 滑动周转前的内部存储数组引用，之后应保持同一实例
        Object storage = valuesField.get(entries);
        for (/* 当前构造、删除或遍历的订单号 */ long id = 33; id <= 4096; id++) {
            index.apply(id - 32, null, identities);
            // 当前构造、转为运行时或更新的测试订单
            var order = new CoreOrderState(id, ProductLine.SPOT, 7, "1", CoreOrderSide.BUY, 90, 10, 0, false, CoreOrderStatus.OPEN, 1);
            index.apply(id, com.surprising.aeron.service.snapshot.state.RuntimeStateProjector.toRuntimeOrder(
                    order, identities, instrument(order.instrumentId())), identities);
        }
        assertThat(valuesField.get(entries)).isSameAs(storage);
        var actual = index.page(7, "1", 0, 32).orderIds();
        assertThat(actual).hasSize(32).allMatch(id -> id > 4064 && id <= 4096);
        assertThat(index.page(0, null, 0, 16).orderIds()).hasSize(16);
        assertThat(index.count()).isEqualTo(32);
        for (/* 当前构造、删除或遍历的订单号 */ long id = 4065; id <= 4096; id++) index.apply(id, null, identities);
        assertThat(index.count()).isZero();
        assertThat(index.sortedIdsDescending(7)).isEmpty();
        assertThat(index.sortedIdsDescending("1")).isEmpty();
    }

    /**
     * 检查路由没有 OrderRuntime 字段，部分成交仍 OPEN 时复用同一个路由对象，终态时撤掉成员。
     */
    @Test
    void runtimeUpdatesRetainRoutingOnlyAndRetireTerminalMembership() {
        // 本用例原始身份表，用于冷订单转运行时及反向标的查询
        var identities = new com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry();
        // 本用例 Owner 活跃订单路由索引
        var index = new ActiveOrderIndex(TradingCoreState.empty(ProductLine.SPOT));
        // 尚未部分成交的原始 OPEN 冷订单
        var initial = new CoreOrderState(1, ProductLine.SPOT, 7, "1", CoreOrderSide.BUY, 90, 10, 0, false, CoreOrderStatus.OPEN, 1);
        // 当前构造、转为运行时或更新的测试订单
        var order = com.surprising.aeron.service.snapshot.state.RuntimeStateProjector.toRuntimeOrder(initial, identities, instrument("1"));
        index.apply(1, order, identities);
        // 当前借出的轻量路由，或回收池中逐项验证的空路由
        var route = index.activeOrderRoute(1);
        assertThat(route.userId()).isEqualTo(7);
        assertThat(route.instrumentId()).isEqualTo("1");
        assertThat(java.util.Arrays.stream(route.getClass().getDeclaredFields()).map(/* 当前反射字段，检查其值或类型以确认路由不含订单正文 */ field -> field.getType().getName()))
                .doesNotContain(com.surprising.aeron.service.order.state.OrderRuntime.class.getName());
        index.apply(1, order.withFill(4, 6, 3, CoreOrderStatus.OPEN, 2), identities);
        assertThat(index.activeOrderRoute(1)).isSameAs(route);
        assertThat(index.page(7, "1", 0, 10).orderIds()).containsExactly(1L);
        index.apply(1, order.withStatus(CoreOrderStatus.CANCELED, 3), identities);
        assertThat(index.activeOrderRoute(1)).isNull();
        assertThat(index.sortedIdsDescending(7)).isEmpty();
    }

    /**
     * 用同订单号构造不同用户及标的新状态，验证索引撤旧桶再登记新路由；这是索引迁移测试，不代表业务允许转让订单。
     */
    @Test
    void runtimeScopeChangesReplaceMembershipWithoutRetainingThePreviousParticipant() {
        // 本用例原始身份表，用于冷订单转运行时及反向标的查询
        var identities = new com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry();
        // 本用例 Owner 活跃订单路由索引
        var index = new ActiveOrderIndex(TradingCoreState.empty(ProductLine.SPOT));
        // 首次原订单、第一份交集游标或第一页结果，按各用例对应角色使用
        var first = new CoreOrderState(1, ProductLine.SPOT, 7, "1", CoreOrderSide.BUY, 90, 10, 0, false, CoreOrderStatus.OPEN, 1);
        // 替换用冷订单、另一对照订单或第二页结果
        var second = new CoreOrderState(1, ProductLine.SPOT, 8, "2", CoreOrderSide.SELL, 110, 10, 0, false, CoreOrderStatus.OPEN, 2);
        index.apply(1, com.surprising.aeron.service.snapshot.state.RuntimeStateProjector.toRuntimeOrder(
                first, identities, instrument(first.instrumentId())), identities);
        // 同订单号不同用户及标的的替代运行时状态，仅验证索引迁移
        var replacement = com.surprising.aeron.service.snapshot.state.RuntimeStateProjector.toRuntimeOrder(
                second, identities, instrument(second.instrumentId()));
        index.apply(1, replacement, identities);
        assertThat(index.activeOrderRoute(1).userId()).isEqualTo(8);
        assertThat(index.sortedIdsDescending(7)).isEmpty();
        assertThat(index.sortedIdsDescending("1")).isEmpty();
        assertThat(index.sortedIdsDescending(8)).containsExactly(1L);
        assertThat(index.activeOrderRoute(1).instrumentId()).isEqualTo("2");
    }

    /**
     * 十二个订单按用户和标的分组，用原订单数据核对有、无命中时的交集分页。
     */
    @Test
    void pageMatchesUserAndInstrumentIntersection() {
        // 本用例订单集合，终态测试为运行时数组，冷重建测试为按订单号映射
        Map<Long, CoreOrderState> orders = new HashMap<>();
        for (/* 当前构造、删除或遍历的订单号 */ long id = 1; id <= 12; id++) {
            // 当前订单或交集查询的用户 ID
            long user = id <= 9 ? 11 : 12;
            // 当前分组、合约构造或交集查询使用的永久数字标的文本
            String instrumentId = id % 3 == 0 ? "2" : "1";
            orders.put(id, new CoreOrderState(id, ProductLine.SPOT, user, instrumentId, CoreOrderSide.BUY, 100, 1, 0, false, CoreOrderStatus.OPEN, 1));
        }
        // 包含本用例用户、合约和订单的冷状态
        var state = new TradingCoreState(ProductLine.SPOT, 1,
                Map.of(11L, CoreUserState.empty(ProductLine.SPOT, 11),
                        12L, CoreUserState.empty(ProductLine.SPOT, 12)), orders,
                instruments(ProductLine.SPOT, "1", "2"), CoreRiskState.empty(), CoreTreasuryState.empty());
        // 本用例 Owner 活跃订单路由索引
        var index = new ActiveOrderIndex(state);
        for (long /* 当前订单或交集查询的用户 ID */ user : new long[]{11, 12, 99}) {
            for (String /* 当前分组、合约构造或交集查询使用的永久数字标的文本 */ instrumentId : new String[]{"1", "2", "24"}) {
                var expected = orders.values().stream()
                        .filter(order -> order.userId() == user && order.instrumentId().equals(instrumentId))
                        .map(CoreOrderState::orderId).sorted(java.util.Comparator.reverseOrder()).toList();
                assertThat(index.page(user, instrumentId, 0, 32).orderIds()).containsExactlyElementsOf(expected);
            }
        }
    }

    /**
     * 验证账户 Lane 的 admissionOrders 汇总：开仓部分成交、只减仓订单取消后，待成交数量及保证金模式计数变化正确；不使用 Owner 轻量路由做这些汇总。
     */
    @Test
    void maintainsRiskAggregatesAcrossOrderUpdates() {
        // 用户十一跨仓买入的开仓订单，初始待成交五
        CoreOrderState opening = new CoreOrderState(7, ProductLine.LINEAR_PERPETUAL, 11, "1", CoreOrderSide.BUY, 100, 5, 0, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderStatus.OPEN, 1);
        // 同用户逐仓卖出的只减仓订单，初始待成交三
        CoreOrderState reducing = new CoreOrderState(8, ProductLine.LINEAR_PERPETUAL, 11, "1", CoreOrderSide.SELL, 100, 3, 0, true, CoreMarginMode.ISOLATED, CorePositionSide.NET,
                CoreOrderStatus.OPEN, 1);
        // 用来投影账户准入索引的原 U 本位永续冷状态
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(11L, CoreUserState.empty(ProductLine.LINEAR_PERPETUAL, 11)),
                Map.of(7L, opening, 8L, reducing), instruments(ProductLine.LINEAR_PERPETUAL, "1"),
                CoreRiskState.empty(), CoreTreasuryState.empty());
        // 本用例原始身份表，用于冷订单转运行时及反向标的查询
        var identities = new com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry();
        try (/* 投影出的冷交易运行时，结束时自动关闭 */ var runtime = com.surprising.aeron.service.snapshot.state.RuntimeStateProjector.project(before, identities)) {
            // 账户 Lane 自己的订单准入索引，不是 Owner 轻量路由
            var laneOrders = runtime.onLane(11L, lane -> lane.admissionOrderIndex(identities.symbolId("1")));
            // 对买入、净仓、逐仓条件查询所得的账户准入汇总
            var summary = laneOrders.inspect(11, "1", CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.ISOLATED);
            assertThat(summary.pendingQuantity()).isEqualTo(5);
            assertThat(summary.reduceOnlyQuantity()).isZero();
            assertThat(summary.marginModeCount()).isEqualTo(1);
            runtime.onLane(11L, lane -> {
                var filled = runtime.order(7).withFill(3, 2, 0, CoreOrderStatus.OPEN, 2);
                filled.executionValue(-1, 0); // 原夹具未提供累计成交额，保留同一哨兵。
                runtime.replaceOrder(filled);
                runtime.replaceOrder(runtime.order(8).withStatus(CoreOrderStatus.CANCELED, 2));
                return null;
            });
            summary = laneOrders.inspect(11, "1", CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.ISOLATED);
            assertThat(summary.pendingQuantity()).isEqualTo(2);
            assertThat(summary.reduceOnlyQuantity()).isZero();
            assertThat(summary.marginModeCount()).isZero();
        }
    }

    /**
     * 从包含一个 OPEN 订单的冷状态构建索引，检查全局页及所属用户路由。
     */
    @Test
    void rebuildUsesOpenOrderLifecycle() {
        // 当前构造、转为运行时或更新的测试订单
        CoreOrderState order = new CoreOrderState(7, ProductLine.SPOT, 11, "1", CoreOrderSide.BUY, 100, 5, 0, false, CoreOrderStatus.OPEN, 1);
        // 包含本用例用户、合约和订单的冷状态
        TradingCoreState state = new TradingCoreState(ProductLine.SPOT, 1,
                Map.of(11L, CoreUserState.empty(ProductLine.SPOT, 11)), Map.of(7L, order),
                instruments(ProductLine.SPOT, "1"), CoreRiskState.empty(), CoreTreasuryState.empty());

        // 本用例 Owner 活跃订单路由索引
        ActiveOrderIndex index = new ActiveOrderIndex(state);
        assertThat(index.page(0, null, 0, 1024).orderIds()).containsExactly(7L);
        assertThat(index.activeOrderRoute(7).userId()).isEqualTo(11);
    }

    /**
     * 构造两千零四十九个活单，用排他游标连续读取一千零二十四、一千零二十四和一条，检查降序无重复、末页游标为零。
     */
    @Test
    void pageUsesExclusiveCursorAndTheBoundedLifecycleLimit() {
        // 本用例订单集合，终态测试为运行时数组，冷重建测试为按订单号映射
        Map<Long, CoreOrderState> orders = new HashMap<>();
        for (/* 构造连续分页订单的当前业务号 */ long orderId = 1; orderId <= 2_049; orderId++) {
            orders.put(orderId, new CoreOrderState(orderId, ProductLine.SPOT, 11, "1", CoreOrderSide.BUY, 100, 1, 0, false, CoreOrderStatus.OPEN, 1));
        }
        // 包含本用例用户、合约和订单的冷状态
        TradingCoreState state = new TradingCoreState(ProductLine.SPOT, 1,
                Map.of(11L, CoreUserState.empty(ProductLine.SPOT, 11)), orders,
                instruments(ProductLine.SPOT, "1"), CoreRiskState.empty(), CoreTreasuryState.empty());
        // 本用例 Owner 活跃订单路由索引
        ActiveOrderIndex index = new ActiveOrderIndex(state);

        // 首次原订单、第一份交集游标或第一页结果，按各用例对应角色使用
        ActiveOrderIndex.Page first = index.page(11, "1", 0, ActiveOrderIndex.MAX_PAGE_SIZE);
        // 替换用冷订单、另一对照订单或第二页结果
        ActiveOrderIndex.Page second = index.page(11, "1", first.nextCursorOrderId(),
                ActiveOrderIndex.MAX_PAGE_SIZE);
        // 第三份对照冷订单或末页结果
        ActiveOrderIndex.Page third = index.page(11, "1", second.nextCursorOrderId(),
                ActiveOrderIndex.MAX_PAGE_SIZE);

        assertThat(first.orderIds()).hasSize(1_024).startsWith(2_049L).endsWith(1_026L);
        assertThat(second.orderIds()).hasSize(1_024).startsWith(1_025L).endsWith(2L);
        assertThat(third.orderIds()).containsExactly(1L);
        assertThat(third.nextCursorOrderId()).isZero();
    }

    /**
     * 检查标的和用户的 primitive 降序数组，以及全局、用户和标的条件下排他游标选择的下一条。
     */
    @Test
    void primitiveSortedCursorAvoidsBoxedCompatibilityView() {
        // 首次原订单、第一份交集游标或第一页结果，按各用例对应角色使用
        CoreOrderState first = new CoreOrderState(7, ProductLine.SPOT, 11, "1", CoreOrderSide.BUY, 100, 1, 0, false, CoreOrderStatus.OPEN, 1);
        // 替换用冷订单、另一对照订单或第二页结果
        CoreOrderState second = new CoreOrderState(9, ProductLine.SPOT, 11, "1", CoreOrderSide.BUY, 101, 1, 0, false, CoreOrderStatus.OPEN, 1);
        // 第三份对照冷订单或末页结果
        CoreOrderState third = new CoreOrderState(8, ProductLine.SPOT, 12, "1", CoreOrderSide.BUY, 102, 1, 0, false, CoreOrderStatus.OPEN, 1);
        // 包含本用例用户、合约和订单的冷状态
        TradingCoreState state = new TradingCoreState(ProductLine.SPOT, 1,
                Map.of(11L, CoreUserState.empty(ProductLine.SPOT, 11),
                        12L, CoreUserState.empty(ProductLine.SPOT, 12)),
                Map.of(7L, first, 8L, third, 9L, second), instruments(ProductLine.SPOT, "1"), CoreRiskState.empty(),
                CoreTreasuryState.empty());
        // 本用例 Owner 活跃订单路由索引
        ActiveOrderIndex index = new ActiveOrderIndex(state);

        assertThat(index.sortedIdsDescending("1")).containsExactly(9L, 8L, 7L);
        assertThat(index.sortedIdsDescending(11L)).containsExactly(9L, 7L);
        assertThat(index.sortedIdsDescending(999L)).isEmpty();
        assertThat(index.page(0, null, 0, 1).orderIds()).containsExactly(9L);
        assertThat(index.page(0, null, 9, 1).orderIds()).containsExactly(8L);
        assertThat(index.page(11, "1", 0, 1).orderIds()).containsExactly(9L);
        assertThat(index.page(11, "1", 9, 1).orderIds()).containsExactly(7L);
        assertThat(index.page(11, "1", 7, 1).orderIds()).isEmpty();
    }

    /**
     * 为现货测试创建指定数字标的，标的一为 BTC，其余为 ETH，报价与结算资产均为 USDT。
     * @param instrumentId 测试合约的永久数字标的文本
     */
    private static CoreInstrument instrument(String instrumentId) {
        // 标的一选择 BTC，否则选择 ETH 的测试基础币
        String baseAsset = (instrumentId.equals("1") ? "BTC" : "ETH");
        return CoreInstrument.from(ProductLine.SPOT,
                new RegisterInstrumentCommand(
                        instrumentId, baseAsset, "USDT", "USDT", 1, 1, 1, null, 0, 0, 0, -1, 0));
    }

    /**
     * 按产品线类型创建测试合约并冻结映射；币本位使用基础币结算，交割与期权提供到期参数，期权还提供类型及行权价。
     * @param productLine 决定合约类型、结算资产及到期配置的独立产品线
     * @param symbols 本次需要构造的永久数字标的文本列表
     */
    private static Map<String, CoreInstrument> instruments(ProductLine productLine, String... symbols) {
        // 本次按标的文本逐项构造的合约映射，返回前复制为只读
        Map<String, CoreInstrument> instruments = new HashMap<>();
        for (String /* 当前分组、合约构造或交集查询使用的永久数字标的文本 */ instrumentId : symbols) {
            // 标的一选择 BTC，否则选择 ETH 的测试基础币
            String baseAsset = (instrumentId.equals("1") ? "BTC" : "ETH");
            // 按当前产品线选出的合约类型，决定币本位、到期和期权字段
            ContractType type = ContractType.valueOf(productLine.contractTypeCode());
            instruments.put(instrumentId, CoreInstrument.from(productLine,
                    new RegisterInstrumentCommand(
                            instrumentId, baseAsset, "USDT", type.isInverse() ? baseAsset : "USDT", 1, 1,
                            type.isInverse() ? 1_000 : 1,
                            (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                                    100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                    java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000)))),
                            0, 0, type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0,
                            type.isOption() ? 0 : -1, type.isOption() ? 100 : 0)));
        }
        return Map.copyOf(instruments);
    }
}
