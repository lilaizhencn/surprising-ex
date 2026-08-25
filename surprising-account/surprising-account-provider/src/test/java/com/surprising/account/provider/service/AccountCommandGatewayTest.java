package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.api.model.PositionModeUpdateRequest;
import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.aeron.protocol.CoreBalanceView;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.PositionMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountCommandGatewayTest {

    @Test
    void productTransferUsesDedicatedAeronCommands() {
        AccountAeronGateway sourceAeron = mock(AccountAeronGateway.class);
        when(sourceAeron.command(any(), any(), eq(7L), any()))
                .thenReturn(new CoreResponse(ResponseStatus.APPLIED, 1, 1));
        when(sourceAeron.userState(7L)).thenReturn(new CoreUserStateView(ProductLine.SPOT, 7, 2,
                CorePositionMode.ONE_WAY, List.of(new CoreBalanceView("USDT", 750, 0)), List.of(), List.of()));
        AccountAeronGateway targetAeron = mock(AccountAeronGateway.class);
        when(targetAeron.command(any(), any(), eq(7L), any()))
                .thenReturn(new CoreResponse(ResponseStatus.APPLIED, 1, 1));
        when(targetAeron.userState(7L)).thenReturn(new CoreUserStateView(ProductLine.LINEAR_PERPETUAL, 7, 2,
                CorePositionMode.ONE_WAY, List.of(new CoreBalanceView("USDT", 250, 0)), List.of(), List.of()));
        AccountCommandGateway source = new AccountCommandGateway(properties(ProductLine.SPOT), sourceAeron);
        AccountCommandGateway target = new AccountCommandGateway(
                properties(ProductLine.LINEAR_PERPETUAL), targetAeron);
        ProductTransferOperationRequest request = new ProductTransferOperationRequest(91L, 7L,
                ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL, AccountType.FUNDING,
                AccountType.USDT_PERPETUAL, "USDT", 250L, "transfer-91", "allocation");

        source.transferOut(request);
        target.transferIn(request);
        source.completeTransfer(request);

        verify(sourceAeron).command(eq(CoreMessageType.TRANSFER_OUT), any(), eq(7L), any());
        verify(targetAeron).command(eq(CoreMessageType.TRANSFER_IN), any(), eq(7L), any());
        verify(sourceAeron).command(eq(CoreMessageType.COMPLETE_TRANSFER), any(), eq(7L), any());
    }

    @Test
    void balanceAdjustmentSubmitsAeronCommandAndReadsAuthoritativeState() {
        AccountAeronGateway aeron = mock(AccountAeronGateway.class);
        when(aeron.command(eq(CoreMessageType.ADJUST_BALANCE), any(), eq(7L), any()))
                .thenReturn(new CoreResponse(ResponseStatus.APPLIED, 1, 1));
        when(aeron.userState(7L)).thenReturn(new CoreUserStateView(ProductLine.LINEAR_PERPETUAL, 7, 1,
                CorePositionMode.ONE_WAY, List.of(new CoreBalanceView("USDT", 900, 100)), List.of(), List.of()));
        AccountCommandGateway gateway = new AccountCommandGateway(
                properties(ProductLine.LINEAR_PERPETUAL), aeron);

        var response = gateway.adjustBalance(new BalanceAdjustmentRequest(7, "usdt", 1_000,
                "deposit-1", "test"), null, null);

        assertThat(response.availableUnits()).isEqualTo(900);
        assertThat(response.lockedUnits()).isEqualTo(100);
        assertThat(response.equityUnits()).isEqualTo(1_000);
        verify(aeron).command(eq(CoreMessageType.ADJUST_BALANCE), any(), eq(7L), any());
    }

    @Test
    void positionModeUsesStableAeronStateInsteadOfLocalReducer() {
        AccountAeronGateway aeron = mock(AccountAeronGateway.class);
        when(aeron.command(eq(CoreMessageType.UPDATE_POSITION_MODE), any(), anyLong(), any()))
                .thenReturn(new CoreResponse(ResponseStatus.APPLIED, 1, 1));
        when(aeron.userState(7L)).thenReturn(new CoreUserStateView(ProductLine.LINEAR_PERPETUAL, 7, 2,
                CorePositionMode.HEDGE, List.of(), List.of(), List.of()));
        AccountCommandGateway gateway = new AccountCommandGateway(
                properties(ProductLine.LINEAR_PERPETUAL), aeron);

        var response = gateway.updatePositionMode(new PositionModeUpdateRequest(7,
                ProductLine.LINEAR_PERPETUAL, PositionMode.HEDGE, "mode-1"));

        assertThat(response.positionMode()).isEqualTo(PositionMode.HEDGE);
    }

    private static AccountProperties properties(ProductLine productLine) {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(productLine);
        return properties;
    }
}
