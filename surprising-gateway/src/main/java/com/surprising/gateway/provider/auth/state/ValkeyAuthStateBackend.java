package com.surprising.gateway.provider.auth.state;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

@Component
public class ValkeyAuthStateBackend implements AuthStateBackend {

    private static final Logger log = LoggerFactory.getLogger(ValkeyAuthStateBackend.class);

    private static final DefaultRedisScript<Long> HASH_PUT = new DefaultRedisScript<>("""
            redis.call('DEL',KEYS[1])
            for i=2,#ARGV,2 do redis.call('HSET',KEYS[1],ARGV[i],ARGV[i+1]) end
            local ttl=tonumber(ARGV[1])
            if ttl>0 then redis.call('PEXPIRE',KEYS[1],ttl) end
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RENEW_SESSION = new DefaultRedisScript<>("""
            if redis.call('EXISTS',KEYS[1])==0 then return 0 end
            redis.call('HSET',KEYS[1],'renewedAt',ARGV[2])
            redis.call('PEXPIRE',KEYS[1],ARGV[1])
            if redis.call('HGET',KEYS[2],'persistent')~='1' then redis.call('PEXPIRE',KEYS[2],ARGV[1]) end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private RedisMessageListenerContainer container;

    public ValkeyAuthStateBackend(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Map<String, String> hashGet(String key) {
        return call(() -> {
            Map<Object, Object> entries = redis.opsForHash().entries(key);
            var result = new java.util.HashMap<String, String>(entries.size());
            entries.forEach((field, value) -> result.put(field.toString(), value.toString()));
            return result;
        });
    }

    @Override
    public void hashPut(String key, Map<String, String> fields, Duration ttl) {
        List<String> args = new ArrayList<>();
        args.add(Long.toString(ttl == null ? 0 : Math.max(1, ttl.toMillis())));
        fields.forEach((field, value) -> {
            if (value != null) {
                args.add(field);
                args.add(value);
            }
        });
        call(() -> redis.execute(HASH_PUT, List.of(key), args.toArray()));
    }

    @Override
    public boolean exists(String key) {
        return call(() -> Boolean.TRUE.equals(redis.hasKey(key)));
    }

    @Override
    public void delete(String... keys) {
        call(() -> redis.delete(List.of(keys)));
    }

    @Override
    public boolean renewSession(String sessionKey, String userKey, Duration ttl, long nowMillis) {
        Long renewed = call(() -> redis.execute(RENEW_SESSION, List.of(sessionKey, userKey),
                Long.toString(ttl.toMillis()), Long.toString(nowMillis)));
        return renewed != null && renewed == 1L;
    }

    @Override
    public void publish(String channel, String message) {
        call(() -> redis.convertAndSend(channel, message));
    }

    @Override
    public synchronized void subscribe(String channel, Consumer<String> listener) {
        try {
            if (container == null) {
                container = new RedisMessageListenerContainer();
                container.setConnectionFactory(redis.getConnectionFactory());
                container.afterPropertiesSet();
                container.start();
            }
            MessageListener adapter = (message, pattern) ->
                    listener.accept(new String(message.getBody(), StandardCharsets.UTF_8));
            container.addMessageListener(adapter, new ChannelTopic(channel));
        } catch (RuntimeException ex) {
            log.warn("auth state invalidation subscription failed; local cache expiry still bounds staleness", ex);
        }
    }

    @jakarta.annotation.PreDestroy
    synchronized void stop() throws Exception {
        if (container != null) container.destroy();
    }

    private <T> T call(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException ex) {
            throw new AuthStateUnavailableException("auth state store is unavailable", ex);
        }
    }
}
