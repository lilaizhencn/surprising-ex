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
    @Test void creationRecoveryUsesOnlyCommittedReceiptsAndParentState() {
        var properties = new TradingOrderProperties(); properties.getKafka().setProductLine(ProductLine.SPOT);
        var store = mock(AeronAlgoOrderStore.class);
        var projection = mock(com.surprising.trading.order.repository.AlgoOrderProjectionRepository.class);
        var receipts = mock(com.surprising.trading.order.repository.AeronOrderProjectionRepository.class);
        var service = new AlgoOrderService(properties,mock(OrderService.class),store,projection,receipts);
        var command = StableOrderIdentity.algoCommandId(ProductLine.SPOT,7,"original");
        var pending = new com.surprising.trading.api.model.OrderCommandReceipt(command,"RESULT_UNKNOWN","RESULT_UNKNOWN","",null,java.util.List.of(),null,null);
        org.mockito.Mockito.when(receipts.algoSubmission(ProductLine.SPOT,7,command)).thenReturn(pending);
        org.assertj.core.api.Assertions.assertThat(service.submission(7,"original").outcome()).isEqualTo("RESULT_UNKNOWN");
        org.mockito.Mockito.verifyNoInteractions(store);
        var parent = mock(com.surprising.aeron.protocol.CoreAlgoOrderView.class);
        org.mockito.Mockito.when(parent.algoOrderId()).thenReturn(StableOrderIdentity.algoOrderId(ProductLine.SPOT,7,"original"));
        org.mockito.Mockito.when(projection.byClient(ProductLine.SPOT,7,"original")).thenReturn(parent);
        var recovered = service.submission(7,"original");
        org.assertj.core.api.Assertions.assertThat(recovered.code()).isEqualTo("NONE");
        org.assertj.core.api.Assertions.assertThat(recovered.outcome()).isEqualTo("TERMINAL");
        org.mockito.Mockito.verify(store).response(parent);
        org.mockito.Mockito.verify(store,org.mockito.Mockito.never()).get(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.verify(store,org.mockito.Mockito.never()).query(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyInt());
    }
    @Test
    void twapRejectsChildQuantityThatCannotFinishInsideDuration() {
        AlgoOrderService service = service(ProductLine.LINEAR_PERPETUAL);
        assertThatThrownBy(() -> service.place(new PlaceAlgoOrderRequest(
                1001L, "twap-small-child", "1", AlgoOrderType.TWAP, OrderSide.BUY,
                0L, 100L, 10L, 10L, 20L, MarginMode.CROSS, PositionSide.NET,
                false, false, TimeInForce.IOC, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("childQuantitySteps is too small");
    }

    @Test
    void twapSliceCeilingCannotOverflowAtTheInt64Boundary() {
        assertThatThrownBy(() -> service(ProductLine.SPOT).place(new PlaceAlgoOrderRequest(
                1001L, "twap-int64", "1", AlgoOrderType.TWAP, OrderSide.BUY,
                0L, Long.MAX_VALUE, 1L, 1L, 20L, MarginMode.CROSS, PositionSide.NET,
                false, false, TimeInForce.IOC, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("childQuantitySteps is too small");
    }

    @Test
    void allProductLinesApplyTheSameAlgoValidation() {
        for (ProductLine line : ProductLine.values()) {
            AlgoOrderService service = service(line);
            assertThatThrownBy(() -> service.place(new PlaceAlgoOrderRequest(
                    1001L, "algo-" + line, "1", AlgoOrderType.TWAP, OrderSide.BUY,
                    0L, 100L, 10L, 10L, 20L, MarginMode.CROSS, PositionSide.NET,
                    false, false, TimeInForce.IOC, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("childQuantitySteps is too small");
        }
    }

    @Test
    void rejectsMissingClientAlgoOrderIdBeforeCoreSubmission() {
        AlgoOrderService service = service(ProductLine.LINEAR_PERPETUAL);

        assertThatThrownBy(() -> service.place(new PlaceAlgoOrderRequest(
                1001L, " ", "1", AlgoOrderType.TWAP, OrderSide.BUY,
                0L, 100L, 50L, 10L, 20L, MarginMode.CROSS, PositionSide.NET,
                false, false, TimeInForce.IOC, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientAlgoOrderId is required");
    }

    @Test void openReadsOnlyTheCommittedProjectionForEachProduct() {
        for (var line : ProductLine.values()) {
            var properties = new TradingOrderProperties();
            properties.getKafka().setProductLine(line);
            var store = mock(AeronAlgoOrderStore.class);
            var projection = mock(com.surprising.trading.order.repository.AlgoOrderProjectionRepository.class);
            org.mockito.Mockito.when(projection.openOrders(line, 1001, "1", 10)).thenReturn(java.util.List.of());
            var service = new AlgoOrderService(properties, mock(OrderService.class), store, projection, mock(com.surprising.trading.order.repository.AeronOrderProjectionRepository.class));
            org.assertj.core.api.Assertions.assertThat(service.openOrders(1001, "1", 10).orders()).isEmpty();
            org.mockito.Mockito.verify(projection).openOrders(line,1001,"1",10);
            org.mockito.Mockito.verifyNoInteractions(store);
        }
    }

    private AlgoOrderService service(ProductLine line) {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(line);
        properties.getAlgo().setMinDurationSeconds(1);
        properties.getAlgo().setMinIntervalSeconds(1);
        return new AlgoOrderService(properties, mock(OrderService.class), mock(AeronAlgoOrderStore.class),
                mock(com.surprising.trading.order.repository.AlgoOrderProjectionRepository.class), mock(com.surprising.trading.order.repository.AeronOrderProjectionRepository.class));
    }
}
