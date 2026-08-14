package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AlgoOrderType;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PlaceAlgoOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.config.TradingOrderProperties;
import org.junit.jupiter.api.Test;

class AlgoOrderServiceTest {
    @Test
    void twapRejectsChildQuantityThatCannotFinishInsideDuration() {
        AlgoOrderService service = service(ProductLine.LINEAR_PERPETUAL);
        assertThatThrownBy(() -> service.place(new PlaceAlgoOrderRequest(
                1001L, "twap-small-child", "BTC-USDT", AlgoOrderType.TWAP, OrderSide.BUY,
                0L, 100L, 10L, 10L, 20L, MarginMode.CROSS, PositionSide.NET,
                false, false, TimeInForce.IOC, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("childQuantitySteps is too small");
    }

    @Test
    void allProductLinesApplyTheSameAlgoValidation() {
        for (ProductLine line : ProductLine.values()) {
            AlgoOrderService service = service(line);
            assertThatThrownBy(() -> service.place(new PlaceAlgoOrderRequest(
                    1001L, "algo-" + line, "BTC-USDT", AlgoOrderType.TWAP, OrderSide.BUY,
                    0L, 100L, 10L, 10L, 20L, MarginMode.CROSS, PositionSide.NET,
                    false, false, TimeInForce.IOC, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("childQuantitySteps is too small");
        }
    }

    private AlgoOrderService service(ProductLine line) {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(line);
        properties.getAlgo().setMinDurationSeconds(1);
        properties.getAlgo().setMinIntervalSeconds(1);
        return new AlgoOrderService(properties, mock(OrderService.class), mock(AeronAlgoOrderStore.class),
                mock(AeronOrderIdGenerator.class));
    }
}
