package com.surprising.liquidation.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.aeron.protocol.CoreLiquidationActionView;
import com.surprising.aeron.protocol.CoreLiquidationBatchResultView;
import com.surprising.aeron.protocol.CoreRiskScanContinuation;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.CoreLiquidationProjection;
import com.surprising.liquidation.provider.repository.CoreLiquidationProjectionRepository;
import com.surprising.liquidation.provider.repository.CoreLiquidationProjectionRepository.ProjectionPage;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiquidationServiceTest {

    @Test
    void executesBoundedCoreWorkInOneBatch() {
        LiquidationProperties properties = new LiquidationProperties();
        LiquidationAeronGateway aeron = mock(LiquidationAeronGateway.class);
        CoreLiquidationProjectionRepository projections = mock(CoreLiquidationProjectionRepository.class);
        CoreLiquidationActionView action = new CoreLiquidationActionView(7, 11, "BTC-USDT",
                CoreMarginMode.CROSS, CorePositionSide.NET, 2, 9, 3, 3, 60_000);
        CoreLiquidationWorkView work = new CoreLiquidationWorkView(
                ProductLine.LINEAR_PERPETUAL, 7, true,
                new CoreRiskScanContinuation("BTC-USDT", 9, 0), List.of(action), List.of());
        when(aeron.work(0, 256, 1_048_576)).thenReturn(work);
        when(aeron.executeBatch(work, 3_000, 1_024))
                .thenReturn(new CoreLiquidationBatchResultView(1, 1, 0, 0, 3, 1));
        LiquidationService service = new LiquidationService(properties, aeron, projections);

        LiquidationService.WorkCycle cycle = service.processWork();

        assertThat(cycle).isEqualTo(new LiquidationService.WorkCycle(true, 1, 1, 0, 0, 3));
        verify(aeron).executeBatch(work, 3_000, 1_024);
    }

    @Test
    void rejectsWrongProductLine() {
        LiquidationProperties properties = new LiquidationProperties();
        LiquidationAeronGateway aeron = mock(LiquidationAeronGateway.class);
        CoreLiquidationWorkView crossLine = new CoreLiquidationWorkView(ProductLine.INVERSE_PERPETUAL,
                0, true, null, List.of(), List.of());
        when(aeron.work(0, 256, 1_048_576)).thenReturn(crossLine);
        LiquidationService service = new LiquidationService(properties, aeron,
                mock(CoreLiquidationProjectionRepository.class));

        assertThatThrownBy(service::processWork)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Core liquidation work ProductLine mismatch");
        org.mockito.Mockito.verify(aeron, org.mockito.Mockito.never())
                .executeBatch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void rejectsNonAdvancingAndGapCursor() {
        LiquidationProperties properties = new LiquidationProperties();
        LiquidationAeronGateway repeatedAeron = mock(LiquidationAeronGateway.class);
        when(repeatedAeron.work(0, 256, 1_048_576)).thenReturn(new CoreLiquidationWorkView(
                ProductLine.LINEAR_PERPETUAL, 0, false, null, List.of(), List.of()));
        LiquidationService repeated = new LiquidationService(properties, repeatedAeron,
                mock(CoreLiquidationProjectionRepository.class));

        assertThatThrownBy(repeated::processWork)
                .hasMessage("Core liquidation work cursor did not advance");

        CoreLiquidationActionView action = new CoreLiquidationActionView(7, 11, "BTC-USDT",
                CoreMarginMode.CROSS, CorePositionSide.NET, 2, 9, 3, 3, 60_000);
        LiquidationAeronGateway gapAeron = mock(LiquidationAeronGateway.class);
        when(gapAeron.work(0, 256, 1_048_576)).thenReturn(new CoreLiquidationWorkView(
                ProductLine.LINEAR_PERPETUAL, 8, false, null, List.of(action), List.of()));
        LiquidationService gap = new LiquidationService(properties, gapAeron,
                mock(CoreLiquidationProjectionRepository.class));

        assertThatThrownBy(gap::processWork).hasMessage("Core liquidation work cursor gap");
        org.mockito.Mockito.verify(gapAeron, org.mockito.Mockito.never())
                .executeBatch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void disabledExecutionDoesNotQueryOrMutateCore() {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getExecution().setEnabled(false);
        LiquidationAeronGateway aeron = mock(LiquidationAeronGateway.class);
        LiquidationService service = new LiquidationService(properties, aeron,
                mock(CoreLiquidationProjectionRepository.class));

        assertThat(service.processWork()).isEqualTo(new LiquidationService.WorkCycle(false, 0, 0, 0, 0, 0));
        org.mockito.Mockito.verifyNoInteractions(aeron);
    }

    @Test
    void historyMapsOnlyTheCoreProjection() {
        LiquidationProperties properties = new LiquidationProperties();
        CoreLiquidationProjectionRepository projections = mock(CoreLiquidationProjectionRepository.class);
        CoreLiquidationProjection projection = new CoreLiquidationProjection(7, 11, "BTC-USDT", "USDT",
                CoreMarginMode.ISOLATED, CorePositionSide.LONG, 9, 3, 3, 0,
                60_000, 3_000, 2, "COMPLETED", Instant.ofEpochMilli(1234));
        when(projections.page("LINEAR_PERPETUAL", 11L, 25, null, null))
                .thenReturn(new ProjectionPage(List.of(projection), null, false, "createdAt.desc"));
        LiquidationService service = new LiquidationService(properties, mock(LiquidationAeronGateway.class),
                projections);

        var response = service.orders(11L, 25);

        assertThat(response.orders()).singleElement().satisfies(order -> {
            assertThat(order.candidateId()).isEqualTo(7);
            assertThat(order.orderId()).isEqualTo(7);
            assertThat(order.takeoverPriceTicks()).isEqualTo(60_000);
            assertThat(order.liquidationFeeUnits()).isEqualTo(2);
            assertThat(order.status()).isEqualTo(com.surprising.liquidation.api.model.LiquidationOrderStatus.FILLED);
        });
    }
}
