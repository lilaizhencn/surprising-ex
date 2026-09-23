package com.surprising.price.index.service;

import lombok.extern.slf4j.Slf4j;

import com.surprising.price.index.config.IndexPriceProperties;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** 通过有界批量删除控制指数价格审计表规模。 */
@Component
@Slf4j
public class IndexPriceAuditRetentionService {


    private final IndexPriceAuditService auditService;
    private final IndexPriceProperties properties;

    public IndexPriceAuditRetentionService(IndexPriceAuditService auditService, IndexPriceProperties properties) {
        this.auditService = auditService;
        this.properties = properties;
    }

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
