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
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Redis 风险状态投影，以及从交易对到风险组的反向索引。 */
@Component
public class RedisRiskStateStore {

    private static final DefaultRedisScript<Long> RELEASE_LEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> RENEW_LEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> RENEW_REBUILD = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 0
            end
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            redis.call('PEXPIRE', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> COMPLETE_REBUILD = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 0
            end
            redis.call('DEL', KEYS[1], KEYS[2])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> REPLACE_GROUP = new DefaultRedisScript<>("""
            local ttl = ARGV[3]
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ttl)
            redis.call('DEL', KEYS[2])
            for i = 5, #KEYS do
                if ARGV[i] == '1' then
                    redis.call('SADD', KEYS[i], ARGV[1])
                    redis.call('PEXPIRE', KEYS[i], ttl)
                    redis.call('SADD', KEYS[2], KEYS[i])
                else
                    redis.call('SREM', KEYS[i], ARGV[1])
                end
            end
            if redis.call('SCARD', KEYS[2]) > 0 then
                redis.call('PEXPIRE', KEYS[2], ttl)
            end
            redis.call('SADD', KEYS[3], ARGV[1])
            redis.call('PEXPIRE', KEYS[3], ttl)
            if ARGV[4] == '1' then
                redis.call('SADD', KEYS[4], ARGV[1])
                redis.call('PEXPIRE', KEYS[4], ttl)
            end
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> PRUNE_GROUP = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[5]) ~= ARGV[2] then
                return -1
            end
            if redis.call('SISMEMBER', KEYS[4], ARGV[1]) == 1 then
                return 0
            end
            for i = 6, #KEYS do
                redis.call('SREM', KEYS[i], ARGV[1])
            end
            redis.call('DEL', KEYS[1], KEYS[2])
            redis.call('SREM', KEYS[3], ARGV[1])
            return 1
            """, Long.class);

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

    public ProjectionUpdate replace(
            ProductLine productLine,
            RiskGroupKey key,
            Supplier<CachedRiskGroup> stateSupplier) {
        String groupId = groupId(key);
        ProjectionLease groupLease = acquireGroupLease(productLine, groupId);
        try {
            CachedRiskGroup state = stateSupplier.get();
            if (!key.equals(state.key())) {
                throw new IllegalStateException("Redis 风险组投影键不一致: " + groupId);
            }
            if (!renew(groupLease, groupLockTtl())) {
                throw new ProjectionLostException("Redis 风险组锁已经丢失: " + groupId);
            }
            return new ProjectionUpdate(state, replaceLocked(productLine, groupId, state));
        } finally {
            release(groupLease);
        }
    }

    private boolean replaceLocked(ProductLine productLine, String groupId, CachedRiskGroup state) {
        String membership = membershipKey(productLine, groupId);
        String previousPayload = redis.opsForValue().get(stateKey(productLine, groupId));
        boolean changed = previousPayload == null;
        if (previousPayload != null) {
            try {
                CachedRiskGroup previousState = objectMapper.readValue(previousPayload, CachedRiskGroup.class);
                if (previousState.capturedAt().isAfter(state.capturedAt())) {
                    markSeen(productLine, groupId);
                    return false;
                }
                changed = previousState.walletBalanceUnits() != state.walletBalanceUnits()
                        || !previousState.positions().equals(state.positions());
            } catch (RuntimeException ignored) {
                changed = true;
            }
        }
        Set<String> previous = members(membership);
        Set<String> current = new LinkedHashSet<>();
        for (CachedRiskPosition position : state.positions()) {
            current.add(indexKey(productLine, position.symbol(), position.instrumentVersion()));
        }
        Set<String> allIndices = new LinkedHashSet<>(previous);
        allIndices.addAll(current);
        String generation = redis.opsForValue().get(buildingKey(productLine));
        List<String> keys = new ArrayList<>(4 + allIndices.size());
        keys.add(stateKey(productLine, groupId));
        keys.add(membership);
        keys.add(groupsKey(productLine));
        keys.add(seenKey(productLine, generation == null ? "none" : generation));
        keys.addAll(allIndices);
        List<String> arguments = new ArrayList<>(4 + allIndices.size());
        arguments.add(groupId);
        arguments.add(objectMapper.writeValueAsString(state));
        arguments.add(Long.toString(stateTtl().toMillis()));
        arguments.add(generation == null ? "0" : "1");
        for (String index : allIndices) {
            arguments.add(current.contains(index) ? "1" : "0");
        }
        Long applied = redis.execute(REPLACE_GROUP, keys, arguments.toArray());
        if (!Long.valueOf(1L).equals(applied)) {
            throw new IllegalStateException("Redis 风险组原子替换失败: " + groupId);
        }
        return changed;
    }

    /** 尝试取得单产品线投影协调权，未取得时由其他节点继续扫描。 */
    public ProjectionLease tryAcquireProjection(ProductLine productLine) {
        return tryAcquire(projectionLeaseKey(productLine), projectionLeaseTtl());
    }

    public boolean renewProjection(ProjectionLease lease) {
        return renew(lease, projectionLeaseTtl());
    }

    public void releaseProjection(ProjectionLease lease) {
        release(lease);
    }

    /** 开始新的对账代；活动投影保持可读，事件更新会同步登记到本代。 */
    public String startRebuild(ProductLine productLine) {
        String generation = UUID.randomUUID().toString();
        redis.delete(seenKey(productLine, generation));
        redis.opsForValue().set(buildingKey(productLine), generation, projectionLeaseTtl());
        return generation;
    }

    public void renewRebuild(ProductLine productLine, String generation) {
        Long renewed = redis.execute(
                RENEW_REBUILD,
                List.of(buildingKey(productLine), seenKey(productLine, generation)),
                generation,
                Long.toString(projectionLeaseTtl().toMillis()));
        if (!Long.valueOf(1L).equals(renewed)) {
            throw new ProjectionLostException("Redis 风险投影对账代已经失效");
        }
    }

    /** 删除本代未观察到的陈旧风险组；每组删除会与事件替换使用同一把分布式锁。 */
    public void pruneUnseen(ProductLine productLine, String generation) {
        for (String groupId : members(groupsKey(productLine))) {
            ProjectionLease groupLease = acquireGroupLease(productLine, groupId);
            try {
                List<String> keys = new ArrayList<>();
                keys.add(stateKey(productLine, groupId));
                keys.add(membershipKey(productLine, groupId));
                keys.add(groupsKey(productLine));
                keys.add(seenKey(productLine, generation));
                keys.add(buildingKey(productLine));
                keys.addAll(members(membershipKey(productLine, groupId)));
                Long pruned = redis.execute(PRUNE_GROUP, keys, groupId, generation);
                if (Long.valueOf(-1L).equals(pruned)) {
                    throw new ProjectionLostException("Redis 风险投影对账代已经失效");
                }
            } finally {
                release(groupLease);
            }
        }
    }

    public void completeRebuild(ProductLine productLine, String generation) {
        Long completed = redis.execute(
                COMPLETE_REBUILD,
                List.of(buildingKey(productLine), seenKey(productLine, generation)),
                generation);
        if (!Long.valueOf(1L).equals(completed)) {
            throw new ProjectionLostException("Redis 风险投影对账代完成失败");
        }
    }

    public void abandonRebuild(ProductLine productLine, String generation) {
        if (generation == null) {
            return;
        }
        redis.execute(
                COMPLETE_REBUILD,
                List.of(buildingKey(productLine), seenKey(productLine, generation)),
                generation);
    }

    public List<String> groupIds(ProductLine productLine, String symbol, long instrumentVersion) {
        Set<String> values = redis.opsForSet().members(indexKey(productLine, symbol, instrumentVersion));
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().sorted().toList();
    }

    /** 读取已经完成恢复的单个风险组；实时事件路径不通过数据库回退。 */
    public CachedRiskGroup read(ProductLine productLine, RiskGroupKey key) {
        String payload = redis.opsForValue().get(stateKey(productLine, groupId(key)));
        if (payload == null || payload.isBlank()) {
            return null;
        }
        CachedRiskGroup state = objectMapper.readValue(payload, CachedRiskGroup.class);
        if (!key.equals(state.key())) {
            throw new IllegalStateException("Redis 风险组键与载荷不一致: " + groupId(key));
        }
        return state;
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

    public void refreshReady(ProductLine productLine) {
        if (ready(productLine)) {
            redis.expire(readyKey(productLine), readyTtl());
        }
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

    private String buildingKey(ProductLine productLine) {
        return prefix() + ":rebuild:" + productLine.name();
    }

    private String seenKey(ProductLine productLine, String generation) {
        return prefix() + ":rebuild-seen:" + productLine.name() + ":" + generation;
    }

    private String projectionLeaseKey(ProductLine productLine) {
        return prefix() + ":projection-lease:" + productLine.name();
    }

    private String groupLeaseKey(ProductLine productLine, String groupId) {
        return prefix() + ":group-lease:" + productLine.name() + ":" + groupId;
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

    private Duration projectionLeaseTtl() {
        Duration configured = properties.getRedisState().getProjectionLeaseTtl();
        return configured == null || configured.isZero() || configured.isNegative()
                ? Duration.ofMinutes(1) : configured;
    }

    private ProjectionLease acquireGroupLease(ProductLine productLine, String groupId) {
        long deadline = System.nanoTime() + groupLockWait().toNanos();
        do {
            ProjectionLease lease = tryAcquire(groupLeaseKey(productLine, groupId), groupLockTtl());
            if (lease != null) {
                return lease;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 Redis 风险组锁时线程被中断", ex);
            }
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("获取 Redis 风险组锁超时: " + groupId);
    }

    private ProjectionLease tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(acquired) ? new ProjectionLease(key, token) : null;
    }

    private boolean renew(ProjectionLease lease, Duration ttl) {
        if (lease == null) {
            return false;
        }
        Long renewed = redis.execute(
                RENEW_LEASE, List.of(lease.key()), lease.token(), Long.toString(ttl.toMillis()));
        return Long.valueOf(1L).equals(renewed);
    }

    private void release(ProjectionLease lease) {
        if (lease != null) {
            redis.execute(RELEASE_LEASE, List.of(lease.key()), lease.token());
        }
    }

    private void markSeen(ProductLine productLine, String groupId) {
        String generation = redis.opsForValue().get(buildingKey(productLine));
        if (generation != null) {
            redis.opsForSet().add(seenKey(productLine, generation), groupId);
            redis.expire(seenKey(productLine, generation), projectionLeaseTtl());
        }
    }

    private Duration groupLockTtl() {
        Duration configured = properties.getRedisState().getProjectionGroupLockTtl();
        return configured == null || configured.isZero() || configured.isNegative()
                ? Duration.ofSeconds(5) : configured;
    }

    private Duration groupLockWait() {
        Duration configured = properties.getRedisState().getProjectionGroupLockWait();
        return configured == null || configured.isZero() || configured.isNegative()
                ? Duration.ofSeconds(2) : configured;
    }

    public record ProjectionLease(String key, String token) {
    }

    public record ProjectionUpdate(CachedRiskGroup state, boolean changed) {
    }

    public static final class ProjectionLostException extends IllegalStateException {
        public ProjectionLostException(String message) {
            super(message);
        }
    }
}
