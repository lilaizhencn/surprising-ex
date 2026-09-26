package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.MarginMode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class LeverageRequestServiceTest {

    @Test
    void adminSetRequiresTrustedAdminIdentity() {
        LeverageService leverage = mock(LeverageService.class);
        LeverageRequestService requests = new LeverageRequestService(leverage);
        LeverageSettingRequest request = new LeverageSettingRequest(900001L,
                ProductLine.LINEAR_PERPETUAL, "BTC-USDT-SWAP", MarginMode.CROSS,
                10_000_000L, "maker depth QA");

        assertThatThrownBy(() -> requests.adminSet(null, request, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(leverage);

        requests.adminSet("1", request, null, null);
        verify(leverage).set(request);
    }
}
