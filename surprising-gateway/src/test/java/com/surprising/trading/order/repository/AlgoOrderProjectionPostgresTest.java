package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.*;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class AlgoOrderProjectionPostgresTest {
    private JdbcTemplate jdbc;private AlgoOrderProjectionRepository projection;
    @BeforeEach void prepare() {
        String url=System.getenv("INSTRUMENT_TEST_JDBC_URL");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,System.getenv("INSTRUMENT_TEST_DB_USER"),System.getenv("INSTRUMENT_TEST_DB_PASSWORD")));
        admin.execute("DROP SCHEMA IF EXISTS algo_query_it CASCADE");admin.execute("CREATE SCHEMA algo_query_it");
        jdbc=new JdbcTemplate(new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema=algo_query_it",System.getenv("INSTRUMENT_TEST_DB_USER"),System.getenv("INSTRUMENT_TEST_DB_PASSWORD")));
        for(String table:List.of("core_algo_order_projection","core_algo_projection_initialization"))jdbc.execute("CREATE TABLE "+table+" (LIKE public."+table+" INCLUDING ALL)");
        projection=new AlgoOrderProjectionRepository(jdbc);
    }
    @AfterEach void clean() {if(jdbc!=null)jdbc.execute("DROP SCHEMA algo_query_it CASCADE");}
    private void ready(ProductLine line) {jdbc.update("INSERT INTO core_algo_projection_initialization(product_line,snapshot_log_position) VALUES (?,1)",line.name());}
    private void add(ProductLine line,long id,long user,String instrument,int status) {
        var value=new CoreAlgoOrderView(id,user,"client-"+id,instrument,0,CoreOrderSide.BUY,100,4,2,5,20,
                CoreMarginMode.CROSS,CorePositionSide.NET,false,false,CoreTimeInForce.GTC,status,0,"",1000,6000,0,1000,1001,1,List.of(),1,1,0);
        jdbc.update("INSERT INTO core_algo_order_projection VALUES (?,?,?,?,?,?,?,?,?,?,?)",line.name(),id,user,value.clientAlgoOrderId(),instrument,status,1000,1001,1,2,CoreAlgoOrderCodec.encode(value));
    }
    @Test void openFilterPrecedesLimitAndKeepsProductUserInstrumentAndInt64Ordering() {
        ready(ProductLine.SPOT);ready(ProductLine.OPTION);
        add(ProductLine.SPOT,Long.MAX_VALUE,7,"604",3);add(ProductLine.SPOT,9007199254740993L,7,"604",1);
        add(ProductLine.SPOT,100,7,"605",0);add(ProductLine.SPOT,101,8,"604",0);add(ProductLine.OPTION,9007199254740993L,7,"604",0);
        assertThat(projection.openOrders(ProductLine.SPOT,7,"604",1)).singleElement().satisfies(v->{
            assertThat(v.algoOrderId()).isEqualTo(9007199254740993L);assertThat(v.executedQuantitySteps()).isEqualTo(1);});
        assertThat(projection.openOrders(ProductLine.SPOT,7,null,10)).extracting(CoreAlgoOrderView::algoOrderId).containsExactly(9007199254740993L,100L);
        assertThat(projection.openOrders(ProductLine.OPTION,7,null,10)).hasSize(1);
    }
    @Test void initializingUnavailableAndCorruptDataAre503RatherThanEmptySuccess() {
        assertThatThrownBy(()->projection.openOrders(ProductLine.SPOT,7,null,10)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("ALGO_READ_VIEW_INITIALIZING");
        ready(ProductLine.SPOT);add(ProductLine.SPOT,701,7,"604",0);
        jdbc.update("UPDATE core_algo_order_projection SET revision=99");
        assertThatThrownBy(()->projection.openOrders(ProductLine.SPOT,7,null,10)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("ALGO_READ_VIEW_INVALID");
        jdbc.execute("DROP TABLE core_algo_order_projection");
        assertThatThrownBy(()->projection.openOrders(ProductLine.SPOT,7,null,10)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("ALGO_READ_VIEW_UNAVAILABLE");
    }
    @Test void invalidIdentityAndLimitsAreRejectedBeforeReadingStorage() {
        assertThatThrownBy(()->projection.openOrders(ProductLine.SPOT,0,null,10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->projection.openOrders(ProductLine.SPOT,7,null,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->projection.openOrders(ProductLine.SPOT,7,null,1001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->projection.openOrders(ProductLine.SPOT,7,"BTC-USDT",10)).isInstanceOf(IllegalArgumentException.class);
    }
}
