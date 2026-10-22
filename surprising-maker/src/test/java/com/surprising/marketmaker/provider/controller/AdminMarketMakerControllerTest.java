package com.surprising.marketmaker.provider.controller;

import com.surprising.marketmaker.provider.model.MarketMakerAdminModels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.marketmaker.provider.service.MarketMakerService;
import com.surprising.marketmaker.provider.model.MarketMakerAdminModels.MarketMakerAdminMetricsResponse;
import com.surprising.marketmaker.provider.model.MarketMakerAdminModels.MarketMakerMetricsTotals;
import com.surprising.marketmaker.provider.model.MarketMakerAdminModels.MarketMakerRunLogQueryResponse;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import com.surprising.marketmaker.provider.model.MarketMakerRunEventPageQuery;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AdminMarketMakerControllerTest {

    @Test
    void metricsRequiresAdminIdentityHeader() {
        MarketMakerService service = mock(MarketMakerService.class);
        AdminMarketMakerController controller = new AdminMarketMakerController(service);

        assertThatThrownBy(() -> controller.metrics(" ", null, null, 25))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(service);
    }

    @Test
    void metricsDelegatesForAdminIdentity() {
        MarketMakerService service = mock(MarketMakerService.class);
        MarketMakerAdminMetricsResponse response = emptyMetrics();
        when(service.adminMetrics(25, ProductLine.LINEAR_PERPETUAL)).thenReturn(response);
        AdminMarketMakerController controller = new AdminMarketMakerController(service);

        MarketMakerAdminMetricsResponse result = controller.metrics("1001", null, "USDT_PERPETUAL", 25);

        assertThat(result).isSameAs(response);
        verify(service).adminMetrics(25, ProductLine.LINEAR_PERPETUAL);
    }

    @Test
    void strategyLogsDelegatesForAdminIdentity() {
        MarketMakerService service = mock(MarketMakerService.class);
        MarketMakerRunLogQueryResponse response = new MarketMakerRunLogQueryResponse(Instant.EPOCH, List.of());
        String cursor=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("1970-01-01T00:00:00Z|9".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(service.runLogs(ProductLine.LINEAR_PERPETUAL,
                "47", "1", 900001L, "QUOTE_RECONCILED", new MarketMakerRunEventPageQuery(50, Instant.EPOCH, 9, false)))
                .thenReturn(response);
        AdminMarketMakerController controller = new AdminMarketMakerController(service);

        MarketMakerRunLogQueryResponse result = controller.strategyLogs(
                "1001", "linear-perp", null, "47", "1", 900001L, "QUOTE_RECONCILED", 50,
                cursor, "createdAt.asc");

        assertThat(result).isSameAs(response);
        verify(service).runLogs(ProductLine.LINEAR_PERPETUAL,
                "47", "1", 900001L, "QUOTE_RECONCILED", new MarketMakerRunEventPageQuery(50, Instant.EPOCH, 9, false));
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
