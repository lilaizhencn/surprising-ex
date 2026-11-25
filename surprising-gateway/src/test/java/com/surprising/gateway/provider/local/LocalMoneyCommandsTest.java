package com.surprising.gateway.provider.local;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.account.api.model.*;
import com.surprising.account.provider.exception.AccountCommandRejectedException;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.gateway.provider.model.ProductTransferCommand;
import com.surprising.gateway.provider.model.ProductTransferStatus;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.gateway.provider.service.ProductTransferService;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalMoneyCommandsTest {
    private final AccountCommandGateway commands = mock(AccountCommandGateway.class);
    private final GatewayProductServices products = mock(GatewayProductServices.class);

    @BeforeEach
    void bindProducts() {
        when(products.enabled()).thenReturn(java.util.List.of(ProductLine.values()));
        when(products.service(any(), eq(AccountCommandGateway.class))).thenReturn(commands);
    }

    private ProductTransferCommand transfer() {
        return new ProductTransferCommand(42L, "transfer-007", AccountType.USDT_PERPETUAL,
                AccountType.FUNDING, "USDT", 1250L, "transfer-007", "test");
    }

    @Test
    void localTransferCommandsKeepIdentityAndNeverOpenHttpConnection() {
        var service = new ProductTransferService(products);
        var result = service.transfer(transfer());
        assertThat(result.status()).isEqualTo(ProductTransferStatus.COMPLETED);
        verify(commands).transferOut(any());
        verify(commands).transferIn(any());
        verify(commands).completeTransfer(any());
    }

    @Test
    void localCoreTimeoutDoesNotBecomeARejectedTransfer() {
        when(commands.transferOut(any())).thenThrow(new IllegalStateException("reply lost after commit"));
        var service = new ProductTransferService(products);
        assertThat(service.transfer(transfer()).status()).isEqualTo(ProductTransferStatus.PENDING);

        doThrow(new AccountCommandRejectedException("INSUFFICIENT_FUNDS", "insufficient funds"))
                .when(commands).transferOut(any());
        assertThat(service.transfer(transfer()).status()).isEqualTo(ProductTransferStatus.FAILED);
    }
}
