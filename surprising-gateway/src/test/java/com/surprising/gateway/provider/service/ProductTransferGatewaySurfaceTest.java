package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.model.ProductAccountAdjustment;

import com.surprising.gateway.provider.model.ProductTransferCommand;
import com.surprising.gateway.provider.model.ProductTransferResult;
import com.surprising.gateway.provider.model.ProductTransferStatus;


import com.surprising.gateway.provider.controller.GatewayProxyController;

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
        GatewayProxyController proxy = new GatewayProxyController(properties(), new RestTemplate(), userAuthService(), org.mockito.Mockito.mock(com.surprising.gateway.provider.auth.service.GatewayApiKeyService.class),
                null, new ObjectMapper(), new ProductTransferCoordinator(accountClient), null,
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
        var proxy=new GatewayProxyController(properties(),new RestTemplate(),userAuthService(), org.mockito.Mockito.mock(com.surprising.gateway.provider.auth.service.GatewayApiKeyService.class),null,new ObjectMapper(),
                coordinator,security,products,local,new com.surprising.gateway.provider.product.GatewayProductSelection(products,new ObjectMapper()));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                proxy).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/gateway/account/transfers")
                .header("Authorization","Bearer user").header("Idempotency-Key","original-transfer")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"asset\":\"USDT\",\"amountUnits\":100}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(
                        reason==com.surprising.gateway.provider.exception.ProductTransferSecurityException.Reason.VALUATION_UNAVAILABLE?503:428));
        org.mockito.Mockito.verifyNoInteractions(coordinator);
    }


    @Test void authenticationPrecedesBodyBindingAndCannotReachSecurityOrFunds() {
        var coordinator=mock(ProductTransferCoordinator.class);
        var security=mock(ProductTransferSecurityService.class);
        var auth=userAuthService();
        var controller=controller(coordinator,security,auth);
        var request=new MockHttpServletRequest("POST","/api/v1/gateway/account/transfers");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->controller.proxy("account",HttpMethod.POST,request,"malformed".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        failure->assertThat(failure.getStatusCode().value()).isEqualTo(401));
        org.mockito.Mockito.verifyNoInteractions(auth,security,coordinator);
    }

    @Test void exactAmountAndAuthenticatedIdentityAreBoundInTheOriginalSecurityOrder() {
        var coordinator=mock(ProductTransferCoordinator.class);
        var security=mock(ProductTransferSecurityService.class);
        var auth=userAuthService();
        when(coordinator.transfer(org.mockito.ArgumentMatchers.any())).thenAnswer(call->ProductTransferResult.from(99,
                call.getArgument(0),ProductTransferStatus.COMPLETED,null,null,Instant.now()));
        var request=transferRequest();
        request.addHeader("Idempotency-Key","header-key");
        request.addHeader("X-Security-Email-Code","123456");
        request.addHeader("X-Security-TOTP-Code","654321");
        byte[] body=("{\"sourceAccountType\":\" funding \",\"targetAccountType\":\"USDT_PERPETUAL\",\"asset\":\"USDT\","
                +"\"amountUnits\":9223372036854775807,\"referenceId\":\"original-reference\",\"recipientUserId\":9007199254740993}").getBytes(StandardCharsets.UTF_8);
        var response=controller(coordinator,security,auth).proxy("account",HttpMethod.POST,request,body);
        var order=org.mockito.Mockito.inOrder(auth,security,coordinator);
        order.verify(auth).authenticateBearer("Bearer user");
        order.verify(security).requireIfNeeded(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(new com.surprising.gateway.provider.model.ProductTransferAmount("USDT",Long.MAX_VALUE)),
                org.mockito.ArgumentMatchers.eq("123456"),org.mockito.ArgumentMatchers.eq("654321"),org.mockito.ArgumentMatchers.any());
        var command=org.mockito.ArgumentCaptor.forClass(ProductTransferCommand.class);
        order.verify(coordinator).transfer(command.capture());
        assertThat(command.getValue()).isEqualTo(new ProductTransferCommand(42,"header-key",
                com.surprising.account.api.model.AccountType.FUNDING,com.surprising.account.api.model.AccountType.USDT_PERPETUAL,
                "USDT",Long.MAX_VALUE,"original-reference",null,9007199254740993L));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(response.getBody(),StandardCharsets.UTF_8)).contains("9223372036854775807","9007199254740993","FUNDING");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("invalidTransferFields")
    void invalidWireFieldsNeverReachFunds(String field,String value) {
        var json=new ObjectMapper();
        var payload=(tools.jackson.databind.node.ObjectNode)json.readTree("""
                {"sourceAccountType":"FUNDING","targetAccountType":"USDT_PERPETUAL","asset":"USDT","amountUnits":100,"referenceId":"original"}
                """);
        payload.set(field,json.readTree(value));
        var coordinator=mock(ProductTransferCoordinator.class);
        var controller=controller(coordinator,mock(ProductTransferSecurityService.class),userAuthService());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->controller.proxy("account",HttpMethod.POST,transferRequest(),json.writeValueAsBytes(payload)))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        failure->assertThat(failure.getStatusCode().value()).isEqualTo(400));
        org.mockito.Mockito.verifyNoInteractions(coordinator);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidTransferFields() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("amountUnits","0"),
                org.junit.jupiter.params.provider.Arguments.of("amountUnits","-1"),
                org.junit.jupiter.params.provider.Arguments.of("amountUnits","1.5"),
                org.junit.jupiter.params.provider.Arguments.of("amountUnits","9223372036854775806.0"),
                org.junit.jupiter.params.provider.Arguments.of("amountUnits","\"1.5\""),
                org.junit.jupiter.params.provider.Arguments.of("amountUnits","9223372036854775808"),
                org.junit.jupiter.params.provider.Arguments.of("amountUnits","null"),
                org.junit.jupiter.params.provider.Arguments.of("asset","\"\""),
                org.junit.jupiter.params.provider.Arguments.of("asset","\"bad!?\""),
                org.junit.jupiter.params.provider.Arguments.of("recipientUserId","0"),
                org.junit.jupiter.params.provider.Arguments.of("referenceId","\"\""),
                org.junit.jupiter.params.provider.Arguments.of("referenceId","\"unsafe/key\""),
                org.junit.jupiter.params.provider.Arguments.of("sourceAccountType","\"UNKNOWN\""));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"null","broken","[]"})
    void malformedBodyNeverReachesSecurityOrFunds(String body) {
        var coordinator=mock(ProductTransferCoordinator.class);
        var security=mock(ProductTransferSecurityService.class);
        var controller=controller(coordinator,security,userAuthService());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->controller.proxy("account",HttpMethod.POST,transferRequest(),body.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        failure->assertThat(failure.getStatusCode().value()).isEqualTo(400));
        org.mockito.Mockito.verifyNoInteractions(security,coordinator);
    }

    private MockHttpServletRequest transferRequest() {
        var request=new MockHttpServletRequest("POST","/api/v1/gateway/account/transfers");
        request.addHeader("Authorization","Bearer user");
        return request;
    }

    private GatewayProxyController controller(ProductTransferCoordinator coordinator,ProductTransferSecurityService security,AuthService auth) {
        var products=mock(com.surprising.gateway.provider.product.GatewayProductServices.class);
        var local=mock(com.surprising.gateway.provider.local.LocalBusinessApi.class);
        var json=new ObjectMapper();
        return new GatewayProxyController(properties(),new RestTemplate(),auth, org.mockito.Mockito.mock(com.surprising.gateway.provider.auth.service.GatewayApiKeyService.class),null,json,coordinator,security,
                products,local,new com.surprising.gateway.provider.product.GatewayProductSelection(products,json));
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
