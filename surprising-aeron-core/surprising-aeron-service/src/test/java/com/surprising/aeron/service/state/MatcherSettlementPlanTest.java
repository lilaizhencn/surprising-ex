package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.*;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.matching.MatcherEventFixtures;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.MatcherResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatcherSettlementPlanTest {
    @Test
    void sharedMakerQuantityIsValidatedAcrossItemsBeforeAnyLaneApplies() {
        try (var runtime = new TradingRuntimeState()) {
            var identities = new RuntimeIdentityRegistry();
            int symbol = identities.symbolId("BTC-USDT");
            var instrument = instrument();
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
            runtime.putInstrument(instrument);
            var maker = order(10, 20, symbol, CoreOrderSide.SELL, 5);
            var first = order(11, 21, symbol, CoreOrderSide.BUY, 3);
            var second = order(12, 22, symbol, CoreOrderSide.BUY, 3);
            runtime.putOrder(maker); runtime.putOrder(first); runtime.putOrder(second);
            var scratch = new MatcherSettlementPlan.BatchValidationScratch();
            assertThat(MatcherSettlementPlan.buildBatchItem(1, first, instrument, fill(3), runtime, identities, scratch).tradeCount()).isEqualTo(1);
            assertThatThrownBy(() -> MatcherSettlementPlan.buildBatchItem(1, second, instrument, fill(3), runtime, identities, scratch))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("exceeds");
            assertThat(runtime.order(10).remainingQuantitySteps()).isEqualTo(5);
            scratch.clear();
            MatcherSettlementPlan.buildBatchItem(1, first, instrument, fill(3), runtime, identities, scratch);
            assertThat(MatcherSettlementPlan.buildBatchItem(1, second, instrument, fill(2), runtime, identities, scratch).tradeCount()).isEqualTo(1);
            assertThatThrownBy(() -> MatcherSettlementPlan.buildBatchItem(1, first, instrument, fill(1), runtime, identities, scratch))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
    private static OrderRuntime order(long id,long user,int symbol,CoreOrderSide side,long qty) {
        return new OrderRuntime(id,user,symbol,1,side,100,false,CoreMarginMode.CROSS,CorePositionSide.NET,
                CoreOrderType.LIMIT,CoreTimeInForce.GTC,0,0,qty,0,qty,false);
    }
    private static CoreMatchingResult fill(long quantity) {
        return new CoreMatchingResult(true,"SUCCESS",List.of(),0,true,
                new CoreMatchingResult.NativeCommand(0,0,0,0,0,0,0,0,-1),new CoreMatchingResult.MatcherPrefix(0,0),null,
                List.of(MatcherEventFixtures.trade(10,20,100,quantity,false,false)),
                new MatcherResult.MarketData(List.of(),List.of(),0,0)).withCoreSequence(1);
    }
    private static CoreInstrumentState instrument() {
        return new CoreInstrumentState("BTC-USDT",1,ContractType.LINEAR_PERPETUAL,"BTC","USDT","USDT",
                1,1,1_000_000,100_000,50_000,-10,25,0,null,0,10_000_000,Long.MAX_VALUE,0,1,
                List.of(new CoreRiskLimitBracket(1,0,Long.MAX_VALUE,10_000_000,100_000,50_000)));
    }
}
