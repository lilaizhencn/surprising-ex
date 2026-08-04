package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.instrument.api.model.DeliverySettlementEvent;
import com.surprising.instrument.api.model.OptionExerciseEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class ExpiringContractSettlementFanoutService {

    private final AccountService accountService;
    private final AccountCommandSubmissionService commandSubmissionService;
    private final ObjectMapper objectMapper;

    public ExpiringContractSettlementFanoutService(AccountService accountService,
                                                   AccountCommandSubmissionService commandSubmissionService,
                                                   ObjectMapper objectMapper) {
        this.accountService = accountService;
        this.commandSubmissionService = commandSubmissionService;
        this.objectMapper = objectMapper;
    }

    public int fanout(DeliverySettlementEvent event) {
        return enqueue(accountService.planDeliverySettlement(event), AccountUserCommandType.DELIVERY_SETTLE);
    }

    public int fanout(OptionExerciseEvent event) {
        return enqueue(accountService.planOptionExercise(event), AccountUserCommandType.OPTION_EXERCISE);
    }

    private int enqueue(List<AccountService.UserExpiringSettlementPlan> plans, AccountUserCommandType type) {
        Instant now = Instant.now();
        for (AccountService.UserExpiringSettlementPlan plan : plans) {
            var payload = plan.command();
            String commandId = type.name() + ":" + plan.productLine().name() + ":" + payload.symbol()
                    + ":" + payload.instrumentVersion() + ":" + plan.userId() + ":"
                    + payload.marginMode().name() + ":" + payload.positionSide().name() + ":"
                    + payload.settlementPriceTicks() + ":" + payload.cashSettlementUnitsPerContract();
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
            // Kafka 只负责把命令交给账户用户分区 WAL；数据库 outbox 不再参与生命周期结算入口。
            commandSubmissionService.submit(command);
        }
        return plans.size();
    }
}
