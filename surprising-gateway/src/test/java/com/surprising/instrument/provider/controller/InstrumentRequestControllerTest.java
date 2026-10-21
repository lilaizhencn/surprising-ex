package com.surprising.instrument.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.surprising.instrument.api.model.*;
import com.surprising.product.api.ProductLine;
import com.surprising.instrument.provider.service.*;
import com.surprising.instrument.provider.config.InstrumentProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class InstrumentRequestControllerTest {
    private final InstrumentStorageService storage = mock(InstrumentStorageService.class);
    private final MarketSummaryService market = mock(MarketSummaryService.class);
    private final InstrumentService instruments = new InstrumentService(storage, mock(InstrumentValidator.class),
            new InstrumentProperties(), mock(InstrumentOutboxService.class), market);
    private final InstrumentRequestController requests = new InstrumentRequestController(instruments,
            JsonMapper.builder().findAndAddModules().build());

    @ParameterizedTest
    @EnumSource(value=ContractType.class, names={"LINEAR_DELIVERY","INVERSE_DELIVERY","VANILLA_OPTION"})
    void settlingDeliveryRemainsAvailableByIdAndInCatalog(ContractType type) {
        var settling = instrument(type, InstrumentStatus.SETTLING);
        var draft = instrument(type, InstrumentStatus.DRAFT);
        var closed = instrument(type, InstrumentStatus.CLOSED);
        when(storage.latest(73, type.productLine())).thenReturn(java.util.Optional.of(settling));
        when(storage.list(type.productLine(), null, null))
                .thenReturn(List.of(settling, draft, closed));

        assertThat(requests.latest(73, null, type.productLine().name())).isSameAs(settling);
        var catalog = (InstrumentQueryResponse) requests.list(null, null, null, type.productLine().name(), false, false);
        assertThat(catalog.instruments()).containsExactly(settling);
    }

    @Test
    void unlistedStatusesAndOtherProductVisibilityRemainRestricted() {
        for (var type : ContractType.values()) {
            for (var status : List.of(InstrumentStatus.DRAFT, InstrumentStatus.CLOSED, InstrumentStatus.SETTLING)) {
                if (type.productLine().isDeliveryProduct() && (status == InstrumentStatus.SETTLING || status == InstrumentStatus.CLOSED)) continue;
                var value = instrument(type, status);
                when(storage.latest(73, type.productLine())).thenReturn(java.util.Optional.of(value));
                assertThatThrownBy(() -> requests.latest(73, null, type.productLine().name()))
                        .isInstanceOf(ResponseStatusException.class)
                        .hasMessageContaining("404");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value=ContractType.class, names={"LINEAR_DELIVERY","INVERSE_DELIVERY","VANILLA_OPTION"})
    void closedDeliveryCanBeReadByIdentityButIsAbsentFromDefaultCatalog(ContractType type) {
        var closed = instrument(type, InstrumentStatus.CLOSED);
        when(storage.latest(73, type.productLine())).thenReturn(java.util.Optional.of(closed));
        when(storage.list(type.productLine(), null, null))
                .thenReturn(List.of(closed));
        assertThat(requests.latest(73, null, type.productLine().name())).isSameAs(closed);
        assertThat(((InstrumentQueryResponse) requests.list(null, null, null, type.productLine().name(), false, false)).instruments()).isEmpty();
    }

    @Test void catalogAggregatesOnlyVisibleContractsAndPreservesResponseFields() {
        var visible = instrument(ContractType.LINEAR_PERPETUAL, InstrumentStatus.TRADING);
        var draft = instrument(ContractType.LINEAR_PERPETUAL, InstrumentStatus.DRAFT);
        when(storage.list(ProductLine.LINEAR_PERPETUAL, null, null)).thenReturn(List.of(visible, draft));
        var one = java.math.BigDecimal.ONE;
        var summary = new com.surprising.instrument.provider.model.MarketSummaryRows.Summary(one, one, one, one, one, one, List.of(one));
        when(market.summaries(eq(List.of(73)), any())).thenReturn(java.util.Map.of(73, summary));
        var catalog = instruments.publicCatalog(ProductLine.LINEAR_PERPETUAL, null, null, true);
        assertThat(catalog.instruments().instruments()).containsExactly(visible);
        assertThat(catalog.summaries()).containsEntry(73, summary);
        verify(market).summaries(eq(List.of(73)), any());
    }

    private InstrumentResponse instrument(ContractType type, InstrumentStatus status) {
        var value = mock(InstrumentResponse.class);
        when(value.instrumentId()).thenReturn(73);
        when(value.status()).thenReturn(status);
        when(value.contractType()).thenReturn(type);
        return value;
    }
}
