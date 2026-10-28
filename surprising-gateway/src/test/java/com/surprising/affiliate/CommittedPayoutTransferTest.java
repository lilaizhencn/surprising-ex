package com.surprising.affiliate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.account.api.model.AccountType;
import com.surprising.gateway.provider.model.*;
import com.surprising.gateway.provider.service.*;
import org.junit.jupiter.api.Test;

class CommittedPayoutTransferTest {

  @Test
  void acceptedBusinessIntentCanCompleteWithoutRecheckingCurrentUserPermissions() {
    var accounts = mock(ProductAccountClient.class);
    var applied = ProductAccountAdjustment.applied("applied");
    when(accounts.transferOut(anyString(), any())).thenReturn(applied);
    when(accounts.transferIn(anyString(), any())).thenReturn(applied);
    when(accounts.completeTransfer(anyString(), any())).thenReturn(applied);
    var coordinator = new ProductTransferCoordinator(accounts, null);
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
    verify(accounts, times(2)).transferOut(anyString(), requests.capture());
    assertThat(requests.getAllValues().getFirst()).isEqualTo(
      requests.getAllValues().getLast()
    );
  }
}
