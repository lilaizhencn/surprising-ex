package com.surprising.adl.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.adl.provider.repository.AdlEventRepository;
import com.surprising.aeron.protocol.CoreAdlCandidateView;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.ExecuteAdlCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdlServiceTest {
    private final AdlProperties properties = new AdlProperties();
    private final AdlAeronGateway aeron = mock(AdlAeronGateway.class);
    private final AdlService service = new AdlService(properties, aeron, mock(AdlEventRepository.class));

    @Test
    void skipsOtherInstrumentsBeforeExecutingAProfitableCounterparty() {
        work();
        when(aeron.candidates("USDT", 100)).thenReturn(List.of(
                candidate(21, "2", -10, 990), candidate(22, "1", -10, 990)));
        // Core rejects a command for a different source instrument; it must not block the valid candidate.
        doAnswer(invocation -> {
            ExecuteAdlCommand command = TradingCommandCodec.decodeExecuteAdl(invocation.getArgument(1));
            if (!"1".equals(command.instrumentId())) {
                throw new IllegalStateException("INVALID_COMMAND: ADL command does not match liquidation");
            }
            return null;
        }).when(aeron).execute(any(), any());

        assertThat(service.processResidualDeficits()).isEqualTo(new AdlService.AdlCycle(true, 1, 1));

        ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(aeron).execute(id.capture(), payload.capture());
        assertThat(TradingCommandCodec.decodeExecuteAdl(payload.getValue())).isEqualTo(
                new ExecuteAdlCommand(1, 22, "1", CoreMarginMode.CROSS, CorePositionSide.NET,
                        -10, 100, 3, 8, 770));
        assertThat(id.getValue()).isEqualTo(UUID.nameUUIDFromBytes(
                "LINEAR_PERPETUAL:ADL:1:22:1:3:8".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void retainsCoreCandidateOrderAndExactCoverageAcrossCounterparties() {
        work();
        when(aeron.candidates("USDT", 100)).thenReturn(List.of(
                candidate(21, "2", -10, 990), candidate(22, "1", -5, 495),
                candidate(23, "1", -10, 990)));

        assertThat(service.processResidualDeficits()).isEqualTo(new AdlService.AdlCycle(true, 1, 2));

        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(aeron, times(2)).execute(any(), payloads.capture());
        assertThat(payloads.getAllValues().stream().map(TradingCommandCodec::decodeExecuteAdl)).containsExactly(
                new ExecuteAdlCommand(1, 22, "1", CoreMarginMode.CROSS, CorePositionSide.NET,
                        -5, 100, 3, 5, 495),
                new ExecuteAdlCommand(1, 23, "1", CoreMarginMode.CROSS, CorePositionSide.NET,
                        -10, 100, 3, 3, 275));
    }

    @Test
    void doesNotDeleverageTheSourceUserOrSameDirection() {
        work();
        when(aeron.candidates("USDT", 100)).thenReturn(List.of(
                candidate(11, "1", -10, 990), candidate(23, "1", 10, 990)));

        assertThat(service.processResidualDeficits()).isEqualTo(new AdlService.AdlCycle(true, 1, 0));
        verify(aeron, never()).execute(any(), any());
    }

    @Test
    void leavesWorkPendingWithoutCommandsWhenAllCandidatesBelongToOtherInstruments() {
        work();
        when(aeron.candidates("USDT", 100)).thenReturn(List.of(
                candidate(21, "2", -10, 990), candidate(22, "3", -10, 990)));

        assertThat(service.processResidualDeficits()).isEqualTo(new AdlService.AdlCycle(true, 1, 0));
        verify(aeron, never()).execute(any(), any());
    }

    private void work() {
        var resolution = new CoreLiquidationWorkView.Resolution(1, 11, "1", "USDT",
                CoreMarginMode.CROSS, CorePositionSide.NET, 3, 10, 770, 0,
                CoreLiquidationWorkView.Purpose.ADL);
        when(aeron.resolutionWork(CoreLiquidationWorkView.Purpose.ADL, 0, 50, 1_048_576))
                .thenReturn(new CoreLiquidationWorkView(ProductLine.LINEAR_PERPETUAL, 1, true,
                        null, List.of(), List.of(resolution)));
    }

    private static CoreAdlCandidateView candidate(long userId, String instrumentId, long quantity, long profit) {
        return new CoreAdlCandidateView(userId, instrumentId, "USDT", CoreMarginMode.CROSS,
                CorePositionSide.NET, quantity, 100, 1, 3, Math.absExact(quantity), profit,
                100, 990_000, 10_000_000, 1_000_000);
    }
}
