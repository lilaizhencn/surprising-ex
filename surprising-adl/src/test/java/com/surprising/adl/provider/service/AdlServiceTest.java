package com.surprising.adl.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import com.surprising.adl.api.model.AdminCursorPage;
import com.surprising.adl.api.model.AdlEventResponse;
import com.surprising.adl.api.model.AdlSide;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.adl.provider.model.CoreAdlLiquidationProjection;
import com.surprising.adl.provider.repository.AdlEventRepository;
import com.surprising.adl.provider.repository.AdlSequenceRepository;
import com.surprising.adl.provider.repository.CoreAdlProjectionRepository;
import com.surprising.aeron.protocol.CoreAdlCandidateView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdlServiceTest {

    @Test
    void disabledScannerDoesNotReadPostgres() {
        AdlProperties properties = new AdlProperties();
        properties.getScanner().setEnabled(false);
        CoreAdlProjectionRepository projections = mock(CoreAdlProjectionRepository.class);
        AdlService service = service(properties, projections, mock(AdlAeronGateway.class),
                mock(AdlEventRepository.class), mock(AdlSequenceRepository.class));

        service.processResidualDeficits();

        verifyNoInteractions(projections);
    }

    @Test
    void queueUsesAeronCandidatesAndCursor() {
        AdlAeronGateway aeron = mock(AdlAeronGateway.class);
        CoreAdlCandidateView first = candidate(1001, 900_000);
        CoreAdlCandidateView second = candidate(1002, 800_000);
        when(aeron.candidates("USDT", 1000)).thenReturn(List.of(first, second));
        AdlService service = service(new AdlProperties(), mock(CoreAdlProjectionRepository.class), aeron,
                mock(AdlEventRepository.class), mock(AdlSequenceRepository.class));

        var page = service.queue("usdt", 1);
        var next = service.queue("USDT", 1, page.nextCursor(), null);

        assertThat(page.positions()).singleElement().extracting("userId").isEqualTo(1001L);
        assertThat(page.hasMore()).isTrue();
        assertThat(next.positions()).singleElement().extracting("userId").isEqualTo(1002L);
    }

    @Test
    void eventsKeepPostgresAuditCursorContract() {
        AdlEventRepository events = mock(AdlEventRepository.class);
        AdlEventResponse event = new AdlEventResponse(7, 1, 2, "USDT", "BTC-USDT", AdlSide.LONG,
                3, 10, 11, 100, 20, 20, 80, 900_000, "AERON_ADL_COVERAGE", Instant.EPOCH);
        when(events.page(any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new AdminCursorPage.CursorPage<>(List.of(event), "next", true,
                        "createdAt.desc", 50));
        AdlService service = service(new AdlProperties(), mock(CoreAdlProjectionRepository.class),
                mock(AdlAeronGateway.class), events, mock(AdlSequenceRepository.class));

        var response = service.events(1L, "usdt", "btc-usdt", 50, "cursor", "createdAt.desc");

        assertThat(response.events()).containsExactly(event);
        assertThat(response.nextCursor()).isEqualTo("next");
        verify(events).page(any(), eq(1L), eq("USDT"), eq("BTC-USDT"), eq(50), eq("cursor"), eq("createdAt.desc"));
    }

    @Test
    void rejectsProjectionSelectedWork() {
        AdlProperties properties = new AdlProperties();
        CoreAdlProjectionRepository projections = mock(CoreAdlProjectionRepository.class);
        when(projections.pending(any(), anyInt())).thenReturn(List.of(
                new CoreAdlLiquidationProjection(7, 1001, "BTC-USDT", "USDT", 10, 50)));
        AdlAeronGateway aeron = mock(AdlAeronGateway.class);
        when(aeron.resolutionWork(CoreLiquidationWorkView.Purpose.ADL, 0, 50, 1_048_576))
                .thenReturn(new CoreLiquidationWorkView(ProductLine.INVERSE_PERPETUAL,
                        0, true, null, List.of(), List.of()));
        AdlService service = service(properties, projections, aeron,
                mock(AdlEventRepository.class), mock(AdlSequenceRepository.class));

        assertThatThrownBy(service::processResidualDeficits)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Core ADL work ProductLine mismatch");
        verifyNoInteractions(projections);
        verify(aeron, never()).execute(any(), any());
    }

    private static AdlService service(AdlProperties properties, CoreAdlProjectionRepository projections,
                                      AdlAeronGateway aeron, AdlEventRepository events,
                                      AdlSequenceRepository sequences) {
        return new AdlService(properties, projections, aeron, events, sequences);
    }

    private static CoreAdlCandidateView candidate(long userId, long priority) {
        return new CoreAdlCandidateView(userId, "BTC-USDT", "USDT", CoreMarginMode.CROSS,
                CorePositionSide.LONG, 10, 50_000, 55_000, 9, 550_000, 100_000, 50_000,
                100_000, 10_000_000, priority);
    }
}
