package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.*;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class TradingMetricsPostgresTest {
    private JdbcTemplate jdbc;
    private TradingMetricsRepository repository;
    private com.surprising.trading.order.service.TradingMetricsService service;
    private final ObjectMapper json=new ObjectMapper();
    @BeforeEach void prepare() {
        String url=System.getenv("INSTRUMENT_TEST_JDBC_URL"),user=System.getenv("INSTRUMENT_TEST_DB_USER"),password=System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));admin.execute("CREATE SCHEMA metrics_query_it");
        jdbc=new JdbcTemplate(new DriverManagerDataSource(url+"?currentSchema=metrics_query_it",user,password));
        jdbc.execute("CREATE TABLE core_trading_statistics_snapshot (LIKE public.core_trading_statistics_snapshot INCLUDING ALL)");
        repository=new TradingMetricsRepository(jdbc);
        service=new com.surprising.trading.order.service.TradingMetricsService(repository,json);
    }
    @AfterEach void clean(){if(jdbc!=null)jdbc.execute("DROP SCHEMA metrics_query_it CASCADE");}
    private void add(ProductLine product,long now) {
        var data=new LinkedHashMap<String,Object>();data.put("schema","committed-trading-v1");data.put("productLine",product.name());
        data.put("asOfLogPosition",Long.MAX_VALUE);data.put("generatedAt",Instant.ofEpochMilli(now).toString());data.put("windowMinutes",60);
        data.put("orders",Map.of("submittedCommands",2,"rejectedCommands",1,"rejectRatePpm",500000,"uniqueUsers",1,"openOrders",2));
        data.put("commands",Map.of("total",3,"rejected",1,"rejectRatePpm",333333,"uniqueAccounts",2));
        data.put("trades",Map.of("trades",1,"uniqueParticipants",2));data.put("triggerOrders",Map.of("pending",0,"expiredPending",0));
        var symbols=List.of(Map.of("instrumentId","1"),Map.of("instrumentId","2"));data.put("symbols",symbols);
        data.put("positions",Map.of("usersWithPositions",2,"openPositions",2,"symbolCount",2,"symbols",symbols));
        jdbc.update("INSERT INTO core_trading_statistics_snapshot VALUES (?,60,?,?,?::jsonb)",product.name(),Long.MAX_VALUE,now,json.writeValueAsString(data));
    }
    @Test void absentStaleCorruptAndFailedDatabaseReadsNeverBecomeSuccessfulZeroStatistics() {
        assertThatThrownBy(()->service.require(ProductLine.OPTION,60,20)).isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class);
        add(ProductLine.OPTION,System.currentTimeMillis()-181000);
        assertThatThrownBy(()->service.require(ProductLine.OPTION,60,20)).isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class).hasMessageContaining("STALE");
        jdbc.update("DELETE FROM core_trading_statistics_snapshot");add(ProductLine.OPTION,System.currentTimeMillis());
        jdbc.update("UPDATE core_trading_statistics_snapshot SET metrics=metrics-'positions'");
        assertThatThrownBy(()->service.require(ProductLine.OPTION,60,20)).isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class).hasMessageContaining("INVALID");
        jdbc.execute("DROP TABLE core_trading_statistics_snapshot");
        assertThatThrownBy(()->service.require(ProductLine.OPTION,60,20)).isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class).hasMessageContaining("UNAVAILABLE");
    }
    @Test void sixProductsKeepTheirOwnSnapshotsAndLimitOnlyRankedRowsWithoutChangingTotals() {
        for(var line:ProductLine.values())add(line,System.currentTimeMillis());
        for(var line:ProductLine.values()) {
            var data=service.require(line,60,1);assertThat(data.get("productLine").asText()).isEqualTo(line.name());
            assertThat(data.get("asOfLogPosition").toString()).isEqualTo(Long.toString(Long.MAX_VALUE));
            assertThat(data.get("symbols").size()).isEqualTo(1);
            assertThat(data.get("positions").get("usersWithPositions").asInt()).isEqualTo(2);
        }
    }
    @Test void storedRowAndNativeDocumentKeepBigIntegersAndUnknownVersionedFields() {
        long now=System.currentTimeMillis();add(ProductLine.SPOT,now);
        String huge="92233720368547758081234567890";
        jdbc.update("UPDATE core_trading_statistics_snapshot SET metrics=jsonb_set(metrics,'{orders,submittedCommands}',?::jsonb) || ?::jsonb",
                huge,"{\"futureCounter\":"+huge+"}");
        var row=repository.find(ProductLine.SPOT,60).orElseThrow();
        assertThat(row.clusterPosition()).isEqualTo(Long.MAX_VALUE);
        assertThat(row.generatedAtEpochMs()).isEqualTo(now);
        var data=service.require(ProductLine.SPOT,60,1);
        assertThat(data.get("orders").get("submittedCommands").bigIntegerValue()).isEqualTo(new java.math.BigInteger(huge));
        assertThat(data.get("futureCounter").bigIntegerValue()).isEqualTo(new java.math.BigInteger(huge));
        assertThat(data.get("positions").get("symbolCount").asInt()).isEqualTo(2);
        assertThat(data.get("positions").get("symbols").size()).isEqualTo(1);
        assertThat(json.writeValueAsString(data)).contains(huge);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("wrongIdentity")
    void persistedPayloadMustMatchEverySnapshotIdentity(String field,String value) {
        add(ProductLine.SPOT,System.currentTimeMillis());
        jdbc.update("UPDATE core_trading_statistics_snapshot SET metrics=jsonb_set(metrics,ARRAY[?]::text[],?::jsonb)",field,value);
        assertThatThrownBy(()->service.require(ProductLine.SPOT,60,1))
                .isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class).hasMessageContaining("INVALID");
    }
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> wrongIdentity() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("schema","\"wrong-schema\""),
                org.junit.jupiter.params.provider.Arguments.of("productLine","\"OPTION\""),
                org.junit.jupiter.params.provider.Arguments.of("asOfLogPosition","1"),
                org.junit.jupiter.params.provider.Arguments.of("windowMinutes","61"),
                org.junit.jupiter.params.provider.Arguments.of("generatedAt","\"1970-01-01T00:00:00Z\""));
    }
}
