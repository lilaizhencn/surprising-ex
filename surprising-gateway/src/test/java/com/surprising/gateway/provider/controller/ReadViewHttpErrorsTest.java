package com.surprising.gateway.provider.controller;

import com.surprising.account.provider.controller.AccountRequestHandler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.surprising.account.provider.controller.AccountInternalController;
import com.surprising.account.provider.service.*;
import com.surprising.gateway.provider.controller.advice.ReadViewHttpErrors;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReadViewHttpErrorsTest {
    @org.junit.jupiter.api.Test
    void actualInternalAccountHttpMapsNativeUnavailableWithoutReturningEmptyBalances() throws Exception {
        var account = mock(AccountService.class);
        when(account.balances(42L)).thenThrow(new ReadViewUnavailableException("READ_VIEW_INITIALIZING"));
        var controller = new AccountInternalController(new AccountRequestHandler(account, mock(com.surprising.account.provider.service.AccountQueryService.class), mock(AccountCommandGateway.class)));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ReadViewHttpErrors()).build();
        mvc.perform(get("/api/v1/accounts/balances").param("userId", "42")).andExpect(status().isServiceUnavailable())
                .andExpect(result -> assertThat(result.getResponse().getErrorMessage()).isEqualTo("READ_VIEW_INITIALIZING"));
        verify(account).balances(42L);
    }
}
