package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

class ProductTransferGatewaySurfaceTest {

    @Test
    void postGatewayTransferReturnsCompletedTransferContract() {
        ProductAccountClient accountClient = mock(ProductAccountClient.class);
        when(accountClient.transferOut(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(ProductTransferOperationRequest.class)))
                .thenReturn(ProductAccountAdjustment.applied("ok"));
        when(accountClient.transferIn(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(ProductTransferOperationRequest.class)))
                .thenReturn(ProductAccountAdjustment.applied("ok"));
        when(accountClient.completeTransfer(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(ProductTransferOperationRequest.class)))
                .thenReturn(ProductAccountAdjustment.applied("ok"));
        var local = mock(com.surprising.gateway.provider.local.LocalBusinessApi.class);
        when(local.productLine()).thenReturn(com.surprising.product.api.ProductLine.LINEAR_PERPETUAL);
        var products = mock(com.surprising.gateway.provider.product.GatewayProductServices.class);
        when(products.enabled()).thenReturn(java.util.List.of(com.surprising.product.api.ProductLine.LINEAR_PERPETUAL));
        when(products.local(org.mockito.ArgumentMatchers.any())).thenReturn(local);
        GatewayProxyService proxy = new GatewayProxyService(properties(), new RestTemplate(), userAuthService(),
                null, null, new ObjectMapper(), new ProductTransferCoordinator(accountClient), null,
                products, local, new com.surprising.gateway.provider.product.GatewayProductSelection(products, new ObjectMapper()));
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(
                "POST", "/api/v1/gateway/account/transfers");
        servletRequest.addHeader("Authorization", "Bearer user");
        servletRequest.addHeader("Idempotency-Key", "surface-001");
        byte[] body = "{\"sourceAccountType\":\"FUNDING\",\"targetAccountType\":\"USDT_PERPETUAL\","
                .concat("\"asset\":\"USDT\",\"amountUnits\":100,\"referenceId\":\"surface-001\","
                        + "\"reason\":\"surface\"}").getBytes(StandardCharsets.UTF_8);

        ResponseEntity<byte[]> response = proxy.proxy("account", HttpMethod.POST, servletRequest, body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
                .contains("\"transferId\":")
                .contains("\"status\":\"COMPLETED\"");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(com.surprising.gateway.provider.exception.ProductTransferSecurityException.Reason.class)
    void nativeSecurityFailuresKeepTheirHttpStatusAndNeverStartFundsMovement(
            com.surprising.gateway.provider.exception.ProductTransferSecurityException.Reason reason) throws Exception {
        var coordinator=mock(ProductTransferCoordinator.class);
        var security=mock(ProductTransferSecurityService.class);
        org.mockito.Mockito.doThrow(new com.surprising.gateway.provider.exception.ProductTransferSecurityException(reason,"security failure"))
                .when(security).requireIfNeeded(org.mockito.ArgumentMatchers.eq(42L),org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var local=mock(com.surprising.gateway.provider.local.LocalBusinessApi.class);
        var products=mock(com.surprising.gateway.provider.product.GatewayProductServices.class);
        when(products.enabled()).thenReturn(List.of(com.surprising.product.api.ProductLine.LINEAR_PERPETUAL));
        var proxy=new GatewayProxyService(properties(),new RestTemplate(),userAuthService(),null,null,new ObjectMapper(),
                coordinator,security,products,local,new com.surprising.gateway.provider.product.GatewayProductSelection(products,new ObjectMapper()));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new com.surprising.gateway.provider.controller.GatewayProxyController(proxy,products)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/gateway/account/transfers")
                .header("Authorization","Bearer user").header("Idempotency-Key","original-transfer")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"asset\":\"USDT\",\"amountUnits\":100}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(
                        reason==com.surprising.gateway.provider.exception.ProductTransferSecurityException.Reason.VALUATION_UNAVAILABLE?503:428));
        org.mockito.Mockito.verifyNoInteractions(coordinator);
    }

    private GatewayProperties properties() {
        GatewayProperties properties = new GatewayProperties();
        properties.setRoutes(java.util.Map.of("account",
                new GatewayProperties.BackendRoute("http://account:9086", "/api/v1/accounts", true)));
        return properties;
    }

    private AuthService userAuthService() {
        AuthService service = mock(AuthService.class);
        when(service.authenticateBearer("Bearer user"))
                .thenReturn(new JwtPrincipal(42L, "user", "NORMAL", List.of("USER"),
                        Instant.now().plusSeconds(60)));
        return service;
    }
}
