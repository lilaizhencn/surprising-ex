package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.repository.AdminAuditRepository;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.controller.advice.ProductAvailabilityHttpAdvice;
import com.surprising.gateway.provider.exception.ProductUnavailableException;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.gateway.provider.product.GatewayProductSelection;
import com.surprising.gateway.provider.service.GatewayProductSettings;
import com.surprising.gateway.provider.service.LiquidityOperationsService;
import com.surprising.gateway.provider.service.ProductAccountAccess;
import com.surprising.instrument.provider.service.InstrumentService;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class ProductUnavailableHttpBoundaryTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void nativeUnavailableProductStopsBeforeAccountRuntimeAndCannotBecomeZeroBalance(ProductLine line) throws Exception {
        var settings=mock(GatewayProductSettings.class);when(settings.list()).thenReturn(List.of());
        var products=new GatewayProductServices(null,null,settings);
        var accountType=AccountType.valueOf(line.accountTypeCode());
        var operation=new ProductTransferOperationRequest(1,42,line,line,accountType,accountType,"USDT",100,"operation:1","test");
        assertThatThrownBy(()->new ProductAccountAccess(products).transferOut(line.accountTypeCode(),operation))
                .isInstanceOfSatisfying(ProductUnavailableException.class, failure->assertThat(failure.product()).isEqualTo(line));
        var auth=mock(AuthService.class);when(auth.authenticateBearer("Bearer own")).thenReturn(
                new JwtPrincipal(42,"user","NORMAL",List.of(),Instant.now().plusSeconds(60)));
        var mvc=MockMvcBuilders.standaloneSetup(new AccountAssetsController(auth,
                new GatewayProductSelection(products,new ObjectMapper()),products))
                .setControllerAdvice(new ProductAvailabilityHttpAdvice()).build();
        for(String path:List.of("balances","product-balances")) {
            mvc.perform(get("/api/v1/gateway/account/"+path).header("Authorization","Bearer own")
                    .header("X-Product-Line",line.name()).param("userId","42").param("accountType",accountType.name()))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("product is not enabled: "+line.name()));
        }
        products.close();
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void actualHostOperationsMapNativeFailureAndNeverStartAuditOrBusiness(ProductLine line) throws Exception {
        var products=new GatewayProductServices(null,null,mock(GatewayProductSettings.class));
        var audit=mock(AdminAuditRepository.class);var instruments=mock(InstrumentService.class);
        String token="host-operation-product-availability-secret";
        var mvc=MockMvcBuilders.standaloneSetup(new InternalLiquidityOperationsController(token,
                new LiquidityOperationsService(products,instruments,audit),new ObjectMapper()))
                .setControllerAdvice(new ProductAvailabilityHttpAdvice()).build();
        mvc.perform(get("/internal/v1/operations/liquidity/leverage").with(request->{request.setRemoteAddr("127.0.0.1");return request;})
                .header("X-Operations-Token",token).param("userId","42").param("instrumentId","604")
                .param("productLine",line.name()).param("marginMode","CROSS"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("product is not enabled: "+line.name()));
        verifyNoInteractions(audit,instruments);products.close();
    }
}
