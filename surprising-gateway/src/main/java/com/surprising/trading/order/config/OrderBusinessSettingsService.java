package com.surprising.trading.order.config;

import java.time.Duration;
import java.time.Instant;
import com.surprising.product.api.ProductLine;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 数据库保存订单业务规则；Core 合约同步将同一规则写入撮合核心。 */
@Service
public class OrderBusinessSettingsService {
    public record Snapshot(long configurationVersion, OrderBusinessSettings settings, String updatedBy, String reason, Instant updatedAt) {}
    public record Update(OrderBusinessSettings settings, Long expectedVersion, String reason) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TradingOrderProperties properties;
    private final ProductLine line;
    private volatile Snapshot current;
    public OrderBusinessSettingsService(JdbcTemplate jdbc, ObjectMapper json, TradingOrderProperties properties) {
        this.jdbc=jdbc;this.json=json;this.properties=properties;this.line=properties.getKafka().getProductLine();
    }
    @PostConstruct public void initialize() {
        jdbc.update("INSERT INTO order_business_settings(product_line,settings,version,updated_by,reason) VALUES (?,?::jsonb,1,'SYSTEM','initial order settings') ON CONFLICT DO NOTHING",line.name(),json.writeValueAsString(OrderBusinessSettings.initial()));
        reload();
    }
    public Snapshot current(ProductLine requested) {
        if (requested != line) throw new IllegalArgumentException("order settings product line mismatch");
        if (current == null) throw new IllegalStateException("order settings not initialized");
        return current;
    }
    public Update parseUpdate(tools.jackson.databind.JsonNode body) {
        requireKeys(body, java.util.Set.of("settings", "expectedVersion", "reason"));
        var settings=body.get("settings");requireKeys(settings,java.util.Set.of("risk","algo"));
        var risk=settings.get("risk");var algo=settings.get("algo");
        requireKeys(risk,java.util.Set.of("marketMaxSlippagePpm","marketMaxMarkAgeMs","limitPriceProtectionEnabled","limitPriceBandPpm","limitPriceMaxMarkAgeMs"));
        requireKeys(algo,java.util.Set.of("enabled","claimBatchSize","scanDelayMs","minIntervalSeconds","maxIntervalSeconds","minDurationSeconds","maxDurationSeconds","claimLeaseMs"));
        for(var section:java.util.List.of(risk,algo)) for(var entry:section.properties()) {
            boolean flag=entry.getKey().equals("enabled") || entry.getKey().equals("limitPriceProtectionEnabled");
            var value=entry.getValue();
            if(flag ? !value.isBoolean() : !value.isIntegralNumber() || !value.canConvertToLong())
                throw new IllegalArgumentException(entry.getKey()+" has invalid type");
        }
        if(!body.get("expectedVersion").isIntegralNumber() || !body.get("expectedVersion").canConvertToLong()
                || !body.get("reason").isString())throw new IllegalArgumentException("version and reason have invalid types");
        return json.treeToValue(body,Update.class);
    }
    private void requireKeys(tools.jackson.databind.JsonNode node, java.util.Set<String> expected) {
        if(node==null || !node.isObject() || !new java.util.HashSet<>(node.propertyNames()).equals(expected))
            throw new IllegalArgumentException("complete settings required; unknown keys rejected");
    }
    public synchronized Snapshot save(ProductLine requested, Update request, String admin) {
        current(requested);
        if (request == null || request.settings() == null || request.expectedVersion() == null || request.expectedVersion() < 1
                || admin == null || !admin.matches("[1-9][0-9]*") || request.reason() == null || request.reason().isBlank() || request.reason().length() > 1000)
            throw new IllegalArgumentException("complete settings, version, administrator and reason (1-1000 characters) required");
        var rows=jdbc.query("UPDATE order_business_settings SET settings=?::jsonb,version=version+1,updated_by=?,reason=?,updated_at=now() WHERE product_line=? AND version=? RETURNING *",
            (rs,n)->decode(rs),json.writeValueAsString(request.settings()),admin,request.reason().trim(),line.name(),request.expectedVersion());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"order settings changed; reload before saving");
        install(rows.getFirst());return current;
    }
    @Scheduled(fixedDelay=1000) public synchronized void reload() {
        var value=jdbc.queryForObject("SELECT * FROM order_business_settings WHERE product_line=?",(rs,n)->decode(rs),line.name());
        if(value==null)throw new IllegalStateException("order settings missing");
        if(current==null || value.configurationVersion()>current.configurationVersion())install(value);
    }
    private Snapshot decode(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Snapshot(rs.getLong("version"),json.readValue(rs.getString("settings"),OrderBusinessSettings.class),rs.getString("updated_by"),rs.getString("reason"),rs.getTimestamp("updated_at").toInstant());
    }
    private void install(Snapshot value) {
        var settings=value.settings();var risk=new TradingOrderProperties.Risk();var p=settings.risk();
        risk.setMarketMaxSlippagePpm(p.marketMaxSlippagePpm());risk.setMarketMaxMarkAgeMs(p.marketMaxMarkAgeMs());
        risk.setLimitPriceProtectionEnabled(p.limitPriceProtectionEnabled());risk.setLimitPriceBandPpm(p.limitPriceBandPpm());risk.setLimitPriceMaxMarkAgeMs(p.limitPriceMaxMarkAgeMs());
        var algo=new TradingOrderProperties.Algo();var a=settings.algo();
        algo.setEnabled(a.enabled());algo.setClaimBatchSize(a.claimBatchSize());algo.setScanDelayMs(a.scanDelayMs());
        algo.setMinIntervalSeconds(a.minIntervalSeconds());algo.setMaxIntervalSeconds(a.maxIntervalSeconds());
        algo.setMinDurationSeconds(a.minDurationSeconds());algo.setMaxDurationSeconds(a.maxDurationSeconds());algo.setClaimLease(Duration.ofMillis(a.claimLeaseMs()));
        properties.setRisk(risk);properties.setAlgo(algo);current=value;
    }
}
