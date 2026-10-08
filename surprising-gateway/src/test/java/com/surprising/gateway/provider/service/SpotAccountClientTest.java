package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

class SpotAccountClientTest {
    @Test void custodyAlwaysSelectsSpotEvenWhenPerpetualIsEnabled() {
        var products = mock(GatewayProductServices.class);
        var spot = mock(AccountCommandGateway.class);
        var perpetual = mock(AccountCommandGateway.class);
        when(products.service(ProductLine.SPOT, AccountCommandGateway.class)).thenReturn(spot);
        when(products.service(ProductLine.LINEAR_PERPETUAL, AccountCommandGateway.class)).thenReturn(perpetual);
        new SpotAccountClient(products).adjustBalance(42, "usdt", 1250, "deposit:1", "custody deposit");
        verify(spot).adjustBalance(new BalanceAdjustmentRequest(42,"USDT",1250,"deposit:1","custody deposit"),null,null);
        verifyNoInteractions(perpetual);
        assertThat(java.util.Arrays.stream(SpotAccountClient.class.getDeclaredFields())
                .anyMatch(field -> field.getType() == org.springframework.web.client.RestTemplate.class)).isFalse();
    }
}
