package com.surprising.aeron.service.orchestration.replay;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.*;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.account.CoreUserState;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.ledger.CoreTreasuryState;
import com.surprising.aeron.service.state.model.*;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.model.CoreMarkPriceState;
import com.surprising.aeron.service.state.model.CorePositionState;
import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class CommittedPortfolioSnapshotTest {
    private static final long NOW = 1_792_454_400_000L;

    @ParameterizedTest @EnumSource(ContractType.class)
    void completeEquityUsesTheProductSettlementMath(ContractType type) {
        var state = state(type, NOW, 1_000, 20);
        var snapshot = CommittedPortfolioSnapshot.capture(state, NOW, NOW, 60_000, Set.of());
        var user = snapshot.users().get(7L);
        assertTrue(user.complete());
        String expected = type == ContractType.SPOT ? "1020"
                : type.isOption() ? "1280" : type.isInverse() ? "5635" : "1080";
        assertEquals(expected, user.netAssetUnits().get(type.isInverse() ? "BTC" : "USDT"));
        assertEquals(type.productLine(), snapshot.productLine());
        assertThrows(UnsupportedOperationException.class, () -> user.netAssetUnits().put("BTC", "1"));
    }

    @Test void unavailableOrFutureMarksCannotPublishPartialEquity() {
        for (long markTime : new long[]{NOW - 60_001, NOW + 1}) {
            var snapshot = CommittedPortfolioSnapshot.capture(state(ContractType.LINEAR_PERPETUAL, markTime, 1000, 0), NOW, NOW, 60_000, Set.of());
            assertFalse(snapshot.users().get(7L).complete());
        }
    }

    @Test void balancesAreExactAboveLongAndDoubleRangesAndMarginIsNotCountedTwice() {
        var snapshot = CommittedPortfolioSnapshot.capture(state(ContractType.SPOT, NOW, Long.MAX_VALUE, Long.MAX_VALUE), NOW, NOW, 60_000, Set.of());
        assertEquals("18446744073709551614", snapshot.users().get(7L).netAssetUnits().get("USDT"));
    }

    @Test void pendingTransfersMakeTheUserUnavailableAndFutureStateIsRejected() {
        var state = state(ContractType.SPOT, NOW, 1000, 0);
        var pending = CommittedPortfolioSnapshot.capture(state, NOW, NOW, 60_000, Set.of(7L, 8L));
        assertFalse(pending.users().get(7L).complete());
        assertFalse(pending.users().get(8L).complete());
        assertTrue(pending.users().get(8L).netAssetUnits().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> CommittedPortfolioSnapshot.capture(state, NOW + 1, NOW, 60_000, Set.of()));
    }

    @Test void queryingACommittedReplayDoesNotChangeItsBusinessState() {
        try (var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            var header = CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE, new UUID(77, 1), ProductLine.SPOT,
                    CommandSource.OPERATIONS, 7, 1, 7, NOW, 1);
            replay.apply(CoreMessageCodec.encode(new CoreMessage(header, TradingCommandCodec.encodeBalanceAdjustment(
                    new BalanceAdjustmentCommand("USDT", 9007199254740993L)))), NOW, 1);
            long before = replay.businessHash();
            assertEquals("9007199254740993", replay.portfolioSnapshot(NOW, 60_000).users().get(7L).netAssetUnits().get("USDT"));
            assertEquals(before, replay.businessHash());
        }
    }

    private TradingCoreState state(ContractType type, long markTime, long available, long locked) {
        String settleAsset = type.isInverse() ? "BTC" : "USDT";
        var instrument = CoreInstrument.from(type.productLine(), new RegisterInstrumentCommand("604", type.ordinal(), "BTC", "USDT", settleAsset,
                1, 1, type.isInverse() ? 1_000_000 : 1, 100_000, 50_000, 0, 0, type.isOption() || type.isDelivery() ? NOW + 86_400_000 : 0,
                type.isOption() ? 0 : -1, type.isOption() ? 100 : 0));
        var positions = type == ContractType.SPOT ? Map.<String, CorePositionState>of()
                : Map.of("604", new CorePositionState("604", settleAsset, 2, 100, 200, 0, 0));
        var user = new CoreUserState(type.productLine(), 7, 1, Map.of(settleAsset, new AssetBalance(settleAsset, available, locked)), Map.of(), positions);
        var risk = new CoreRiskState(Map.of("604", new CoreMarkPriceState("604", 130, 130, 130, 1, markTime)), Map.of(), Map.of(), Map.of(), 1);
        return new TradingCoreState(type.productLine(), 1, Map.of(7L, user), Map.of(), Map.of("604", instrument), risk, CoreTreasuryState.empty());
    }
}
