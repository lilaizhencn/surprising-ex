package com.surprising.realtime.api;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.*;

/** Derived, rebuildable read view. Every mutation is atomic within one user hash slot. */
public final class ValkeyReadViewStore {
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> DELTA =
            new DefaultRedisScript<>(
                    """
                    local fence=redis.call('HGET',KEYS[1],'@fence')
                    local count=0
                    for i=2,#ARGV,3 do
                     local prior=redis.call('HGET',KEYS[2],ARGV[i])
                     if not ((fence and fence>=ARGV[i+1]) or (prior and prior>=ARGV[i+1])) then
                      if not prior and redis.call('HLEN',KEYS[2])>=8192 then
                       redis.call('HSET',KEYS[1],'@invalid','capacity')
                      else
                       redis.call('HSET',KEYS[1],ARGV[i],ARGV[i+2]);redis.call('HSET',KEYS[2],ARGV[i],ARGV[i+1]);count=count+1
                      end
                     end
                    end
                    redis.call('EXPIRE',KEYS[1],3600);redis.call('EXPIRE',KEYS[2],3600)
                    local watermark=redis.call('HGET',KEYS[1],'@exportSequence')
                    if not watermark or watermark<ARGV[1] then redis.call('HSET',KEYS[1],'@exportSequence',ARGV[1]) end
                    return count
                    """,
                    Long.class);
    private static final DefaultRedisScript<Long> SNAPSHOT =
            new DefaultRedisScript<>(
                    """
                    local fence=redis.call('HGET',KEYS[1],'@fence')
                    if fence and fence>ARGV[1] then return 0 end
                    local fields=redis.call('HGETALL',KEYS[2])
                    for i=1,#fields,2 do
                     if fields[i+1]<=ARGV[1] then redis.call('HDEL',KEYS[1],fields[i]);redis.call('HDEL',KEYS[2],fields[i]) end
                    end
                    for i=5,#ARGV,2 do
                     local prior=redis.call('HGET',KEYS[2],ARGV[i])
                     if not prior or prior<=ARGV[1] then
                      redis.call('HSET',KEYS[1],ARGV[i],ARGV[i+1]);redis.call('HSET',KEYS[2],ARGV[i],ARGV[1])
                     end
                    end
                    local watermark=redis.call('HGET',KEYS[1],'@exportSequence')
                    if not watermark or watermark<ARGV[3] then redis.call('HSET',KEYS[1],'@exportSequence',ARGV[3]) end
                    redis.call('HDEL',KEYS[1],'@invalid')
                    redis.call('HSET',KEYS[1],'@fence',ARGV[1],'@snapshotAt',ARGV[2],'@epoch',ARGV[4])
                    redis.call('EXPIRE',KEYS[1],3600);redis.call('EXPIRE',KEYS[2],3600)
                    return 1
                    """,
                    Long.class);

    public ValkeyReadViewStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void unavailable(ProductLine product, long user) {
        redis.opsForHash().put(keys(product, user).getFirst(), "@invalid", "snapshot_unavailable");
        redis.expire(keys(product, user).getFirst(), java.time.Duration.ofHours(1));
    }

    public boolean apply(RealtimeFrame frame) {
        return applyBatch(List.of(frame)) > 0;
    }

    public long applyBatch(List<RealtimeFrame> frames) {
        return applyBatch(frames, 0);
    }

    public long applyBatch(List<RealtimeFrame> frames, long exportSequence) {
        if (frames.isEmpty()) return 0;
        var first = frames.getFirst();
        var args = new ArrayList<String>(frames.size() * 3 + 1);
        args.add(RealtimeVersion.of(exportSequence, 0));
        for (var frame : frames) {
            if (frame.userId() == 0
                    || frame.snapshotId() != 0
                    || frame.userId() != first.userId()
                    || frame.productLine() != first.productLine())
                throw new IllegalArgumentException("mixed user transaction");
            args.add(field(frame));
            args.add(RealtimeVersion.of(frame.sequence(), frame.ordinal()));
            args.add(encode(frame));
        }
        Long applied =
                redis.execute(DELTA, keys(first.productLine(), first.userId()), args.toArray());
        return applied == null ? 0 : applied;
    }

    public boolean install(List<RealtimeFrame> snapshot, long receivedAt) {
        return install(snapshot, receivedAt, "0");
    }

    public void sourceEpoch(ProductLine product, String epoch) {
        redis.opsForValue().set("rt:source:" + product.name(), epoch);
    }

    public boolean install(List<RealtimeFrame> snapshot, long receivedAt, String epoch) {
        if (snapshot.size() < 3) throw new IllegalArgumentException("incomplete snapshot");
        var begin = snapshot.getFirst();
        var end = snapshot.getLast();
        if (snapshot.get(1).kind() != RealtimeFrame.Kind.USER)
            throw new IllegalArgumentException("missing user snapshot");
        var identities = new java.util.HashSet<String>();
        if (begin.kind() != RealtimeFrame.Kind.SNAPSHOT_BEGIN
                || end.kind() != RealtimeFrame.Kind.SNAPSHOT_END)
            throw new IllegalArgumentException("missing snapshot fence");
        var args = new ArrayList<String>();
        args.add(RealtimeVersion.fence(begin.sequence()));
        args.add(Long.toString(receivedAt));
        args.add(RealtimeVersion.of(exportSequence(end), 0));
        args.add(epoch);
        for (int i = 0; i < snapshot.size(); i++) {
            var f = snapshot.get(i);
            if (f.userId() != begin.userId()
                    || f.productLine() != begin.productLine()
                    || f.snapshotId() != begin.snapshotId()
                    || f.sequence() != begin.sequence()
                    || f.ordinal() != i)
                throw new IllegalArgumentException("snapshot gap or identity mismatch");
            if (i > 0 && i < snapshot.size() - 1) {
                if (!identities.add(field(f)))
                    throw new IllegalArgumentException("duplicate snapshot entity");
                args.add(field(f));
                args.add(encode(f));
            }
        }
        return Long.valueOf(1)
                .equals(
                        redis.execute(
                                SNAPSHOT,
                                keys(begin.productLine(), begin.userId()),
                                args.toArray()));
    }

    public ReadView read(ProductLine product, long user, long now, long maxAgeMillis) {
        Map<Object, Object> values = redis.opsForHash().entries(keys(product, user).getFirst());
        if (!values.containsKey("@fence")) return new ReadView("INITIALIZING", "", 0, List.of());
        String epoch = redis.opsForValue().get("rt:source:" + product.name());
        boolean gap =
                !Objects.equals(values.getOrDefault("@epoch", "0"), epoch == null ? "0" : epoch);
        long snapshotAt = Long.parseLong(values.get("@snapshotAt").toString());
        var frames = new ArrayList<RealtimeFrame>();
        values.forEach(
                (k, v) -> {
                    if (!k.toString().startsWith("@"))
                        frames.add(
                                RealtimeFrameCodec.decode(
                                        Base64.getDecoder().decode(v.toString())));
                });
        frames.sort(
                Comparator.comparingLong(RealtimeFrame::sequence)
                        .thenComparingInt(RealtimeFrame::ordinal));
        return new ReadView(
                gap || values.containsKey("@invalid") || now - snapshotAt > maxAgeMillis
                        ? "STALE"
                        : "READY",
                values.get("@fence").toString(),
                snapshotAt,
                List.copyOf(frames),
                values.getOrDefault("@exportSequence", RealtimeVersion.of(0, 0)).toString());
    }

    public record ReadView(
            String status,
            String snapshotVersion,
            long snapshotAt,
            List<RealtimeFrame> frames,
            String exportVersion) {
        public ReadView(
                String status,
                String snapshotVersion,
                long snapshotAt,
                List<RealtimeFrame> frames) {
            this(status, snapshotVersion, snapshotAt, frames, RealtimeVersion.of(0, 0));
        }

        public long exportSequence() {
            return Long.parseLong(exportVersion.substring(0, 19));
        }
    }

    public static long exportSequence(RealtimeFrame end) {
        return end.payloadLength() == 8
                ? java.nio.ByteBuffer.wrap(end.payload())
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .getLong()
                : 0;
    }

    private static String field(RealtimeFrame f) {
        return f.kind() + ":" + f.entityId();
    }

    private static String encode(RealtimeFrame f) {
        return Base64.getEncoder().encodeToString(RealtimeFrameCodec.encode(f));
    }

    private static List<String> keys(ProductLine p, long u) {
        if (p == null || u <= 0) throw new IllegalArgumentException("invalid read view identity");
        String key = "rt:view:{" + p.name() + ":" + u + "}";
        return List.of(key, key + ":versions");
    }
}
