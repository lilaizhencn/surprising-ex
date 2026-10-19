package com.surprising.trading.order.repository;

import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.math.BigInteger;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Reads the complete, product-scoped statistics snapshot published by realtime. */
@Repository
public final class TradingMetricsRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public TradingMetricsRepository(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }

    @SuppressWarnings("unchecked")
    public Map<String,Object> require(ProductLine product,int window,int limit) {
        if(product==null||!Set.of(60,360,1440,10080).contains(window)||limit<1||limit>100)
            throw new IllegalArgumentException("statistics window must be 60,360,1440,10080 and limit in [1,100]");
        try {
            var rows=jdbc.query("SELECT cluster_position,generated_at_epoch_ms,metrics::text FROM core_trading_statistics_snapshot WHERE product_line=? AND window_minutes=?",
                    (rs,index)->new Row(rs.getLong(1),rs.getLong(2),rs.getString(3)),product.name(),window);
            if(rows.isEmpty())throw unavailable("TRADING_METRICS_INITIALIZING");
            var row=rows.getFirst();long now=System.currentTimeMillis();
            if(row.generated()>now+1000||now-row.generated()>180000)throw unavailable("TRADING_METRICS_STALE");
            try {
                Map<String,Object> data=json.readValue(row.raw(),Map.class);
                if(!"committed-trading-v1".equals(data.get("schema"))||!product.name().equals(data.get("productLine"))
                        ||number(data.get("asOfLogPosition"))!=row.position()||number(data.get("windowMinutes"))!=window
                        ||Instant.parse((String)data.get("generatedAt")).toEpochMilli()!=row.generated())throw new IllegalStateException("statistics identity mismatch");
                requireNumbers(data,"orders","submittedCommands","rejectedCommands","rejectRatePpm","uniqueUsers","openOrders");
                requireNumbers(data,"commands","total","rejected","rejectRatePpm","uniqueAccounts");
                requireNumbers(data,"trades","trades","uniqueParticipants");requireNumbers(data,"positions","usersWithPositions","openPositions","symbolCount");
                requireNumbers(data,"triggerOrders","pending","expiredPending");
                var symbols=(List<Map<String,Object>>)data.get("symbols");var positions=(Map<String,Object>)data.get("positions");
                var positionSymbols=(List<Map<String,Object>>)positions.get("symbols");
                data.put("symbols",List.copyOf(symbols.subList(0,Math.min(limit,symbols.size()))));
                positions.put("symbols",List.copyOf(positionSymbols.subList(0,Math.min(limit,positionSymbols.size()))));
                data.put("limit",limit);data.put("dataAgeMs",Math.max(0,now-row.generated()));
                return data;
            } catch(RuntimeException invalid) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"TRADING_METRICS_INVALID",invalid);
            }
        } catch(DataAccessException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"TRADING_METRICS_UNAVAILABLE",unavailable);
        }
    }
    private record Row(long position,long generated,String raw) {}
    private static long number(Object value) { return new BigInteger(Objects.requireNonNull(value).toString()).longValueExact(); }
    private static void requireNumbers(Map<String,Object> data,String section,String...fields) {
        if(!(data.get(section) instanceof Map<?,?> values))throw new IllegalArgumentException("statistics section missing");
        for(String field:fields)if(new BigInteger(Objects.requireNonNull(values.get(field)).toString()).signum()<0)throw new IllegalArgumentException("invalid statistics count");
    }
    private static ResponseStatusException unavailable(String reason) { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,reason); }
}
