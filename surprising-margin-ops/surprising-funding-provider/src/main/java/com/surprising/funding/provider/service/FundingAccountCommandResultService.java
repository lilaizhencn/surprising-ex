package com.surprising.funding.provider.service;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.repository.FundingRepository;
import java.util.List;
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
        applyBatch(List.of(event));
    }

    @Transactional
    public void applyBatch(List<AccountCommandResultEvent> events) {
        List<FundingRepository.PaymentResult> results = events.stream()
                .filter(event -> event.commandType() == AccountUserCommandType.FUNDING_SETTLE
                        && "FUNDING".equals(event.source()))
                .map(this::toPaymentResult)
                .toList();
        fundingRepository.completePayments(results);
    }

    /**
     * The database terminal command is authoritative. This repairs a missing, duplicated, or
     * reordered result event and therefore removes cross-topic ordering from correctness.
     */
    @Scheduled(fixedDelayString = "${surprising.funding.settlement.reconcile-delay-ms:1000}")
    @Transactional
    public void reconcileTerminalCommands() {
        List<FundingRepository.PaymentResult> results = fundingRepository
                .terminalAccountCommandsForPendingPayments(
                        Math.max(1, properties.getSettlement().getReconcileBatchSize()));
        fundingRepository.completePayments(results);
    }

    private FundingRepository.PaymentResult toPaymentResult(AccountCommandResultEvent event) {
        if (event.productLine() != properties.getKafka().getProductLine()) {
            throw new IllegalStateException("funding result product line mismatch");
        }
        return new FundingRepository.PaymentResult(
                event.commandId(),
                event.userId(),
                event.status().name(),
                event.errorCode(),
                event.errorMessage(),
                event.completedAt());
    }
}
