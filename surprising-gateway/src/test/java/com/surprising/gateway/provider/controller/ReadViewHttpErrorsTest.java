package com.surprising.gateway.provider.controller;

import com.surprising.account.provider.controller.AccountRequestController;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.controller.AccountInternalController;
import com.surprising.account.provider.service.*;
import com.surprising.gateway.provider.controller.advice.ReadViewHttpErrors;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReadViewHttpErrorsTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void actualInternalAccountHttpMapsNativeUnavailableWithoutReturningEmptyBalances(ProductLine product) throws Exception {
        var account = mock(AccountService.class);
        when(account.balances(42L)).thenThrow(new ReadViewUnavailableException("READ_VIEW_INITIALIZING"));
        var properties = new AccountProperties();properties.getKafka().setProductLine(product);
        var controller = new AccountInternalController(new AccountRequestController(account, mock(AccountCommandGateway.class), properties));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ReadViewHttpErrors()).build();
        mvc.perform(get("/api/v1/accounts/balances").param("userId", "42")).andExpect(status().isServiceUnavailable())
                .andExpect(result -> assertThat(result.getResponse().getErrorMessage()).isEqualTo("READ_VIEW_INITIALIZING"));
        verify(account).balances(42L);
    }
}
