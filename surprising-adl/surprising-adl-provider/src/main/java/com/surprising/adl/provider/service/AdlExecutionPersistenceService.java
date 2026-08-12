package com.surprising.adl.provider.service;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.AdlTargetSettlementAccountCommand;
import com.surprising.account.api.model.DeficitReservationAccountCommand;
import com.surprising.adl.provider.model.AdlExecutionPlan;
import com.surprising.adl.provider.model.AdlSagaState;
import com.surprising.adl.provider.repository.AdlAccountOutboxRepository;
import com.surprising.adl.provider.repository.AdlExecutionSagaRepository;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class AdlExecutionPersistenceService {

    private final AdlExecutionSagaRepository sagaRepository;
    private final AdlAccountOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public AdlExecutionPersistenceService(AdlExecutionSagaRepository sagaRepository,
                                          AdlAccountOutboxRepository outboxRepository,
                                          ObjectMapper objectMapper) {
        this.sagaRepository = sagaRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void create(AdlExecutionPlan plan, Instant now) {
        sagaRepository.insert(plan, now);
        DeficitReservationAccountCommand deficitPayload =
                new DeficitReservationAccountCommand(plan.asset(), plan.coveredUnits());
        AccountUserCommand reserve = command(plan.reserveCommandId(), plan.productLine(), plan.deficitUserId(),
                AccountUserCommandType.ADL_DEFICIT_RESERVE, plan.executionId(), null, deficitPayload, now);
        AccountUserCommand target = command(plan.targetCommandId(), plan.productLine(), plan.targetUserId(),
                AccountUserCommandType.ADL_TARGET_SETTLE, plan.executionId(), plan.reserveCommandId(),
                new AdlTargetSettlementAccountCommand(
                        plan.executionId(), plan.deficitUserId(), plan.asset(), plan.symbol(),
                        plan.targetMarginMode(), plan.targetPositionSide(), plan.expectedSignedSteps(),
                        plan.closedQuantitySteps(), plan.entryPriceTicks(), plan.markPriceTicks(),
                        plan.realizedProfitUnits(), plan.coveredUnits()), now);
        AccountUserCommand finalize = command(
                plan.finalizeCommandId(), plan.productLine(), plan.deficitUserId(),
                AccountUserCommandType.ADL_DEFICIT_FINALIZE, plan.executionId(),
                plan.targetCommandId(), deficitPayload, now);
        outboxRepository.enqueue(plan.executionId(), reserve, now);
        outboxRepository.enqueue(plan.executionId(), target, now);
        outboxRepository.enqueue(plan.executionId(), finalize, now);
    }

    @Transactional
    public void beginRelease(AdlSagaState saga, Instant now) {
        String releaseCommandId = "ADL_RELEASE:" + saga.productLine() + ":" + saga.executionId();
        AccountUserCommand release = command(
                releaseCommandId,
                ProductLine.valueOf(saga.productLine()),
                saga.deficitUserId(),
                AccountUserCommandType.ADL_DEFICIT_RELEASE,
                saga.executionId(),
                null,
                new DeficitReservationAccountCommand(saga.asset(), saga.coveredUnits()),
                now);
        sagaRepository.beginRelease(saga, releaseCommandId, now);
        outboxRepository.enqueue(saga.executionId(), release, now);
    }

    private AccountUserCommand command(String commandId,
                                       ProductLine productLine,
                                       long userId,
                                       AccountUserCommandType type,
                                       long executionId,
                                       String dependency,
                                       Object payload,
                                       Instant now) {
        return new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION,
                commandId,
                productLine,
                userId,
                type,
                "ADL",
                Long.toString(executionId),
                dependency,
                objectMapper.writeValueAsString(payload),
                now,
                null);
    }
}
