package com.surprising.aeron.service.settlement;

import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreCommandResultCodec;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.account.UserAccountSnapshot;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.treasury.SystemFunds;
import com.surprising.aeron.service.treasury.SystemFundsChange;
import com.surprising.product.api.ProductLine;
import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 现货成交入账接近 Long.MAX_VALUE 时保持可用与冻结之和不溢出，并将超额记入清算余额以维持守恒。 */
class SpotTradeSettlementOverflowTest {
    private long sequence;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void spotBuyBaseCreditCapsBelowLockedHeadroomAndConservesTotalUnits(boolean multiFill) {
        try (var tradingCore = new TradingCore(ProductLine.SPOT)) {
            long stepUnits = 1_000_000_000_000_000L; // 0.001 ETH at 10^18 scale
            var registration = new RegisterInstrumentCommand(
                    "1002", "ETH", "USDT", "USDT",
                    10_000, 10_000_000, 1, null,
                    200, 500, 0, -1, 0, 1, true, true, false,
                    0b11, 0b1111, stepUnits);
            apply(tradingCore, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(registration, ProductLine.SPOT));

            long initialBuyerEthAvailable = Long.MAX_VALUE - (stepUnits * 2L);
            long initialBuyerEthLocked = stepUnits; // existing sell order leaves headroom == 1 step
            long initialSellerEth = stepUnits * 10L;
            long initialBuyerUsdt = 100_000_000_000L;

            fund(tradingCore, 101, "ETH", initialBuyerEthAvailable + initialBuyerEthLocked);
            fund(tradingCore, 101, "USDT", initialBuyerUsdt);
            fund(tradingCore, 202, "ETH", initialSellerEth);

            // Lock 1 step of ETH on buyer (available drops by stepUnits, locked becomes stepUnits).
            place(tradingCore, 101, 1, CoreOrderSide.SELL, 1, 900_000, CoreTimeInForce.GTC);

            if (multiFill) {
                place(tradingCore, 202, 2, CoreOrderSide.SELL, 1, 500_000, CoreTimeInForce.GTC);
                place(tradingCore, 202, 3, CoreOrderSide.SELL, 2, 500_000, CoreTimeInForce.GTC);
            } else {
                place(tradingCore, 202, 2, CoreOrderSide.SELL, 3, 500_000, CoreTimeInForce.GTC);
            }

            // Buyer fills 3 ETH steps: 1 step fits in headroom, 2 steps overflow Long.MAX_VALUE - locked.
            var buyResult = place(tradingCore, 101, 10, CoreOrderSide.BUY, 3, 500_000, CoreTimeInForce.IOC);
            var orders = CoreCommandResultCodec.decode(buyResult.data()).orders();
            assertThat(orders).anySatisfy(order -> {
                assertThat(order.orderId()).isEqualTo(10);
                assertThat(order.status()).isEqualTo("FILLED");
            });

            var state = tradingCore.tradingState();
            AssetBalance buyerEth = state.users().get(101L).balances().get("ETH");
            assertThat(buyerEth.lockedUnits()).isEqualTo(stepUnits);
            assertThat(buyerEth.availableUnits()).isEqualTo(Long.MAX_VALUE - stepUnits);
            assertThat(BigInteger.valueOf(buyerEth.availableUnits()).add(BigInteger.valueOf(buyerEth.lockedUnits())))
                    .isEqualTo(BigInteger.valueOf(Long.MAX_VALUE));

            long clearingEth = state.treasuryState().clearingPnlBalances().getOrDefault("ETH", 0L);
            assertThat(clearingEth).isEqualTo(stepUnits * 2L);

            // Cancel buyer's open sell order to prove release(locked) cannot overflow available.
            apply(tradingCore, CoreMessageType.CANCEL_ORDER, 101,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(1)));
            state = tradingCore.tradingState();
            assertThat(state.users().get(101L).balances().get("ETH").availableUnits()).isEqualTo(Long.MAX_VALUE);
            assertThat(state.users().get(101L).balances().get("ETH").lockedUnits()).isZero();

            BigInteger finalEth = totalUnits(state.users().get(101L), "ETH")
                    .add(totalUnits(state.users().get(202L), "ETH"))
                    .add(BigInteger.valueOf(state.treasuryState().feeBalances().getOrDefault("ETH", 0L)))
                    .add(BigInteger.valueOf(state.treasuryState().clearingPnlBalances().getOrDefault("ETH", 0L)));
            BigInteger expectedEth = BigInteger.valueOf(initialBuyerEthAvailable)
                    .add(BigInteger.valueOf(initialBuyerEthLocked))
                    .add(BigInteger.valueOf(initialSellerEth));
            assertThat(finalEth).isEqualTo(expectedEth);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void spotSellQuoteCreditHandlesNetNearMaxAndOverflowConservation(boolean multiFill) {
        try (var tradingCore = new TradingCore(ProductLine.SPOT)) {
            // fee = 500 ppm (taker negative feeDelta); quote per step = 20_000 * 100 = 2_000_000
            var registration = new RegisterInstrumentCommand(
                    "1002", "ETH", "USDT", "USDT",
                    100, 1, 1, null,
                    200, 500, 0, -1, 0, 1, true, true, false,
                    0b11, 0b1111, 1);
            apply(tradingCore, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(registration, ProductLine.SPOT));

            long quotePerStep = 2_000_000L;
            long sellerQuoteStart = Long.MAX_VALUE - (quotePerStep * 2L);
            long buyerQuoteStart = 100_000_000L;

            fund(tradingCore, 101, "USDT", buyerQuoteStart);
            fund(tradingCore, 202, "ETH", 10L);
            fund(tradingCore, 202, "USDT", sellerQuoteStart);

            if (multiFill) {
                place(tradingCore, 101, 1, CoreOrderSide.BUY, 1, 20_000, CoreTimeInForce.GTC);
                place(tradingCore, 101, 2, CoreOrderSide.BUY, 2, 20_000, CoreTimeInForce.GTC);
            } else {
                place(tradingCore, 101, 1, CoreOrderSide.BUY, 3, 20_000, CoreTimeInForce.GTC);
            }

            // Seller sells 3 steps at 20,000 -> gross quote = 6,000,000, taker fee = 3,000, net = 5,997,000.
            // 2 * quotePerStep = 4,000,000 fits in headroom; excess 1,997,000 goes to clearingPnl.
            apply(tradingCore, CoreMessageType.PLACE_ORDER, 202, TradingCommandCodec.encodePlaceOrder(
                    new PlaceOrderCommand(10, "1002", CoreOrderSide.SELL, 20_000, 3,
                            false, CoreMarginMode.CROSS, CorePositionSide.NET,
                            CoreOrderType.LIMIT, CoreTimeInForce.IOC, false, "sell-overflow-10")));

            var state = tradingCore.tradingState();
            AssetBalance sellerUsdt = state.users().get(202L).balances().get("USDT");
            assertThat(sellerUsdt.availableUnits()).isEqualTo(Long.MAX_VALUE);
            assertThat(state.treasuryState().clearingPnlBalances().getOrDefault("USDT", 0L))
                    .isEqualTo(1_997_000L);
            assertThat(state.treasuryState().feeBalances().getOrDefault("USDT", 0L))
                    .isEqualTo(4_200L); // maker 1_200 + taker 3_000

            BigInteger finalUsdt = totalUnits(state.users().get(101L), "USDT")
                    .add(totalUnits(state.users().get(202L), "USDT"))
                    .add(BigInteger.valueOf(state.treasuryState().feeBalances().getOrDefault("USDT", 0L)))
                    .add(BigInteger.valueOf(state.treasuryState().clearingPnlBalances().getOrDefault("USDT", 0L)));
            assertThat(finalUsdt).isEqualTo(BigInteger.valueOf(buyerQuoteStart).add(BigInteger.valueOf(sellerQuoteStart)));
        }
    }

    @Test
    void creditAvailablePreservesLockedHeadroomAndHandlesZeroExactHeadroom() {
        var change = new SystemFundsChange();
        long available = Long.MAX_VALUE - 500L;
        long locked = 300L;

        long updated = SpotTradeSettlementService.creditAvailable(available, locked, 350L, 4, change);
        assertThat(updated).isEqualTo(Long.MAX_VALUE - locked);

        SystemFunds funds = new SystemFunds();
        change.apply(funds);
        assertThat(funds.clearingPnl(4)).isEqualTo(150L);

        // No further headroom when available + locked == Long.MAX_VALUE.
        var zeroChange = new SystemFundsChange();
        long saturated = SpotTradeSettlementService.creditAvailable(updated, locked, 99L, 4, zeroChange);
        assertThat(saturated).isEqualTo(updated);
        zeroChange.apply(funds);
        assertThat(funds.clearingPnl(4)).isEqualTo(249L);
    }

    private static BigInteger totalUnits(UserAccountSnapshot user, String asset) {
        if (user == null || user.balances() == null) return BigInteger.ZERO;
        AssetBalance balance = user.balances().get(asset);
        if (balance == null) return BigInteger.ZERO;
        return BigInteger.valueOf(balance.availableUnits()).add(BigInteger.valueOf(balance.lockedUnits()));
    }

    private void fund(TradingCore tradingCore, long user, String asset, long units) {
        apply(tradingCore, CoreMessageType.ADJUST_BALANCE, user,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, units)));
    }

    private CoreResponse place(TradingCore tradingCore, long user, long id, CoreOrderSide side,
                               long quantity, long price, CoreTimeInForce tif) {
        return apply(tradingCore, CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(
                new PlaceOrderCommand(id, "1002", side, price, quantity,
                        false, CoreMarginMode.CROSS, CorePositionSide.NET,
                        CoreOrderType.LIMIT, tif, false, "spot-overflow-" + id)));
    }

    private CoreResponse apply(TradingCore tradingCore, CoreMessageType type, long user, byte[] payload) {
        long seq = ++sequence;
        var message = new CoreMessage(CoreMessageHeader.command(type, UUID.randomUUID(), ProductLine.SPOT,
                CommandSource.OPERATIONS, 991, seq, user, 1_700_000_000_000L + seq, seq), payload);
        var result = tradingCore.apply(message);
        long directDeadline = System.nanoTime() + 5_000_000_000L;
        while (result == null && tradingCore.directCommand.active() && System.nanoTime() < directDeadline) {
            result = tradingCore.directCommand.poll();
            if (result == null) Thread.onSpinWait();
        }
        if (result == null && tradingCore.directCommand.active()) {
            throw new AssertionError("Direct command timed out: " + type);
        }
        if (result.resultCode() == CoreResultCode.MATCHING_PENDING) {
            result = CoreTestCompletion.completeMatchingSynchronously(
                    tradingCore, tradingCore.matchingSequence(message.header().commandId()), 1_700_000_000_000L + seq, seq);
        }
        assertThat(result.commandStatus()).as(type + " " + result.resultCode()).isEqualTo(ResponseStatus.APPLIED);
        return result;
    }
}
