package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CoreOptionPremiumFeeFundingTest {
    private long sequence;
    private static final long WALLET = 10_000_000_000_000L;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void betterSellFillPaysFeeFromPremiumWithoutLosingFunds(boolean restoreBeforeClose) {
        var original = new TradingCoreRuntime(ProductLine.OPTION);
        TradingCoreRuntime runtime = original;
        try {
            var registration = new RegisterInstrumentCommand("1", ContractType.VANILLA_OPTION.ordinal(), "BTC", "USDT", "USDT",
                    10_000, 10_000_000, 1, 10_000, 5_000, 200, 500, 1_800_000_000_000L, 0, 500_000,
                    100_000_000, 1_000_000_000_000_000L, 0, 1_000_000_000_000_000L,
                    List.of(new CoreRiskLimitBracket(1, 0, 1_000_000_000_000_000L, 100_000_000, 10_000, 5_000)),
                    1, true, true, true, 0b11, 0b1111, 1);
            apply(runtime, CoreMessageType.REGISTER_INSTRUMENT, 0, TradingCommandCodec.encodeRegisterInstrument(registration));
            for (long user : List.of(101L, 202L)) apply(runtime, CoreMessageType.ADJUST_BALANCE, user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", WALLET)));
            apply(runtime, CoreMessageType.APPLY_MARK_PRICE, 0, TradingCommandCodec.encodeApplyMarkPrice(
                    new ApplyMarkPriceCommand("1", 10_000, 500_000, 505_000, 1, 1_700_000_000_000L)));
            place(runtime, 202, 1, CoreOrderSide.SELL, 10_010, CoreOrderType.LIMIT, false);
            place(runtime, 101, 2, CoreOrderSide.BUY, 0, CoreOrderType.MARKET, false);
            if (restoreBeforeClose) runtime = TradingCoreRuntime.fromSnapshot(ProductLine.OPTION, original.snapshot(71));
            place(runtime, 202, 3, CoreOrderSide.BUY, 9_990, CoreOrderType.LIMIT, true);
            place(runtime, 101, 4, CoreOrderSide.SELL, 0, CoreOrderType.MARKET, true);
            var state = runtime.tradingState();
            assertThat(state.user(101).totalUnits("USDT")).isEqualTo(WALLET - 300_000);
            assertThat(state.user(202).totalUnits("USDT")).isEqualTo(WALLET + 160_000);
            assertThat(state.treasuryState().feeBalances().get("USDT")).isEqualTo(140_000);
            for (long user : List.of(101L, 202L)) {
                assertThat(state.user(user).balances().get("USDT").lockedUnits()).isZero();
                assertThat(state.user(user).reservations()).isEmpty();
                assertThat(state.user(user).positions().values()).allMatch(position -> position.signedQuantitySteps() == 0);
            }
            assertThat(state.user(101).totalUnits("USDT") + state.user(202).totalUnits("USDT")
                    + state.treasuryState().feeBalances().get("USDT")).isEqualTo(2 * WALLET);
        } finally { if (runtime != original) runtime.close(); original.close(); }
    }

    private void place(TradingCoreRuntime runtime, long user, long id, CoreOrderSide side, long price, CoreOrderType type, boolean reduce) {
        apply(runtime, CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(
                id, "1", side, price, 1, reduce, CoreMarginMode.CROSS, CorePositionSide.NET,
                type, type == CoreOrderType.MARKET ? CoreTimeInForce.IOC : CoreTimeInForce.GTC, false, "option-fee-" + id)));
    }

    private void apply(TradingCoreRuntime runtime, CoreMessageType type, long user, byte[] payload) {
        long seq = ++sequence;
        var message = new CoreMessage(CoreMessageHeader.command(type, UUID.randomUUID(), ProductLine.OPTION,
                CommandSource.OPERATIONS, 991, seq, user, 1_700_000_000_000L + seq, seq), payload);
        var result = runtime.apply(message);
        if (result.resultCode() == CoreResultCode.MATCHING_PENDING)
            result = CoreTestCompletion.completeMatchingSynchronously(runtime, runtime.matchingSequence(message.header().commandId()),
                    1_700_000_000_000L + seq, seq);
        assertThat(result.commandStatus()).as(type + " " + result.resultCode()).isEqualTo(ResponseStatus.APPLIED);
    }
}
