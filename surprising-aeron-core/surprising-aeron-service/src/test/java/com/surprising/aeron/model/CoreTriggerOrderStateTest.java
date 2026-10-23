package com.surprising.aeron.model;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.CoreTriggerCondition;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.aeron.service.core.CoreStateTestFixtures;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoreTriggerOrderStateTest {
    @Test
    void bindingAndPublicViewKeepIdentityForEveryProduct() {
        for (ProductLine line : ProductLine.values()) {
            var instrument = instrument(line, "1");
            var state = pending(instrument);
            var view = state.view();

            assertThat(state.instrumentId()).isEqualTo("1");
            assertThat(state.productLine()).isEqualTo(line);
            assertThat(view.instrumentId()).isEqualTo("1");
            assertThat(view.productLine()).isEqualTo(line);
            assertThat(CoreTriggerOrderState.from(view, instrument)).isEqualTo(state);
        }
    }

    @Test
    void restoringKeepsCreationFeesInsteadOfCurrentInstrumentDefaults() {
        for (ProductLine line : ProductLine.values()) {
            var historical = pending(instrument(line, "1")).withExecutionSnapshot(-123, 456);
            var currentInstrument = instrument(line, "1");
            var restored = CoreTriggerOrderState.from(historical.view(), currentInstrument);

            assertThat(currentInstrument.makerFeeRatePpm()).isZero();
            assertThat(currentInstrument.takerFeeRatePpm()).isZero();
            assertThat(restored.instrument()).isSameAs(currentInstrument);
            assertThat(restored.makerFeeRatePpm()).isEqualTo(-123);
            assertThat(restored.takerFeeRatePpm()).isEqualTo(456);
            assertThat(restored.view()).isEqualTo(historical.view());
        }
    }

    @Test
    void bindingRejectsAnotherInstrumentId() {
        for (ProductLine line : ProductLine.values()) {
            var view = pending(instrument(line, "1")).view();
            assertThatThrownBy(() -> CoreTriggerOrderState.from(view, instrument(line, "2")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("instrument mismatch");
        }
    }

    @Test
    void bindingRejectsAnotherProductWithTheSameId() {
        for (ProductLine line : ProductLine.values()) {
            var view = pending(instrument(line, "1")).view();
            var other = line == ProductLine.SPOT ? ProductLine.LINEAR_PERPETUAL : ProductLine.SPOT;
            assertThatThrownBy(() -> CoreTriggerOrderState.from(view, instrument(other, "1")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("instrument mismatch");
        }
    }

    private static CoreInstrument instrument(ProductLine line, String id) {
        return CoreInstrument.from(line, CoreStateTestFixtures.instrument(line, id, "BTC",
                line == ProductLine.INVERSE_PERPETUAL || line == ProductLine.INVERSE_DELIVERY ? "USD" : "USDT",
                CoreStateTestFixtures.settleAsset(line)));
    }

    private static CoreTriggerOrderState pending(CoreInstrument instrument) {
        return new CoreTriggerOrderState(1, 7, "trigger-1", "", instrument,
                CoreOrderSide.BUY, CoreTriggerOrderType.STOP_LOSS, CoreTriggerCondition.GREATER_OR_EQUAL,
                100, 0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                100, 1, CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                0, 0, 0, "", 0, 0, 100, 100, 1);
    }
}
