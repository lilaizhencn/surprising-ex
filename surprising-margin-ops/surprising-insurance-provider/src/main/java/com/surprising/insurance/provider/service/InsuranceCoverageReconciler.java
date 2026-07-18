package com.surprising.insurance.provider.service;

import com.surprising.insurance.provider.repository.InsuranceRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class InsuranceCoverageReconciler {

    private final InsuranceRepository insuranceRepository;
    private final ObjectMapper objectMapper;

    public InsuranceCoverageReconciler(InsuranceRepository insuranceRepository,
                                       ObjectMapper objectMapper) {
        this.insuranceRepository = insuranceRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Account command rows are authoritative; no result-topic ordering is required for fund
     * deduction, reservation release, or coverage completion.
     */
    @Scheduled(fixedDelayString = "${surprising.insurance.coverage.reconcile-delay-ms:200}")
    @Transactional
    public void reconcile() {
        Instant now = Instant.now();
        for (InsuranceRepository.PendingCoverage coverage : insuranceRepository.lockPendingCoverages(500)) {
            if (coverage.reserveRejected()) {
                insuranceRepository.failCoverage(coverage, now);
            } else if (coverage.finalizeApplied()) {
                insuranceRepository.completeCoverage(
                        coverage, remainingDeficit(coverage.finalizeResult()), now);
            } else if (coverage.reserveApplied()
                    && "PENDING_RESERVE".equals(coverage.coverageStatus())) {
                insuranceRepository.markPendingFinalize(coverage.coverageId(), now);
            }
        }
    }

    private long remainingDeficit(String resultPayload) {
        if (resultPayload == null || resultPayload.isBlank()) {
            throw new IllegalStateException("insurance finalize result payload is missing");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.readValue(resultPayload, Map.class);
        Object value = result.get("remainingDeficitUnits");
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("insurance finalize result remaining deficit is missing");
        }
        return number.longValue();
    }
}
