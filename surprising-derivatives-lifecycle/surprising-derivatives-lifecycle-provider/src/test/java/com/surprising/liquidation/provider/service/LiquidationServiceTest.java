package com.surprising.liquidation.provider.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.aeron.protocol.*;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.repository.CoreLiquidationProjectionRepository;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiquidationServiceTest {
    private final LiquidationProperties properties = new LiquidationProperties();
    private final LiquidationAeronGateway aeron = mock(LiquidationAeronGateway.class);
    private final LiquidationService service = new LiquidationService(properties, aeron,
            mock(CoreLiquidationProjectionRepository.class));

    @Test
    void advancesTriggerOnlyWorkEvenWithoutRiskOrLiquidationCandidates() {
        control(true, 60_000);
        when(aeron.work(0, 256, 1_048_576)).thenReturn(emptyWork());
        assertThat(service.processWork().riskScanContinued()).isTrue();
        assertThat(service.processWork().riskScanContinued()).isFalse();
        var ordered = inOrder(aeron);
        ordered.verify(aeron).riskScanControl();
        ordered.verify(aeron).continueRiskScan(64);
        ordered.verify(aeron, times(2)).work(0, 256, 1_048_576);
        verify(aeron, never()).executeBatch(any(), anyLong(), anyInt());
    }

    @Test
    void executesLiquidationsAfterScanningWithoutReusingQueriedRiskCursor() {
        control(true, 0);
        var action = new CoreLiquidationActionView(1, 7, "BTC-USDT-SWAP", CoreMarginMode.CROSS,
                CorePositionSide.NET, 10, 1, 1, 100);
        var work = new CoreLiquidationWorkView(ProductLine.LINEAR_PERPETUAL, 1, true,
                new CoreRiskScanContinuation("BTC-USDT-SWAP", 10, 0), List.of(action), List.of());
        when(aeron.work(0, 256, 1_048_576)).thenReturn(work);
        when(aeron.executeBatch(work, 3000, 0)).thenReturn(new CoreLiquidationBatchResultView(1, 1, 0, 0, 0, 0));
        assertThat(service.processWork().applied()).isEqualTo(1);
        var ordered = inOrder(aeron);
        ordered.verify(aeron).riskScanControl();
        ordered.verify(aeron).continueRiskScan(64);
        ordered.verify(aeron).work(0, 256, 1_048_576);
        ordered.verify(aeron).executeBatch(work, 3000, 0);
    }

    @Test
    void disabledRiskControlDoesNotAdvanceScanning() {
        control(false, 0);
        when(aeron.work(0, 256, 1_048_576)).thenReturn(emptyWork());
        assertThat(service.processWork().riskScanContinued()).isFalse();
        verify(aeron, never()).continueRiskScan(anyInt());
    }

    @Test
    void failedContinuationIsRetriedOnNextCycle() {
        control(true, 60_000);
        doThrow(new IllegalStateException("unavailable")).doNothing().when(aeron).continueRiskScan(64);
        when(aeron.work(0, 256, 1_048_576)).thenReturn(emptyWork());
        assertThatThrownBy(service::processWork).hasMessage("unavailable");
        assertThat(service.processWork().riskScanContinued()).isTrue();
        verify(aeron, times(2)).continueRiskScan(64);
    }

    private void control(boolean enabled, long delay) {
        when(aeron.riskScanControl()).thenReturn(new CoreRiskScanControlView(1, "risk", enabled,
                delay, 64, "test", "test", 0));
    }

    private CoreLiquidationWorkView emptyWork() {
        return new CoreLiquidationWorkView(ProductLine.LINEAR_PERPETUAL, 0, true, null, List.of(), List.of());
    }
}
