package com.surprising.instrument.provider.repository;

import static org.assertj.core.api.Assertions.*;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainComponent;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainEvent;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class InstrumentLifecycleDrainPostgresTest {
    private JdbcTemplate jdbc;
    private String schema;
    private InstrumentLifecycleDrainRepository repository;
    @BeforeEach void prepare() {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL");
        assertThat(url).startsWith("jdbc:postgresql://127.0.0.1:");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD")));
        schema = "lifecycle_core_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD")));
        jdbc.execute("CREATE TABLE instrument_lifecycle_drain_acks (LIKE public.instrument_lifecycle_drain_acks INCLUDING ALL)");
        repository = new InstrumentLifecycleDrainRepository(jdbc);
    }
    @AfterEach void cleanup() { if (jdbc != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void onlyCurrentProductAndVersionCoreOrderAcknowledgementCanMakeReady(ProductLine line) {
        acknowledge(line, 12, InstrumentLifecycleDrainComponent.ACCOUNT);
        assertThat(repository.isReady(line, "73", 12)).isFalse();
        acknowledge(line, 11, InstrumentLifecycleDrainComponent.ORDER);
        assertThat(repository.isReady(line, "73", 12)).isFalse();
        acknowledge(line, 12, InstrumentLifecycleDrainComponent.ORDER);
        acknowledge(line, 12, InstrumentLifecycleDrainComponent.ORDER);
        assertThat(repository.isReady(line, "73", 12)).isTrue();
        for (var other : ProductLine.values()) if (other != line) assertThat(repository.isReady(other, "73", 12)).isFalse();
        assertThat(repository.isReady(line, "73", 13)).isFalse();
    }
    private void acknowledge(ProductLine line, long version, InstrumentLifecycleDrainComponent component) {
        repository.acknowledge(new InstrumentLifecycleDrainEvent(InstrumentLifecycleDrainEvent.CURRENT_SCHEMA_VERSION,
                "73", version, line, component, Instant.ofEpochSecond(1_700_000_000)), Instant.now());
    }
}
