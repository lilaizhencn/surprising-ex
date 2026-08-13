package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.aeron.protocol.CoreBalanceView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CorePositionView;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.PositionMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountServiceLocalSnapshotTest {

    @Test
    void balanceAndPositionsReadOnlyAeronStrongState() {
        AccountQueryService projection = mock(AccountQueryService.class);
        AccountAeronGateway aeron = mock(AccountAeronGateway.class);
        when(aeron.userState(1001L)).thenReturn(snapshot());
        AccountService service = service(aeron, projection);

        assertThat(service.balance(1001L, "usdt").availableUnits()).isEqualTo(800L);
        assertThat(service.position(1001L, "btc-usdt").signedQuantitySteps()).isEqualTo(10L);
        assertThat(service.positions(1001L).count()).isEqualTo(1);
        assertThat(service.positionMode(1001L).positionMode()).isEqualTo(PositionMode.ONE_WAY);
        verifyNoInteractions(projection);
    }

    @Test
    void missingAeronStateFailsClosedInsteadOfQueryingProjectionDatabase() {
        AccountQueryService projection = mock(AccountQueryService.class);
        AccountAeronGateway aeron = mock(AccountAeronGateway.class);
        AccountService service = service(aeron, projection);

        assertThatThrownBy(() -> service.balance(1001L, "USDT"))
                .isInstanceOf(AccountStateUnavailableException.class);
        verifyNoInteractions(projection);
    }

    @Test
    void unsupportedProductLineAndTransferDoNotFallBackToDatabase() {
        AccountQueryService projection = mock(AccountQueryService.class);
        AccountCommandGateway gateway = mock(AccountCommandGateway.class);
        AccountAeronGateway aeron = mock(AccountAeronGateway.class);
        AccountService service = new AccountService(properties(), mock(AccountUserStateReducer.class), gateway,
                aeron, projection);

        assertThatThrownBy(() -> service.positionMode(ProductLine.LINEAR_DELIVERY, 1001L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.transfer(new com.surprising.account.api.model.ProductTransferRequest(
                1001L, AccountType.USDT_PERPETUAL, AccountType.FUNDING, "USDT", 1L, "ref", "test")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(projection);
    }

    private static AccountService service(AccountAeronGateway aeron, AccountQueryService projection) {
        return new AccountService(properties(), mock(AccountUserStateReducer.class),
                mock(AccountCommandGateway.class), aeron, projection);
    }

    private static AccountProperties properties() {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        properties.getKafka().setProductTopicsEnabled(true);
        return properties;
    }

    private static CoreUserStateView snapshot() {
        return new CoreUserStateView(ProductLine.LINEAR_PERPETUAL, 1001L, 1L, CorePositionMode.ONE_WAY,
                List.of(new CoreBalanceView("USDT", 800L, 200L)), List.of(),
                List.of(new CorePositionView("BTC-USDT", "USDT", CoreMarginMode.CROSS,
                        CorePositionSide.NET, 1L, 10L, 100L, 1000L, 0L, 200L)));
    }
}
