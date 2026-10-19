package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.*;
import com.surprising.product.api.ProductLine;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL", matches=".+")
class TradeProjectionPostgresTest {
    private JdbcTemplate jdbc;
    private TradeProjectionRepository repository;
    @BeforeEach void prepare() {
        String url=System.getenv("INSTRUMENT_TEST_JDBC_URL"),user=System.getenv("INSTRUMENT_TEST_DB_USER"),password=System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        admin.execute("CREATE SCHEMA trade_query_it");
        jdbc=new JdbcTemplate(new DriverManagerDataSource(url+"?currentSchema=trade_query_it",user,password));
        jdbc.execute("CREATE TABLE core_execution_projection (LIKE public.core_execution_projection INCLUDING ALL)");
        jdbc.execute("CREATE TABLE core_execution_projection_initialization (LIKE public.core_execution_projection_initialization INCLUDING ALL)");
        repository=new TradeProjectionRepository(jdbc);
    }
    @AfterEach void clean() { if(jdbc!=null)jdbc.execute("DROP SCHEMA trade_query_it CASCADE"); }
    private void ready(ProductLine line) { jdbc.update("INSERT INTO core_execution_projection_initialization(product_line,snapshot_log_position) VALUES (?,32)",line.name()); }
    private void add(ProductLine line,long position,int index,long time,long takerUser,long makerUser) {
        jdbc.update("""
                INSERT INTO core_execution_projection(product_line,export_sequence,execution_index,trade_id,taker_order_id,maker_order_id,
                  taker_user_id,maker_user_id,instrument_id,taker_side,taker_position_side,maker_position_side,
                  taker_fee_rate_ppm,maker_fee_rate_ppm,price_ticks,quantity_steps,occurred_at_epoch_ms)
                VALUES (?,?,?,?,?,?,?,?,?,'BUY','NET','NET',500,-100,100,2,?)
                """,line.name(),position,index,line+":"+position+":1:"+Long.MAX_VALUE+":"+index,
                Long.MAX_VALUE,9007199254740993L,takerUser,makerUser,"604",time);
    }
    @Test void missingInitializationAndDatabaseFailureAre503RatherThanSuccessfulEmptyLists() {
        assertThatThrownBy(() -> repository.search(ProductLine.OPTION,null,null,null,10,null,null))
                .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        ready(ProductLine.OPTION);jdbc.execute("DROP TABLE core_execution_projection");
        assertThatThrownBy(() -> repository.search(ProductLine.OPTION,null,null,null,10,null,null)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void bothSortDirectionsTraverseEqualTimesAndFullInt64PositionsWithoutDuplicates() {
        ready(ProductLine.OPTION);add(ProductLine.OPTION,10,0,1000,7,8);add(ProductLine.OPTION,20,0,2000,7,8);
        add(ProductLine.OPTION,Long.MAX_VALUE,0,2000,7,8);add(ProductLine.OPTION,Long.MAX_VALUE,1,2000,7,8);
        for(String sort:List.of("eventTime.desc","eventTime.asc")) {
            var positions=new ArrayList<String>();String cursor=null;
            do {var page=repository.search(ProductLine.OPTION,null,null,null,1,cursor,sort);
                page.trades().forEach(v->positions.add(v.clusterPosition()+":"+v.executionIndex()));cursor=page.nextCursor();}while(cursor!=null);
            var expected=List.of("10:0","20:0",Long.MAX_VALUE+":0",Long.MAX_VALUE+":1");
            assertThat(positions).containsExactlyElementsOf(sort.endsWith("asc")?expected:expected.reversed());
        }
    }
    @Test void userAndOrderMustBelongToTheSameSideAndSelfTradeIsCountedOnlyOnce() {
        ready(ProductLine.OPTION);add(ProductLine.OPTION,10,0,1000,7,8);add(ProductLine.OPTION,20,0,2000,7,7);
        assertThat(repository.search(ProductLine.OPTION,7L,"604",Long.MAX_VALUE,10,null,null).count()).isEqualTo(2);
        assertThat(repository.search(ProductLine.OPTION,7L,null,9007199254740993L,10,null,null).count()).isEqualTo(1);
        var maker=repository.search(ProductLine.OPTION,8L,null,9007199254740993L,10,null,null).trades().getFirst();
        assertThat(maker.makerOrderId()).isEqualTo(9007199254740993L);assertThat(maker.makerFeeRatePpm()).isEqualTo(-100);
        assertThat(repository.search(ProductLine.OPTION,8L,null,Long.MAX_VALUE,10,null,null).trades()).isEmpty();
    }
    @Test void sixProductIdentitiesAndFilterScopedCursorsCannotMix() {
        for(var line:ProductLine.values()) {ready(line);add(line,10,0,1000,7,8);add(line,20,0,2000,7,8);}
        var page=repository.search(ProductLine.OPTION,null,null,null,1,null,null);
        assertThat(page.productLine()).isEqualTo(ProductLine.OPTION);
        for(var line:ProductLine.values())assertThat(repository.search(line,null,null,null,10,null,null).trades()).allMatch(v->v.productLine()==line);
        for(var line:ProductLine.values())if(line!=ProductLine.OPTION)
            assertThatThrownBy(()->repository.search(line,null,null,null,1,page.nextCursor(),null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->repository.search(ProductLine.OPTION,7L,null,null,1,page.nextCursor(),null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->repository.search(ProductLine.OPTION,null,"604",null,1,page.nextCursor(),null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->repository.search(ProductLine.OPTION,null,null,null,1,page.nextCursor(),"eventTime.asc")).isInstanceOf(IllegalArgumentException.class);
    }
}
