package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;
import com.surprising.trading.order.repository.TradingMetricsRepository;
import java.math.BigInteger;
import java.time.Instant;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 校验Core统计快照的身份和新鲜度，只截取榜单而不改变统计总量或大整数。 */
@Service
public class TradingMetricsService {
    private final TradingMetricsRepository repository;
    private final ObjectMapper json;
    public TradingMetricsService(TradingMetricsRepository repository, ObjectMapper json) { this.repository = repository; this.json = json; }
    public ObjectNode require(ProductLine product, int window, int limit) {
        var row = repository.find(product, window).orElseThrow(() -> new ReadViewUnavailableException("TRADING_METRICS_INITIALIZING"));
        long now = System.currentTimeMillis();
        if (row.generatedAtEpochMs() > now + 1000 || now - row.generatedAtEpochMs() > 180000)
            throw new ReadViewUnavailableException("TRADING_METRICS_STALE");
        try {
            var data = (ObjectNode) json.readTree(row.metricsJson());
            if (!"committed-trading-v1".equals(data.path("schema").asText()) || !product.name().equals(data.path("productLine").asText())
                    || number(data.get("asOfLogPosition")) != row.clusterPosition() || number(data.get("windowMinutes")) != window
                    || Instant.parse(data.get("generatedAt").asText()).toEpochMilli() != row.generatedAtEpochMs())
                throw new IllegalStateException("statistics identity mismatch");
            requireNumbers(data, "orders", "submittedCommands", "rejectedCommands", "rejectRatePpm", "uniqueUsers", "openOrders");
            requireNumbers(data, "commands", "total", "rejected", "rejectRatePpm", "uniqueAccounts");
            requireNumbers(data, "trades", "trades", "uniqueParticipants");
            requireNumbers(data, "positions", "usersWithPositions", "openPositions", "symbolCount");
            requireNumbers(data, "triggerOrders", "pending", "expiredPending");
            data.set("symbols", first((ArrayNode) data.get("symbols"), limit));
            var positions = (ObjectNode) data.get("positions");
            positions.set("symbols", first((ArrayNode) positions.get("symbols"), limit));
            data.put("limit", limit);
            data.put("dataAgeMs", Math.max(0, now - row.generatedAtEpochMs()));
            return data;
        } catch (RuntimeException invalid) {
            throw new ReadViewUnavailableException("TRADING_METRICS_INVALID", invalid);
        }
    }
    private ArrayNode first(ArrayNode source, int limit) {
        var result = json.createArrayNode();
        for (int i = 0; i < Math.min(limit, source.size()); i++) result.add(source.get(i));
        return result;
    }
    private static long number(JsonNode value) { return new BigInteger(value.asText()).longValueExact(); }
    private static void requireNumbers(ObjectNode data, String section, String... fields) {
        if (!(data.get(section) instanceof ObjectNode values)) throw new IllegalArgumentException("statistics section missing");
        for (String field : fields) if (new BigInteger(values.get(field).asText()).signum() < 0)
            throw new IllegalArgumentException("invalid statistics count");
    }
}
