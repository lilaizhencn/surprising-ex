package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.*;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;
import com.surprising.trading.trigger.repository.TriggerOrderProjectionRepository;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class ProjectionReadAssociationPostgresTest {
    private CountingJdbc jdbc;
    private String schema;
    @BeforeEach void prepare() {
        String url=System.getenv("INSTRUMENT_TEST_JDBC_URL"),user=System.getenv("INSTRUMENT_TEST_DB_USER"),password=System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        schema="projection_association_"+UUID.randomUUID().toString().replace("-","");
        new JdbcTemplate(new DriverManagerDataSource(url,user,password)).execute("CREATE SCHEMA "+schema);
        jdbc=new CountingJdbc(new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password));
        for(String table:List.of("core_algo_order_projection","core_algo_projection_initialization","core_execution_projection",
                "core_execution_projection_initialization","core_trigger_order_projection","core_trigger_projection_initialization"))
            jdbc.execute("CREATE TABLE "+table+" (LIKE public."+table+" INCLUDING ALL)");
    }
    @AfterEach void clean() {if(jdbc!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}

    @ParameterizedTest @EnumSource(ProductLine.class)
    void everyProjectionReadAssociatesReadinessInOneStatementAndDistinguishesReadyEmpty(ProductLine line) {
        var algo=new AlgoOrderProjectionRepository(jdbc);
        var trades=new TradeProjectionRepository(jdbc);
        var triggers=new TriggerOrderProjectionRepository(jdbc);
        ready(ProductLine.values()[(line.ordinal()+1)%ProductLine.values().length]);
        List<Runnable> reads=List.of(
                ()->assertThat(algo.openOrders(line,7,null,10)).isEmpty(),
                ()->assertThat(algo.byId(line,7,99)).isNull(),
                ()->assertThat(algo.byClient(line,7,"unseen")).isNull(),
                ()->assertThat(trades.ownFills(line,7,null,null,null,null,10,null).trades()).isEmpty(),
                ()->assertThat(trades.search(line,null,null,null,10,null,false).trades()).isEmpty(),
                ()->assertThat(triggers.get(line,7L,99)).isEmpty(),
                ()->assertThat(triggers.search(line,null,null,null,null,10,null,false).orders()).isEmpty());
        for(var read:reads) {
            jdbc.reads=0;
            assertThatThrownBy(read::run).isInstanceOf(ReadViewUnavailableException.class).hasMessageContaining("INITIALIZING");
            assertSingleAssociation();
        }
        ready(line);
        for(var read:reads) {jdbc.reads=0;read.run();assertSingleAssociation();}
    }
    private void assertSingleAssociation() {
        assertThat(jdbc.reads).isEqualTo(1);
        assertThat(jdbc.lastSql).contains("LEFT JOIN LATERAL", "__projection_ready", "initialization");
    }
    private void ready(ProductLine line) {
        jdbc.update("INSERT INTO core_algo_projection_initialization(product_line,snapshot_log_position) VALUES (?,32)",line.name());
        jdbc.update("INSERT INTO core_execution_projection_initialization(product_line,snapshot_log_position) VALUES (?,32)",line.name());
        jdbc.update("INSERT INTO core_trigger_projection_initialization(product_line,snapshot_log_position,initial_export_sequence) VALUES (?,32,2)",line.name());
    }
    private static class CountingJdbc extends JdbcTemplate {
        int reads; String lastSql;
        CountingJdbc(javax.sql.DataSource source) {super(source);}
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object...values) {
            reads++;lastSql=sql;return super.query(sql,mapper,values);
        }
        @Override public <T> T queryForObject(String sql,Class<T> type,Object...values) {
            reads++;lastSql=sql;return super.queryForObject(sql,type,values);
        }
    }
}
