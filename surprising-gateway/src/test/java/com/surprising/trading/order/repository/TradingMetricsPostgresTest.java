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
    private final ObjectMapper json=new ObjectMapper();
    @BeforeEach void prepare() {
        String url=System.getenv("INSTRUMENT_TEST_JDBC_URL"),user=System.getenv("INSTRUMENT_TEST_DB_USER"),password=System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));admin.execute("CREATE SCHEMA metrics_query_it");
        jdbc=new JdbcTemplate(new DriverManagerDataSource(url+"?currentSchema=metrics_query_it",user,password));
        jdbc.execute("CREATE TABLE core_trading_statistics_snapshot (LIKE public.core_trading_statistics_snapshot INCLUDING ALL)");
        repository=new TradingMetricsRepository(jdbc,json);
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
        assertThatThrownBy(()->repository.require(ProductLine.OPTION,60,20)).isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class);
        add(ProductLine.OPTION,System.currentTimeMillis()-181000);
        assertThatThrownBy(()->repository.require(ProductLine.OPTION,60,20)).isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class).hasMessageContaining("STALE");
        jdbc.update("DELETE FROM core_trading_statistics_snapshot");add(ProductLine.OPTION,System.currentTimeMillis());
        jdbc.update("UPDATE core_trading_statistics_snapshot SET metrics=metrics-'positions'");
        assertThatThrownBy(()->repository.require(ProductLine.OPTION,60,20)).isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class).hasMessageContaining("INVALID");
        jdbc.execute("DROP TABLE core_trading_statistics_snapshot");
        assertThatThrownBy(()->repository.require(ProductLine.OPTION,60,20)).isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class).hasMessageContaining("UNAVAILABLE");
    }
    @Test void sixProductsKeepTheirOwnSnapshotsAndLimitOnlyRankedRowsWithoutChangingTotals() {
        for(var line:ProductLine.values())add(line,System.currentTimeMillis());
        for(var line:ProductLine.values()) {
            var data=repository.require(line,60,1);assertThat(data.get("productLine")).isEqualTo(line.name());
            assertThat(data.get("asOfLogPosition").toString()).isEqualTo(Long.toString(Long.MAX_VALUE));
            assertThat((List<?>)data.get("symbols")).hasSize(1);
            assertThat(((Map<?,?>)data.get("positions")).get("usersWithPositions")).isEqualTo(2);
        }
        assertThatThrownBy(()->repository.require(ProductLine.OPTION,120,20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->repository.require(ProductLine.OPTION,60,101)).isInstanceOf(IllegalArgumentException.class);
    }
}
