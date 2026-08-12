package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.SensitiveActionVerificationService;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class ProductTransferSecurityServiceTest {

    private final WithdrawalValuationClient valuationClient = mock(WithdrawalValuationClient.class);
    private final SensitiveActionVerificationService verificationService =
            mock(SensitiveActionVerificationService.class);

    @Test
    void smallTransferDoesNotRequireChallenge() {
        ProductTransferSecurityService service = service();
        when(valuationClient.toUsdt("USDT", new BigDecimal("0.025"))).thenReturn(new BigDecimal("0.025"));

        service.requireIfNeeded(42L, body(2_500_000L), null, null, Instant.now());

        verify(verificationService, never()).verify(anyLong(), any(), any(), any(), any());
    }

    @Test
    void largeTransferFailsClosedUntilSensitiveChallengeIsVerified() {
        ProductTransferSecurityService service = service();
        when(valuationClient.toUsdt("USDT", new BigDecimal("20000"))).thenReturn(new BigDecimal("20000"));
        when(verificationService.verify(eq(42L), eq("LARGE_TRANSFER"), eq("123456"), eq("654321"), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.requireIfNeeded(42L, body(2_000_000_000_000L),
                "123456", "654321", Instant.now()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("large transfer security verification is required");
    }

    @Test
    void verifiedLargeTransferCanProceed() {
        ProductTransferSecurityService service = service();
        when(valuationClient.toUsdt("USDT", new BigDecimal("20000"))).thenReturn(new BigDecimal("20000"));
        when(verificationService.verify(eq(42L), eq("LARGE_TRANSFER"), eq("123456"), eq("654321"), any()))
                .thenReturn(true);

        service.requireIfNeeded(42L, body(2_000_000_000_000L), "123456", "654321", Instant.now());

        verify(verificationService).verify(eq(42L), eq("LARGE_TRANSFER"), eq("123456"), eq("654321"), any());
    }

    private ProductTransferSecurityService service() {
        return new ProductTransferSecurityService(new GatewayProperties(), valuationClient,
                verificationService, new ObjectMapper());
    }

    private byte[] body(long amountUnits) {
        return ("{\"asset\":\"USDT\",\"amountUnits\":" + amountUnits + "}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
