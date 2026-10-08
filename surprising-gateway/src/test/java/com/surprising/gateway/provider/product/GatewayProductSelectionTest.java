package com.surprising.gateway.provider.product;

import static org.assertj.core.api.Assertions.*;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class GatewayProductSelectionTest {
    private final GatewayProductServices products = org.mockito.Mockito.mock(GatewayProductServices.class);
    private List<ProductLine> enabled = List.of(ProductLine.values());
    private final GatewayProductSelection selection = new GatewayProductSelection(products, new ObjectMapper());
    GatewayProductSelectionTest() {
        org.mockito.Mockito.when(products.enabled()).thenAnswer(i -> enabled);
        org.mockito.Mockito.doAnswer(i -> {
            if (!enabled.contains(i.getArgument(0))) throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"not enabled");
            return null;
        }).when(products).requireEnabled(org.mockito.ArgumentMatchers.any());
    }
    private MockHttpServletRequest request() { return new MockHttpServletRequest("POST","/api/v1/gateway/trading"); }
    private byte[] body(String body) { return body.getBytes(StandardCharsets.UTF_8); }
    @ParameterizedTest @EnumSource(ProductLine.class)
    void acceptsEquivalentSelectorsAndSelectsEachOfTheSixProducts(ProductLine product) {
        var request = request(); request.addHeader("X-Product-Line",product.name());
        request.addParameter("accountType",product.accountTypeCode());
        assertThat(selection.resolve(request,body("{\"contractType\":\""+product.contractTypeCode()+"\"}"),true)).isEqualTo(product);
    }
    @Test void refusesMissingSelectorForMultipleProducts() {
        assertThatThrownBy(() -> selection.resolve(request(),null,true)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("productLine is required");
        assertThat(selection.resolve(request(),null,false)).isNull();
        enabled = List.of(ProductLine.SPOT);
        assertThat(selection.resolve(request(),null,true)).isEqualTo(ProductLine.SPOT);
    }
    @Test void rejectsConflictingHeadersQueriesAndBatchBodies() {
        var request=request(); request.addHeader("X-Product-Line","SPOT");
        for (byte[] body : List.of(body("{\"productLine\":\"LINEAR_PERPETUAL\"}"),
                body("{\"orders\":[{\"accountType\":\"SPOT\"},{\"accountType\":\"USDT_PERPETUAL\"}]}")))
            assertThatThrownBy(() -> selection.resolve(request,body,true)).hasMessageContaining("conflicting");
        request.addParameter("productLine","SPOT","OPTION");
        assertThatThrownBy(() -> selection.resolve(request,null,true)).hasMessageContaining("conflicting");
    }
    @Test void rejectsDisabledUnknownAndNonStringProducts() {
        enabled = List.of(ProductLine.SPOT);
        var request=request(); request.addParameter("productLine","OPTION");
        assertThatThrownBy(() -> selection.resolve(request,null,true)).hasMessageContaining("not enabled");
        for(String body:List.of("{\"productLine\":\"made-up\"}","{\"productLine\":[]}","invalid"))
            assertThatThrownBy(() -> selection.resolve(request(),body(body),true)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void compatibilityPathCannotBeOverriddenByAHeader() {
        var request=new MockHttpServletRequest("POST","/fapi/v1/order");request.addHeader("X-Product-Line","SPOT");
        assertThatThrownBy(() -> selection.resolve(request,null,true)).hasMessageContaining("conflicting");
    }
    @Test void fundingSelectsSpotAndOnlyCoreAddressesRemainInDeploymentConfiguration() {
        var request=request(); request.addParameter("accountType","FUNDING");
        assertThat(selection.resolve(request,null,true)).isEqualTo(ProductLine.SPOT);
        var config = new GatewayProductsProperties();
        assertThatThrownBy(() -> config.validate(ProductLine.SPOT)).hasMessageContaining("missing Gateway Core connection");
        config.getCores().put(ProductLine.SPOT, new GatewayProductsProperties.Core());
        assertThatCode(() -> config.validate(ProductLine.SPOT)).doesNotThrowAnyException();
    }
    @Test void rejectsTimeoutsThatLoseMillisecondPrecisionOrExceedTheRequestBudget() {
        var config = new GatewayProductsProperties();
        var core = new GatewayProductsProperties.Core();
        config.getCores().put(ProductLine.SPOT, core);
        for (var timeout : List.of(java.time.Duration.ZERO, java.time.Duration.ofNanos(1),
                java.time.Duration.ofSeconds(61))) {
            core.setResponseTimeout(timeout);
            assertThatThrownBy(() -> config.validate(ProductLine.SPOT)).hasMessageContaining("invalid Gateway Core");
        }
        for (var timeout : List.of(java.time.Duration.ofMillis(1), java.time.Duration.ofMinutes(1))) {
            core.setResponseTimeout(timeout);
            assertThatCode(() -> config.validate(ProductLine.SPOT)).doesNotThrowAnyException();
        }
    }
}
