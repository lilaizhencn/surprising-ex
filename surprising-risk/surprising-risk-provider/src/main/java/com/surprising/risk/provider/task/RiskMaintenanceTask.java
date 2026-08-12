package com.surprising.risk.provider.task;

import com.surprising.risk.provider.service.RiskOutboxPublisher;
import com.surprising.risk.provider.service.RiskService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 风控模块定时任务入口，只负责调用风控服务层。
 */
@Component
public class RiskMaintenanceTask {

    private final RiskService riskService;
    private final RiskOutboxPublisher outboxPublisher;

    public RiskMaintenanceTask(RiskService riskService,
                               RiskOutboxPublisher outboxPublisher) {
        this.riskService = riskService;
        this.outboxPublisher = outboxPublisher;
    }

    @Scheduled(fixedDelayString = "${surprising.risk.calculation.scan-delay-ms:1000}")
    public void scanRisk() {
        riskService.scan();
    }

    /** 风险计算与数据库读模型投影解耦，数据库不可用时本地事实仍可继续累积。 */
    @Scheduled(fixedDelayString = "${surprising.risk.local-state.projection-delay-ms:100}")
    public void projectRisk() {
        riskService.projectPending();
    }

    @Scheduled(fixedDelayString = "${surprising.risk.outbox.publish-delay-ms:200}")
    public void publishOutbox() {
        outboxPublisher.publishPending();
    }

    @Scheduled(fixedDelayString = "${surprising.risk.outbox.cleanup-delay-ms:60000}")
    public void cleanupOutbox() {
        outboxPublisher.cleanupPublished();
    }
}
