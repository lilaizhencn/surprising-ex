package com.surprising.gateway.provider.content.service;

import com.surprising.gateway.provider.content.client.ContentR2Storage;
import com.surprising.gateway.provider.content.config.ContentMediaProperties;
import com.surprising.gateway.provider.content.repository.ContentMediaRepository;
import com.surprising.gateway.provider.content.repository.ContentMediaReferenceRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A separate scheduler prevents slow object storage from blocking financial reconciliation tasks. */
@Service
public class ContentMediaCleanupService {
    private static final Logger LOG = LoggerFactory.getLogger(ContentMediaCleanupService.class);
    private final ContentMediaRepository media;
    private final ContentR2Storage storage;
    private final ContentMediaProperties properties;
    private final ContentMediaReferenceRepository references;
    private final TransactionTemplate transaction;
    public ContentMediaCleanupService(ContentMediaRepository media, ContentR2Storage storage,
                                     ContentMediaProperties properties, PlatformTransactionManager manager, ContentMediaReferenceRepository references) {
        this.media = media; this.storage = storage; this.properties = properties;
        this.references = references;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(130);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M", scheduler = "contentMediaCleanupScheduler")
    public void clean() {
        if (!properties.enabled()) return;
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        for (int count = 0; count < 20; count++) {
            try {
                Boolean deleted = transaction.execute(status -> {
                    var candidate = media.lockOldUnreferenced(cutoff);
                    if (candidate.isEmpty()) return false;
                    var object = candidate.get();
                    // A reference may have committed between the candidate snapshot and the row lock.
                    // Recheck with a fresh READ COMMITTED snapshot while the media row excludes new references.
                    if (references.exists(object.mediaId())) return false;
                    storage.delete(object.objectKey());
                    media.delete(object.mediaId());
                    return true;
                });
                if (!Boolean.TRUE.equals(deleted)) return;
            } catch (RuntimeException failure) {
                // Retain metadata on failure, including after an ambiguous object-storage response.
                // S3 delete is idempotent; the next run can safely retry this object.
                LOG.warn("Unreferenced article media cleanup failed; metadata retained for retry", failure);
                return;
            }
        }
    }
}
