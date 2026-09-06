package com.surprising.instrument.provider.service;

import static org.assertj.core.api.Assertions.*;
import com.surprising.instrument.api.model.*;
import com.surprising.instrument.provider.repository.*;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Execute against a dedicated empty database initialized with the root init.sql. */
@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL", matches=".+")
class InstrumentCurrentConfigurationIntegrationTest {
    @Test
    void allSixLinesKeepOneCurrentRowAndAtomicBeforeAfterAudit() {
        var source = new DriverManagerDataSource(System.getenv("INSTRUMENT_TEST_JDBC_URL"), "maintenance", "");
        var jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var json = JsonMapper.builder().findAndAddModules().build();
        var repository = new InstrumentRepository(jdbc);
        var audit = new InstrumentChangeLogRepository(jdbc);
        var storage = new InstrumentStorageService(repository, audit, new InstrumentRiskBracketRepository(jdbc),
                new InstrumentIndexSourceRepository(jdbc), new InstrumentAssetScaleRepository(jdbc), json);
        for (var line : ProductLine.values()) {
            var initial = storage.list(line, null, null).getFirst();
            ObjectNode fields = (ObjectNode) json.valueToTree(initial);
            fields.remove(List.of("changeId", "lastChangeId", "createdAt", "updatedAt"));
            fields.put("status", "HALT");
            var request = json.treeToValue(fields, InstrumentUpsertRequest.class);
            long count = jdbc.queryForObject("SELECT count(*) FROM instruments WHERE product_line=?", Long.class, line.name());
            var changed = tx.execute(status -> storage.save(initial.symbol(), request, "operator-42", "pause for maintenance", Instant.now()));
            assertThat(changed.status()).isEqualTo(InstrumentStatus.HALT);
            assertThat(changed.changeId()).isEqualTo(initial.changeId());
            assertThat(changed.lastChangeId()).isGreaterThan(initial.lastChangeId());
            assertThat(storage.latest(initial.symbol(), line)).contains(changed);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM instruments WHERE product_line=?", Long.class, line.name())).isEqualTo(count);
            var entry = audit.list(line, initial.symbol(), 0, 1).getFirst();
            assertThat(entry.operatorId()).isEqualTo("operator-42");
            assertThat(entry.reason()).isEqualTo("pause for maintenance");
            assertThat(json.readTree(entry.beforeValues()).get("status").asString()).isEqualTo(initial.status().name());
            assertThat(json.readTree(entry.afterValues()).get("status").asString()).isEqualTo("HALT");
            assertThat(json.readTree(entry.afterValues()).has("version")).isFalse();
            assertThat(changed.riskLimitBrackets()).isEqualTo(initial.riskLimitBrackets());
            assertThat(changed.indexSources()).isEqualTo(initial.indexSources());
            assertThatThrownBy(() -> tx.execute(status -> storage.save(initial.symbol(), request, "", "invalid actor", Instant.now())))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(storage.latest(initial.symbol(), line)).contains(changed);
            assertThat(audit.list(line, initial.symbol(), 0, 1).getFirst()).isEqualTo(entry);
            var oldUnits=audit.tradeEncoding(line,initial.symbol(),initial.changeId());
            fields.put("priceTickUnits",Math.multiplyExact(initial.priceTickUnits(),2));
            var reconfigured=tx.execute(status -> storage.save(initial.symbol(),json.treeToValue(fields,InstrumentUpsertRequest.class),
                    "operator-42","new price tick",Instant.now()));
            assertThat(reconfigured.changeId()).isEqualTo(reconfigured.lastChangeId()).isGreaterThan(changed.lastChangeId());
            assertThat(audit.tradeEncoding(line,initial.symbol(),initial.changeId())).isEqualTo(oldUnits);
            assertThat(audit.tradeEncoding(line,initial.symbol(),reconfigured.changeId()).priceTickUnits()).isEqualTo(initial.priceTickUnits()*2);
        }
    }
}
