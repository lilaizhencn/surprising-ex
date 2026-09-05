package com.surprising.account.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.account.provider.service.AccountAeronGateway;
import com.surprising.aeron.protocol.CoreBalanceView;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;

class PerpetualAccountStateInternalControllerTest {

    @Test
    void recoveryReadsCanonicalAeronUserState() {
        AccountAeronGateway aeron = mock(AccountAeronGateway.class);
        when(aeron.userState(900001L)).thenReturn(new CoreUserStateView(ProductLine.LINEAR_PERPETUAL,
                900001L, 17L, CorePositionMode.ONE_WAY,
                List.of(new CoreBalanceView("USDT", 800L, 200L)), List.of(), List.of()));
        var controller = new PerpetualAccountStateInternalController(aeron);

        var response = controller.recover(ProductLine.LINEAR_PERPETUAL, 900001L);

        assertThat(response.accountRevision()).isEqualTo(17L);
        assertThat(response.balances()).containsExactly(
                new com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent.Balance("USDT", 800L, 200L));
    }
}
