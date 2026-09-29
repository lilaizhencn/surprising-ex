package com.surprising.funding.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.funding.api.model.FundingPaymentQueryResponse;
import com.surprising.funding.provider.service.FundingRuntimeConfigService;
import com.surprising.funding.provider.service.FundingService;
import java.util.List;
import org.junit.jupiter.api.Test;

class FundingControllerTest {

    @Test
    void userPaymentsPassCursorToProductLineService() {
        FundingService service = mock(FundingService.class);
        FundingController controller = new FundingController(service, mock(FundingRuntimeConfigService.class));
        FundingPaymentQueryResponse expected = new FundingPaymentQueryResponse(
                0, List.of(), "next", true, "createdAt.desc", 100);
        when(service.payments(42L, null, 100, "cursor", "createdAt.desc")).thenReturn(expected);

        assertThat(controller.payments(42L, null, 100, "cursor", "createdAt.desc")).isSameAs(expected);
        verify(service).payments(42L, null, 100, "cursor", "createdAt.desc");
    }
}
