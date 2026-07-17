package com.surprising.price.mark.service;

import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.repository.MarkPriceRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps the mark-price audit table bounded without issuing one unbounded delete. */
@Component
public class MarkPriceAuditRetentionService {

    private static final Logger log = LoggerFactory.getLogger(MarkPriceAuditRetentionService.class);

    private final MarkPriceRepository repository;
    private final MarkPriceProperties properties;

    public MarkPriceAuditRetentionService(MarkPriceRepository repository, MarkPriceProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${surprising.price.mark.audit.cleanup-delay-ms:3600000}")
    public void deleteExpiredAuditRows() {
        MarkPriceProperties.Audit audit = properties.getAudit();
        Instant cutoff = Instant.now().minus(audit.getRetention());
        int deleted = 0;
        for (int batch = 0; batch < audit.getMaxBatchesPerRun(); batch++) {
            int rows = repository.deleteAuditBefore(cutoff, audit.getCleanupBatchSize());
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
