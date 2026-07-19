package com.surprising.risk.provider.service;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.CachedRiskGroup;
import com.surprising.risk.provider.model.CachedRiskPosition;
import com.surprising.risk.provider.model.RiskGroupKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Redis risk-state projection plus symbol-to-risk-group reverse index. */
@Component
public class RedisRiskStateStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RiskProperties properties;

    public RedisRiskStateStore(StringRedisTemplate redis,
                               ObjectMapper objectMapper,
                               RiskProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public boolean replace(ProductLine productLine, CachedRiskGroup state) {
        String groupId = groupId(state.key());
        String membershipKey = membershipKey(productLine, groupId);
        String previousPayload = redis.opsForValue().get(stateKey(productLine, groupId));
        boolean changed = previousPayload == null;
        if (previousPayload != null) {
            try {
                CachedRiskGroup previousState = objectMapper.readValue(previousPayload, CachedRiskGroup.class);
                changed = previousState.walletBalanceUnits() != state.walletBalanceUnits()
                        || !previousState.positions().equals(state.positions());
            } catch (RuntimeException ignored) {
                changed = true;
            }
        }
        Set<String> previous = members(membershipKey);
        Set<String> current = new LinkedHashSet<>();
        for (CachedRiskPosition position : state.positions()) {
            current.add(indexKey(productLine, position.symbol(), position.instrumentVersion()));
        }

        redis.opsForValue().set(stateKey(productLine, groupId), objectMapper.writeValueAsString(state), stateTtl());
        for (String index : previous) {
            if (!current.contains(index)) {
                redis.opsForSet().remove(index, groupId);
            }
        }
        for (String index : current) {
            redis.opsForSet().add(index, groupId);
            redis.expire(index, stateTtl());
        }
        redis.delete(membershipKey);
        if (!current.isEmpty()) {
            redis.opsForSet().add(membershipKey, current.toArray(String[]::new));
            redis.expire(membershipKey, stateTtl());
        }
        redis.opsForSet().add(groupsKey(productLine), groupId);
        redis.expire(groupsKey(productLine), stateTtl());
        return changed;
    }

    /** Clears the complete product-line projection before an authoritative rebuild. */
    public void clear(ProductLine productLine) {
        Set<String> groupIds = members(groupsKey(productLine));
        for (String groupId : groupIds) {
            String membershipKey = membershipKey(productLine, groupId);
            for (String index : members(membershipKey)) {
                redis.opsForSet().remove(index, groupId);
            }
            redis.delete(List.of(membershipKey, stateKey(productLine, groupId)));
        }
        redis.delete(groupsKey(productLine));
        markNotReady(productLine);
    }

    public List<String> groupIds(ProductLine productLine, String symbol, long instrumentVersion) {
        Set<String> values = redis.opsForSet().members(indexKey(productLine, symbol, instrumentVersion));
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().sorted().toList();
    }

    public List<CachedRiskGroup> groups(ProductLine productLine, List<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return List.of();
        }
        List<String> keys = groupIds.stream().map(id -> stateKey(productLine, id)).toList();
        List<String> payloads = redis.opsForValue().multiGet(keys);
        if (payloads == null || payloads.isEmpty()) {
            return List.of();
        }
        List<CachedRiskGroup> states = new ArrayList<>(payloads.size());
        for (String payload : payloads) {
            if (payload != null) {
                states.add(objectMapper.readValue(payload, CachedRiskGroup.class));
            }
        }
        return List.copyOf(states);
    }

    public boolean claim(ProductLine productLine, MarkPriceEvent event) {
        Boolean claimed = redis.opsForValue().setIfAbsent(
                triggerKey(productLine, event.symbol(), event.instrumentVersion(), event.sequence()),
                "1", stateTtl());
        return Boolean.TRUE.equals(claimed);
    }

    public boolean claimHeartbeat(ProductLine productLine, MarkPriceEvent event, Duration interval) {
        Duration effective = interval == null || interval.isZero() || interval.isNegative()
                ? Duration.ofSeconds(30) : interval;
        long intervalMillis = Math.max(1L, effective.toMillis());
        long bucket = Math.floorDiv(event.eventTime().toEpochMilli(), intervalMillis);
        Boolean claimed = redis.opsForValue().setIfAbsent(
                prefix() + ":heartbeat:" + productLine.name() + ":" + event.symbol() + ":"
                        + event.instrumentVersion() + ":" + bucket,
                "1", effective.multipliedBy(2L));
        return Boolean.TRUE.equals(claimed);
    }

    public boolean ready(ProductLine productLine) {
        return Boolean.TRUE.equals(redis.hasKey(readyKey(productLine)));
    }

    public void markReady(ProductLine productLine) {
        redis.opsForValue().set(readyKey(productLine), "1", readyTtl());
    }

    public void markNotReady(ProductLine productLine) {
        redis.delete(readyKey(productLine));
    }

    static String groupId(RiskGroupKey key) {
        return key.userId() + "|" + key.accountType() + "|" + key.settleAsset();
    }

    private Set<String> members(String key) {
        Set<String> values = redis.opsForSet().members(key);
        return values == null ? Set.of() : new HashSet<>(values);
    }

    private String stateKey(ProductLine productLine, String groupId) {
        return prefix() + ":state:" + productLine.name() + ":" + groupId;
    }

    private String membershipKey(ProductLine productLine, String groupId) {
        return prefix() + ":memberships:" + productLine.name() + ":" + groupId;
    }

    private String indexKey(ProductLine productLine, String symbol, long version) {
        return prefix() + ":index:" + productLine.name() + ":" + symbol + ":" + version;
    }

    private String triggerKey(ProductLine productLine, String symbol, long version, long sequence) {
        return prefix() + ":trigger:" + productLine.name() + ":" + symbol + ":" + version + ":" + sequence;
    }

    private String readyKey(ProductLine productLine) {
        return prefix() + ":ready:" + productLine.name();
    }

    private String groupsKey(ProductLine productLine) {
        return prefix() + ":groups:" + productLine.name();
    }

    private String prefix() {
        String configured = properties.getRedisState().getKeyPrefix();
        return configured == null || configured.isBlank() ? "surprising:risk-state:v2" : configured.trim();
    }

    private Duration stateTtl() {
        Duration configured = properties.getRedisState().getStateTtl();
        return configured == null || configured.isZero() || configured.isNegative()
                ? Duration.ofMinutes(10) : configured;
    }

    private Duration readyTtl() {
        Duration configured = properties.getRedisState().getReadyTtl();
        return configured == null || configured.isZero() || configured.isNegative()
                ? Duration.ofSeconds(30) : configured;
    }
}
