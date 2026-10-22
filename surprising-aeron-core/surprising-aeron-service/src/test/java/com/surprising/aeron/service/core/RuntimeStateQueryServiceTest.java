package com.surprising.aeron.service.core;

import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.account.BalanceRuntime;
import com.surprising.aeron.service.account.UserRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.order.state.ReservationRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.query.state.*;
import com.surprising.aeron.service.query.state.RuntimeStateQueryService;
import com.surprising.aeron.service.snapshot.state.RuntimeStateMaterializer;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证账户运行时索引能生成与业务快照一致的用户、普通订单和客户端订单视图及哈希，同时检查缺失查询不扩展身份表，完整用户查询不能无限物化实体。
 */
class RuntimeStateQueryServiceTest {

    /**
     * 直接准备用户余额、冻结、持仓、杠杆和订单索引，查询后核对实体条数、用户及订单哈希与物化快照相同，客户端标识查询也应命中同一订单。
     */
    @Test
    void buildsUserOrderAndClientQueriesFromRuntimeIndexes() {
        // 本用例共用的运行时身份映射，客户端身份路由绑定到同一个运行时。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 本测试直接写入账户及订单索引的运行时，查询与物化参照都从它读取。
        TradingAccountRuntime runtime = new TradingAccountRuntime();
        identities.bindClientLanes(runtime);
        runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 7);
        // 合约 1 的整数编号，冻结、持仓和订单都使用同一身份。
        int symbolId = identities.symbolId("1");
        // 当前测试资产的整数编号，基本用例为 USDT，超限用例每次创建不同资产。
        int assetId = identities.assetId("USDT");
        // 提供给当前持仓和订单的线性永续合约配置。
        var instrument = CoreStateTestFixtures.runtimeInstrument();
        runtime.registerInstrument(instrument);
        // 用户 1001 的 client-71 客户端订单身份，映射到 Core 订单 71。
        long clientKey = identities.clientKey(1001, "client-71");
        // 用户 1001 在合约 1 的净持仓整数键。
        long positionKey = identities.positionKey(1001, "1");
        runtime.putUser(new UserRuntime(ProductLine.LINEAR_PERPETUAL, 1001, 3, CorePositionMode.ONE_WAY));
        runtime.putBalance(new BalanceRuntime(1001, assetId, 1_000, 150));
        runtime.putReservation(new ReservationRuntime(71, 1001, symbolId,
                ReservationKind.DERIVATIVE_MARGIN, assetId, 100, 0, 0, 2));
        runtime.putPosition(positionKey, new PositionRuntime(1001, symbolId, assetId, CoreMarginMode.CROSS,
                CorePositionSide.NET, instrument, 2, 60_000, 120_000, 7, 50));
        runtime.putLeverage(new CoreLeverageKey(1001, "1", CoreMarginMode.CROSS), 5_000_000);
        runtime.putOrder(new OrderRuntime(71, ProductLine.LINEAR_PERPETUAL, 1001, symbolId, instrument,
                CoreOrderSide.BUY, 60_000, 60_000, 2, 0, 2, false, CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "client-71",
                UUID.fromString("10000000-0000-0000-0000-000000000071"), -10, 25, 0,
                1_000, 1_000, 9, CoreOrderStatus.OPEN, 1));
        runtime.putClientOrder(1001, clientKey, 71);

        // 把同一运行时完整物化得到的业务状态，用它独立核对用户与订单状态哈希。
        TradingCoreState snapshot = RuntimeStateMaterializer.materialize(runtime, identities);
        // 在该用户所属 Lane 内组装的完整用户查询结果。
        var user = RuntimeStateQueryService.userState(runtime, runtime.accountLaneForUser(1001), identities, 1001);
        // 按 Core 订单编号 71 直接查询的订单结果。
        var order = RuntimeStateQueryService.orderState(runtime, runtime.accountLaneForUser(1001), identities, 71);
        // 按用户及客户端标识查询的结果，应与直接订单编号查询完全相同。
        var clientOrder = RuntimeStateQueryService.clientOrderState(runtime, runtime.accountLaneForUser(1001), identities, 1001, "client-71");

        assertThat(user.found()).isTrue();
        assertThat(user.stateHash()).isEqualTo(snapshot.userStateHash(1001));
        assertThat(user.view().balances()).hasSize(1);
        assertThat(user.view().reservations()).hasSize(1);
        assertThat(user.view().positions()).hasSize(1);
        assertThat(user.view().leverages()).hasSize(1);
        assertThat(order.found()).isTrue();
        assertThat(order.stateHash()).isEqualTo(snapshot.orderStateHash(71));
        assertThat(clientOrder).isEqualTo(order);
    }

    /**
     * 查询不存在的客户端订单标识应返回未找到，前后客户端身份数量保持不变，不能因只读查询分配新身份。
     */
    @Test
    void missingClientLookupDoesNotAllocateAnIdentity() {
        // 本用例共用的运行时身份映射，客户端身份路由绑定到同一个运行时。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 本测试直接写入账户及订单索引的运行时，查询与物化参照都从它读取。
        TradingAccountRuntime runtime = new TradingAccountRuntime();
        identities.bindClientLanes(runtime);
        // 缺失客户端查询之前的身份表数量，查询后不能增加。
        long before = identities.clientIdentityCount();

        // 不存在客户端标识的只读查询结果，found 应为 false。
        var result = RuntimeStateQueryService.clientOrderState(runtime, runtime.accountLaneForUser(1001), identities, 1001, "missing");

        assertThat(result.found()).isFalse();
        assertThat(identities.clientIdentityCount()).isEqualTo(before);
    }

    /**
     * 为一个现货用户放入比完整查询上限多一条的资产余额，完整用户查询应给出 tooLarge 标记，不返回无界投影。
     */
    @Test
    void rejectsAnUnboundedUserProjection() {
        // 本用例共用的运行时身份映射，客户端身份路由绑定到同一个运行时。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 本测试直接写入账户及订单索引的运行时，查询与物化参照都从它读取。
        TradingAccountRuntime runtime = new TradingAccountRuntime();
        identities.bindClientLanes(runtime);
        runtime.setMetadata(ProductLine.SPOT, 1);
        runtime.putUser(new UserRuntime(ProductLine.SPOT, 1001, 1, CorePositionMode.ONE_WAY));
        for (/* 给同一用户构造不同资产余额的循环下标，遍历到上限加一条。 */ int index = 0; index <= RuntimeStateQueryService.MAX_USER_QUERY_ENTITIES; index++) {
            // 当前测试资产的整数编号，基本用例为 USDT，超限用例每次创建不同资产。
            int assetId = identities.assetId("ASSET" + index);
            runtime.putBalance(new BalanceRuntime(1001, assetId, 1, 0));
        }

        assertThat(RuntimeStateQueryService.userState(runtime, runtime.accountLaneForUser(1001), identities, 1001).tooLarge()).isTrue();
    }
}
