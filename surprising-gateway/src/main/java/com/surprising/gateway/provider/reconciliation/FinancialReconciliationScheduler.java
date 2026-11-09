package com.surprising.gateway.provider.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 资金高频对账调度器
 * 默认每 5 分钟执行一次高频对账任务（满足 5~10 分钟对账的业务要求），
 * 发现不一致立即熔断停机提现并发报警通知。
 */
@Component
@ConditionalOnProperty(prefix = "surprising.gateway.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FinancialReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(FinancialReconciliationScheduler.class);

    private final FinancialReconciliationEngine engine;

    public FinancialReconciliationScheduler(FinancialReconciliationEngine engine) {
        this.engine = engine;
    }

    /**
     * 默认每 5 分钟整点执行（00, 05, 10, 15, 20...）
     */
    @Scheduled(cron = "${surprising.gateway.reconciliation.cron:0 */5 * * * ?}")
    public void executeScheduledReconciliation() {
        try {
            engine.runReconciliation();
        } catch (Exception ex) {
            log.error("Scheduled financial reconciliation failed with unhandled exception: {}", ex.getMessage(), ex);
        }
    }
}
