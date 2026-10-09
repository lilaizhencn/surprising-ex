package com.surprising.funding.provider.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL", matches=".+")
class FundingHistoryPostgresTest {
    private JdbcTemplate jdbc;
    private String schema;
    private final FundingProperties properties = new FundingProperties();
    @BeforeEach void prepare() {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL");
        String user = System.getenv("INSTRUMENT_TEST_DB_USER"), password = System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "funding_history_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url + (url.contains("?")?"&":"?") + "currentSchema="+schema,user,password));
        for (String table : List.of("funding_settlement_rates", "core_funding_settlement_projection"))
            jdbc.execute("CREATE TABLE " + table + " (LIKE public." + table + " INCLUDING ALL)");
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
    }
    @AfterEach void clean() { if(jdbc!=null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }

    @Test void firstReservedRateSurvivesNewPredictionAndRepositoryRestartWithProductIsolation() {
        var marks = mock(LatestMarkPriceCache.class);
        when(marks.fresh(eq("1"), any())).thenReturn(Optional.of(mock(MarkPriceEvent.class)));
        var first = rate(100, 10);
        assertThat(new FundingSettlementRepository(jdbc, properties, marks).reserveCore(first).rate()).isEqualTo(first);
        assertThat(new FundingSettlementRepository(jdbc, properties, marks).reserveCore(rate(999, 20)).rate()).isEqualTo(first);
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        assertThat(new FundingSettlementRepository(jdbc, properties, marks).reserveCore(rate(999, 20)).rate().fundingRatePpm()).isEqualTo(999);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM funding_settlement_rates", Integer.class)).isEqualTo(2);
    }

    @Test void historyUsesActualCompletedRateAndPagesWithoutInventingUnknownComponents() {
        var known = rate(100, 10);
        jdbc.update("""
            INSERT INTO funding_settlement_rates VALUES (?,?,?,?,?,?,?,?,?,?)
            """, "LINEAR_PERPETUAL", "1", known.fundingTime().toEpochMilli(), known.sequence(),
                java.sql.Timestamp.from(known.fundingTime()), 8, 100L, 90L, 10L, java.sql.Timestamp.from(known.eventTime()));
        insert("LINEAR_PERPETUAL", 900, 100, 4096, "APPLIED");
        insert("LINEAR_PERPETUAL", 1800, 200, 8192, "APPLIED");
        insert("LINEAR_PERPETUAL", 2700, 300, 12288, "REJECTED");
        insert("INVERSE_PERPETUAL", 3600, 400, 16384, "APPLIED");
        var repository = new FundingRateRepository(jdbc, properties);
        var first = repository.historyPage("1", 1, null, null);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.items()).hasSize(1);
        assertThat(first.items().getFirst().fundingRatePpm()).isEqualTo(200);
        assertThat(first.items().getFirst().premiumRatePpm()).isNull();
        assertThat(first.items().getFirst().fundingIntervalHours()).isNull();
        var next = repository.historyPage("1", 1, first.nextCursor(), null);
        assertThat(next.hasMore()).isFalse();
        assertThat(next.items().getFirst().fundingRatePpm()).isEqualTo(100);
        assertThat(next.items().getFirst().premiumRatePpm()).isEqualTo(90);
        assertThat(next.items().getFirst().fundingIntervalHours()).isEqualTo(8);
        assertThat(repository.historyPage("1", 10, null, "eventTime.asc").items())
                .extracting(value -> value.fundingRatePpm()).containsExactly(100L,200L);
        // A mismatching reservation must never decorate the actual Core rate with false components.
        jdbc.update("UPDATE funding_settlement_rates SET funding_rate_ppm=999");
        assertThat(repository.historyPage("1", 10, null, "eventTime.asc").items().getFirst().premiumRatePpm()).isNull();
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        assertThat(repository.historyPage("1", 10, null, null).items()).extracting(value -> value.fundingRatePpm()).containsExactly(400L);
    }
    private FundingRateResponse rate(long rate, long seq) {
        return new FundingRateResponse("1",seq,rate,rate-10,10,Instant.ofEpochMilli(900),8,"PREDICTED",Instant.ofEpochMilli(1000));
    }
    private void insert(String line, long id, long rate, long position, String status) {
        jdbc.update("""
            INSERT INTO core_funding_settlement_projection(product_line,settlement_id,export_sequence,instrument_id,
              funding_rate_ppm,command_status,result_code,total_long_payment_units,total_short_payment_units,
              position_count,occurred_at_epoch_ms) VALUES (?,?,?,'1',?,?,'NONE',-100,100,2,?)
            """,line,id,position,rate,status,id+1000);
    }
}
