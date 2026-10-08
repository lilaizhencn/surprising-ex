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
    private final GatewayProductsProperties products = new GatewayProductsProperties();
    private final GatewayProductSelection selection = new GatewayProductSelection(products, new ObjectMapper());
    GatewayProductSelectionTest() { products.setEnabled(List.of(ProductLine.values())); }
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
        products.setEnabled(List.of(ProductLine.SPOT));
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
        products.setEnabled(List.of(ProductLine.SPOT));
        var request=request(); request.addParameter("productLine","OPTION");
        assertThatThrownBy(() -> selection.resolve(request,null,true)).hasMessageContaining("not enabled");
        for(String body:List.of("{\"productLine\":\"made-up\"}","{\"productLine\":[]}","invalid"))
            assertThatThrownBy(() -> selection.resolve(request(),body(body),true)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void compatibilityPathCannotBeOverriddenByAHeader() {
        var request=new MockHttpServletRequest("POST","/fapi/v1/order");request.addHeader("X-Product-Line","SPOT");
        assertThatThrownBy(() -> selection.resolve(request,null,true)).hasMessageContaining("conflicting");
    }
    @Test void fundingSelectsSpotAndStartupRequiresConnectionsForAllEnabledProducts() {
        var request=request();request.addParameter("accountType","FUNDING");
        assertThat(selection.resolve(request,null,true)).isEqualTo(ProductLine.SPOT);
        assertThatThrownBy(products::validate).hasMessageContaining("missing Gateway Core connection");
        for(var product:products.getEnabled()) products.getCores().put(product,new GatewayProductsProperties.Core());
        assertThatCode(products::validate).doesNotThrowAnyException();
        products.setEnabled(List.of(ProductLine.SPOT,ProductLine.SPOT));
        assertThatThrownBy(products::validate).hasMessageContaining("distinct");
    }
    @Test void rejectsTimeoutsThatLoseMillisecondPrecisionOrExceedTheRequestBudget() {
        products.setEnabled(List.of(ProductLine.SPOT));
        var core = new GatewayProductsProperties.Core();
        products.getCores().put(ProductLine.SPOT, core);
        for (var timeout : List.of(java.time.Duration.ZERO, java.time.Duration.ofNanos(1),
                java.time.Duration.ofSeconds(61))) {
            core.setResponseTimeout(timeout);
            assertThatThrownBy(products::validate).hasMessageContaining("invalid Gateway Core");
        }
        for (var timeout : List.of(java.time.Duration.ofMillis(1), java.time.Duration.ofMinutes(1))) {
            core.setResponseTimeout(timeout);
            assertThatCode(products::validate).doesNotThrowAnyException();
        }
    }
}
