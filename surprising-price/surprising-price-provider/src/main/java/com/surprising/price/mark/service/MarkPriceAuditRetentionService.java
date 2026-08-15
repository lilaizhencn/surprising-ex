package com.surprising.price.mark.service;

import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.repository.MarkPriceTickRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 通过分批删除限制标记价审计表规模，避免单次无界删除。 */
@Component
    public class MarkPriceAuditRetentionService {

    private static final Logger log = LoggerFactory.getLogger(MarkPriceAuditRetentionService.class);

    private final MarkPriceTickRepository tickRepository;
    private final MarkPriceProperties properties;

    public MarkPriceAuditRetentionService(MarkPriceTickRepository tickRepository, MarkPriceProperties properties) {
        this.tickRepository = tickRepository;
        this.properties = properties;
    }

    public void deleteExpiredAuditRows() {
        MarkPriceProperties.Audit audit = properties.getAudit();
        Instant cutoff = Instant.now().minus(audit.getRetention());
        int deleted = 0;
        for (int batch = 0; batch < audit.getMaxBatchesPerRun(); batch++) {
            int rows = tickRepository.deleteBefore(cutoff, audit.getCleanupBatchSize());
            deleted += rows;
            if (rows < audit.getCleanupBatchSize()) {
                break;
            }
        }
        if (deleted > 0) {
            log.info("Deleted {} expired mark-price audit rows older than {}", deleted, cutoff);
        }
    }
}
