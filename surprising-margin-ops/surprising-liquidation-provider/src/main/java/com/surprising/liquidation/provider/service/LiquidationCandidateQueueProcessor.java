package com.surprising.liquidation.provider.service;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.repository.LiquidationRepository;
import com.surprising.risk.api.model.LiquidationCandidateEvent;
import java.util.List;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class LiquidationCandidateQueueProcessor {
    private static final Logger log=LoggerFactory.getLogger(LiquidationCandidateQueueProcessor.class);
    private final RedisLiquidationCandidateQueue queue; private final LiquidationRepository repository;
    private final LiquidationService service; private final LiquidationProperties properties;
    public LiquidationCandidateQueueProcessor(RedisLiquidationCandidateQueue queue, LiquidationRepository repository,
                                              LiquidationService service, LiquidationProperties properties) {
        this.queue=queue; this.repository=repository; this.service=service; this.properties=properties;
    }
    public void enqueueAndDrain(LiquidationCandidateEvent event) {
        if (!queue.offer(event)) { service.processCandidate(event); return; }
        drain();
    }
    @Scheduled(fixedDelayString = "${surprising.liquidation.redis-index.drain-delay-ms:100}") public void drain() {
        int limit=Math.max(1, properties.getRedisIndex().getCandidateBatchSize());
        List<LiquidationCandidateEvent> candidates=queue.candidates(limit)
                .orElseGet(() -> repository.newCandidateEvents(limit));
        for (LiquidationCandidateEvent candidate:candidates) {
            try { service.processCandidate(candidate); queue.remove(candidate.candidateId()); }
            catch (RuntimeException ex) { log.warn("liquidation candidate remains queued id={}",candidate.candidateId(),ex); }
        }
    }
}
