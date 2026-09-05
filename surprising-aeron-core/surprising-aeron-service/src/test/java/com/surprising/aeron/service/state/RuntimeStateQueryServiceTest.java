package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeStateQueryServiceTest {

    @Test
    void buildsUserOrderAndClientQueriesFromRuntimeIndexes() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = new TradingRuntimeState();
        runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 7);
        int symbolId = identities.symbolId("BTC-USDT");
        int assetId = identities.assetId("USDT");
        long clientKey = identities.clientKey(1001, "client-71");
        long positionKey = identities.positionKey(1001, "BTC-USDT");
        runtime.putUser(new UserRuntime(ProductLine.LINEAR_PERPETUAL, 1001, 3, CorePositionMode.ONE_WAY));
        runtime.putBalance(new BalanceRuntime(1001, assetId, 1_000, 150));
        runtime.putReservation(new ReservationRuntime(71, 1001, symbolId, 1,
                ReservationKind.DERIVATIVE_MARGIN, assetId, 100, 0, 0, 2));
        runtime.putPosition(positionKey, new PositionRuntime(1001, symbolId, assetId, CoreMarginMode.CROSS,
                CorePositionSide.NET, 1, 2, 60_000, 120_000, 7, 50));
        runtime.putLeverage(new CoreLeverageKey(1001, "BTC-USDT", CoreMarginMode.CROSS), 5_000_000);
        runtime.putOrder(new OrderRuntime(71, ProductLine.LINEAR_PERPETUAL, 1001, symbolId, 1,
                CoreOrderSide.BUY, 60_000, 60_000, 2, 0, 2, false, CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "client-71",
                UUID.fromString("10000000-0000-0000-0000-000000000071"), -10, 25, 0,
                1_000, 1_000, 9, CoreOrderStatus.OPEN, 1));
        runtime.putClientOrder(1001, clientKey, 71);

        TradingCoreState snapshot = RuntimeStateMaterializer.materialize(runtime, identities);
        var user = RuntimeStateQueryService.userState(runtime, identities, 1001);
        var order = RuntimeStateQueryService.orderState(runtime, identities, 71);
        var clientOrder = RuntimeStateQueryService.clientOrderState(runtime, identities, 1001, "client-71");

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

    @Test
    void missingClientLookupDoesNotAllocateAnIdentity() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = new TradingRuntimeState();
        long before = identities.snapshot().nextClientKey();

        var result = RuntimeStateQueryService.clientOrderState(runtime, identities, 1001, "missing");

        assertThat(result.found()).isFalse();
        assertThat(identities.snapshot().nextClientKey()).isEqualTo(before);
    }

    @Test
    void rejectsAnUnboundedUserProjection() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = new TradingRuntimeState();
        runtime.setMetadata(ProductLine.SPOT, 1);
        runtime.putUser(new UserRuntime(ProductLine.SPOT, 1001, 1, CorePositionMode.ONE_WAY));
        for (int index = 0; index <= RuntimeStateQueryService.MAX_USER_QUERY_ENTITIES; index++) {
            int assetId = identities.assetId("ASSET" + index);
            runtime.putBalance(new BalanceRuntime(1001, assetId, 1, 0));
        }

        assertThat(RuntimeStateQueryService.userState(runtime, identities, 1001).tooLarge()).isTrue();
    }
}
