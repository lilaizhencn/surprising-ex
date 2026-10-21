package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.controller.C2cAdminController;
import com.surprising.gateway.c2c.controller.C2cMerchantController;
import com.surprising.gateway.c2c.service.C2cAccess;
import com.surprising.gateway.c2c.service.C2cAdminAccess;
import com.surprising.gateway.c2c.service.C2cAdminMarkets;
import com.surprising.gateway.c2c.service.C2cMerchantBonds;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class C2cConfigurationValidationTest {
    private C2cAdminMarkets markets;
    private C2cMerchantBonds bonds;
    private C2cAdminController controller;
    private C2cMerchantController merchant;
    private ObjectMapper json;
    private MockHttpServletRequest http;
    @BeforeEach void setup() {
        markets=mock(C2cAdminMarkets.class);bonds=mock(C2cMerchantBonds.class);json=new ObjectMapper();http=new MockHttpServletRequest();
        var admin=mock(C2cAdminAccess.class);var access=mock(C2cAccess.class);when(access.user(any())).thenReturn(C2cHttpFixture.principal(12));
        controller=new C2cAdminController(admin,markets,null,null,null,json, org.mockito.Mockito.mock(com.surprising.gateway.c2c.controller.C2cAdminRequestBinding.class));merchant=new C2cMerchantController(bonds,access,admin,json, org.mockito.Mockito.mock(com.surprising.gateway.c2c.controller.C2cAdminRequestBinding.class));
    }

    @Test void configurationJsonVersionAndRangesAreValidatedBeforeBusinessWrites() {
        assertThatThrownBy(()->controller.configure("Bearer qa","null".getBytes(StandardCharsets.UTF_8),http)).isInstanceOf(IllegalArgumentException.class);
        for(var input:new C2cAdminController.ConfigurationInput[]{
                new C2cAdminController.ConfigurationInput("1",true,0,"Synthetic valid reason"),
                new C2cAdminController.ConfigurationInput("01",true,10,"Synthetic valid reason"),
                new C2cAdminController.ConfigurationInput("1",true,10,"bad")})
            assertThatThrownBy(()->controller.configure("Bearer qa",json.writeValueAsBytes(input),http)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(markets,bonds);
    }

    @Test void marketAmountsAndPaymentMethodCurrenciesAreParsedAtTheController() {
        var market=new C2cAdminController.MarketInput(null,"0","USDT","CNY",2,true,"1e3","10000","1","100",1000,900,5,true,100,"Synthetic market validation");
        assertThatThrownBy(()->controller.market("Bearer qa",json.writeValueAsBytes(market),http)).isInstanceOf(IllegalArgumentException.class);
        var inverted=new C2cAdminController.MarketInput(null,"0","USDT","CNY",2,true,"100","1","1","100",1000,900,5,true,100,"Synthetic inverted limits");
        assertThatThrownBy(()->controller.market("Bearer qa",json.writeValueAsBytes(inverted),http)).isInstanceOf(IllegalArgumentException.class);
        var method=new C2cAdminController.MethodInput("ALIPAY","1","Valid label",true,List.of("bad"),false,true,"Synthetic method validation");
        assertThatThrownBy(()->controller.method("Bearer qa",json.writeValueAsBytes(method),http)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(markets,bonds);
    }

    @Test void depositIdentityAndPolicyFormatNeverReachTheBondServiceWhenInvalid() {
        assertThatThrownBy(()->merchant.deposit("Bearer qa",new C2cMerchantController.Deposit(null,"1",null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->merchant.deposit("Bearer qa",new C2cMerchantController.Deposit(UUID.randomUUID(),"0",null))).isInstanceOf(IllegalArgumentException.class);
        var policy=new C2cMerchantController.Policy("1","USDT","1e2","Synthetic policy validation");
        assertThatThrownBy(()->merchant.configure("Bearer qa",json.writeValueAsBytes(policy),http)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->merchant.configure("Bearer qa","null".getBytes(StandardCharsets.UTF_8),http)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(markets,bonds);
    }
}
