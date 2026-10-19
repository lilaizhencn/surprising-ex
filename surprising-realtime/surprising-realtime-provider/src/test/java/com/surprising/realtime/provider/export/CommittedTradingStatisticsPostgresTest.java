package com.surprising.realtime.provider.export;

import static org.assertj.core.api.Assertions.*;
import com.surprising.product.api.ProductLine;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.orchestration.CommittedTradeReplay.CommittedCommandResult;
import com.surprising.aeron.service.state.CommittedTradingActivity;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class CommittedTradingStatisticsPostgresTest {
    private JdbcTemplate jdbc;
    private CommittedTradingStatistics statistics;
    @BeforeEach void prepare() {
        String url=System.getenv("INSTRUMENT_TEST_JDBC_URL"),user=System.getenv("INSTRUMENT_TEST_DB_USER"),password=System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));admin.execute("CREATE SCHEMA statistics_it");
        var source=new DriverManagerDataSource(url+"?currentSchema=statistics_it",user,password);jdbc=new JdbcTemplate(source);
        for(var name:List.of("core_trading_command_statistics","core_trading_statistics_cursor","core_trading_statistics_initialization","core_trading_statistics_snapshot","core_execution_projection"))
            jdbc.execute("CREATE TABLE "+name+" (LIKE public."+name+" INCLUDING ALL)");
        statistics=new CommittedTradingStatistics(jdbc,new TransactionTemplate(new DataSourceTransactionManager(source)));
    }
    @AfterEach void clean() { if(jdbc!=null)jdbc.execute("DROP SCHEMA statistics_it CASCADE"); }
    private CommittedCommandResult attempt(ProductLine product,long position,long commandId,CoreMessageType type,CoreResultCode code) {
        return new CommittedCommandResult(product,7,new UUID(0,commandId),type,"client","604",
                code==CoreResultCode.NONE?ResponseStatus.APPLIED:ResponseStatus.REJECTED,code,position,position,System.currentTimeMillis(),List.of());
    }
    @Test void replayDeduplicatesLogPositionsWhileDistinctCommittedRetryAttemptsCountAndRemainProductIsolated() {
        for(var line:ProductLine.values()) {
            var first=attempt(line,10,1,CoreMessageType.PLACE_ORDER,CoreResultCode.NONE);
            var retry=attempt(line,20,1,CoreMessageType.PLACE_ORDER,CoreResultCode.IDEMPOTENCY_CONFLICT);
            statistics.repair(line,List.of(first,first,retry));statistics.repair(line,List.of(first,retry));
            assertThat(jdbc.queryForObject("SELECT sum(commands) FROM core_trading_command_statistics WHERE product_line=?",Long.class,line.name())).isEqualTo(2);
        }
        assertThat(jdbc.queryForObject("SELECT sum(commands) FROM core_trading_command_statistics",Long.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_trading_statistics_snapshot",Integer.class)).isZero();
    }
    @Test void snapshotsNeverCombineFutureSqlCountersWithOlderNativeStateAndKeepQuantitiesPerInstrumentWithoutOverflow() {
        var line=ProductLine.OPTION;long now=System.currentTimeMillis();
        statistics.repair(line,List.of(attempt(line,100,1,CoreMessageType.PLACE_ORDER,CoreResultCode.NONE),
                attempt(line,200,2,CoreMessageType.PLACE_ORDER,CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE)));
        var huge=BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO);
        var activity=new CommittedTradingActivity(3,2,2,1,0,List.of(new CommittedTradingActivity.Instrument("604",3,huge,2,2,1,1,huge,huge)));
        statistics.publish(line,activity,100,now);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_trading_statistics_snapshot",Integer.class)).isZero();
        statistics.publish(line,activity,200,now);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_trading_statistics_snapshot",Integer.class)).isEqualTo(4);
        var json=new ObjectMapper().readTree(jdbc.queryForObject("SELECT metrics::text FROM core_trading_statistics_snapshot WHERE window_minutes=60",String.class));
        assertThat(json.path("orders").path("submittedCommands").asLong()).isEqualTo(2);
        assertThat(json.path("orders").path("rejectRatePpm").asLong()).isEqualTo(500000);
        assertThat(json.path("orders").path("openOrders").asLong()).isEqualTo(3);
        assertThat(json.path("positions").path("usersWithPositions").asLong()).isEqualTo(2);
        assertThat(json.path("symbols").get(0).path("openQuantitySteps").asText()).isEqualTo(huge.toString());
        assertThat(json.path("trades").has("notionalTicksSteps")).isFalse();
    }
    @Test void oldBucketsFallOutsideTheWindowAndBalanceCommandsDoNotInflatePlaceOrderCounts() {
        var line=ProductLine.OPTION;long now=System.currentTimeMillis();
        var fresh=attempt(line,10,1,CoreMessageType.PLACE_ORDER_BATCH,CoreResultCode.NONE);
        var old=new CommittedCommandResult(line,7,new UUID(0,2),CoreMessageType.PLACE_ORDER,"old","604",ResponseStatus.APPLIED,CoreResultCode.NONE,20,20,now-8*86400000L,List.of());
        statistics.repair(line,List.of(fresh,old,attempt(line,30,3,CoreMessageType.ADJUST_BALANCE,CoreResultCode.NONE)));
        statistics.publish(line,new CommittedTradingActivity(0,0,0,0,0,List.of()),30,now);
        assertThat(jdbc.queryForList("SELECT (metrics->'orders'->>'submittedCommands')::bigint FROM core_trading_statistics_snapshot",Long.class)).containsOnly(1L);
        statistics.initialize(line,30);assertThat(statistics.initialized(line)).isTrue();assertThat(statistics.initialized(ProductLine.SPOT)).isFalse();
    }
}
