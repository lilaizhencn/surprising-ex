package com.surprising.price.index.service;

import com.surprising.price.index.config.IndexPriceProperties;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 通过有界批量删除控制指数价格审计表规模。 */
@Component
public class IndexPriceAuditRetentionService {

    private static final Logger log = LoggerFactory.getLogger(IndexPriceAuditRetentionService.class);

    private final IndexPriceAuditService auditService;
    private final IndexPriceProperties properties;

    public IndexPriceAuditRetentionService(IndexPriceAuditService auditService, IndexPriceProperties properties) {
        this.auditService = auditService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${surprising.price.index.audit.cleanup-delay-ms:60000}")
    public void deleteExpiredAuditRows() {
        IndexPriceProperties.Audit audit = properties.getAudit();
        Instant cutoff = Instant.now().minus(audit.getRetention());
        int deleted = 0;
        for (int batch = 0; batch < audit.getMaxBatchesPerRun(); batch++) {
            int rows = auditService.deleteBefore(cutoff, audit.getCleanupBatchSize());
            deleted += rows;
            if (rows < audit.getCleanupBatchSize()) {
                break;
            }
        }
        if (deleted > 0) {
            log.info("Deleted {} expired index-price audit rows older than {}", deleted, cutoff);
        }
    }
}
