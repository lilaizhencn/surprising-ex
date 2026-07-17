package com.surprising.adl.provider.service;

import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.risk.api.model.RiskPositionUpdatedEvent;
import com.surprising.adl.provider.model.AdlCandidate;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Latest risk-snapshot ranking only; every selected member is revalidated and locked in PostgreSQL. */
@Component
public class RedisAdlCandidateIndex {
    private final StringRedisTemplate redis;
    private final AdlProperties properties;
    public RedisAdlCandidateIndex(StringRedisTemplate redis, AdlProperties properties) { this.redis = redis; this.properties = properties; }

    public void synchronize(RiskPositionUpdatedEvent event) {
        String member = member(event);
        try {
            if (event.signedQuantitySteps() == 0 || event.unrealizedPnlUnits() <= 0 || event.notionalUnits() <= 0) {
                redis.opsForZSet().remove(key(event.settleAsset()), member); return;
            }
            long profitRate = AdlMath.profitRatePpm(event.unrealizedPnlUnits(), event.notionalUnits());
            long leverage = AdlMath.effectiveLeveragePpm(event.notionalUnits(), event.positionMarginUnits());
            long priority = AdlMath.priorityScorePpm(profitRate, leverage);
            if (priority <= 0) { redis.opsForZSet().remove(key(event.settleAsset()), member); return; }
            // Negative score makes ZRANGEBYSCORE return highest ADL priority first.
            redis.opsForZSet().add(key(event.settleAsset()), member, -((double) priority));
        } catch (RuntimeException ignored) { }
    }

    public void synchronize(AdlCandidate candidate) {
        String member = candidate.userId()+"|"+candidate.symbol()+"|"+candidate.marginMode().name()+"|"+candidate.positionSide().name();
        redis.opsForZSet().add(key(candidate.asset()), member, -((double) candidate.priorityScorePpm()));
    }
    public void clear(String asset) { redis.delete(key(asset)); }
    public void markReady(String asset) { redis.opsForValue().set(readyKey(asset), "1", Duration.ofMillis(Math.max(1L, properties.getRedisIndex().getReadyTtlMs()))); }

    public Optional<List<Member>> candidates(String asset, int limit) {
        try {
            if (!Boolean.TRUE.equals(redis.hasKey(readyKey(asset)))) return Optional.empty();
            var values = redis.opsForZSet().range(key(asset), 0, Math.max(0, Math.min(limit, 2_000) - 1));
            return Optional.of(values == null ? List.of() : values.stream().map(this::parse).toList());
        } catch (RuntimeException ex) { return Optional.empty(); }
    }
    private Member parse(String raw) {
        String[] p = raw.split("\\|", 4);
        if (p.length != 4) throw new IllegalArgumentException("invalid ADL Redis member");
        return new Member(Long.parseLong(p[0]), p[1], p[2], p[3]);
    }
    private String member(RiskPositionUpdatedEvent e) { return e.userId()+"|"+e.symbol()+"|"+e.marginMode().name()+"|"+e.positionSide().name(); }
    String key(String asset) { return prefix()+":queue:{"+asset+"}"; }
    private String readyKey(String asset) { return prefix()+":ready:"+asset; }
    private String prefix() { String p=properties.getRedisIndex().getKeyPrefix(); return p==null||p.isBlank()?"surprising:adl:v1":p.trim(); }
    public record Member(long userId, String symbol, String marginMode, String positionSide) { }
}
