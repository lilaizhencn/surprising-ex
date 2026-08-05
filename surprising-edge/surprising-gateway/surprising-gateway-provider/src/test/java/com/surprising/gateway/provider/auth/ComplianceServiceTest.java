package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.ComplianceModels.AmlCase;
import com.surprising.gateway.provider.auth.ComplianceModels.RiskTag;
import com.surprising.gateway.provider.service.KycDocumentService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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

    private final ComplianceService service = new ComplianceService(
            userProjectionRepository, kycRepository, riskTagRepository, amlCaseRepository,
            authService, approvalService, kycDocumentService, adminAuditRepository);

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
}
