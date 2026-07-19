package com.surprising.liquidation.provider.service;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.risk.api.model.LiquidationCandidateEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Redis-backed priority queue with atomic lease, retry and crash recovery. */
@Component
public class RedisLiquidationCandidateQueue {

    private static final DefaultRedisScript<Long> OFFER = new DefaultRedisScript<>("""
            local inserted = 0
            for i = 1, #ARGV, 3 do
              local id = ARGV[i]
              if redis.call('HEXISTS', KEYS[4], id) == 0 then
                redis.call('HSET', KEYS[4], id, ARGV[i + 2])
                redis.call('HSET', KEYS[5], id, ARGV[i + 1])
                redis.call('ZADD', KEYS[1], ARGV[i + 1], id)
                inserted = inserted + 1
              end
            end
            return inserted
            """, Long.class);

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> CLAIM = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local lease_until = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local promote_limit = math.max(limit * 4, 512)

            local function promote(source, max_score)
              local ids = redis.call('ZRANGEBYSCORE', source, '-inf', max_score, 'LIMIT', 0, promote_limit)
              for _, id in ipairs(ids) do
                if redis.call('ZREM', source, id) == 1 then
                  local priority = redis.call('HGET', KEYS[5], id)
                  local payload = redis.call('HGET', KEYS[4], id)
                  if priority and payload then
                    redis.call('ZADD', KEYS[1], priority, id)
                  else
                    redis.call('HDEL', KEYS[4], id)
                    redis.call('HDEL', KEYS[5], id)
                  end
                end
              end
            end

            promote(KEYS[3], now)
            promote(KEYS[2], now)

            local popped = redis.call('ZPOPMAX', KEYS[1], limit)
            local result = {}
            for i = 1, #popped, 2 do
              local id = popped[i]
              local payload = redis.call('HGET', KEYS[4], id)
              if payload then
                redis.call('ZADD', KEYS[3], lease_until, id)
                table.insert(result, id)
                table.insert(result, payload)
              else
                redis.call('HDEL', KEYS[5], id)
              end
            end
            return result
            """, List.class);

    private static final DefaultRedisScript<Long> ACKNOWLEDGE = new DefaultRedisScript<>("""
            local removed = 0
            for _, id in ipairs(ARGV) do
              redis.call('ZREM', KEYS[1], id)
              redis.call('ZREM', KEYS[2], id)
              redis.call('ZREM', KEYS[3], id)
              removed = removed + redis.call('HDEL', KEYS[4], id)
              redis.call('HDEL', KEYS[5], id)
            end
            return removed
            """, Long.class);

    private static final DefaultRedisScript<Long> RETRY = new DefaultRedisScript<>("""
            local due = ARGV[1]
            local retried = 0
            for i = 2, #ARGV do
              local id = ARGV[i]
              redis.call('ZREM', KEYS[3], id)
              if redis.call('HEXISTS', KEYS[4], id) == 1 then
                redis.call('ZADD', KEYS[2], due, id)
                retried = retried + 1
              end
            end
            return retried
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final LiquidationProperties properties;

    public RedisLiquidationCandidateQueue(StringRedisTemplate redis,
                                           ObjectMapper mapper,
                                           LiquidationProperties properties) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties;
    }

    public int offer(List<LiquidationCandidateEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        List<String> arguments = new ArrayList<>(events.size() * 3);
        try {
            for (LiquidationCandidateEvent event : events) {
                arguments.add(Long.toString(event.candidateId()));
                arguments.add(Long.toString(event.marginRatioPpm()));
                arguments.add(mapper.writeValueAsString(event));
            }
            Long inserted = redis.execute(OFFER, keys(), arguments.toArray());
            if (inserted == null) {
                throw new IllegalStateException("Redis did not return a candidate offer result");
            }
            return Math.toIntExact(inserted);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to offer liquidation candidates to Redis", ex);
        }
    }

    public List<LiquidationCandidateEvent> claim(int limit, Duration leaseDuration, Instant now) {
        int normalizedLimit = Math.max(1, Math.min(limit, 2_000));
        long nowMillis = now.toEpochMilli();
        long leaseUntilMillis = now.plus(leaseDuration).toEpochMilli();
        List<?> result = redis.execute(CLAIM, keys(), Long.toString(nowMillis), Long.toString(leaseUntilMillis),
                Integer.toString(normalizedLimit));
        if (result == null || result.isEmpty()) {
            return List.of();
        }
        if ((result.size() & 1) != 0) {
            throw new IllegalStateException("Redis returned an invalid liquidation candidate lease");
        }
        List<LiquidationCandidateEvent> events = new ArrayList<>(result.size() / 2);
        try {
            for (int i = 0; i < result.size(); i += 2) {
                String candidateId = result.get(i).toString();
                LiquidationCandidateEvent event = mapper.readValue(result.get(i + 1).toString(),
                        LiquidationCandidateEvent.class);
                if (!candidateId.equals(Long.toString(event.candidateId()))) {
                    throw new IllegalStateException("Redis candidate id does not match its payload");
                }
                events.add(event);
            }
            return List.copyOf(events);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to decode leased liquidation candidates", ex);
        }
    }

    public void acknowledge(List<Long> candidateIds) {
        executeIds(ACKNOWLEDGE, candidateIds, null);
    }

    public void retry(List<Long> candidateIds, Duration delay, Instant now) {
        executeIds(RETRY, candidateIds, Long.toString(now.plus(delay).toEpochMilli()));
    }

    private void executeIds(DefaultRedisScript<Long> script, List<Long> candidateIds, String firstArgument) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return;
        }
        List<String> arguments = new ArrayList<>(candidateIds.size() + (firstArgument == null ? 0 : 1));
        if (firstArgument != null) {
            arguments.add(firstArgument);
        }
        candidateIds.stream().map(String::valueOf).forEach(arguments::add);
        Long result = redis.execute(script, keys(), arguments.toArray());
        if (result == null) {
            throw new IllegalStateException("Redis did not confirm liquidation candidate state transition");
        }
    }

    private List<String> keys() {
        String scope = prefix() + ":{" + properties.getKafka().getProductLine().name() + "}";
        return List.of(scope + ":ready", scope + ":delayed", scope + ":inflight", scope + ":payload",
                scope + ":priority");
    }

    private String prefix() {
        String value = properties.getRedisIndex().getKeyPrefix();
        return value == null || value.isBlank() ? "surprising:liquidation:v2" : value.trim();
    }
}
