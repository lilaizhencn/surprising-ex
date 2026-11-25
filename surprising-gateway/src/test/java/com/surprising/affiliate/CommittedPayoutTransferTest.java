package com.surprising.affiliate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.gateway.provider.model.*;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.gateway.provider.service.*;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

class CommittedPayoutTransferTest {

  @Test
  void acceptedBusinessIntentCanCompleteWithoutRecheckingCurrentUserPermissions() {
    var accounts = mock(AccountCommandGateway.class);
    var products = mock(GatewayProductServices.class);
    when(products.service(ProductLine.LINEAR_PERPETUAL, AccountCommandGateway.class)).thenReturn(accounts);
    var coordinator = new ProductTransferService(products, null);
    var command = new ProductTransferCommand(
      9,
      "affiliate:payout-1",
      AccountType.USDT_PERPETUAL,
      AccountType.USDT_PERPETUAL,
      "USDT",
      2000,
      "affiliate:payout-1",
      "返佣结算",
      1
    );
    assertThatThrownBy(() -> coordinator.transfer(command)).isInstanceOf(
      IllegalArgumentException.class
    );
    var first = coordinator.executeCommittedTransfer(command);
    var retry = coordinator.executeCommittedTransfer(command);
    assertThat(first.status()).isEqualTo(ProductTransferStatus.COMPLETED);
    assertThat(retry.transferId()).isEqualTo(first.transferId());
    var requests = org.mockito.ArgumentCaptor.forClass(
      com.surprising.account.api.model.ProductTransferOperationRequest.class
    );
    verify(accounts, times(2)).transferOut(requests.capture());
    assertThat(requests.getAllValues().getFirst()).isEqualTo(
      requests.getAllValues().getLast()
    );
  }
}
