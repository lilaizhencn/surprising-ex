package com.surprising.price.index.service;

import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.repository.IndexPriceRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps index audit tables bounded without an unbounded delete. */
@Component
public class IndexPriceAuditRetentionService {

    private static final Logger log = LoggerFactory.getLogger(IndexPriceAuditRetentionService.class);

    private final IndexPriceRepository repository;
    private final IndexPriceProperties properties;

    public IndexPriceAuditRetentionService(IndexPriceRepository repository, IndexPriceProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${surprising.price.index.audit.cleanup-delay-ms:60000}")
    public void deleteExpiredAuditRows() {
        IndexPriceProperties.Audit audit = properties.getAudit();
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
            log.info("Deleted {} expired index-price audit rows older than {}", deleted, cutoff);
        }
    }
}
