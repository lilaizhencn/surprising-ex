package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CorePositionView;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.orchestration.pipeline.CoreTestCompletion;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class CoreUserRevisionTest {
    private static final long TIME = 1_700_000_000_000L, BALANCE = 1_000_000;
    private long sequence;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void admissionFillAndTerminalReleaseKeepTheirExactRevisionCounts(boolean reverse) {
        try (var core = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            send(core, CoreMessageType.REGISTER_INSTRUMENT, 0, TradingCommandCodec.encodeRegisterInstrument(
                    new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT",
                            1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0)));
            send(core, CoreMessageType.APPLY_MARK_PRICE, 0, TradingCommandCodec.encodeApplyMarkPrice(
                    new ApplyMarkPriceCommand("1", 100, 1, TIME)));
            for (long user : new long[]{7, 8}) {
                send(core, CoreMessageType.ADJUST_BALANCE, user, TradingCommandCodec.encodeBalanceAdjustment(
                        new BalanceAdjustmentCommand("USDT", BALANCE)));
                assertThat(CoreTestCompletion.userState(core, user).revision()).isOne();
            }
            for (int index = 0; index < 4; index++) {
                long user = (index & 1) == 0 ? 7 : 8;
                boolean sell = ((index & 1) == 0) != (reverse && index >= 2);
                send(core, CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(
                        new PlaceOrderCommand(100 + index, "1", sell ? CoreOrderSide.SELL : CoreOrderSide.BUY,
                                100, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                                CoreTimeInForce.GTC, false, "revision-" + index)));
                if (index == 0) {
                    var resting = CoreTestCompletion.userState(core, 7);
                    assertThat(resting.revision()).isEqualTo(2);
                    assertThat(resting.reservations()).singleElement()
                            .satisfies(v -> assertThat(v.reservedUnits()).isEqualTo(11));
                }
                if ((index & 1) == 0) continue;
                int pairs = (index + 1) / 2;
                for (long uid : new long[]{7, 8}) {
                    var state = CoreTestCompletion.userState(core, uid);
                    long expected = uid == 7 ? 1 + 3L * pairs : 1 + 2L * pairs;
                    if (uid == 8 && reverse && pairs == 2) expected++;
                    assertThat(state.revision()).as("user=%s pairs=%s", uid, pairs).isEqualTo(expected);
                    assertThat(state.positionMode()).isEqualTo(CorePositionMode.ONE_WAY);
                    assertThat(state.reservations()).isEmpty();
                    long margin = state.positions().stream().mapToLong(CorePositionView::positionMarginUnits).sum();
                    assertThat(state.balances()).singleElement().satisfies(balance -> {
                        assertThat(balance.availableUnits() + balance.lockedUnits()).isEqualTo(BALANCE);
                        assertThat(balance.lockedUnits()).isEqualTo(margin);
                    });
                    if (reverse && pairs == 2)
                        assertThat(state.positions()).allSatisfy(p -> assertThat(p.signedQuantitySteps()).isZero());
                }
            }
        }
    }

    private void send(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        long id = ++sequence;
        var message = new CoreMessage(CoreMessageHeader.command(type, new UUID(948, id),
                ProductLine.LINEAR_PERPETUAL, CommandSource.OPERATIONS, 948, id, user, TIME, id), payload);
        assertThat(CoreTestCompletion.applyAsynchronously(core, message).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
    }
}
