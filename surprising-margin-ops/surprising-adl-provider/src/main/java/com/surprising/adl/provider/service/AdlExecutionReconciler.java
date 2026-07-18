package com.surprising.adl.provider.service;

import com.surprising.adl.provider.repository.AdlExecutionRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class AdlExecutionReconciler {

    private final AdlExecutionRepository executionRepository;
    private final ObjectMapper objectMapper;

    public AdlExecutionReconciler(AdlExecutionRepository executionRepository,
                                 ObjectMapper objectMapper) {
        this.executionRepository = executionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Reads authoritative command states from the database. Kafka result events may improve
     * observability, but ADL completion and compensation never depend on their ordering.
     */
    @Scheduled(fixedDelayString = "${surprising.adl.scanner.reconcile-delay-ms:200}")
    @Transactional
    public void reconcile() {
        Instant now = Instant.now();
        for (AdlExecutionRepository.SagaState saga : executionRepository.lockPending(500)) {
            if (saga.reserveRejected()) {
                executionRepository.failWithoutReservation(saga, now);
            } else if (saga.targetRejectedAfterReservation() && saga.releaseCommandId() == null) {
                executionRepository.beginRelease(saga, now);
            } else if (saga.releaseApplied()) {
                executionRepository.completeRelease(saga, now);
            } else if (saga.finalizeApplied()) {
                executionRepository.complete(saga, remainingDeficit(saga.finalizeResult()), now);
            }
        }
    }

    private long remainingDeficit(String resultPayload) {
        if (resultPayload == null || resultPayload.isBlank()) {
            throw new IllegalStateException("ADL finalize result payload is missing");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.readValue(resultPayload, Map.class);
        Object value = result.get("remainingDeficitUnits");
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("ADL finalize result remaining deficit is missing");
        }
        return number.longValue();
    }
}
