package com.surprising.liquidation.provider.service;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.risk.api.model.LiquidationCandidateEvent;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Redis only orders durable candidates; liquidation service still conditionally claims them in PostgreSQL. */
@Component
public class RedisLiquidationCandidateQueue {
    private final StringRedisTemplate redis; private final ObjectMapper mapper; private final LiquidationProperties properties;
    public RedisLiquidationCandidateQueue(StringRedisTemplate redis, ObjectMapper mapper, LiquidationProperties properties) {
        this.redis=redis; this.mapper=mapper; this.properties=properties;
    }
    public boolean offer(LiquidationCandidateEvent event) {
        try {
            redis.opsForValue().set(payloadKey(event.candidateId()), mapper.writeValueAsString(event), Duration.ofHours(1));
            Boolean value=redis.opsForZSet().add(queueKey(), Long.toString(event.candidateId()), event.marginRatioPpm());
            redis.opsForValue().set(readyKey(), "1", Duration.ofSeconds(30));
            return value != null;
        } catch (Exception ex) { return false; }
    }
    public Optional<List<LiquidationCandidateEvent>> candidates(int limit) {
        try {
            if (!Boolean.TRUE.equals(redis.hasKey(readyKey()))) return Optional.empty();
            var ids=redis.opsForZSet().range(queueKey(), 0, Math.max(0, Math.min(limit, 2_000)-1));
            if (ids==null) return Optional.of(List.of());
            var events=ids.stream().map(this::load).toList();
            if (events.stream().anyMatch(Optional::isEmpty)) return Optional.empty();
            return Optional.of(events.stream().flatMap(Optional::stream).toList());
        } catch (RuntimeException ex) { return Optional.empty(); }
    }
    public void remove(long candidateId) {
        try { redis.opsForZSet().remove(queueKey(), Long.toString(candidateId)); redis.delete(payloadKey(candidateId)); } catch (RuntimeException ignored) { }
    }
    private Optional<LiquidationCandidateEvent> load(String id) {
        try { String p=redis.opsForValue().get(payloadKey(Long.parseLong(id))); return p==null?Optional.empty():Optional.of(mapper.readValue(p,LiquidationCandidateEvent.class)); }
        catch (Exception ex) { return Optional.empty(); }
    }
    private String queueKey() { return prefix()+":candidates:{"+properties.getKafka().getProductLine().name()+"}"; }
    private String payloadKey(long id) { return prefix()+":candidate:"+id; }
    private String readyKey() { return prefix()+":ready:"+properties.getKafka().getProductLine().name(); }
    private String prefix() { String p=properties.getRedisIndex().getKeyPrefix(); return p==null||p.isBlank()?"surprising:liquidation:v1":p.trim(); }
}
