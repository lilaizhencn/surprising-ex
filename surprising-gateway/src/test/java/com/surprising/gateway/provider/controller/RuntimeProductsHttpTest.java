package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.local.LocalBusinessApi;
import com.surprising.gateway.provider.product.GatewayProductSelection;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

class RuntimeProductsHttpTest {
    private final GatewayProductServices products = mock(GatewayProductServices.class);
    private final AuthService auth = mock(AuthService.class);
    private final LocalBusinessApi local = mock(LocalBusinessApi.class);
    private final GatewayProductSelection selection = mock(GatewayProductSelection.class);
    private final ObjectMapper json = new ObjectMapper();
    private final MockMvc mvc;

    RuntimeProductsHttpTest() {
        var properties = new GatewayProperties();
        properties.getRoutes().put("account", new GatewayProperties.BackendRoute("local:", "/api/v1/accounts", true));
        properties.getAdminRoutes().put("account", new GatewayProperties.BackendRoute("local:", "/api/v1/admin/accounts", true));
        when(products.enabled()).thenReturn(List.of(ProductLine.SPOT));
        when(products.disabled()).thenReturn(List.of(ProductLine.OPTION));
        when(products.local(ProductLine.SPOT)).thenReturn(local);
        when(selection.resolve(any(), any(), anyBoolean())).thenReturn(ProductLine.SPOT);
        when(auth.authenticateBearer(null)).thenThrow(new IllegalArgumentException("bearer token required"));
        var controller = new GatewayProxyController(properties, new RestTemplate(), auth, null,
                json, null, null, products, local, selection);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new com.surprising.gateway.provider.interceptor.ProductRequestBodyFilter(),
                        new com.surprising.gateway.provider.interceptor.AdminIpWhitelistFilter(properties),
                        new com.surprising.gateway.provider.interceptor.AdminPermissionFilter(auth))
                .build();
    }

    @Test
    void publicAnonymousDiscoveryUsesTheCanonicalPathWithoutEnteringTheProxy() throws Exception {
        var response = mvc.perform(get("/api/v1/runtime"))
                .andExpect(status().isOk())
                .andExpect(handler().methodName("runtime"))
                .andExpect(jsonPath("$.productLines[0]").value("SPOT"))
                .andExpect(jsonPath("$.disabledProductLines[0]").value("OPTION"))
                .andReturn().getResponse();
        assertThat(json.readTree(response.getContentAsByteArray()).propertyNames())
                .containsExactlyInAnyOrder("productLines", "disabledProductLines");
        verifyNoInteractions(auth, selection, local);
    }

    @Test
    void noReadyProductsStillDistinguishesExplicitDisablingFromStartupFailure() throws Exception {
        when(products.enabled()).thenReturn(List.of());
        when(products.disabled()).thenReturn(List.of(ProductLine.SPOT));
        mvc.perform(get("/api/v1/runtime"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productLines").isEmpty())
                .andExpect(jsonPath("$.disabledProductLines.length()").value(1))
                .andExpect(jsonPath("$.disabledProductLines[0]").value("SPOT"));
        verifyNoInteractions(auth, selection, local);
    }

    @Test
    void discoveryIsReadOnlyWithoutAnAliasAndPrivateRoutesRemainAuthenticated() throws Exception {
        mvc.perform(post("/api/v1/runtime")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/v1/gateway/runtime")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/gateway/account/balances").header("X-Product-Line", "SPOT"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/gateway/account/balances").header("X-Product-Line", "SPOT"))
                .andExpect(status().isUnauthorized());
        verify(local, never()).invoke(any(), any(), any(), any(), any(), any());
    }
}
