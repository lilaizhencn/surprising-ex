package com.surprising.trading.order.controller;

import com.surprising.trading.order.service.LeverageService;
import com.surprising.trading.order.exception.OrderCommandRejectedException;

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

class LeverageRequestHandlerTest {

    @Test
    void terminalBusinessRejectionRemainsAConflictAtTheHttpBoundary() {
        var leverage = mock(LeverageService.class);
        var failure = new OrderCommandRejectedException(
                com.surprising.aeron.protocol.CoreResultCode.LEVERAGE_UPDATE_BLOCKED);
        org.mockito.Mockito.when(leverage.set(org.mockito.ArgumentMatchers.any())).thenThrow(failure);
        var request = new LeverageSettingRequest(1, ProductLine.LINEAR_PERPETUAL, "604",
                MarginMode.CROSS, 2_000_000, "web");
        assertThatThrownBy(() -> TradingController.setLeverage(leverage, request, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getCause()).isSameAs(failure);
                    assertThat(error.getReason()).contains("Close positions");
                    assertThat(error.getBody().getProperties()).containsEntry("code","LEVERAGE_UPDATE_BLOCKED");
                });
    }

    @Test
    void adminSetRequiresTrustedAdminIdentity() {
        LeverageService leverage = mock(LeverageService.class);
        AdminTradingController controller = new AdminTradingController(null, null, null, leverage, null, null);
        LeverageSettingRequest request = new LeverageSettingRequest(900001L,
                ProductLine.LINEAR_PERPETUAL, "49", MarginMode.CROSS,
                10_000_000L, "maker depth QA");

        assertThatThrownBy(() -> controller.adminSetLeverage(null, request, null, null))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(leverage);

        controller.adminSetLeverage("1", request, null, null);
        verify(leverage).set(request);
    }
}
