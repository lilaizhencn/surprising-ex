package com.surprising.marketmaker.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.marketmaker.provider.service.MarketMakerService;
import com.surprising.marketmaker.provider.service.MarketMakerService.MarketMakerAdminMetricsResponse;
import com.surprising.marketmaker.provider.service.MarketMakerService.MarketMakerMetricsTotals;
import com.surprising.marketmaker.provider.service.MarketMakerService.MarketMakerPnlAttributionResponse;
import com.surprising.marketmaker.provider.service.MarketMakerService.MarketMakerPnlAttributionTotals;
import com.surprising.marketmaker.provider.service.MarketMakerService.MarketMakerRunLogQueryResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AdminMarketMakerControllerTest {

    @Test
    void metricsRequiresAdminIdentityHeader() {
        MarketMakerService service = mock(MarketMakerService.class);
        AdminMarketMakerController controller = new AdminMarketMakerController(service);

        assertThatThrownBy(() -> controller.metrics(" ", 25))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(service);
    }

    @Test
    void metricsDelegatesForAdminIdentity() {
        MarketMakerService service = mock(MarketMakerService.class);
        MarketMakerAdminMetricsResponse response = emptyMetrics();
        when(service.adminMetrics(25)).thenReturn(response);
        AdminMarketMakerController controller = new AdminMarketMakerController(service);

        MarketMakerAdminMetricsResponse result = controller.metrics("1001", 25);

        assertThat(result).isSameAs(response);
        verify(service).adminMetrics(25);
    }

    @Test
    void strategyLogsDelegatesForAdminIdentity() {
        MarketMakerService service = mock(MarketMakerService.class);
        MarketMakerRunLogQueryResponse response = new MarketMakerRunLogQueryResponse(Instant.EPOCH, List.of());
        when(service.runLogs("BTC-USDT-MM-A", "BTC-USDT", 900001L, "QUOTE_RECONCILED", 50,
                "cursor", "createdAt.asc"))
                .thenReturn(response);
        AdminMarketMakerController controller = new AdminMarketMakerController(service);

        MarketMakerRunLogQueryResponse result = controller.strategyLogs(
                "1001", "BTC-USDT-MM-A", "BTC-USDT", 900001L, "QUOTE_RECONCILED", 50,
                "cursor", "createdAt.asc");

        assertThat(result).isSameAs(response);
        verify(service).runLogs("BTC-USDT-MM-A", "BTC-USDT", 900001L, "QUOTE_RECONCILED", 50,
                "cursor", "createdAt.asc");
    }

    @Test
    void pnlAttributionDelegatesForAdminIdentity() {
        MarketMakerService service = mock(MarketMakerService.class);
        MarketMakerPnlAttributionResponse response = new MarketMakerPnlAttributionResponse(
                Instant.EPOCH, Instant.EPOCH.minusSeconds(3600), 1,
                new MarketMakerPnlAttributionTotals(0, 0, 0, 0, 0, 0, 0), List.of());
        when(service.pnlAttribution("BTC-USDT-MM-A", "BTC-USDT", 900001L, 24, 100))
                .thenReturn(response);
        AdminMarketMakerController controller = new AdminMarketMakerController(service);

        MarketMakerPnlAttributionResponse result = controller.pnlAttribution(
                "1001", "BTC-USDT-MM-A", "BTC-USDT", 900001L, 24, 100);

        assertThat(result).isSameAs(response);
        verify(service).pnlAttribution("BTC-USDT-MM-A", "BTC-USDT", 900001L, 24, 100);
    }

    private MarketMakerAdminMetricsResponse emptyMetrics() {
        return new MarketMakerAdminMetricsResponse(
                Instant.EPOCH,
                "mm-test",
                new MarketMakerMetricsTotals(0L, 0L, 0L, 0L, 0L, 0L, 0L,
                        0L, 0L, 0L, 0L, 0L, 0L, 0L),
                List.of(),
                List.of(),
                List.of());
    }
}
