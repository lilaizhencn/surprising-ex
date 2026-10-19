package com.surprising.realtime.api;

import com.surprising.aeron.protocol.CoreOrderBookView;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.protocol.RealtimeFrameCodec;
import com.surprising.product.api.InstrumentIds;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.Base64;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Router owns one expiring full book per product/instrument; readers never query trading Core. */
public final class ValkeyMarketData {
    private static final long MAX_AGE_MILLIS = 5_000;
    private final StringRedisTemplate redis;
    private final ValkeySnapshotRequests requests;

    public ValkeyMarketData(StringRedisTemplate redis) {
        this.redis = redis;
        requests = new ValkeySnapshotRequests(redis);
    }

    /** Called only by the router after it has assembled the complete BOOK commit. */
    public void install(RealtimeFrame frame, String epoch, long now) {
        if (frame.kind() != RealtimeFrame.Kind.BOOK || frame.userId() != 0
                || !InstrumentIds.valid(frame.instrumentId()) || epoch == null)
            throw new IllegalArgumentException("invalid public book");
        String value = epoch + "\n" + now + "\n"
                + Base64.getEncoder().encodeToString(RealtimeFrameCodec.encode(frame));
        redis.opsForValue().set(key(frame.productLine(), frame.instrumentId()), value,
                Duration.ofMillis(MAX_AGE_MILLIS));
    }

    public Book requireBook(ProductLine product, String instrumentId) {
        if (product == null || !InstrumentIds.valid(instrumentId))
            throw new IllegalArgumentException("invalid book identity");
        long now = System.currentTimeMillis();
        try {
            // REST-only consumers also renew demand; the router coalesces it with WS demand.
            requests.renewBook(product, instrumentId, now + 30_000);
            String value = redis.opsForValue().get(key(product, instrumentId));
            String epoch = redis.opsForValue().get("rt:source:" + product.name());
            if (value == null || epoch == null) throw unavailable();
            String[] stored = value.split("\n", 3);
            if (stored.length != 3 || !stored[0].equals(epoch)) throw unavailable();
            long receivedAt = Long.parseLong(stored[1]);
            long readAt = System.currentTimeMillis();
            if (receivedAt > readAt || readAt - receivedAt >= MAX_AGE_MILLIS) throw unavailable();
            var frame = RealtimeFrameCodec.decode(Base64.getDecoder().decode(stored[2]));
            if (frame.productLine() != product || frame.kind() != RealtimeFrame.Kind.BOOK
                    || frame.userId() != 0 || !frame.instrumentId().equals(instrumentId)) throw unavailable();
            var book = CoreStateQueryCodec.decodeOrderBookView(frame.payload());
            if (book.levels().stream().anyMatch(level -> !instrumentId.equals(level.instrumentId())))
                throw unavailable();
            return new Book(book, receivedAt);
        } catch (org.springframework.dao.DataAccessException | IllegalArgumentException | java.nio.BufferUnderflowException invalid) {
            throw unavailable();
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_BOOK_UNAVAILABLE");
    }

    private static String key(ProductLine product, String instrumentId) {
        return "rt:book:{" + product.name() + ":" + instrumentId + "}";
    }

    public record Book(CoreOrderBookView view, long refreshedAt) {}
}
