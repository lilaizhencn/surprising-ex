package com.surprising.funding.provider.service;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentResult;
import com.surprising.funding.provider.repository.FundingPaymentCompletionRepository;
import com.surprising.funding.provider.repository.FundingPendingCommandRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FundingAccountCommandResultService {

    private final FundingPaymentCompletionRepository completionRepository;
    private final FundingPendingCommandRepository pendingCommandRepository;
    private final FundingProperties properties;

    public FundingAccountCommandResultService(FundingPaymentCompletionRepository completionRepository,
                                              FundingPendingCommandRepository pendingCommandRepository,
                                              FundingProperties properties) {
        this.completionRepository = completionRepository;
        this.pendingCommandRepository = pendingCommandRepository;
        this.properties = properties;
    }

    @Transactional
    public void apply(AccountCommandResultEvent event) {
        applyBatch(List.of(event));
    }

    @Transactional
    public void applyBatch(List<AccountCommandResultEvent> events) {
        List<FundingPaymentResult> results = events.stream()
                .filter(event -> event.commandType() == AccountUserCommandType.FUNDING_SETTLE
                        && "FUNDING".equals(event.source()))
                .map(this::toPaymentResult)
                .toList();
        completionRepository.completeBatch(results);
    }

    /**
     * 数据库中的账户命令终态是权威结果。此任务修复遗漏、重复或乱序的结果事件，
     * 从而避免把跨主题消息顺序作为正确性的前提。
     */
    @Transactional
    public void reconcileTerminalCommands() {
        List<FundingPaymentResult> results = pendingCommandRepository
                .findTerminal(
                        Math.max(1, properties.getSettlement().getReconcileBatchSize()));
        completionRepository.completeBatch(results);
    }

    private FundingPaymentResult toPaymentResult(AccountCommandResultEvent event) {
        if (event.productLine() != properties.getKafka().getProductLine()) {
            throw new IllegalStateException("funding result product line mismatch");
        }
        return new FundingPaymentResult(
                event.commandId(),
                event.userId(),
                event.status().name(),
                event.errorCode(),
                event.errorMessage(),
                event.completedAt());
    }
}
