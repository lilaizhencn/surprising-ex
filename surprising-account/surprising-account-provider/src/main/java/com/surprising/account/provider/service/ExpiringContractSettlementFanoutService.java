package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.repository.AccountOutboxRepository;
import com.surprising.instrument.api.model.DeliverySettlementEvent;
import com.surprising.instrument.api.model.OptionExerciseEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ExpiringContractSettlementFanoutService {

    private final AccountService accountService;
    private final AccountOutboxRepository outboxRepository;
    private final AccountProperties properties;
    private final ObjectMapper objectMapper;

    public ExpiringContractSettlementFanoutService(AccountService accountService,
                                                   AccountOutboxRepository outboxRepository,
                                                   AccountProperties properties,
                                                   ObjectMapper objectMapper) {
        this.accountService = accountService;
        this.outboxRepository = outboxRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public int fanout(DeliverySettlementEvent event) {
        return enqueue(accountService.planDeliverySettlement(event), AccountUserCommandType.DELIVERY_SETTLE);
    }

    @Transactional
    public int fanout(OptionExerciseEvent event) {
        return enqueue(accountService.planOptionExercise(event), AccountUserCommandType.OPTION_EXERCISE);
    }

    private int enqueue(List<AccountService.UserExpiringSettlementPlan> plans, AccountUserCommandType type) {
        Instant now = Instant.now();
        for (AccountService.UserExpiringSettlementPlan plan : plans) {
            var payload = plan.command();
            String commandId = type.name() + ":" + plan.productLine().name() + ":" + payload.symbol()
                    + ":" + payload.instrumentVersion() + ":" + plan.userId() + ":"
                    + payload.marginMode().name() + ":" + payload.positionSide().name();
            AccountUserCommand command = new AccountUserCommand(
                    AccountUserCommand.CURRENT_SCHEMA_VERSION,
                    commandId,
                    plan.productLine(),
                    plan.userId(),
                    type,
                    "INSTRUMENT_LIFECYCLE",
                    payload.symbol() + ":" + payload.instrumentVersion(),
                    null,
                    objectMapper.writeValueAsString(payload),
                    now,
                    null);
            outboxRepository.enqueueUserCommand(
                    properties.getKafka().getUserCommandsTopic(), "EXPIRING_POSITION_COMMAND", command, now);
        }
        return plans.size();
    }
}
