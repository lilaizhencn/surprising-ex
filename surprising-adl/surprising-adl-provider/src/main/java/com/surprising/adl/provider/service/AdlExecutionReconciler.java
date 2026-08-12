package com.surprising.adl.provider.service;

import com.surprising.adl.provider.model.AdlSagaState;
import com.surprising.adl.provider.repository.AdlAccountCommandRepository;
import com.surprising.adl.provider.repository.AdlEventRepository;
import com.surprising.adl.provider.repository.AdlExecutionSagaRepository;
import com.surprising.adl.provider.repository.AdlPendingExecutionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final AdlAccountCommandRepository accountCommandRepository;

    public AdlExecutionReconciler(AdlPendingExecutionRepository pendingExecutionRepository,
                                  AdlExecutionSagaRepository sagaRepository,
                                  AdlEventRepository eventRepository,
                                  AdlExecutionPersistenceService persistenceService,
                                  ObjectMapper objectMapper) {
        this(pendingExecutionRepository, sagaRepository, eventRepository, persistenceService, objectMapper, null);
    }

    @Autowired
    public AdlExecutionReconciler(AdlPendingExecutionRepository pendingExecutionRepository,
                                  AdlExecutionSagaRepository sagaRepository,
                                  AdlEventRepository eventRepository,
                                  AdlExecutionPersistenceService persistenceService,
                                  ObjectMapper objectMapper,
                                  AdlAccountCommandRepository accountCommandRepository) {
        this.pendingExecutionRepository = pendingExecutionRepository;
        this.sagaRepository = sagaRepository;
        this.eventRepository = eventRepository;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
        this.accountCommandRepository = accountCommandRepository;
    }

    /**
     * 从数据库读取权威账户命令终态。Kafka 结果事件可改善可观测性，但 ADL 完成与补偿不依赖其顺序。
     */
    @Transactional
    public void reconcile() {
        Instant now = Instant.now();
        List<AdlSagaState> pending = pendingExecutionRepository.lock(500);
        Map<String, AdlAccountCommandRepository.CommandState> commands = accountCommandRepository == null
                ? Map.of()
                : accountCommandRepository.findStates(commandIds(pending));
        for (AdlSagaState row : pending) {
            AdlSagaState saga = mergeCommandStates(row, commands);
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

    private Collection<String> commandIds(List<AdlSagaState> pending) {
        List<String> ids = new ArrayList<>();
        for (AdlSagaState saga : pending) {
            ids.add(saga.reserveCommandId());
            ids.add(saga.targetCommandId());
            ids.add(saga.finalizeCommandId());
            ids.add(saga.releaseCommandId());
        }
        return ids;
    }

    private AdlSagaState mergeCommandStates(
            AdlSagaState saga, Map<String, AdlAccountCommandRepository.CommandState> commands) {
        var reserve = command(commands, saga.reserveCommandId());
        var target = command(commands, saga.targetCommandId());
        var finalize = command(commands, saga.finalizeCommandId());
        var release = command(commands, saga.releaseCommandId());
        String errorCode = firstNonBlank(
                target == null ? null : target.errorCode(),
                reserve == null ? null : reserve.errorCode(),
                finalize == null ? null : finalize.errorCode(),
                release == null ? null : release.errorCode(),
                saga.terminalErrorCode());
        String errorMessage = firstNonBlank(
                target == null ? null : target.errorMessage(),
                reserve == null ? null : reserve.errorMessage(),
                finalize == null ? null : finalize.errorMessage(),
                release == null ? null : release.errorMessage(),
                saga.terminalErrorMessage());
        return new AdlSagaState(
                saga.executionId(), saga.productLine(), saga.accountType(), saga.deficitUserId(),
                saga.targetUserId(), saga.asset(), saga.symbol(), saga.targetSide(), saga.targetPositionSide(),
                saga.closedQuantitySteps(), saga.entryPriceTicks(), saga.markPriceTicks(),
                saga.requestedDeficitUnits(), saga.realizedProfitUnits(), saga.coveredUnits(),
                saga.priorityScorePpm(), saga.reserveCommandId(), saga.targetCommandId(),
                saga.finalizeCommandId(), saga.releaseCommandId(), saga.sagaStatus(),
                status(reserve, saga.reserveStatus()), status(target, saga.targetStatus()),
                status(finalize, saga.finalizeStatus()), status(release, saga.releaseStatus()),
                finalize == null ? saga.finalizeResult() : finalize.resultPayload(), errorCode, errorMessage);
    }

    private String status(AdlAccountCommandRepository.CommandState command, String fallback) {
        return command == null ? fallback : command.status().name();
    }

    private AdlAccountCommandRepository.CommandState command(
            Map<String, AdlAccountCommandRepository.CommandState> commands, String commandId) {
        return commandId == null || commandId.isBlank() ? null : commands.get(commandId);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
