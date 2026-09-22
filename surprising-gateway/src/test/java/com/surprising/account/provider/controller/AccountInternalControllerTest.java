package com.surprising.account.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.ProductTransferRecordQueryResponse;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.account.provider.service.AccountService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountInternalControllerTest {



    private final AccountCommandGateway commandGateway = mock(AccountCommandGateway.class);

    private final AccountService accountService = mock(AccountService.class);

    private final AccountProperties properties = new AccountProperties();

    private final BalanceAdjustmentRequest request = new BalanceAdjustmentRequest(42L, "USDT", 1_250_000L, "custody-wallet:event-1:deposit.confirmed", "custody wallet deposit");

    private AccountInternalController controller;

    @BeforeEach
    void setUp() {
        controller = new AccountInternalController(new com.surprising.account.provider.service.AccountRequestService(accountService, commandGateway, properties));
    }

    @Test
    void acceptsInternalAdjustmentWithoutCredentials() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/accounts/admin/balance-adjustments")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(new tools.jackson.databind.ObjectMapper().writeValueAsString(request)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        verify(commandGateway).adjustBalance(request, null, null);
    }

    @Test
    void missingRequestBodyIsStillRejected() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/accounts/admin/balance-adjustments"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(commandGateway);
    }

    @Test
    void exposesUserProductTransferRecordsThroughAccountGateway() {
        ProductTransferRecordQueryResponse expected = new ProductTransferRecordQueryResponse(0, List.of(), null, false, "createdAt.desc", 50);
        when(accountService.productTransfers(42L, AccountType.SPOT, "USDT", 50, null, null)).thenReturn(expected);
        ProductTransferRecordQueryResponse actual = controller.userProductTransfers(42L, AccountType.SPOT, "USDT", 50, null, null);
        assertThat(actual).isSameAs(expected);
        verify(accountService).productTransfers(42L, AccountType.SPOT, "USDT", 50, null, null);
    }

}
