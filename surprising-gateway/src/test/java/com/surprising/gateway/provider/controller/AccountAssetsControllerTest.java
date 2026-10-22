package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.model.GatewayProductSetting;
import com.surprising.gateway.provider.service.GatewayProductSettings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.surprising.account.api.model.*;
import com.surprising.account.provider.service.AccountService;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.product.*;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class AccountAssetsControllerTest {
    private final AuthService auth = mock(AuthService.class);
    private final GatewayProductServices products = mock(GatewayProductServices.class);
    private final GatewayProductSelection selection = new GatewayProductSelection(products, new ObjectMapper());
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new AccountAssetsController(auth, selection, products))
            .setControllerAdvice(new com.surprising.gateway.provider.controller.advice.ReadViewHttpErrors()).build();

    AccountAssetsControllerTest() {
        when(auth.authenticateBearer("Bearer own")).thenReturn(
                new JwtPrincipal(42, "user", "ACTIVE", List.of(), Instant.now().plusSeconds(60)));
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void disabledProductsHaveEmptyBalancesWithoutStartingAnyRuntime(ProductLine line) throws Exception {
        when(products.disabled()).thenReturn(List.of(line));
        for (String path : List.of("balances", "product-balances")) {
            mvc.perform(get("/api/v1/gateway/account/" + path).header("Authorization", "Bearer own")
                    .header("X-Product-Line", line.name()).param("userId", "42")
                    .param("accountType", line.accountTypeCode()))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.count").value(0))
                    .andExpect(jsonPath("$.balances").isEmpty());
        }
        verify(products, never()).service(any(), any());
    }

    @Test void enabledAccountStillUsesAuthoritativeQueryAndPreservesFailures() throws Exception {
        var accounts = mock(AccountService.class);
        when(products.service(ProductLine.SPOT, AccountService.class)).thenReturn(accounts);
        when(accounts.balances(42)).thenReturn(new BalanceQueryResponse(1,
                List.of(new BalanceResponse(42, "USDT", 50, 10, 60, Instant.now()))));
        mvc.perform(get("/api/v1/gateway/account/balances").header("Authorization", "Bearer own")
                .header("X-Product-Line", "SPOT").param("userId", "42"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balances[0].equityUnits").value(60));
        when(accounts.balances(42)).thenThrow(new com.surprising.realtime.api.exception.ReadViewUnavailableException("read view stale"));
        mvc.perform(get("/api/v1/gateway/account/balances").header("Authorization", "Bearer own")
                .header("X-Product-Line", "SPOT").param("userId", "42"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test void zeroBalancesStillRequireAuthenticationAndExactOwnerAndProduct() throws Exception {
        when(products.disabled()).thenReturn(List.of(ProductLine.SPOT));
        mvc.perform(get("/api/v1/gateway/account/balances").param("userId", "42").param("productLine", "SPOT"))
                .andExpect(status().isUnauthorized());
        when(auth.authenticateBearer("Bearer bad")).thenThrow(new IllegalArgumentException("expired"));
        mvc.perform(get("/api/v1/gateway/account/balances").header("Authorization", "Bearer bad")
                .param("userId", "42").param("productLine", "SPOT")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/gateway/account/balances").header("Authorization", "Bearer own")
                .param("userId", "43").param("productLine", "SPOT")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/gateway/account/balances").header("Authorization", "Bearer own")
                .header("X-Product-Line", "SPOT").param("userId", "42").param("productLine", "OPTION"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/gateway/account/balances").header("Authorization", "Bearer own").param("userId", "42"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/gateway/account/product-balances").header("Authorization", "Bearer own")
                .header("X-Product-Line", "SPOT").param("userId", "42").param("accountType", "FUNDING"))
                .andExpect(status().isBadRequest());
        verify(products, never()).service(any(), any());
    }

    @Test void onlyDisabledDatabaseConfigurationDefinesZeroNotMissingRuntime() {
        var settings = mock(GatewayProductSettings.class);
        when(settings.list()).thenReturn(List.of(
                new GatewayProductSetting(ProductLine.SPOT, false, 1, "qa", "", Instant.now()),
                new GatewayProductSetting(ProductLine.OPTION, true, 1, "qa", "", Instant.now())));
        var services = new GatewayProductServices(null, null, settings);
        assertThat(services.enabled()).isEmpty();
        assertThat(services.disabled()).containsExactly(ProductLine.SPOT);
    }
}
