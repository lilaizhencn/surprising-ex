package com.surprising.funding.provider.service;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.repository.FundingRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FundingAccountCommandResultService {

    private final FundingRepository fundingRepository;
    private final FundingProperties properties;

    public FundingAccountCommandResultService(FundingRepository fundingRepository,
                                              FundingProperties properties) {
        this.fundingRepository = fundingRepository;
        this.properties = properties;
    }

    @Transactional
    public void apply(AccountCommandResultEvent event) {
        if (event.commandType() != AccountUserCommandType.FUNDING_SETTLE
                || !"FUNDING".equals(event.source())) {
            return;
        }
        if (event.productLine() != properties.getKafka().getProductLine()) {
            throw new IllegalStateException("funding result product line mismatch");
        }
        fundingRepository.completePayment(
                event.commandId(),
                event.userId(),
                event.status().name(),
                event.errorCode(),
                event.errorMessage(),
                event.completedAt());
    }

    /**
     * The database terminal command is authoritative. This repairs a missing, duplicated, or
     * reordered result event and therefore removes cross-topic ordering from correctness.
     */
    @Scheduled(fixedDelayString = "${surprising.funding.settlement.reconcile-delay-ms:1000}")
    @Transactional
    public void reconcileTerminalCommands() {
        for (FundingRepository.TerminalAccountCommand command
                : fundingRepository.terminalAccountCommandsForPendingPayments(500)) {
            fundingRepository.completePayment(
                    command.commandId(),
                    command.userId(),
                    command.status(),
                    command.errorCode(),
                    command.errorMessage(),
                    command.completedAt());
        }
    }
}
