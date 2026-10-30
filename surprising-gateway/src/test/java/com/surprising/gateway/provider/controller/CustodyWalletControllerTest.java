package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycProfile;
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
                authService, walletClient, verificationService, complianceService, withdrawalService);

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
                mock(ComplianceService.class), mock(CustodyWithdrawalService.class));

        assertThat(controller.chains("Bearer token"))
                .containsExactly(Map.of("chain", "ETH", "enabled", true));
        verify(walletClient).chains();
    }
}
