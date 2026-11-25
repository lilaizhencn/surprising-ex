package com.surprising.realtime.provider.export;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.provider.export.service.CommittedAlgoProjectionService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class CommittedAlgoProjectionPostgresTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private CommittedAlgoProjectionService projection;
    @BeforeEach void prepare() {
        String url=System.getenv("INSTRUMENT_TEST_JDBC_URL");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,System.getenv("INSTRUMENT_TEST_DB_USER"),System.getenv("INSTRUMENT_TEST_DB_PASSWORD")));
        admin.execute("DROP SCHEMA IF EXISTS algo_export_it CASCADE");admin.execute("CREATE SCHEMA algo_export_it");
        var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema=algo_export_it",System.getenv("INSTRUMENT_TEST_DB_USER"),System.getenv("INSTRUMENT_TEST_DB_PASSWORD"));
        jdbc=new JdbcTemplate(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        for(String table:List.of("trading_algo_orders"))jdbc.execute("CREATE TABLE "+table+" (LIKE public."+table+" INCLUDING ALL)");
        projection=ProjectionTestFixtures.algos(jdbc,transaction);
    }
    @AfterEach void clean() {if(jdbc!=null)jdbc.execute("DROP SCHEMA algo_export_it CASCADE");}
    private CoreAlgoOrderView view(long id,long user,long revision,long executed,long active) {
        return new CoreAlgoOrderView(id,user,"client-"+id,"604",0,CoreOrderSide.BUY,100,4,2,5,20,
                CoreMarginMode.CROSS,CorePositionSide.NET,false,CoreTimeInForce.GTC,1,201,"",1000,6000,1000,1000+revision,revision,List.of(201L),executed,active,active>0?1:0, 0);
    }
    private void write(long sequence,CoreAlgoOrderView... values) {transaction.executeWithoutResult(tx->projection.persist(ProductLine.SPOT,List.of(values),sequence));}
    private CoreAlgoOrderView stored(long id) {return CoreAlgoOrderCodec.decode(jdbc.queryForObject("SELECT raw_algo_state FROM trading_algo_orders WHERE product_line='SPOT' AND algo_order_id=?",byte[].class,id));}
    @Test void childProgressUsesExportSequenceEvenWhenParentRevisionIsUnchanged() {
        write(2,view(701,7,2,0,2));write(3,view(701,7,2,1,1));
        assertThat(stored(701).executedQuantitySteps()).isEqualTo(1);
        write(3,view(701,7,2,1,1));write(2,view(701,7,2,0,2));
        assertThat(stored(701).activeQuantitySteps()).isEqualTo(1);
        assertThatThrownBy(()->write(3,view(701,7,2,2,0))).isInstanceOf(IllegalStateException.class);
        assertThat(stored(701).executedQuantitySteps()).isEqualTo(1);
        assertThatThrownBy(()->write(4,view(701,7,1,0,2))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->write(4,view(701,8,3,0,2))).isInstanceOf(IllegalStateException.class);
    }
    @Test void multiParentFailureRollsBackEarlierWrites() {
        jdbc.execute("ALTER TABLE trading_algo_orders ADD CONSTRAINT reject_second CHECK(user_id<>8)");
        assertThatThrownBy(()->write(2,view(701,7,1,0,2),view(702,8,1,0,2))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trading_algo_orders",Integer.class)).isZero();
    }
}
