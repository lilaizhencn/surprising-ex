package com.surprising.adl.provider.service;

import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.adl.provider.repository.AdlRepository;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AdlRedisIndexCoordinator {
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0
            """, Long.class);
    private final AdlRepository repository; private final RedisAdlCandidateIndex index;
    private final StringRedisTemplate redis; private final AdlProperties properties;
    public AdlRedisIndexCoordinator(AdlRepository repository, RedisAdlCandidateIndex index,
                                    StringRedisTemplate redis, AdlProperties properties) {
        this.repository=repository; this.index=index; this.redis=redis; this.properties=properties;
    }
    @EventListener(ApplicationReadyEvent.class) public void onReady() { rebuild(); }
    @Scheduled(fixedDelayString = "${surprising.adl.redis-index.reconcile-delay-ms:10000}") public void rebuild() {
        ProductLine productLine = properties.getKafka().getProductLine();
        String token=UUID.randomUUID().toString(); String lock=rebuildLockKey(productLine);
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock, token, Duration.ofSeconds(30)))) return;
        try {
            for (String asset : repository.candidateAssets()) {
                index.clear(productLine, asset);
                repository.queue(asset, 5_000, Duration.ofMillis(Math.max(1L, properties.getScanner().getMaxMarkAgeMs())))
                        .forEach(index::synchronize);
                index.markReady(productLine, asset);
            }
        } finally { redis.execute(RELEASE, List.of(lock), token); }
    }
    String rebuildLockKey(ProductLine productLine) { return prefix()+":rebuild-lock:"+productLine.name(); }
    private String prefix() { String p=properties.getRedisIndex().getKeyPrefix(); return p==null||p.isBlank()?"surprising:adl:v1":p.trim(); }
}
