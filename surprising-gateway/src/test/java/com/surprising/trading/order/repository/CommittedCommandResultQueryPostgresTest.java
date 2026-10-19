package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.*;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.service.StableOrderIdentity;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class CommittedCommandResultQueryPostgresTest {
    private JdbcTemplate jdbc;
    private AeronOrderProjectionRepository repository;
    private final UUID id = StableOrderIdentity.commandId(ProductLine.SPOT, 7, "lost-reply");

    @BeforeEach void prepare() {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url,
                System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD")));
        admin.execute("CREATE SCHEMA receipt_query_it");
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=receipt_query_it",
                System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE core_command_result_projection (LIKE public.core_command_result_projection INCLUDING ALL)");
        repository = new AeronOrderProjectionRepository(jdbc);
        jdbc.update("""
                INSERT INTO core_command_result_projection VALUES ('SPOT',?,7,'PLACE_ORDER','lost-reply','1',
                  'REJECTED','ORDER_NOT_FOUND',1,32,1700000000000)
                """, id);
    }

    @AfterEach void clean() { if (jdbc != null) jdbc.execute("DROP SCHEMA receipt_query_it CASCADE"); }

    @Test void aCommittedRejectionWithoutAnOrderIsRecoveredByBothCommandAndClientIdentity() {
        assertThat(repository.commandResult(ProductLine.SPOT, 7, id)).satisfies(result -> {
            assertThat(result.commandId()).isEqualTo(id);
            assertThat(result.outcome()).isEqualTo("TERMINAL");
            assertThat(result.code()).isEqualTo("ORDER_NOT_FOUND");
        });
        assertThat(repository.submission(ProductLine.SPOT, 7, "lost-reply", id)).satisfies(result -> {
            assertThat(result.userId()).isEqualTo(7);
            assertThat(result.productLine()).isEqualTo(ProductLine.SPOT);
            assertThat(result.clientOrderId()).isEqualTo("lost-reply");
            assertThat(result.instrumentId()).isEqualTo("1");
            assertThat(result.outcome()).isEqualTo("TERMINAL");
        });
    }

    @Test void missingResultsAndDifferentUsersOrProductsRemainUnknown() {
        assertThat(repository.commandResult(ProductLine.SPOT, 8, id).outcome()).isEqualTo("RESULT_UNKNOWN");
        assertThat(repository.commandResult(ProductLine.OPTION, 7, id).outcome()).isEqualTo("RESULT_UNKNOWN");
        assertThat(repository.submission(ProductLine.SPOT, 7, "new-request", UUID.randomUUID()).outcome()).isEqualTo("RESULT_UNKNOWN");
        assertThatThrownBy(() -> repository.submission(ProductLine.SPOT, 7, "different-key", id))
                .isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE core_command_result_projection SET result_code='NOT_A_CORE_CODE'");
        assertThatThrownBy(() -> repository.commandResult(ProductLine.SPOT, 7, id))
                .isInstanceOf(org.springframework.dao.DataRetrievalFailureException.class);
    }
}
