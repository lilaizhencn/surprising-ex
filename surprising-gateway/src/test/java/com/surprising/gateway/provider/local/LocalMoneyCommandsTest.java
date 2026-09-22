package com.surprising.gateway.provider.local;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.account.api.model.*;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.account.provider.service.AccountCommandRejectedException;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.*;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

class LocalMoneyCommandsTest {
    private final AccountCommandGateway commands = mock(AccountCommandGateway.class);
    private final RestTemplate http = mock(RestTemplate.class);
    private final AccountProperties account = new AccountProperties();
    private final GatewayProperties gateway = new GatewayProperties();

    private ProductTransferOperationRequest transfer() {
        return new ProductTransferOperationRequest(7001L, 42L, ProductLine.LINEAR_PERPETUAL, ProductLine.SPOT,
                AccountType.USDT_PERPETUAL, AccountType.FUNDING, "USDT", 1250L, "transfer-007", "test");
    }

    @Test
    void localTransferCommandsKeepIdentityAndNeverOpenHttpConnection() {
        account.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        var client = new ProductAccountAccess(gateway, http, commands, account);
        var request = transfer();
        assertThat(client.transferOut("USDT_PERPETUAL", request).status()).isEqualTo(ProductAccountAdjustment.Status.APPLIED);
        assertThat(client.completeTransfer("USDT_PERPETUAL", request).status()).isEqualTo(ProductAccountAdjustment.Status.APPLIED);
        verify(commands).transferOut(request);
        verify(commands).completeTransfer(request);
        verifyNoInteractions(http);
    }

    @Test
    void localCoreTimeoutDoesNotBecomeARejectedTransfer() {
        account.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        when(commands.transferOut(any())).thenThrow(new IllegalStateException("reply lost after commit"));
        var client = new ProductAccountAccess(gateway, http, commands, account);
        assertThat(client.transferOut("USDT_PERPETUAL", transfer()).status()).isEqualTo(ProductAccountAdjustment.Status.UNKNOWN);
        doThrow(new AccountCommandRejectedException("INSUFFICIENT_FUNDS", "insufficient funds")).when(commands).transferOut(any());
        assertThat(client.transferOut("USDT_PERPETUAL", transfer()).status()).isEqualTo(ProductAccountAdjustment.Status.REJECTED);
        verifyNoInteractions(http);
    }

    @Test
    void custodyAdjustmentCallsLocalSpotCoreWithSameReferenceAndSignedAmount() {
        account.getKafka().setProductLine(ProductLine.SPOT);
        var client = new SpotAccountClient(gateway, http, commands, account);
        client.adjustBalance(42, "usdt", -100, "withdrawal-1", "withdrawal");
        verify(commands).adjustBalance(new BalanceAdjustmentRequest(42, "USDT", -100, "withdrawal-1", "withdrawal"), null, null);
        verifyNoInteractions(http);
    }

    @Test
    void custodyCannotDebitPerpetualAccountAsIfItWereSpot() {
        account.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        gateway.getCustodyWallet().setSpotAccountBaseUrl("");
        var client = new SpotAccountClient(gateway, http, commands, account);
        assertThatThrownBy(() -> client.adjustBalance(42, "USDT", -100, "withdrawal-1", "withdrawal"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("endpoint is not configured");
        verifyNoInteractions(commands, http);
    }

    @Test
    void localCustodyTimeoutRetainsUnknownOutcomeForReconciliation() {
        account.getKafka().setProductLine(ProductLine.SPOT);
        when(commands.adjustBalance(any(), isNull(), isNull())).thenThrow(new IllegalStateException("reply lost"));
        var client = new SpotAccountClient(gateway, http, commands, account);
        assertThatThrownBy(() -> client.adjustBalance(42, "USDT", -100, "withdrawal-1", "withdrawal"))
                .isInstanceOf(SpotAccountClient.SpotAccountUnknownException.class);
        verifyNoInteractions(http);
    }
}
