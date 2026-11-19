package com.surprising.gateway.provider.subaccount.service;

import com.surprising.account.api.model.ProductBalanceResponse;
import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.gateway.provider.auth.model.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.service.AuthPersistenceService;
import com.surprising.gateway.provider.subaccount.model.SubAccountModels.*;
import com.surprising.gateway.provider.subaccount.repository.SubAccountRepository;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubAccountServiceTest {

    private final SubAccountRepository repository = mock(SubAccountRepository.class);
    private final AuthPersistenceService authPersistenceService = mock(AuthPersistenceService.class);
    private final AccountCommandGateway accountGateway = mock(AccountCommandGateway.class);

    private final SubAccountService service = new SubAccountService(repository, authPersistenceService, accountGateway);

    @Test
    @DisplayName("主账户成功创建子账户并持久化关系")
    void shouldCreateSubAccountSuccessfully() {
        AuthenticatedUser mockSubUser = new AuthenticatedUser(2001L, "sub_1001_trader1", null, "NORMAL", List.of("USER"), Instant.now());
        when(authPersistenceService.createUser(any(), any(), any(), any())).thenReturn(mockSubUser);

        SubAccountRecord mockRecord = new SubAccountRecord(
                1L, 1001L, 2001L, "trader1", "TRADE_SPOT,TRADE_CONTRACT", "ACTIVE", Instant.now()
        );
        when(repository.createRelation(eq(1001L), eq(2001L), eq("trader1"), anyString())).thenReturn(mockRecord);

        CreateSubAccountRequest request = new CreateSubAccountRequest("trader1", "TRADE_SPOT");
        SubAccountRecord result = service.createSubAccount(1001L, request);

        assertNotNull(result);
        assertEquals(1001L, result.parentUserId());
        assertEquals(2001L, result.subUserId());
        assertEquals("trader1", result.subAccountName());
    }

    @Test
    @DisplayName("主子账户之间成功完成秒级零费率资金划转")
    void shouldTransferFundsBetweenParentAndSub() {
        SubAccountRecord relation = new SubAccountRecord(
                1L, 1001L, 2001L, "trader1", "TRADE_SPOT", "ACTIVE", Instant.now()
        );
        when(repository.findRelation(1001L, 2001L)).thenReturn(Optional.of(relation));

        ProductBalanceResponse mockBalance = new ProductBalanceResponse(
                1001L, com.surprising.account.api.model.AccountType.SPOT, "USDT", 1000_000000L, 0L, 1000_000000L, Instant.now()
        );
        when(accountGateway.transferOut(any())).thenReturn(mockBalance);
        when(accountGateway.transferIn(any())).thenReturn(mockBalance);

        SubAccountTransferRequest request = new SubAccountTransferRequest(
                2001L, true, "USDT", 500_000000L, ProductLine.SPOT
        );

        SubAccountTransferResponse response = service.transfer(1001L, request);

        assertNotNull(response);
        assertEquals("SUCCESS", response.status());
        assertEquals(1001L, response.fromUserId());
        assertEquals(2001L, response.toUserId());
        assertEquals(500_000000L, response.amountUnits());

        ArgumentCaptor<ProductTransferOperationRequest> captor = ArgumentCaptor.forClass(ProductTransferOperationRequest.class);
        verify(accountGateway).transferOut(captor.capture());
        verify(accountGateway).transferIn(captor.capture());
        verify(accountGateway).completeTransfer(captor.capture());

        ProductTransferOperationRequest op = captor.getValue();
        assertEquals(1001L, op.userId());
        assertEquals(2001L, op.recipientUserId());
    }

    @Test
    @DisplayName("非本人子账户划转时抛出异常防御越权")
    void shouldRejectTransferForUnrelatedSubAccount() {
        when(repository.findRelation(1001L, 9999L)).thenReturn(Optional.empty());

        SubAccountTransferRequest request = new SubAccountTransferRequest(
                9999L, true, "USDT", 100L, ProductLine.SPOT
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.transfer(1001L, request)
        );
        assertTrue(ex.getMessage().contains("SubAccount not found"));
    }

    @Test
    @DisplayName("冻结子账户后拒绝主子划转")
    void shouldRejectTransferWhenSubAccountFrozen() {
        SubAccountRecord relation = new SubAccountRecord(
                1L, 1001L, 2001L, "trader1", "TRADE_SPOT", "FROZEN", Instant.now()
        );
        when(repository.findRelation(1001L, 2001L)).thenReturn(Optional.of(relation));
        SubAccountTransferRequest request = new SubAccountTransferRequest(
                2001L, true, "USDT", 100L, ProductLine.SPOT
        );
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                service.transfer(1001L, request)
        );
        assertTrue(ex.getMessage().contains("not active"));
        verify(accountGateway, never()).transferOut(any());
    }

    @Test
    @DisplayName("冻结关系后发布子账户用户状态")
    void shouldPublishUserStateAfterFreeze() {
        com.surprising.gateway.provider.auth.state.AuthStateSync sync =
                org.mockito.Mockito.mock(com.surprising.gateway.provider.auth.state.AuthStateSync.class);
        service.setAuthStateSync(sync);
        SubAccountRecord frozen = new SubAccountRecord(
                1L, 1001L, 2001L, "trader1", "TRADE_SPOT", "FROZEN", Instant.now()
        );
        when(repository.findBySubUserId(2001L)).thenReturn(Optional.of(frozen));
        SubAccountRecord result = service.adminUpdateStatus(2001L, "FROZEN");
        assertEquals("FROZEN", result.status());
        verify(repository).updateStatusBySubUserId(2001L, "FROZEN");
        verify(sync).reloadUser(2001L);
    }

    @Test
    @DisplayName("主账号可以查看子账号资产并冻结或解冻")
    void canViewAndFreezeOwnedSubAccount() {
        com.surprising.account.provider.service.AccountService accounts = mock(com.surprising.account.provider.service.AccountService.class);
        SubAccountService managing = new SubAccountService(repository, authPersistenceService, accountGateway, accounts);
        SubAccountRecord relation = new SubAccountRecord(
                1L, 1001L, 2001L, "trader1", "TRADE_SPOT", "ACTIVE", Instant.now());
        SubAccountRecord frozen = new SubAccountRecord(
                1L, 1001L, 2001L, "trader1", "TRADE_SPOT", "FROZEN", Instant.now());
        when(repository.findRelation(1001L, 2001L)).thenReturn(Optional.of(relation), Optional.of(frozen), Optional.of(frozen));
        when(repository.updateStatus(1001L, 2001L, "FROZEN")).thenReturn(true);
        ProductBalanceResponse balance = new ProductBalanceResponse(
                2001L, com.surprising.account.api.model.AccountType.SPOT, "USDT", 100_000000L, 0L, 100_000000L, Instant.now());
        when(accounts.productBalances(2001L, com.surprising.account.api.model.AccountType.SPOT))
                .thenReturn(new com.surprising.account.api.model.ProductBalanceQueryResponse(1, List.of(balance)));

        var assets = managing.assets(1001L, 2001L, com.surprising.account.api.model.AccountType.SPOT);
        assertEquals(1, assets.count());
        assertEquals(2001L, assets.balances().get(0).userId());

        SubAccountRecord updated = managing.updateStatus(1001L, 2001L, "FROZEN");
        assertEquals("FROZEN", updated.status());
        verify(repository).updateStatus(1001L, 2001L, "FROZEN");
    }

    @Test
    @DisplayName("不能查看或冻结不属于自己的子账号")
    void cannotManageAnotherParentsSubAccount() {
        com.surprising.account.provider.service.AccountService accounts = mock(com.surprising.account.provider.service.AccountService.class);
        SubAccountService managing = new SubAccountService(repository, authPersistenceService, accountGateway, accounts);
        when(repository.findRelation(1001L, 2001L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> managing.assets(1001L, 2001L, com.surprising.account.api.model.AccountType.SPOT));
        assertThrows(IllegalArgumentException.class,
                () -> managing.updateStatus(1001L, 2001L, "FROZEN"));
        verify(repository, never()).updateStatus(anyLong(), anyLong(), anyString());
        verify(accounts, never()).productBalances(anyLong(), any());
    }
}
