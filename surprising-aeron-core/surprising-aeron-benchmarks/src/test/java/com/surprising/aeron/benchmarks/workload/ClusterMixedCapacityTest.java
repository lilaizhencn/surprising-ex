package com.surprising.aeron.benchmarks.workload;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.LaneTopology;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ClusterMixedCapacityTest {
    @Test void diagnosticCapacityMakesTheActualSessionLimitExplicit() {
        var capacity=ClusterMixedCapacityMain.commandCapacity(2048,2048);
        assertThat(capacity.commandSessions()).isEqualTo(1);
        assertThat(capacity.maxCommandInFlightPerSession()).isEqualTo(2048);
        assertThat(capacity.commandMailboxCapacity()).isEqualTo(2048);
        assertThatThrownBy(()->ClusterMixedCapacityMain.commandCapacity(256,1024)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ClusterMixedCapacityMain.commandCapacity(0,0)).isInstanceOf(IllegalArgumentException.class);
    }
    private static CoreResponse response(CoreOrderBatchResult.Item... items) {
        return new CoreResponse(ResponseStatus.APPLIED,ResponseStatus.APPLIED,CoreResultCode.NONE,1,0,0,
                TradingOrderBatchCodec.encodeResult(new CoreOrderBatchResult(List.of(items))));
    }

    @Test void realBatchCodecCountsFillsNotQuantityAndAllowsRetiredOrderViews() {
        var item=new CoreOrderBatchResult.Item(0,10,0,0,ResponseStatus.APPLIED,CoreResultCode.NONE,null,
                List.of(new CoreExecutionView(10,11,1,2,101,2),new CoreExecutionView(10,12,1,3,101,3)));
        assertThat(ClusterMixedCapacityMain.validateBatch(response(item),new long[]{10})).isEqualTo(2);
    }

    @Test void batchStatusIdentityAndExecutionIdentityAreRequired() {
        var wrong=new CoreOrderBatchResult.Item(0,10,0,0,ResponseStatus.APPLIED,CoreResultCode.NONE,null,
                List.of(new CoreExecutionView(99,11,1,2,101,1)));
        assertThatThrownBy(()->ClusterMixedCapacityMain.validateBatch(response(wrong),new long[]{10})).isInstanceOf(IllegalStateException.class);
        var rejected=new CoreOrderBatchResult.Item(0,10,0,0,ResponseStatus.REJECTED,CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE,null,List.of());
        assertThatThrownBy(()->ClusterMixedCapacityMain.validateBatch(response(rejected),new long[]{10})).isInstanceOf(IllegalStateException.class);
        var applied=new CoreOrderBatchResult.Item(0,10,0,0,ResponseStatus.APPLIED,CoreResultCode.NONE,null,List.of());
        assertThatThrownBy(()->ClusterMixedCapacityMain.validateBatch(response(applied),new long[]{11})).isInstanceOf(IllegalStateException.class);
    }

    @Test void batchOrderCursorMustNotReuseAnotherItemsIdentity() {
        var order = new CoreOrderStateView(11, com.surprising.product.api.ProductLine.SPOT, 1,
                "BTC-USDT", 1, CoreOrderSide.BUY, 100, 1, 0, 1, false, "OPEN", 1);
        var item = new CoreOrderBatchResult.Item(0, 10, 0, 0, ResponseStatus.APPLIED,
                CoreResultCode.NONE, order, List.of());
        assertThatThrownBy(() -> ClusterMixedCapacityMain.validateBatch(response(item), new long[]{10}))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("order state mismatch");
    }

    @Test void originalPopulationCoversFourLanesAndHftPairsCrossLanes() {
        var users=ClusterMixedCapacityMain.users();var topology=LaneTopology.configured(false);
        assertThat(users).hasSize(1769).doesNotHaveDuplicates();
        for(int i=0;i<users.size();i++)assertThat(topology.accountLaneId(users.get(i))).isEqualTo(i&3);
        for(int i=0;i<256;i++)assertThat(topology.accountLaneId(users.get(1257+i)))
                .isNotEqualTo(topology.accountLaneId(users.get(1513+(i+1)%256)));
    }

    @Test void financialAuditIncludesEveryTreasuryLedgerAndSubtractsDeficit() {
        var t=new CoreTreasuryAssetView("USDT",2,3,5,7,11,13,17);
        assertThat(ClusterMixedCapacityMain.treasuryFunds(t)).isEqualTo(48);
        assertThat(ClusterMixedCapacityMain.expectedFunds()).isEqualTo(1_768_000_000_125L);
    }

    @Test void closedPositionWithRealizedLossIsFlatButLiveExposureOrMarginIsNot() {
        assertThat(ClusterMixedCapacityMain.flat(List.of(new CorePositionView("S","USDT",0,0,0,0,-990,0)))).isTrue();
        assertThat(ClusterMixedCapacityMain.flat(List.of(new CorePositionView("S","USDT",1,1,100,100,0,10)))).isFalse();
        assertThat(ClusterMixedCapacityMain.flat(List.of(new CorePositionView("S","USDT",1,0,0,0,-990,10)))).isFalse();
    }
}
