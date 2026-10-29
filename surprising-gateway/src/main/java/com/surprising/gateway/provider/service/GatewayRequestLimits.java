package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.config.GatewayLimitProperties;
import com.surprising.gateway.provider.config.GatewayLimitProperties.Group;
import com.surprising.gateway.provider.config.GatewayLimitProperties.Rule;
import com.surprising.product.api.ProductLine;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** Gateway admission only; no limiter I/O or counters enter the deterministic trading Core. */
@Service
public final class GatewayRequestLimits {
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
        local clock=redis.call('TIME')
        local now=clock[1]*1000+math.floor(clock[2]/1000)
        local remaining={}
        local wait=0
        for i,key in ipairs(KEYS) do
          local offset=(i-1)*5
          local rate=tonumber(ARGV[offset+1])
          local period=tonumber(ARGV[offset+4])
          local capacity=tonumber(ARGV[offset+2])*period
          local cost=tonumber(ARGV[offset+3])*period
          local fixed=ARGV[offset+5]=='1'
          local window=math.floor(now/period)
          if fixed then capacity=rate*period end
          local stored=redis.call('HMGET',key,'tokens','time','window')
          local tokens=capacity
          if stored[1] then
            if fixed then
              if tonumber(stored[3])==window then tokens=tonumber(stored[1]) end
            else tokens=math.min(capacity,tonumber(stored[1])+math.min(math.max(0,now-tonumber(stored[2])),math.ceil(capacity/rate))*rate) end
          end
          remaining[i]=tokens-cost
          if tokens<cost then
            local delay=fixed and ((window+1)*period-now) or math.ceil((cost-tokens)/rate)
            wait=math.max(wait,delay)
          end
        end
        if wait>0 then return wait end
        for i,key in ipairs(KEYS) do
          local offset=(i-1)*5
          local period=tonumber(ARGV[offset+4])
          if ARGV[offset+5]=='1' then
            local window=math.floor(now/period)
            redis.call('HSET',key,'tokens',remaining[i],'time',now,'window',window)
            redis.call('PEXPIRE',key,(window+1)*period-now+1000)
          else
            redis.call('HSET',key,'tokens',remaining[i],'time',now)
            redis.call('PEXPIRE',key,math.ceil(tonumber(ARGV[offset+2])*period/tonumber(ARGV[offset+1]))+1000)
          end
        end
        return 0
        """, Long.class);
    private static final DefaultRedisScript<Long> SOCKET = new DefaultRedisScript<>("""
        local clock=redis.call('TIME')
        local now=clock[1]*1000+math.floor(clock[2]/1000)
        redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now)
        if not redis.call('ZSCORE',KEYS[1],ARGV[1]) and redis.call('ZCARD',KEYS[1])>=tonumber(ARGV[2]) then
          local first=redis.call('ZRANGE',KEYS[1],0,0,'WITHSCORES')
          return math.max(1,math.ceil(tonumber(first[2])-now))
        end
        redis.call('ZADD',KEYS[1],now+tonumber(ARGV[3]),ARGV[1])
        redis.call('PEXPIRE',KEYS[1],tonumber(ARGV[3])+1000)
        return 0
        """, Long.class);

    public enum Reason { RATE_LIMITED, REQUEST_EXCEEDS_RATE_BURST, GATEWAY_BUSY, RATE_LIMIT_STORAGE_UNAVAILABLE, WEBSOCKET_CONNECTION_LIMIT }
    public static final class Rejection extends RuntimeException {
        private final Reason reason;
        private final String group;
        private final long retryAfterMillis;
        public Rejection(Reason reason, String group, long retryAfterMillis) {
            super(reason.name()); this.reason = reason; this.group = group; this.retryAfterMillis = retryAfterMillis;
        }
        public Reason reason() { return reason; }
        public String group() { return group; }
        public long retryAfterSeconds() { return Math.max(1, (retryAfterMillis + 999) / 1000); }
    }

    private record Charge(String key, Rule rule, int cost, boolean fixedWindow) {
        Charge(String key, Rule rule, int cost) { this(key, rule, cost, false); }
    }
    private final StringRedisTemplate redis;
    private final GatewayLimitProperties properties;
    private final MeterRegistry metrics;
    private final Semaphore requests;
    private final Semaphore cancels;
    private final Semaphore receipts;
    private final Semaphore sockets;
    private final Semaphore uploads;
    private final String instanceId = UUID.randomUUID().toString();

    public GatewayRequestLimits(StringRedisTemplate redis, GatewayLimitProperties properties, MeterRegistry metrics) {
        this.redis = redis; this.properties = properties; this.metrics = metrics;
        requests = new Semaphore(properties.getConcurrentRequests());
        cancels = new Semaphore(properties.getConcurrentCancels());
        receipts = new Semaphore(properties.getConcurrentReceipts());
        sockets = new Semaphore(properties.getSocketsPerGateway());
        uploads = new Semaphore(properties.getConcurrentUploads());
    }

    public void checkIp(String ip, int cost, boolean login) {
        String actor = properties.getNamespace() + ":{ip:" + ip + "}:";
        var charges = new ArrayList<Charge>();
        charges.add(new Charge(actor + "requests", properties.getIp(), cost));
        if (login) charges.add(new Charge(actor + "login", properties.getLoginIp(), 1));
        consume(charges, "IP");
    }

    public void checkLoginAccount(String identifier) {
        if (!properties.isEnabled()) return;
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identifier.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
            consume(List.of(new Charge(properties.getNamespace() + ":{login:" + digest + "}", properties.getLoginAccount(), 1)), "LOGIN");
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public void checkPublicRequest(String ip, String path, boolean write, Group group) {
        String reserve = switch (group) { case CANCEL, CANCEL_ALL -> "cancel"; case RECEIPT -> "receipt"; default -> "requests"; };
        Rule global = reserve.equals("requests") ? properties.getPublicRequests() : properties.getReservedRequests();
        consume(List.of(new Charge(properties.getNamespace() + ":{public}:" + reserve, global, 1)), "PUBLIC");
        if (!write) return;
        Rule rule;
        String operation;
        if (path.equals("/api/v1/auth/register")) { rule = properties.getRegistrationIp(); operation = "REGISTER"; }
        else if (path.equals("/api/v1/auth/refresh") || path.equals("/api/v1/auth/browser/refresh")) {
            rule = properties.getRefreshIp(); operation = "REFRESH";
        } else if (path.startsWith("/api/v1/auth/") && !path.equals("/api/v1/auth/login")
                && !path.endsWith("/logout")) { rule = properties.getVerificationIp(); operation = "VERIFICATION"; }
        else return;
        consume(List.of(new Charge(properties.getNamespace() + ":{ip:" + ip + "}:" + operation, rule, 1)), operation);
    }

    /** One shared destination budget across all email purposes and all Gateway nodes. */
    public void checkEmail(String recipient) {
        String actor = properties.getNamespace() + ":{email}:";
        String destination = digest(recipient.strip().toLowerCase(Locale.ROOT));
        consume(List.of(new Charge(actor + destination + ":cooldown", properties.getEmailCooldown(), 1),
                new Charge(actor + destination + ":hour", properties.getEmailHourly(), 1, true),
                new Charge(actor + destination + ":day", properties.getEmailDaily(), 1, true),
                new Charge(actor + "provider", properties.getEmailGlobal(), 1),
                new Charge(actor + "provider:day", properties.getEmailGlobal(), 1, true)), "EMAIL");
    }

    public void checkUpload(long user, long bytes) {
        int kibibytes = Math.toIntExact(Math.max(1, (bytes + 1023) / 1024));
        String actor = properties.getNamespace() + ":{uploads}:";
        consume(List.of(new Charge(actor + user + ":count", properties.getUploadCount(), 1, true),
                new Charge(actor + user + ":kib", properties.getUploadKibibytes(), kibibytes, true),
                new Charge(actor + "global-kib", properties.getUploadGlobalKibibytes(), kibibytes, true)), "UPLOAD");
    }

    public void checkUploadVerification(long user) {
        String actor = properties.getNamespace() + ":{uploads}:";
        consume(List.of(new Charge(actor + user + ":verify", properties.getUploadVerification(), 1),
                new Charge(actor + "global-verify", properties.getUploadGlobalVerification(), 1)), "UPLOAD_VERIFY");
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public void checkUser(long user, String apiKey, Group group, ProductLine product, int cost) {
        String actor = properties.getNamespace() + ":{user:" + user + "}:";
        var charges = new ArrayList<Charge>();
        charges.add(new Charge(actor + group, properties.getUser().get(group), cost));
        if (apiKey != null) charges.add(new Charge(actor + group + ":key:" + apiKey, properties.getApiKey().get(group), cost));
        if (product != null && properties.getProduct().containsKey(group))
            charges.add(new Charge(actor + group + ":product:" + product, properties.getProduct().get(group), cost));
        consume(charges, group.name());
    }

    private void consume(List<Charge> charges, String group) {
        if (!properties.isEnabled()) return;
        var keys = new ArrayList<String>(charges.size()); var args = new ArrayList<String>(charges.size() * 5);
        for (var charge : charges) {
            if (charge.cost() < 1 || charge.cost() > charge.rule().burst())
                throw new Rejection(Reason.REQUEST_EXCEEDS_RATE_BURST, group, 1000);
            keys.add(charge.key() + (charge.fixedWindow() ? ":w" : ":p") + charge.rule().period().toMillis()); args.add(Integer.toString(charge.rule().rate()));
            args.add(Integer.toString(charge.rule().burst())); args.add(Integer.toString(charge.cost()));
            args.add(Long.toString(charge.rule().period().toMillis())); args.add(charge.fixedWindow() ? "1" : "0");
        }
        try {
            Long wait = redis.execute(CONSUME, keys, args.toArray());
            if (wait == null) throw unavailable(group);
            if (wait > 0) {
                metrics.counter("gateway.limits.rejected", "group", group).increment();
                throw new Rejection(Reason.RATE_LIMITED, group, wait);
            }
        } catch (DataAccessException failure) { throw unavailable(group); }
    }

    public AutoCloseable admit(Group group) {
        if (!properties.isEnabled()) return () -> {};
        Semaphore permits = switch (group) {
            case CANCEL, CANCEL_ALL -> cancels;
            case RECEIPT -> receipts;
            case UPLOAD -> uploads;
            default -> requests;
        };
        if (!permits.tryAcquire()) throw new Rejection(Reason.GATEWAY_BUSY, group.name(), 1000);
        var released = new AtomicBoolean();
        return () -> { if (released.compareAndSet(false, true)) permits.release(); };
    }

    public AutoCloseable admitSocket() {
        if (!properties.isEnabled()) return () -> {};
        if (!sockets.tryAcquire()) throw new Rejection(Reason.GATEWAY_BUSY, "WEBSOCKET", 1000);
        var released = new AtomicBoolean();
        return () -> { if (released.compareAndSet(false, true)) sockets.release(); };
    }

    public void checkNewSocket(String ip) {
        consume(List.of(new Charge(properties.getNamespace() + ":{ip:" + ip + "}:socket-opens", properties.getNewWebSocket(), 1)), "WEBSOCKET");
    }

    public void checkSocketAuthentication(String ip) {
        consume(List.of(new Charge(properties.getNamespace() + ":{ip:" + ip + "}:socket-auth",
                properties.getSocketAuthentication(), 1)), "WEBSOCKET_AUTH");
    }

    public void checkSocketMessage(com.surprising.websocket.provider.session.ClientConnection connection) {
        if (!properties.isEnabled()) return;
        var rule = properties.getSocketMessages();
        long wait = connection.consumeInbound(rule.rate(), rule.burst(), rule.period().toMillis());
        if (wait > 0) throw new Rejection(Reason.RATE_LIMITED, "WEBSOCKET", Math.max(wait, refillMillis(rule)));
        if (connection.limitRefreshDue(System.nanoTime()))
            connection.renewLimits(renewSocket(connection.clientIp(), connection.authenticatedUserId(), connection.id()));
        if (connection.authenticatedUserId() != null) {
            try { checkUser(connection.authenticatedUserId(), connection.apiKey(), Group.WEBSOCKET, null, 1); }
            catch (Rejection failure) {
                if (failure.reason() != Reason.RATE_LIMITED) throw failure;
                // A reconnect restores all subscriptions, so wait for a full bucket rather than one message.
                long recovery = refillMillis(properties.getUser().get(Group.WEBSOCKET));
                if (connection.apiKey() != null) recovery = Math.max(recovery, refillMillis(properties.getApiKey().get(Group.WEBSOCKET)));
                throw new Rejection(failure.reason(), failure.group(), Math.max(failure.retryAfterMillis, recovery));
            }
        }
    }

    private static long refillMillis(Rule rule) {
        return ((long) rule.burst() * rule.period().toMillis() + rule.rate() - 1) / rule.rate();
    }

    public long renewSocket(String ip, Long user, String connectionId) {
        long started = System.nanoTime();
        if (!properties.isEnabled()) return Long.MAX_VALUE;
        socket(properties.getNamespace() + ":{ip:" + ip + "}:sockets", connectionId, properties.getSocketsPerIp());
        if (user != null) socket(properties.getNamespace() + ":{user:" + user + "}:sockets", connectionId, properties.getSocketsPerUser());
        return started + properties.getSocketLeaseSeconds() * 1_000_000_000L;
    }

    private void socket(String key, String id, int capacity) {
        try {
            Long wait = redis.execute(SOCKET, List.of(key), instanceId + ":" + id, Integer.toString(capacity),
                    Integer.toString(properties.getSocketLeaseSeconds() * 1000));
            if (wait == null) throw unavailable("WEBSOCKET");
            if (wait > 0) throw new Rejection(Reason.WEBSOCKET_CONNECTION_LIMIT, "WEBSOCKET", wait);
        } catch (DataAccessException failure) { throw unavailable("WEBSOCKET"); }
    }

    public void releaseSocket(String ip, Long user, String connectionId) {
        if (!properties.isEnabled()) return;
        try {
            redis.opsForZSet().remove(properties.getNamespace() + ":{ip:" + ip + "}:sockets", instanceId + ":" + connectionId);
            if (user != null) redis.opsForZSet().remove(properties.getNamespace() + ":{user:" + user + "}:sockets", instanceId + ":" + connectionId);
        } catch (DataAccessException failure) {
            // Expiring membership bounds cleanup after an unavailable store or a crashed Gateway.
            metrics.counter("gateway.limits.unavailable", "group", "WEBSOCKET_CLEANUP").increment();
        }
    }

    private Rejection unavailable(String group) {
        metrics.counter("gateway.limits.unavailable", "group", group).increment();
        return new Rejection(Reason.RATE_LIMIT_STORAGE_UNAVAILABLE, group, 1000);
    }
}
