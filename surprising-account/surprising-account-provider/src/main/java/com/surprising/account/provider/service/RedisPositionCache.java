package com.surprising.account.provider.service;

import com.surprising.account.api.model.PositionCacheEvent;
import com.surprising.account.api.model.PositionMarginResponse;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.model.CachedPosition;
import com.surprising.account.provider.model.CachedPositionMargin;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis-only user position read model.
 *
 * <p>All three hashes use the same Redis Cluster hash tag. The Lua script compares the PostgreSQL
 * revision and atomically updates position, collateral, and revision values.</p>
 */
@Component
public class RedisPositionCache {

    private static final DefaultRedisScript<Long> APPLY = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('HGET', KEYS[3], ARGV[1]) or '-1')
            local incoming = tonumber(ARGV[2])
            if current >= incoming then
                return 0
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
            redis.call('HSET', KEYS[2], ARGV[1], ARGV[4])
            redis.call('HSET', KEYS[3], ARGV[1], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AccountProperties properties;
    private final PositionCacheMetrics metrics;

    public RedisPositionCache(StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper,
                              AccountProperties properties,
                              PositionCacheMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.metrics = metrics;
        validateConfiguration();
    }

    public boolean apply(PositionCacheEvent event, boolean rebuild) {
        try {
            String field = field(event.symbol(), event.marginMode(), event.positionSide());
            CachedPosition position = new CachedPosition(
                    event.productLine(), event.userId(), event.symbol(), event.instrumentVersion(),
                    event.marginMode(), event.positionSide(), event.signedQuantitySteps(), event.entryPriceTicks(),
                    event.entryValueTicks(), event.realizedPnlUnits(), event.positionUpdatedAt(), event.revision());
            CachedPositionMargin margin = new CachedPositionMargin(
                    event.productLine(), event.userId(), event.symbol(), event.marginAsset(), event.marginMode(),
                    event.positionSide(), event.marginUnits(), event.marginUpdatedAt(), event.revision());
            Long applied = redisTemplate.execute(
                    APPLY,
                    List.of(stateKey(event.productLine(), event.userId()),
                            marginKey(event.productLine(), event.userId()),
                            revisionKey(event.productLine(), event.userId())),
                    field,
                    Long.toString(event.revision()),
                    objectMapper.writeValueAsString(position),
                    objectMapper.writeValueAsString(margin));
            if (applied != null && applied == 1L) {
                metrics.recordApplied(rebuild);
                return true;
            }
            metrics.recordStale();
            return false;
        } catch (RuntimeException ex) {
            metrics.recordFailure();
            markNotReady(event.productLine());
            throw new PositionCacheUnavailableException("failed to update Redis position cache", ex);
        }
    }

    public PositionResponse position(ProductLine productLine,
                                     long userId,
                                     String symbol,
                                     MarginMode marginMode,
                                     PositionSide positionSide) {
        requireReady(productLine);
        try {
            Object value = redisTemplate.opsForHash().get(
                    stateKey(productLine, userId), field(symbol, marginMode, positionSide));
            if (value == null) {
                return emptyPosition(userId, symbol, marginMode, positionSide);
            }
            return toResponse(read(value.toString(), CachedPosition.class));
        } catch (PositionCacheUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            markNotReady(productLine);
            throw new PositionCacheUnavailableException("failed to read Redis position cache", ex);
        }
    }

    public List<PositionResponse> positions(ProductLine productLine,
                                            long userId,
                                            PositionSide positionSide) {
        requireReady(productLine);
        try {
            List<PositionResponse> positions = redisTemplate.opsForHash()
                    .values(stateKey(productLine, userId))
                    .stream()
                    .map(value -> read(value.toString(), CachedPosition.class))
                    .filter(value -> value.signedQuantitySteps() != 0L)
                    .filter(value -> positionSide == null || value.positionSide() == positionSide)
                    .map(this::toResponse)
                    .sorted(Comparator.comparing(PositionResponse::symbol)
                            .thenComparing(PositionResponse::marginMode)
                            .thenComparing(PositionResponse::positionSide))
                    .toList();
            return List.copyOf(positions);
        } catch (PositionCacheUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            markNotReady(productLine);
            throw new PositionCacheUnavailableException("failed to list Redis positions", ex);
        }
    }

    public PositionMarginResponse positionMargin(ProductLine productLine,
                                                 long userId,
                                                 String symbol,
                                                 MarginMode marginMode,
                                                 PositionSide positionSide) {
        requireReady(productLine);
        try {
            Object value = redisTemplate.opsForHash().get(
                    marginKey(productLine, userId), field(symbol, marginMode, positionSide));
            if (value == null) {
                return new PositionMarginResponse(userId, symbol, "", marginMode, positionSide, 0L, Instant.EPOCH);
            }
            CachedPositionMargin margin = read(value.toString(), CachedPositionMargin.class);
            return new PositionMarginResponse(
                    margin.userId(), margin.symbol(), margin.asset(), margin.marginMode(), margin.positionSide(),
                    margin.marginUnits(), margin.updatedAt());
        } catch (PositionCacheUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            markNotReady(productLine);
            throw new PositionCacheUnavailableException("failed to read Redis position margin", ex);
        }
    }

    public boolean ready(ProductLine productLine) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(readyKey(productLine)));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public void markReady(ProductLine productLine) {
        redisTemplate.opsForValue().set(
                readyKey(productLine), "1", properties.getPositionCache().getReadyTtl());
    }

    public void markNotReady(ProductLine productLine) {
        try {
            redisTemplate.delete(readyKey(productLine));
        } catch (RuntimeException ignored) {
            // A subsequent read still fails closed when Redis itself is unavailable.
        }
    }

    public String rebuildLockKey(ProductLine productLine) {
        return keyPrefix() + ":rebuild-lock:" + productLine.name();
    }

    String stateKey(ProductLine productLine, long userId) {
        return userScope(productLine, userId) + ":state";
    }

    String marginKey(ProductLine productLine, long userId) {
        return userScope(productLine, userId) + ":margin";
    }

    String revisionKey(ProductLine productLine, long userId) {
        return userScope(productLine, userId) + ":revision";
    }

    String readyKey(ProductLine productLine) {
        return keyPrefix() + ":ready:" + productLine.name();
    }

    private void requireReady(ProductLine productLine) {
        if (!ready(productLine)) {
            throw new PositionCacheUnavailableException(
                    "position cache is not ready for product line " + productLine);
        }
    }

    private PositionResponse toResponse(CachedPosition position) {
        return new PositionResponse(
                position.userId(), position.symbol(),
                Optional.ofNullable(position.instrumentVersion()).orElse(0L),
                position.marginMode(), position.positionSide(), position.signedQuantitySteps(),
                position.entryPriceTicks(), position.realizedPnlUnits(), position.updatedAt());
    }

    private PositionResponse emptyPosition(long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           PositionSide positionSide) {
        return new PositionResponse(
                userId, symbol, 0L, MarginMode.defaultIfNull(marginMode), PositionSide.defaultIfNull(positionSide),
                0L, 0L, 0L, Instant.EPOCH);
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception ex) {
            throw new PositionCacheUnavailableException("invalid Redis position cache value", ex);
        }
    }

    private String field(String symbol, MarginMode marginMode, PositionSide positionSide) {
        return symbol + "|" + MarginMode.defaultIfNull(marginMode).name()
                + "|" + PositionSide.defaultIfNull(positionSide).name();
    }

    private String userScope(ProductLine productLine, long userId) {
        return keyPrefix() + ":{" + productLine.name() + ":" + userId + "}";
    }

    private String keyPrefix() {
        String configured = properties.getPositionCache().getKeyPrefix();
        return configured == null || configured.isBlank() ? "surprising:position:v1" : configured.trim();
    }

    private void validateConfiguration() {
        AccountProperties.PositionCache cache = properties.getPositionCache();
        if (cache == null) {
            throw new IllegalArgumentException("position cache configuration is required");
        }
        if (cache.getReadyTtl() == null || cache.getReadyTtl().isZero() || cache.getReadyTtl().isNegative()) {
            throw new IllegalArgumentException("position cache readyTtl must be positive");
        }
    }
}
