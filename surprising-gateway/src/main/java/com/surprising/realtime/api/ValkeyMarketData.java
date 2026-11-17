package com.surprising.realtime.api;

import com.surprising.aeron.protocol.CoreOrderBookView;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.protocol.RealtimeFrameCodec;
import com.surprising.product.api.utils.InstrumentIds;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;

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
            String bookKey = key(product, instrumentId);
            String epochKey = "rt:source:" + product.name();
            List<String> results = redis.opsForValue().multiGet(List.of(bookKey, epochKey));
            if (results == null || results.size() < 2) throw unavailable();
            String value = results.get(0);
            String epoch = results.get(1);
            if (value == null || epoch == null) throw unavailable();

            int firstNewline = value.indexOf('\n');
            if (firstNewline <= 0) throw unavailable();
            int secondNewline = value.indexOf('\n', firstNewline + 1);
            if (secondNewline <= firstNewline + 1) throw unavailable();

            String storedEpoch = value.substring(0, firstNewline);
            if (!storedEpoch.equals(epoch)) throw unavailable();

            long receivedAt = Long.parseLong(value.substring(firstNewline + 1, secondNewline));
            long readAt = System.currentTimeMillis();
            if (receivedAt > readAt || readAt - receivedAt >= MAX_AGE_MILLIS) throw unavailable();

            String payloadBase64 = value.substring(secondNewline + 1);
            var frame = RealtimeFrameCodec.decode(Base64.getDecoder().decode(payloadBase64));
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

    private static ReadViewUnavailableException unavailable() {
        return new ReadViewUnavailableException("MARKET_BOOK_UNAVAILABLE");
    }

    private static String key(ProductLine product, String instrumentId) {
        return "rt:book:{" + product.name() + ":" + instrumentId + "}";
    }

    public record Book(CoreOrderBookView view, long refreshedAt) {}
}
