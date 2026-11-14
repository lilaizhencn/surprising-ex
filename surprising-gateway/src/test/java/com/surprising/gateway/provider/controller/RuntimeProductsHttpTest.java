package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class RuntimeProductsHttpTest {
    private final GatewayProductServices products = mock(GatewayProductServices.class);
    private final ObjectMapper json = new ObjectMapper();
    private final MockMvc mvc;

    RuntimeProductsHttpTest() {
        when(products.enabled()).thenReturn(List.of(ProductLine.SPOT));
        when(products.disabled()).thenReturn(List.of(ProductLine.OPTION));
        var controller = new RuntimeController(products);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
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
    }

    @Test
    void discoveryIsReadOnlyWithoutAnAlias() throws Exception {
        mvc.perform(post("/api/v1/runtime")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/v1/gateway/runtime")).andExpect(status().isNotFound());
    }
}
