package com.surprising.funding.provider.service;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentResult;
import com.surprising.funding.provider.repository.FundingPaymentCompletionRepository;
import com.surprising.funding.provider.repository.FundingPendingCommandRepository;
import com.surprising.funding.provider.repository.FundingLocalSettlementProjectionRepository;
import java.util.List;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FundingAccountCommandResultService {

    private final FundingPaymentCompletionRepository completionRepository;
    private final FundingPendingCommandRepository pendingCommandRepository;
    private final FundingProperties properties;
    private final FundingLocalSettlementStore localSettlementStore;
    private final FundingLocalSettlementProjectionRepository localProjectionRepository;

    @Autowired
    public FundingAccountCommandResultService(FundingPaymentCompletionRepository completionRepository,
                                              FundingPendingCommandRepository pendingCommandRepository,
                                              FundingProperties properties,
                                              FundingLocalSettlementStore localSettlementStore,
                                              FundingLocalSettlementProjectionRepository localProjectionRepository) {
        this.completionRepository = completionRepository;
        this.pendingCommandRepository = pendingCommandRepository;
        this.properties = properties;
        this.localSettlementStore = localSettlementStore;
        this.localProjectionRepository = localProjectionRepository;
    }

    FundingAccountCommandResultService(FundingPaymentCompletionRepository completionRepository,
                                       FundingPendingCommandRepository pendingCommandRepository,
                                       FundingProperties properties) {
        this(completionRepository, pendingCommandRepository, properties, null, null);
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
        if (localSettlementStore != null) {
            for (AccountCommandResultEvent event : events) {
                if (event.commandType() == AccountUserCommandType.FUNDING_SETTLE
                        && "FUNDING".equals(event.source())) {
                    localSettlementStore.completePayment(event.commandId(), event.userId(), event.status().name(),
                            event.errorCode(), event.errorMessage(), event.completedAt());
                }
            }
            localProjectionRepository.project(localSettlementStore.projectionSnapshots(), Instant.now());
            return;
        }
        completionRepository.completeBatch(results);
    }

    /**
     * 只在恢复窗口修复遗漏的资金费投影结果。
     *
     * <p>账户用户分区的本地事实流和结果事件才是资金裁决依据；这里读取数据库仅用于
     * 进程崩溃后补齐 funding_payments 的异步投影，不能反向修改账户状态，也不能作为
     * 下单或结算的同步判断。</p>
     */
    @Transactional
    public void reconcileTerminalCommands() {
        if (localSettlementStore != null) {
            localProjectionRepository.project(localSettlementStore.projectionSnapshots(), Instant.now());
            return;
        }
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
