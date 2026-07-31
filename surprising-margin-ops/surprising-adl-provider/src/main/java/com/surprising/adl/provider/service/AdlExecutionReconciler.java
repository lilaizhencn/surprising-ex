package com.surprising.adl.provider.service;

import com.surprising.adl.provider.model.AdlSagaState;
import com.surprising.adl.provider.repository.AdlEventRepository;
import com.surprising.adl.provider.repository.AdlExecutionSagaRepository;
import com.surprising.adl.provider.repository.AdlPendingExecutionRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class AdlExecutionReconciler {

    private final AdlPendingExecutionRepository pendingExecutionRepository;
    private final AdlExecutionSagaRepository sagaRepository;
    private final AdlEventRepository eventRepository;
    private final AdlExecutionPersistenceService persistenceService;
    private final ObjectMapper objectMapper;

    public AdlExecutionReconciler(AdlPendingExecutionRepository pendingExecutionRepository,
                                  AdlExecutionSagaRepository sagaRepository,
                                  AdlEventRepository eventRepository,
                                  AdlExecutionPersistenceService persistenceService,
                                  ObjectMapper objectMapper) {
        this.pendingExecutionRepository = pendingExecutionRepository;
        this.sagaRepository = sagaRepository;
        this.eventRepository = eventRepository;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
    }

    /**
     * 从数据库读取权威账户命令终态。Kafka 结果事件可改善可观测性，但 ADL 完成与补偿不依赖其顺序。
     */
    @Transactional
    public void reconcile() {
        Instant now = Instant.now();
        for (AdlSagaState saga : pendingExecutionRepository.lock(500)) {
            if (saga.reserveRejected()) {
                sagaRepository.failWithoutReservation(saga, now);
            } else if (saga.targetRejectedAfterReservation() && saga.releaseCommandId() == null) {
                persistenceService.beginRelease(saga, now);
            } else if (saga.releaseApplied()) {
                sagaRepository.completeRelease(saga, now);
            } else if (saga.finalizeApplied()) {
                eventRepository.insert(saga, remainingDeficit(saga.finalizeResult()), now);
                sagaRepository.complete(saga.executionId(), now);
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
