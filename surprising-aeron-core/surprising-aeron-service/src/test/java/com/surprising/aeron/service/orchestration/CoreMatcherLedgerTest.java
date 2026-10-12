package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import com.surprising.instrument.api.model.ContractType;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class CoreMatcherLedgerTest {
    private long sequence;
    private static final long TIME = 1_700_000_000_000L;

    @Test
    void matchingAndBatchFeesUseOriginalLedgerAndAccountFactsRemainConserved() throws Exception {
        try (var state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applied(state, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(),
                            "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 10_000, 10_000, 0, -1, 0)));
            applied(state, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME)));
            for (long user : new long[]{1001, 1002}) applied(state, CoreMessageType.ADJUST_BALANCE, user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100_000)));
            assertThat(state.runtimeState.systemLedgerStarted()).isTrue();
            assertThatThrownBy(() -> state.runtimeState.treasury().fee(state.identities.assetId("USDT")))
                    .isInstanceOf(IllegalStateException.class);
            try (var guard = new OwnerAccountReadGuard(state.runtimeState)) {
                applied(state, CoreMessageType.PLACE_ORDER, 1002, TradingCommandCodec.encodePlaceOrder(order(1, CoreOrderSide.SELL, 100, 3)));
                applied(state, CoreMessageType.PLACE_ORDER, 1001, TradingCommandCodec.encodePlaceOrder(order(2, CoreOrderSide.BUY, 100, 1)));
                var response = applied(state, CoreMessageType.PLACE_ORDER_BATCH, 1001,
                        TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(order(3, CoreOrderSide.BUY, 100, 1), order(4, CoreOrderSide.BUY, 100, 1)))));
                assertThat(TradingOrderBatchCodec.firstNonAppliedItem(response, 2)).isEqualTo(-1);
                applied(state, CoreMessageType.PLACE_ORDER, 1002, TradingCommandCodec.encodePlaceOrder(order(5, CoreOrderSide.SELL, 110, 1)));
                applied(state, CoreMessageType.CANCEL_ORDER_BATCH, 1002,
                        TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(new CancelOrderCommand(5)))));
            }
            var trading = state.tradingState();
            assertThat(trading.treasuryState().feeBalances()).containsEntry("USDT", 6L);
            assertThat(trading.users().values().stream().mapToLong(u -> u.totalUnits("USDT")).sum()).isEqualTo(199_994);
            assertThat(trading.orders()).isEmpty();
            assertThat(trading.users().values()).allSatisfy(user -> assertThat(user.reservations()).isEmpty());
            state.assertClusterCallbackComplete();
        }
    }

    private PlaceOrderCommand order(long id, CoreOrderSide side, long price, long qty) {
        return new PlaceOrderCommand(id, "1", side, price, qty, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "ledger-" + id);
    }

    private CoreResponse applied(TradingCoreRuntime state, CoreMessageType type, long user, byte[] payload) {
        long seq = ++sequence;
        var message = new CoreMessage(CoreMessageHeader.command(type, new UUID(927, seq), ProductLine.LINEAR_PERPETUAL,
                CommandSource.OPERATIONS, 927, seq, user, TIME, seq), payload);
        var response = CoreTestCompletion.applyAsynchronously(state, message);
        assertThat(response.commandStatus()).as("%s: %s", type, response.resultCode()).isEqualTo(ResponseStatus.APPLIED);
        return response;
    }
}
