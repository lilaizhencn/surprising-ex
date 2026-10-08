package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.gateway.provider.service.GatewayProxyService;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class GatewayRuntimeTest {
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void exposesConfiguredProductLines(ProductLine product) {
        var local = mock(GatewayProductServices.class);
        when(local.enabled()).thenReturn(java.util.List.of(product));
        var controller = new GatewayProxyController(mock(GatewayProxyService.class), local);
        assertThat(controller.runtime().get("productLines")).containsExactly(product);
    }
}
