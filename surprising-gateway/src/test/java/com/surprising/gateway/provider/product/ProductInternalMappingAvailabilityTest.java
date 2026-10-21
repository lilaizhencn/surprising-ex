package com.surprising.gateway.provider.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class ProductInternalMappingAvailabilityTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void productDisappearingAfterSelectionStillMapsBeforeAHandlerExists(ProductLine line) {
        var products=new GatewayProductServices(null,null,null);
        var selection=mock(GatewayProductSelection.class);
        var request=new MockHttpServletRequest("GET","/internal/v1/accounts/42");
        when(selection.resolve(request,null,true)).thenReturn(line);
        var mapping=new ProductInternalHandlerMapping(products,selection);
        assertThatThrownBy(()->mapping.getHandlerInternal(request)).isInstanceOfSatisfying(ResponseStatusException.class,
                failure->{assertThat(failure.getStatusCode().value()).isEqualTo(404);
                    assertThat(failure.getCause()).isInstanceOf(com.surprising.gateway.provider.exception.ProductUnavailableException.class);});
    }
}
