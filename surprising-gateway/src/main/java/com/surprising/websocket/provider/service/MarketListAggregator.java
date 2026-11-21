package com.surprising.websocket.provider.service;

import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.provider.service.InstrumentService;
import com.surprising.product.api.ProductLine;
import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One quote per product line. Derivatives take the mark price; spot takes the last trade.
 * Subscribers receive that map as a single event, at most once a second.
 */
@Component
public final class MarketListAggregator {
    private static final int PRICE_SCALE = 12;
    private final SubscriptionRegistry registry;
    private final InstrumentService instruments;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, String> prices = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> spotTicks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ProductLine, String> published = new ConcurrentHashMap<>();

    public MarketListAggregator(SubscriptionRegistry registry, InstrumentService instruments, ObjectMapper mapper) {
        this.registry = registry;
        this.instruments = instruments;
        this.mapper = mapper;
    }

    public void observe(RealtimeFrame frame) {
        if (frame == null || frame.instrumentId() == null || frame.instrumentId().isBlank()) return;
        try {
            if (frame.kind() == RealtimeFrame.Kind.MARK && frame.productLine() != ProductLine.SPOT) {
                JsonNode node = mapper.readTree(new String(frame.payload(), StandardCharsets.UTF_8));
                String status = node.path("status").asString("");
                if (!status.isEmpty() && !usableMark(status)) return;
                BigDecimal mark = decimal(node.get("markPrice"));
                if (mark == null || mark.signum() <= 0) return;
                prices.put(key(frame.productLine(), frame.instrumentId()), plain(mark));
            } else if (frame.kind() == RealtimeFrame.Kind.TRADE && frame.productLine() == ProductLine.SPOT) {
                if (frame.payload().length < Long.BYTES) return;
                long ticks = ByteBuffer.wrap(frame.payload()).order(ByteOrder.LITTLE_ENDIAN).getLong();
                if (ticks > 0) spotTicks.put(key(frame.productLine(), frame.instrumentId()), ticks);
            }
        } catch (RuntimeException ignored) {
            // A bad peripheral frame must not stop the list or the other channels.
        }
    }

    /** New subscribers get the prices already folded, without waiting for the next second. */
    public void arm(SubscriptionTopic topic) {
        if (topic.channel() != WsChannel.MARKET_LIST || topic.productLine() == null) return;
        List<Map<String, String>> quotes = quotes(topic.productLine());
        if (quotes.isEmpty()) return;
        registry.publish(topic, Map.of("quotes", quotes), Instant.now());
        published.put(topic.productLine(), fingerprint(quotes));
    }

    public void publish() {
        List<SubscriptionTopic> topics = registry.topics(WsChannel.MARKET_LIST);
        if (topics.isEmpty()) return;
        foldSpotTrades();
        Instant now = Instant.now();
        for (SubscriptionTopic topic : topics) {
            if (topic.productLine() == null) continue;
            List<Map<String, String>> quotes = quotes(topic.productLine());
            String fingerprint = fingerprint(quotes);
            if (fingerprint.equals(published.get(topic.productLine()))) continue;
            registry.publish(topic, Map.of("quotes", quotes), now);
            published.put(topic.productLine(), fingerprint);
        }
    }

    private void foldSpotTrades() {
        if (spotTicks.isEmpty()) return;
        Map<String, long[]> encoding = spotEncoding();
        for (var entry : spotTicks.entrySet()) {
            long[] factors = encoding.get(entry.getKey());
            if (factors == null) continue;
            BigDecimal price = BigDecimal.valueOf(entry.getValue())
                    .multiply(BigDecimal.valueOf(factors[0]))
                    .divide(BigDecimal.valueOf(factors[1]), PRICE_SCALE, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
            if (price.signum() > 0) prices.put(entry.getKey(), price.toPlainString());
            spotTicks.remove(entry.getKey(), entry.getValue());
        }
    }

    private Map<String, long[]> spotEncoding() {
        try {
            var snapshot = instruments.snapshot(ProductLine.SPOT);
            Map<String, long[]> encoding = new LinkedHashMap<>();
            for (InstrumentResponse instrument : snapshot.instruments()) {
                Long scale = snapshot.assetScales().get(instrument.quoteAsset());
                if (scale == null || scale <= 0 || instrument.priceTickUnits() <= 0) continue;
                encoding.put(key(ProductLine.SPOT, Integer.toString(instrument.instrumentId())),
                        new long[] {instrument.priceTickUnits(), scale});
            }
            return encoding;
        } catch (RuntimeException unavailable) {
            return Map.of();
        }
    }

    private List<Map<String, String>> quotes(ProductLine product) {
        String prefix = product.name() + ":";
        List<Map<String, String>> quotes = new ArrayList<>();
        for (var entry : prices.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            quotes.add(Map.of("instrumentId", entry.getKey().substring(prefix.length()), "price", entry.getValue()));
        }
        quotes.sort((left, right) -> left.get("instrumentId").compareTo(right.get("instrumentId")));
        return quotes;
    }

    private static String fingerprint(List<Map<String, String>> quotes) {
        StringBuilder fingerprint = new StringBuilder();
        for (Map<String, String> quote : quotes)
            fingerprint.append(quote.get("instrumentId")).append('=').append(quote.get("price")).append(';');
        return fingerprint.toString();
    }

    private static boolean usableMark(String status) {
        return "HEALTHY".equals(status) || "DEGRADED".equals(status) || "CLAMPED".equals(status);
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        try {
            return node.isNumber() ? node.decimalValue() : new BigDecimal(node.asString());
        } catch (NumberFormatException | ArithmeticException invalid) {
            return null;
        }
    }

    private static String plain(BigDecimal price) {
        return price.stripTrailingZeros().toPlainString();
    }

    private static String key(ProductLine product, String instrumentId) {
        return product.name() + ":" + instrumentId;
    }
}
