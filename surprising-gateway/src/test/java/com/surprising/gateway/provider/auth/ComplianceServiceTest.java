package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.ComplianceModels.AmlCase;
import com.surprising.gateway.provider.auth.ComplianceModels.RiskTag;
import com.surprising.gateway.provider.auth.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.service.KycDocumentService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComplianceServiceTest {

    private final ComplianceUserProjectionRepository userProjectionRepository = mock();
    private final ComplianceKycRepository kycRepository = mock();
    private final ComplianceRiskTagRepository riskTagRepository = mock();
    private final ComplianceAmlCaseRepository amlCaseRepository = mock();
    private final AuthService authService = mock();
    private final AdminApprovalService approvalService = mock();
    private final KycDocumentService kycDocumentService = mock();
    private final AdminAuditRepository adminAuditRepository = mock();
    private final AuthPersistenceService authPersistence = mock();
    private final EmailMessageSender emailMessageSender = mock();
    private final KycProviderRegistry kycProviders = mock();

    private final ComplianceService service = new ComplianceService(
            userProjectionRepository, kycRepository, riskTagRepository, amlCaseRepository,
            authService, approvalService, kycDocumentService, adminAuditRepository,
            authPersistence, emailMessageSender, kycProviders, mock(CountryRepository.class));

    @Test
    void blocksWithdrawalForHighOrCriticalActiveRiskTag() {
        when(riskTagRepository.find(42L, "ACTIVE", 100)).thenReturn(List.of(
                new RiskTag(1L, 42L, "SANCTIONS_REVIEW", "CRITICAL", "ACTIVE", "RULE",
                        "screening match", 7L, null, Instant.now(), null, Instant.now())));

        assertThatThrownBy(() -> service.requireWithdrawalEligibility(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("risk controls");
    }

    @Test
    void blocksWithdrawalForOpenAmlCaseWhenRiskTagsAreClear() {
        when(riskTagRepository.find(42L, "ACTIVE", 100)).thenReturn(List.of());
        when(amlCaseRepository.find(42L, null, 200)).thenReturn(List.of(
                new AmlCase(9L, 42L, "REVIEWING", 80, "RULE", "source of funds review",
                        7L, 7L, null, null, null, Instant.now(), Instant.now())));

        assertThatThrownBy(() -> service.requireWithdrawalEligibility(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("compliance case");
    }

    @Test
    void allowsWithdrawalWhenOnlyResolvedControlsRemain() {
        when(riskTagRepository.find(42L, "ACTIVE", 100)).thenReturn(List.of(
                new RiskTag(1L, 42L, "VELOCITY_REVIEW", "MEDIUM", "ACTIVE", "RULE",
                        "review", 7L, null, Instant.now(), null, Instant.now())));
        when(amlCaseRepository.find(42L, null, 200)).thenReturn(List.of(
                new AmlCase(9L, 42L, "CLEARED", 10, "RULE", "cleared",
                        7L, 7L, 7L, Instant.now(), Instant.now(), Instant.now(), Instant.now())));

        service.requireWithdrawalEligibility(42L);
    }

    @Test
    void simulatedApprovalUsesConfiguredProviderAndMovesPendingProfileToVerified() {
        Instant now = Instant.now();
        KycProfile pending = new KycProfile(42, "STANDARD", "PENDING", "SG", "PASSPORT", "SUMSUB",
                "SIM-SUMSUB-42", null, null, null, null, now, now, "INDIVIDUAL", "[]", "PENDING");
        KycProfile verified = new KycProfile(42, "STANDARD", "VERIFIED", "SG", "PASSPORT", "SUMSUB",
                "SIM-SUMSUB-42", null, now, null, null, now, now, "INDIVIDUAL", "[]", "VERIFIED");
        when(authService.authenticateBearer("Bearer user-token"))
                .thenReturn(new AuthModels.JwtPrincipal(42, "user", "NORMAL", List.of("USER"), now.plusSeconds(60)));
        when(kycProviders.simulationEnabled()).thenReturn(true);
        when(kycProviders.selectedProvider()).thenReturn("SUMSUB");
        when(kycRepository.find(42)).thenReturn(pending);
        when(kycRepository.recordProviderEvent(org.mockito.ArgumentMatchers.eq("SUMSUB"),
                org.mockito.ArgumentMatchers.startsWith("SIMULATION:"), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(kycRepository.applyProviderResult(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq("SUMSUB"), org.mockito.ArgumentMatchers.eq("VERIFIED"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(verified);

        assertThat(service.completeKycSimulation("Bearer user-token", "APPROVED").status()).isEqualTo("VERIFIED");
        verify(kycRepository).applyProviderResult(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq("SUMSUB"), org.mockito.ArgumentMatchers.eq("VERIFIED"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void simulatedCompletionCannotRunWhenProductionModeHasDisabledSimulation() {
        when(authService.authenticateBearer("Bearer user-token"))
                .thenReturn(new AuthModels.JwtPrincipal(42, "user", "NORMAL", List.of("USER"), Instant.now().plusSeconds(60)));
        when(kycProviders.simulationEnabled()).thenReturn(false);
        assertThatThrownBy(() -> service.completeKycSimulation("Bearer user-token", "APPROVED"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("disabled");
        verifyNoInteractions(kycRepository);
    }

    @Test
    void simulatedManualReviewResultCanOnlyBeSubmittedOnce() {
        Instant now = Instant.now();
        KycProfile pending = new KycProfile(42, "STANDARD", "PENDING", "SG", "PASSPORT", "SUMSUB",
                "SIM-SUMSUB-42", null, null, null, null, now, now, "INDIVIDUAL", "[]", "PENDING");
        when(authService.authenticateBearer("Bearer user-token"))
                .thenReturn(new AuthModels.JwtPrincipal(42, "user", "NORMAL", List.of("USER"), now.plusSeconds(60)));
        when(kycProviders.simulationEnabled()).thenReturn(true);
        when(kycProviders.selectedProvider()).thenReturn("SUMSUB");
        when(kycRepository.find(42)).thenReturn(pending);
        when(kycRepository.recordProviderEvent(org.mockito.ArgumentMatchers.eq("SUMSUB"),
                org.mockito.ArgumentMatchers.startsWith("SIMULATION:"), org.mockito.ArgumentMatchers.any())).thenReturn(false);

        assertThatThrownBy(() -> service.completeKycSimulation("Bearer user-token", "MANUAL_REVIEW"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already submitted");
        verify(kycRepository, org.mockito.Mockito.never()).applyProviderResult(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.any());
    }
}
