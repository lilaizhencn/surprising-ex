package com.surprising.gateway.c2c;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.gateway.c2c.service.C2cQueryService;
import com.surprising.gateway.c2c.service.C2cQuickQuoteService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class C2cQueryValidationTest {
    private C2cQueryService queries;
    private C2cQuickQuoteService quick;
    private C2cController controller;
    private UUID market;
    @BeforeEach void setup() {
        queries=mock(C2cQueryService.class);quick=mock(C2cQuickQuoteService.class);
        var access=mock(C2cAccess.class);when(access.user(any())).thenReturn(C2cHttpFixture.principal(12));
        controller=new C2cController(access,null,null,null,queries,quick,null,null);market=UUID.randomUUID();
    }

    @Test void malformedOfferFiltersAreRejectedAtTheController() {
        assertThatThrownBy(()->offers("buy",null,null,null,20,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->offers("BUY","1e2",null,null,20,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->offers("BUY",null,"bad-method",null,20,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->offers("BUY",null,null,-1L,20,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->offers("BUY",null,null,null,51,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->offers("BUY",null,null,null,20,10001)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(queries,quick);
    }

    @Test void malformedOrderPagingAndScopeNeverReachTheQueryService() {
        assertThatThrownBy(()->controller.orders("Bearer test","UNKNOWN",null,"ALL",null,30,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->controller.orders("Bearer test","ALL",null,"buy",null,30,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->controller.orders("Bearer test","ALL",null,"ALL",null,101,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->controller.myAds("Bearer test",0)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(queries,quick);
    }

    @Test void quickQuoteRequiresMarketSideAmountAndSellerPaymentAccount() {
        for(var request:new C2cController.QuickQuoteRequest[]{
                new C2cController.QuickQuoteRequest(null,"BUY","1",null,null),
                new C2cController.QuickQuoteRequest(market,"UNKNOWN","1",null,null),
                new C2cController.QuickQuoteRequest(market,"BUY","",null,null),
                new C2cController.QuickQuoteRequest(market,"BUY","0",null,null),
                new C2cController.QuickQuoteRequest(market,"SELL","1",null,null)})
            assertThatThrownBy(()->controller.quick("Bearer test",request)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(queries,quick);
    }

    private void offers(String side,String amount,String method,Long merchant,int limit,int offset) {
        controller.offers("Bearer test",market,side,amount,method,merchant,false,0,0,3600,"PRICE",limit,offset);
    }
}
