package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.CoreTriggerCondition;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.product.api.ProductLine;
import java.util.LinkedHashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TriggerOrderIndexTest {

    @Test
    void candidatesPageBoundsAndResumesWithoutOmission() {
        Map<Long, CoreTriggerOrderState> triggers = new java.util.TreeMap<>();
        for (long id = 1; id <= 300; id++) {
            triggers.put(id, new CoreTriggerOrderState(id, ProductLine.SPOT, 7, "client-" + id, "",
                    "BTC-USDT", CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT,
                    CoreTriggerCondition.GREATER_OR_EQUAL, 70_000, 0, 0, 0, 0, 0,
                    CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 1, CoreMarginMode.CROSS,
                    CorePositionSide.NET, CoreTriggerOrderStatus.PENDING, 0, 0, 0, "",
                    "trace-" + id, 0, 0, 1, 1, 1));
        }
        TradingCoreState state = new TradingCoreState(ProductLine.SPOT, 0,
                Map.of(7L, CoreUserState.empty(ProductLine.SPOT, 7)), Map.of(), Map.of(),
                CoreRiskState.empty(), CoreTreasuryState.empty(), Map.of(), Map.of(), Map.of(), triggers);
        TriggerOrderIndex index = new TriggerOrderIndex(state);

        long upperId = index.maxPendingId("BTC-USDT");
        int phase = TriggerOrderIndex.PHASE_GREATER_OR_EQUAL;
        long priceCursor = Long.MAX_VALUE;
        long orderCursor = Long.MAX_VALUE;
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        int pages = 0;
        while (phase < TriggerOrderIndex.PHASE_COMPLETE) {
            TriggerOrderIndex.TriggerCandidatePage page = index.candidatesPage("BTC-USDT", 70_000, phase,
                    priceCursor, orderCursor, upperId, 64);
            assertThat(page.ids()).hasSizeLessThanOrEqualTo(64);
            ids.addAll(page.ids());
            pages++;
            if (page.complete()) break;
            phase = page.nextPhase();
            priceCursor = page.nextPriceCursor();
            orderCursor = page.nextOrderCursor();
            assertThat(pages).isLessThan(20);
        }

        assertThat(ids).hasSize(300);
        assertThat(ids).containsExactlyInAnyOrderElementsOf(triggers.keySet());
    }

    @Test
    void trailingCandidatesUseActivationAndCallbackThresholds() {
        Map<Long, CoreTriggerOrderState> triggers = new java.util.TreeMap<>();
        triggers.put(301L, trailing(301, CoreOrderSide.SELL, 100, 100_000, 100, 0, 1_000));
        triggers.put(302L, trailing(302, CoreOrderSide.SELL, 100, 100_000, 0, 0, 0));
        triggers.put(303L, trailing(303, CoreOrderSide.SELL, 0, 100_000, 0, 0, 0));
        triggers.put(304L, trailing(304, CoreOrderSide.BUY, 0, 100_000, 0, 100, 1_000));
        TradingCoreState state = new TradingCoreState(ProductLine.SPOT, 0,
                Map.of(7L, CoreUserState.empty(ProductLine.SPOT, 7)), Map.of(), Map.of(),
                CoreRiskState.empty(), CoreTreasuryState.empty(), Map.of(), Map.of(), Map.of(), triggers);
        TriggerOrderIndex index = new TriggerOrderIndex(state);

        assertThat(candidateIds(index, 95)).containsExactly(303L);
        assertThat(candidateIds(index, 105)).containsExactly(302L, 303L);
        assertThat(candidateIds(index, 115)).containsExactly(304L, 302L, 303L);
    }

    private static java.util.List<Long> candidateIds(TriggerOrderIndex index, long markPrice) {
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        int phase = TriggerOrderIndex.PHASE_GREATER_OR_EQUAL;
        long priceCursor = Long.MAX_VALUE;
        long orderCursor = Long.MAX_VALUE;
        while (phase < TriggerOrderIndex.PHASE_COMPLETE) {
            TriggerOrderIndex.TriggerCandidatePage page = index.candidatesPage("BTC-USDT", markPrice,
                    phase, priceCursor, orderCursor, index.maxPendingId("BTC-USDT"), 64);
            ids.addAll(page.ids());
            if (page.complete()) break;
            phase = page.nextPhase();
            priceCursor = page.nextPriceCursor();
            orderCursor = page.nextOrderCursor();
        }
        return ids;
    }

    private static CoreTriggerOrderState trailing(long id, CoreOrderSide side, long activationPrice,
                                                  long callbackRate, long highest, long lowest, long activatedAt) {
        return new CoreTriggerOrderState(id, ProductLine.SPOT, 7, "client-" + id, "", "BTC-USDT", side,
                CoreTriggerOrderType.TRAILING_STOP, CoreTriggerCondition.GREATER_OR_EQUAL, 0,
                activationPrice, callbackRate, highest, lowest, activatedAt, CoreOrderType.MARKET,
                CoreTimeInForce.IOC, 0, 1, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", "trace-" + id, 0, 0, 1, 1, 1);
    }
}
