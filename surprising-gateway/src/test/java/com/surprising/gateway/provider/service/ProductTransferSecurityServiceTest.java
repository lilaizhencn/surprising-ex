package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.service.SensitiveActionVerificationService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.asset.repository.AssetRepository;
import com.surprising.asset.model.AssetConfiguration.Asset;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class ProductTransferSecurityServiceTest {

    private final WithdrawalValuationClient valuationClient = mock(WithdrawalValuationClient.class);
    private final AssetRepository assets = mock(AssetRepository.class);
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
        scale(100_000_000L);
        return new ProductTransferSecurityService(new GatewayProperties(), valuationClient,
                verificationService, assets, new ObjectMapper());
    }

    @Test
    void sixDecimalAssetAtLargeTransferThresholdCannotBypassVerification() {
        var service = service();
        scale(1_000_000L);
        when(valuationClient.toUsdt("USDT", new BigDecimal("10000"))).thenReturn(new BigDecimal("10000"));
        assertThatThrownBy(() -> service.requireIfNeeded(42L, body(10_000_000_000L), null, null, Instant.now()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("large transfer security verification is required");
        verify(verificationService).verify(eq(42L), eq("LARGE_TRANSFER"), eq(null), eq(null), any());
    }

    @Test
    void eighteenDecimalAssetDoesNotUseAnEightDecimalValuation() {
        var service = service();
        scale(1_000_000_000_000_000_000L);
        when(valuationClient.toUsdt("USDT", new BigDecimal("0.0000000001"))).thenReturn(new BigDecimal("0.0000000001"));
        service.requireIfNeeded(42L, body(100_000_000L), null, null, Instant.now());
        verify(verificationService, never()).verify(anyLong(), any(), any(), any(), any());
    }

    @Test
    void unknownAssetIsRejectedBeforeValuationOrVerification() {
        var service = service();
        when(assets.byAccountingCode("USDT")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requireIfNeeded(42L, body(100L), null, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("asset is not supported");
        org.mockito.Mockito.verifyNoInteractions(valuationClient, verificationService);
    }

    private void scale(long units) {
        when(assets.byAccountingCode("USDT")).thenReturn(Optional.of(
                new Asset(1, "USDT", "Tether", "", units, true, true, 1L, Instant.EPOCH)));
    }

    private byte[] body(long amountUnits) {
        return ("{\"asset\":\"USDT\",\"amountUnits\":" + amountUnits + "}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
