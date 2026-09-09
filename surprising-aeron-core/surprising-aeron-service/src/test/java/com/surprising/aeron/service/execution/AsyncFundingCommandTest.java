package com.surprising.aeron.service.execution;

import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class AsyncFundingCommandTest {
    private long sequence;
    private static final long TIME = 1_700_000_000_000L;

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "INVERSE_PERPETUAL"})
    void fundingPagesMatchSynchronousLedgerAndContinueAfterSnapshot(ProductLine line) {
        try (var state = new TradingCoreRuntime(line)) {
            var type = ContractType.valueOf(line.contractTypeCode());
            String asset = type.isInverse() ? "BTC" : "USDT";
            applied(state, message(line, CoreMessageType.UPSERT_INSTRUMENT, 0,
                    TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand("BTC-USDT", 1,
                            type.ordinal(), "BTC", "USDT", asset, 1, 1, type.isInverse() ? 1000 : 1,
                            100_000, 50_000, 0, 0, 0, -1, 0))));
            applied(state, message(line, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 1, TIME))));
            for (long user = 1; user <= 8; user++) {
                applied(state, message(line, CoreMessageType.ADJUST_BALANCE, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 100_000))));
                applied(state, message(line, CoreMessageType.PLACE_ORDER, user,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100 + user, "BTC-USDT", 1,
                                user % 2 == 1 ? CoreOrderSide.SELL : CoreOrderSide.BUY, 100, 10, false,
                                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                                CoreTimeInForce.GTC, false, "funding-" + user))));
            }
            long funds = economicUnits(state, asset);
            try (var restored = TradingCoreRuntime.fromSnapshot(line, state.snapshot(100))) {
                long cursor = 0;
                for (int page = 0; page < 8; page++) {
                    var command = message(line, CoreMessageType.APPLY_FUNDING, 0,
                            TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(900, "BTC-USDT", 1,
                                    10_000, cursor, 1)));
                    CoreResponse async = page == 0 ? fundingWithPendingBoundaryChecks(state, command) : applied(state, command);
                    CoreResponse sync = restored.apply(command);
                    assertThat(async.commandStatus()).isEqualTo(sync.commandStatus());
                    assertThat(async.resultCode()).isEqualTo(sync.resultCode());
                    assertThat(async.data()).isEqualTo(sync.data());
                    assertThat(state.tradingState().businessStateHash()).isEqualTo(restored.tradingState().businessStateHash());
                    assertThat(economicUnits(state, asset)).isEqualTo(funds);
                    long hash = state.stateHash();
                    assertThat(CoreTestCompletion.applyAsynchronously(state, command).status()).isEqualTo(ResponseStatus.DUPLICATE);
                    assertThat(state.stateHash()).isEqualTo(hash);
                    var progress = state.runtimeState.treasury().fundingProgress(state.identities.symbolId("BTC-USDT"));
                    if (progress == null) break;
                    cursor = progress.nextCursorUserId();
                }
                assertThat(state.tradingState().treasuryState().fundingSettlements()).containsEntry("BTC-USDT", 900L);
                try (var after = TradingCoreRuntime.fromSnapshot(line, state.snapshot(200))) {
                    assertThat(after.stateHash()).isEqualTo(state.stateHash());
                    var next = message(line, CoreMessageType.APPLY_FUNDING, 0,
                            TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(901, "BTC-USDT", 1, -10_000)));
                    assertThat(applied(state, next).data()).isEqualTo(applied(after, next).data());
                    assertThat(state.tradingState().businessStateHash()).isEqualTo(after.tradingState().businessStateHash());
                    assertThat(economicUnits(state, asset)).isEqualTo(funds);
                }
            }
        }
    }

    private static CoreResponse fundingWithPendingBoundaryChecks(TradingCoreRuntime state, CoreMessage message) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!state.runtimeState.tryAcquireOwnerLaneAccess()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("handoff timed out");
            Thread.onSpinWait();
        }
        state.runtimeState.enterAsynchronousCommandScope();
        try {
            assertThat(state.applyDecodedCommand(message, TIME, message.header().sourceSequence(), null, false)).isNull();
            assertThat(state.hasPendingDirectCommand()).isTrue();
            assertThatThrownBy(() -> state.apply(message)).hasMessageContaining("still active");
            assertThatThrownBy(state::assertClusterCallbackComplete).hasMessageContaining("unfinished business");
            assertThatThrownBy(() -> state.snapshots.beginSnapshot(150, deadline))
                    .isInstanceOf(TradingCoreRuntime.SnapshotNotReadyException.class);
            CoreResponse response;
            while ((response = state.pollDirectCommand()) == null) {
                if (System.nanoTime() >= deadline) throw new AssertionError("funding timed out");
                Thread.onSpinWait();
            }
            assertThat(response.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.hasPendingDirectCommand()).isFalse();
            return response;
        } finally {
            state.runtimeState.exitAsynchronousCommandScope();
            state.runtimeState.releaseOwnerLaneAccess();
        }
    }

    private static long economicUnits(TradingCoreRuntime runtime, String asset) {
        var state = runtime.tradingState();
        long total = 0;
        for (var user : state.users().values()) total = Math.addExact(total, user.totalUnits(asset));
        var treasury = state.treasuryState();
        total = Math.addExact(total, treasury.feeBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.insuranceBalances().getOrDefault(asset, 0L));
        total = Math.subtractExact(total, treasury.insuranceDeficits().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.liquidationFeeBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.fundingResidualBalances().getOrDefault(asset, 0L));
        return Math.addExact(total, treasury.roundingResidualBalances().getOrDefault(asset, 0L));
    }

    private CoreMessage message(ProductLine line, CoreMessageType type, long user, byte[] data) {
        long seq = ++sequence;
        return new CoreMessage(CoreMessageHeader.command(type, new UUID(91, seq), line,
                CommandSource.OPERATIONS, 871, seq, user, TIME, seq), data);
    }
    private CoreResponse applied(TradingCoreRuntime state, CoreMessage message) {
        var response = CoreTestCompletion.applyAsynchronously(state, message);
        assertThat(response.commandStatus()).as("%s %s", message.header().messageType(), response.resultCode())
                .isEqualTo(ResponseStatus.APPLIED);
        return response;
    }
}
