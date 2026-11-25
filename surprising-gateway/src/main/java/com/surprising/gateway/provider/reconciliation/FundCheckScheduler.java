package com.surprising.gateway.provider.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 资金高频核查调度器
 * 默认每 5 分钟执行一次高频核查任务（满足 5~10 分钟对账的业务要求），
 * 发现不一致立即熔断停机提现并发报警通知。
 */
@Component
public class FundCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(FundCheckScheduler.class);

    private final FundCheckService service;

    public FundCheckScheduler(FundCheckService service) {
        this.service = service;
    }

    /**
     * 默认每 5 分钟整点执行（00, 05, 10, 15, 20...）
     */
    @Scheduled(cron = "${surprising.gateway.reconciliation.cron:0 */5 * * * ?}")
    public void executeScheduledFundCheck() {
        try {
            service.checkFunds();
        } catch (Exception ex) {
            log.error("Scheduled fund check failed with unhandled exception: {}", ex.getMessage(), ex);
        }
    }
}
