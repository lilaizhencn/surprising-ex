package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.gateway.provider.controller.GatewayProxyController;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class GatewayRuntimeTest {
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void exposesConfiguredProductLines(ProductLine product) {
        var local = mock(GatewayProductServices.class);
        when(local.enabled()).thenReturn(java.util.List.of(product));
        var controller = new GatewayProxyController(null,null,null, org.mockito.Mockito.mock(com.surprising.gateway.provider.auth.service.GatewayApiKeyService.class),null,new tools.jackson.databind.ObjectMapper(),null,null,local,
                mock(com.surprising.gateway.provider.local.LocalBusinessApi.class),mock(com.surprising.gateway.provider.product.GatewayProductSelection.class));
        when(local.disabled()).thenReturn(java.util.List.of(ProductLine.values()).stream().filter(p -> p != product).toList());
        assertThat(controller.runtime().get("productLines")).containsExactly(product);
        assertThat(controller.runtime().get("disabledProductLines")).doesNotContain(product).hasSize(5);
    }
}
