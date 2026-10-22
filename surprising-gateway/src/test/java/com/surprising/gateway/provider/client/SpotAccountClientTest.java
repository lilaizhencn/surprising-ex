package com.surprising.gateway.provider.client;

import com.surprising.gateway.provider.exception.SpotAccountRejectedException;
import com.surprising.gateway.provider.exception.SpotAccountUnknownException;

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
    @Test void rejectedAndInvalidCommandsStayRejectedAndDoNotRetry() {
        var products = mock(GatewayProductServices.class);
        var commands = mock(AccountCommandGateway.class);
        when(products.service(ProductLine.SPOT, AccountCommandGateway.class)).thenReturn(commands);
        var client = new SpotAccountClient(products);
        for (RuntimeException failure : java.util.List.of(
                new com.surprising.account.provider.exception.AccountCommandRejectedException("INSUFFICIENT_FUNDS", "insufficient funds"),
                new IllegalArgumentException("invalid account"))) {
            reset(commands);
            when(commands.adjustBalance(any(), isNull(), isNull())).thenThrow(failure);
            assertThatThrownBy(() -> client.adjustBalance(42, "USDT", -100, "withdrawal:1", "withdrawal"))
                    .isInstanceOf(SpotAccountRejectedException.class).hasMessage(failure.getMessage());
            verify(commands, times(1)).adjustBalance(any(), isNull(), isNull());
        }
    }

    @Test void uncertainCoreOutcomeKeepsCauseAndOriginalReferenceWithoutResubmission() {
        var products = mock(GatewayProductServices.class);
        var commands = mock(AccountCommandGateway.class);
        when(products.service(ProductLine.SPOT, AccountCommandGateway.class)).thenReturn(commands);
        var failure = new IllegalStateException("reply lost after commit");
        when(commands.adjustBalance(any(), isNull(), isNull())).thenThrow(failure);
        assertThatThrownBy(() -> new SpotAccountClient(products).adjustBalance(42, "USDT", -Long.MAX_VALUE,
                "withdrawal:1", "withdrawal")).isInstanceOf(SpotAccountUnknownException.class).hasCause(failure);
        verify(commands, times(1)).adjustBalance(new BalanceAdjustmentRequest(42, "USDT", -Long.MAX_VALUE,
                "withdrawal:1", "withdrawal"), null, null);
    }

    @Test void missingSpotProductDoesNotBecomeAnUncertainCoreSubmission() {
        var products = mock(GatewayProductServices.class);
        var unavailable = new com.surprising.gateway.provider.exception.ProductUnavailableException(ProductLine.SPOT);
        when(products.service(ProductLine.SPOT, AccountCommandGateway.class)).thenThrow(unavailable);
        assertThatThrownBy(() -> new SpotAccountClient(products).adjustBalance(42, "USDT", -100,
                "withdrawal:1", "withdrawal")).isSameAs(unavailable);
    }

    @Test void invalidInternalCommandCannotReachAnyProduct() {
        var products = mock(GatewayProductServices.class);
        assertThatThrownBy(() -> new SpotAccountClient(products).adjustBalance(0, "USDT", -100,
                "withdrawal:1", "withdrawal")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(products);
    }
}
