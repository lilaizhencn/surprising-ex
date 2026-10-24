package com.surprising.account.provider.service;

import com.surprising.account.provider.repository.BalanceAdjustmentRepository;
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
    void pendingTransfersPreserveBothUsersAndAllPublicFields() {
        var aeron = mock(AccountAeronGateway.class);
        var command = new com.surprising.aeron.protocol.TransferFundsCommand(91, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                "SPOT", "USDT_PERPETUAL", "USDT", 37, "transfer-91", "allocation", 7, 8);
        when(aeron.query(eq(CoreMessageType.PENDING_TRANSFER_QUERY), any(), any()))
                .thenReturn(new CoreResponse(ResponseStatus.OK, 1,
                        com.surprising.aeron.protocol.CorePendingTransferCodec.encode(List.of(command))));
        var gateway = new AccountCommandGateway(properties(ProductLine.SPOT), aeron, mock(BalanceAdjustmentRepository.class));
        assertThat(gateway.pendingTransfers(16)).containsExactly(new ProductTransferOperationRequest(91, 7,
                ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL, AccountType.SPOT, AccountType.USDT_PERPETUAL,
                "USDT", 37, "transfer-91", "allocation", 8));
    }

    @Test
    void productTransferUsesDedicatedAeronCommands() {
        AccountAeronGateway sourceAeron = mock(AccountAeronGateway.class);
        when(sourceAeron.command(any(), any(), eq(7L), any()))
                .thenReturn(new CoreResponse(ResponseStatus.APPLIED, 1));
        when(sourceAeron.userState(7L)).thenReturn(new CoreUserStateView(ProductLine.SPOT, 7, 2,
                CorePositionMode.ONE_WAY, List.of(new CoreBalanceView("USDT", 750, 0)), List.of(), List.of()));
        AccountAeronGateway targetAeron = mock(AccountAeronGateway.class);
        when(targetAeron.command(any(), any(), eq(7L), any()))
                .thenReturn(new CoreResponse(ResponseStatus.APPLIED, 1));
        when(targetAeron.userState(7L)).thenReturn(new CoreUserStateView(ProductLine.LINEAR_PERPETUAL, 7, 2,
                CorePositionMode.ONE_WAY, List.of(new CoreBalanceView("USDT", 250, 0)), List.of(), List.of()));
        AccountCommandGateway source = new AccountCommandGateway(properties(ProductLine.SPOT), sourceAeron, mock(BalanceAdjustmentRepository.class));
        AccountCommandGateway target = new AccountCommandGateway(
                properties(ProductLine.LINEAR_PERPETUAL), targetAeron, mock(BalanceAdjustmentRepository.class));
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
                .thenReturn(new CoreResponse(ResponseStatus.APPLIED, 1));
        when(aeron.userState(7L)).thenReturn(new CoreUserStateView(ProductLine.LINEAR_PERPETUAL, 7, 1,
                CorePositionMode.ONE_WAY, List.of(new CoreBalanceView("USDT", 900, 100)), List.of(), List.of()));
        AccountCommandGateway gateway = new AccountCommandGateway(
                properties(ProductLine.LINEAR_PERPETUAL), aeron, mock(BalanceAdjustmentRepository.class));

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
                .thenReturn(new CoreResponse(ResponseStatus.APPLIED, 1));
        when(aeron.userState(7L)).thenReturn(new CoreUserStateView(ProductLine.LINEAR_PERPETUAL, 7, 2,
                CorePositionMode.HEDGE, List.of(), List.of(), List.of()));
        AccountCommandGateway gateway = new AccountCommandGateway(
                properties(ProductLine.LINEAR_PERPETUAL), aeron, mock(BalanceAdjustmentRepository.class));

        var response = gateway.updatePositionMode(new PositionModeUpdateRequest(7,
                ProductLine.LINEAR_PERPETUAL, PositionMode.HEDGE, "mode-1"));

        assertThat(response.positionMode()).isEqualTo(PositionMode.HEDGE);
    }

    @Test
    void adminIntentIsSavedBeforeOfferWithTheExistingStableCommandIdentity() {
        var aeron = mock(AccountAeronGateway.class);
        var journal = mock(BalanceAdjustmentRepository.class);
        when(aeron.userState(7)).thenReturn(new CoreUserStateView(ProductLine.SPOT, 7, 1,
                CorePositionMode.ONE_WAY, List.of(new CoreBalanceView("USDT", 900, 100)), List.of(), List.of()));
        var gateway = new AccountCommandGateway(properties(ProductLine.SPOT), aeron, journal);
        gateway.adjustBalance(new BalanceAdjustmentRequest(7, " usdt ", 100, " ref-7 ", "original"), "11", "admin");
        var id = java.util.UUID.nameUUIDFromBytes("SPOT:7:balance-adjust:ref-7".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var order = org.mockito.Mockito.inOrder(journal, aeron);
        order.verify(journal).prepare(ProductLine.SPOT, id, "BASIC", 11L, "admin", 7, "USDT", 100, "ref-7", "original");
        order.verify(aeron).command(eq(CoreMessageType.ADJUST_BALANCE), eq(id), eq(7L), any());
        order.verify(aeron).userState(7);
    }

    @Test
    void unavailableJournalPreventsAnyCoreOffer() {
        var aeron = mock(AccountAeronGateway.class);
        var journal = mock(BalanceAdjustmentRepository.class);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("journal unavailable"))
                .when(journal).prepare(any(), any(), any(), any(), any(), anyLong(), any(), anyLong(), any(), any());
        var gateway = new AccountCommandGateway(properties(ProductLine.SPOT), aeron, journal);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateway.adjustBalance(
                new BalanceAdjustmentRequest(7, "USDT", 100, "ref-7", "audit"), "11", "admin"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.mockito.Mockito.verifyNoInteractions(aeron);
    }

    @Test
    void zeroAdjustmentRemainsAnAccountCreationCommandAndProductIdentityIsStable() {
        var aeron = mock(AccountAeronGateway.class);
        var journal = mock(BalanceAdjustmentRepository.class);
        when(aeron.userState(7)).thenReturn(new CoreUserStateView(ProductLine.LINEAR_PERPETUAL, 7, 1,
                CorePositionMode.ONE_WAY, List.of(), List.of(), List.of()));
        var gateway = new AccountCommandGateway(properties(ProductLine.LINEAR_PERPETUAL), aeron, journal);
        gateway.adjustProductBalance(new com.surprising.account.api.model.ProductBalanceAdjustmentRequest(
                7, AccountType.USDT_PERPETUAL, "USDT", 0, "create-7", "create account"), null, null);
        var id = java.util.UUID.nameUUIDFromBytes("LINEAR_PERPETUAL:7:product-balance-adjust:create-7"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        verify(journal).prepare(ProductLine.LINEAR_PERPETUAL, id, "PRODUCT", null, null,
                7, "USDT", 0, "create-7", "create account");
        var payload = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(aeron).command(eq(CoreMessageType.ADJUST_BALANCE), eq(id), eq(7L), payload.capture());
        assertThat(com.surprising.aeron.protocol.TradingCommandCodec.decodeBalanceAdjustment(payload.getValue()).deltaUnits()).isZero();
    }

    private static AccountProperties properties(ProductLine productLine) {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(productLine);
        return properties;
    }
}
