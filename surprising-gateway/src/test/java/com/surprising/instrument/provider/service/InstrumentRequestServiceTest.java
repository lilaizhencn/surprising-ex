package com.surprising.instrument.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.surprising.instrument.api.model.*;
import com.surprising.instrument.provider.repository.MarketSummaryRepository;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class InstrumentRequestServiceTest {
    private final InstrumentService instruments = mock(InstrumentService.class);
    private final InstrumentRequestService requests = new InstrumentRequestService(instruments,
            mock(MarketSummaryRepository.class), JsonMapper.builder().findAndAddModules().build());

    @Test
    void settlingLinearDeliveryRemainsAvailableByIdAndInCatalog() {
        var settling = instrument(ContractType.LINEAR_DELIVERY, InstrumentStatus.SETTLING);
        var draft = instrument(ContractType.LINEAR_DELIVERY, InstrumentStatus.DRAFT);
        var closed = instrument(ContractType.LINEAR_DELIVERY, InstrumentStatus.CLOSED);
        when(instruments.latest(73, ProductLine.LINEAR_DELIVERY)).thenReturn(settling);
        when(instruments.list(ProductLine.LINEAR_DELIVERY, null, null))
                .thenReturn(new InstrumentQueryResponse(3, List.of(settling, draft, closed)));

        assertThat(requests.latest(73, null, "LINEAR_DELIVERY")).isSameAs(settling);
        var catalog = (InstrumentQueryResponse) requests.list(null, null, null, "LINEAR_DELIVERY", false, false);
        assertThat(catalog.instruments()).containsExactly(settling);
    }

    @Test
    void unlistedStatusesAndOtherProductVisibilityRemainRestricted() {
        for (var type : ContractType.values()) {
            for (var status : List.of(InstrumentStatus.DRAFT, InstrumentStatus.CLOSED, InstrumentStatus.SETTLING)) {
                if (type == ContractType.LINEAR_DELIVERY && status == InstrumentStatus.SETTLING) continue;
                var value = instrument(type, status);
                when(instruments.latest(73, type.productLine())).thenReturn(value);
                assertThatThrownBy(() -> requests.latest(73, null, type.productLine().name()))
                        .isInstanceOf(ResponseStatusException.class)
                        .hasMessageContaining("404");
            }
        }
    }

    private InstrumentResponse instrument(ContractType type, InstrumentStatus status) {
        var value = mock(InstrumentResponse.class);
        when(value.status()).thenReturn(status);
        when(value.contractType()).thenReturn(type);
        return value;
    }
}
