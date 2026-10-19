package com.surprising.instrument.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.surprising.instrument.api.model.*;
import com.surprising.instrument.provider.repository.MarketSummaryRepository;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class InstrumentRequestServiceTest {
    private final InstrumentService instruments = mock(InstrumentService.class);
    private final InstrumentRequestService requests = new InstrumentRequestService(instruments,
            mock(MarketSummaryRepository.class), JsonMapper.builder().findAndAddModules().build());

    @ParameterizedTest
    @EnumSource(value=ContractType.class, names={"LINEAR_DELIVERY","INVERSE_DELIVERY","VANILLA_OPTION"})
    void settlingDeliveryRemainsAvailableByIdAndInCatalog(ContractType type) {
        var settling = instrument(type, InstrumentStatus.SETTLING);
        var draft = instrument(type, InstrumentStatus.DRAFT);
        var closed = instrument(type, InstrumentStatus.CLOSED);
        when(instruments.latest(73, type.productLine())).thenReturn(settling);
        when(instruments.list(type.productLine(), null, null))
                .thenReturn(new InstrumentQueryResponse(3, List.of(settling, draft, closed)));

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
                when(instruments.latest(73, type.productLine())).thenReturn(value);
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
        when(instruments.latest(73, type.productLine())).thenReturn(closed);
        when(instruments.list(type.productLine(), null, null))
                .thenReturn(new InstrumentQueryResponse(1, List.of(closed)));
        assertThat(requests.latest(73, null, type.productLine().name())).isSameAs(closed);
        assertThat(((InstrumentQueryResponse) requests.list(null, null, null, type.productLine().name(), false, false)).instruments()).isEmpty();
    }

    private InstrumentResponse instrument(ContractType type, InstrumentStatus status) {
        var value = mock(InstrumentResponse.class);
        when(value.status()).thenReturn(status);
        when(value.contractType()).thenReturn(type);
        return value;
    }
}
