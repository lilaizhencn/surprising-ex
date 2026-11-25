package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.model.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.model.KycProfile;
import com.surprising.gateway.provider.auth.service.ComplianceService;
import com.surprising.gateway.provider.auth.service.SensitiveActionVerificationService;
import com.surprising.gateway.provider.client.CustodyWalletClient;
import com.surprising.gateway.provider.service.CustodyWithdrawalService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CustodyWalletControllerTest {

    @Test
    void rejectsForgedSourceAddressAndResolvesAuthenticatedUsersAddress() {
        AuthService authService = mock(AuthService.class);
        CustodyWalletClient walletClient = mock(CustodyWalletClient.class);
        SensitiveActionVerificationService verificationService = mock(SensitiveActionVerificationService.class);
        ComplianceService complianceService = mock(ComplianceService.class);
        CustodyWithdrawalService withdrawalService = mock(CustodyWithdrawalService.class);
        UUID configuredSource = UUID.randomUUID();
        UUID forgedSource = UUID.randomUUID();
        when(authService.authenticateBearer("Bearer token"))
                .thenReturn(new JwtPrincipal(42L, "user", "ACTIVE", List.of(), Instant.now().plusSeconds(600)));
        when(verificationService.verify(eq(42L), eq("WITHDRAWAL"), any(), any(), any(Instant.class)))
                .thenReturn(true);
        when(complianceService.kyc(42L)).thenReturn(new KycProfile(
                42L, "STANDARD", "VERIFIED", null, null, null, null,
                null, null, null, null, Instant.now(), Instant.now()));

        CustodyWithdrawalService.WithdrawalResponse response = new CustodyWithdrawalService.WithdrawalResponse(
                UUID.randomUUID(), "BROADCAST_UNKNOWN", "wallet-withdrawal-1", new BigDecimal("25"),
                Instant.now(), Instant.now(), "25", "0", "25",0L);
        when(withdrawalService.submit(eq(42L), eq("withdraw-1"), any())).thenReturn(response);
        when(withdrawalService.submit(eq(42L),eq("withdraw-1"),org.mockito.ArgumentMatchers.argThat(value -> forgedSource.equals(value.custodyAddressId()))))
                .thenThrow(new IllegalArgumentException("withdrawal source differs from funded source"));
        CustodyWalletController controller = new CustodyWalletController(
                authService, walletClient, verificationService, complianceService, withdrawalService,
                mock(com.surprising.gateway.provider.service.WalletFundingService.class));

        assertThatThrownBy(() -> controller.createWithdrawal("Bearer token", "withdraw-1", null, null,
                new CustodyWalletController.CreateWithdrawalRequest(
                        forgedSource, "ETH", "USDT", "0xrecipient", "25", null, 0L)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        controller.createWithdrawal("Bearer token", "withdraw-1", null, null,
                new CustodyWalletController.CreateWithdrawalRequest(
                        null, "ETH", "USDT", "0xrecipient", "25", null, 0L));

        ArgumentCaptor<CustodyWithdrawalService.WithdrawalRequest> requests =
                ArgumentCaptor.forClass(CustodyWithdrawalService.WithdrawalRequest.class);
        verify(withdrawalService,times(2)).submit(eq(42L),eq("withdraw-1"),requests.capture());
        assertThat(requests.getValue().custodyAddressId()).isNull();
        org.mockito.Mockito.verifyNoInteractions(walletClient);
    }

    @Test
    void requiresUserAuthenticationBeforeReturningEnabledWalletChains() {
        AuthService authService = mock(AuthService.class);
        CustodyWalletClient walletClient = mock(CustodyWalletClient.class);
        when(authService.authenticateBearer("Bearer token"))
                .thenReturn(new JwtPrincipal(42L, "user", "ACTIVE", List.of(), Instant.now().plusSeconds(600)));
        when(walletClient.chains()).thenReturn(List.of(Map.of("chain", "ETH", "enabled", true)));
        CustodyWalletController controller = new CustodyWalletController(
                authService, walletClient, mock(SensitiveActionVerificationService.class),
                mock(ComplianceService.class), mock(CustodyWithdrawalService.class),
                mock(com.surprising.gateway.provider.service.WalletFundingService.class));

        assertThat(controller.chains("Bearer token"))
                .containsExactly(Map.of("chain", "ETH", "enabled", true));
        verify(walletClient).chains();
    }

    @Test
    void createAddressReturnsCachedAddressWithoutCallingWalletClient() {
        AuthService authService = mock(AuthService.class);
        CustodyWalletClient walletClient = mock(CustodyWalletClient.class);
        com.surprising.gateway.provider.repository.UserDepositAddressRepository repo = mock(com.surprising.gateway.provider.repository.UserDepositAddressRepository.class);
        CustodyWalletController controller = new CustodyWalletController(
                authService, walletClient, mock(SensitiveActionVerificationService.class),
                mock(ComplianceService.class), mock(CustodyWithdrawalService.class),
                new com.surprising.gateway.provider.service.WalletFundingService(null, null, walletClient, repo));
        when(authService.authenticateBearer("Bearer token"))
                .thenReturn(new JwtPrincipal(42L, "user", "ACTIVE", List.of(), Instant.now().plusSeconds(600)));
        when(repo.find(42L, "ETH", 1L)).thenReturn(java.util.Optional.of(
                new com.surprising.gateway.provider.repository.UserDepositAddressRecord(
                        1L, 42L, "ETH", "evm", "0xlocal", "tag", 1L, Instant.now(), Instant.now())));
        var result = controller.createAddress("Bearer token", new CustodyWalletController.CreateAddressRequest("ETH", 1L));
        assertThat(result.get("address")).isEqualTo("0xlocal");
        verify(walletClient, org.mockito.Mockito.never()).createAddress(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void delegatesDepositAddressAndAssetsToFundingService() {
        AuthService authService = mock(AuthService.class);
        com.surprising.gateway.provider.service.WalletFundingService fundingService =
                mock(com.surprising.gateway.provider.service.WalletFundingService.class);
        CustodyWalletController controller = new CustodyWalletController(
                authService, mock(CustodyWalletClient.class), mock(SensitiveActionVerificationService.class),
                mock(ComplianceService.class), mock(CustodyWithdrawalService.class), fundingService);
        when(authService.authenticateBearer("Bearer token"))
                .thenReturn(new JwtPrincipal(42L, "user", "ACTIVE", List.of(), Instant.now().plusSeconds(600)));
        var expectedAddress = new com.surprising.gateway.provider.service.WalletFundingService.DepositAddress(
                "USDT", "ETH", "0xabc", "memo1");
        when(fundingService.depositAddress(42L, "USDT", "ETH")).thenReturn(expectedAddress);
        when(fundingService.catalog("deposit")).thenReturn(List.of());

        assertThat(controller.depositAddress("Bearer token",
                new CustodyWalletController.DepositAddressRequest("USDT", "ETH"))).isEqualTo(expectedAddress);
        assertThat(controller.assets("Bearer token", "deposit")).isEmpty();
        verify(fundingService).depositAddress(42L, "USDT", "ETH");
        verify(fundingService).catalog("deposit");
    }
}
