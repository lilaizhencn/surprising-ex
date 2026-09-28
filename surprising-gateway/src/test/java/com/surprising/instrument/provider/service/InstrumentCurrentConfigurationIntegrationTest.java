package com.surprising.instrument.provider.service;

import com.surprising.asset.repository.AssetRepository;
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
        var source = new DriverManagerDataSource(System.getenv("INSTRUMENT_TEST_JDBC_URL"), System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var json = JsonMapper.builder().findAndAddModules().build();
        var repository = new InstrumentRepository(jdbc);
        var audit = new InstrumentChangeLogRepository(jdbc);
        var storage = new InstrumentStorageService(repository, audit, new InstrumentRiskBracketRepository(jdbc),
                new InstrumentIndexSourceRepository(jdbc), new AssetRepository(jdbc), json);
        for (var line : ProductLine.values()) {
            var initial = storage.list(line, null, null).getFirst();
            ObjectNode fields = (ObjectNode) json.valueToTree(initial);
            fields.remove(List.of("changeId", "lastChangeId", "createdAt", "updatedAt", "baseAsset", "quoteAsset", "settleAsset", "contractValueAsset"));
            tx.executeWithoutResult(status -> {
                status.setRollbackOnly();
                jdbc.update("UPDATE assets SET listed=false,trading_enabled=false WHERE asset_id=?", initial.baseAssetId());
                var creating = fields.deepCopy();
                creating.putNull("instrumentId");
                creating.put("symbol", "NEW-ASSET-CHECK-" + line.name());
                assertThatThrownBy(() -> storage.save(creating.get("symbol").asString(),
                        json.treeToValue(creating, InstrumentUpsertRequest.class), "operator-42", "unlisted asset", Instant.now()))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("listed");
            });
            fields.put("status", "HALT");
            var request = json.treeToValue(fields, InstrumentUpsertRequest.class);
            long count = jdbc.queryForObject("SELECT count(*) FROM instruments WHERE product_line=?", Long.class, line.name());
            var changed = tx.execute(status -> storage.save(initial.symbol(), request, "operator-42", "pause for maintenance", Instant.now()));
            assertThat(changed.status()).isEqualTo(InstrumentStatus.HALT);
            assertThat(changed.changeId()).isEqualTo(initial.changeId());
            assertThat(changed.lastChangeId()).isGreaterThan(initial.lastChangeId());
            assertThat(storage.latest(initial.instrumentId(), line)).contains(changed);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM instruments WHERE product_line=?", Long.class, line.name())).isEqualTo(count);
            var entry = audit.list(line, initial.instrumentId(), 0, 1).getFirst();
            assertThat(entry.operatorId()).isEqualTo("operator-42");
            assertThat(entry.reason()).isEqualTo("pause for maintenance");
            assertThat(json.readTree(entry.beforeValues()).get("status").asString()).isEqualTo(initial.status().name());
            assertThat(json.readTree(entry.afterValues()).get("status").asString()).isEqualTo("HALT");
            assertThat(json.readTree(entry.afterValues()).has("version")).isFalse();
            assertThat(changed.riskLimitBrackets()).isEqualTo(initial.riskLimitBrackets());
            assertThat(changed.indexSources()).isEqualTo(initial.indexSources());
            assertThatThrownBy(() -> tx.execute(status -> storage.save(initial.symbol(), request, "", "invalid actor", Instant.now())))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(storage.latest(initial.instrumentId(), line)).contains(changed);
            assertThat(audit.list(line, initial.instrumentId(), 0, 1).getFirst()).isEqualTo(entry);
            fields.put("symbol", "RENAMED-" + initial.instrumentId());
            var renamed = tx.execute(status -> storage.save(fields.get("symbol").asString(),
                    json.treeToValue(fields, InstrumentUpsertRequest.class), "operator-42", "rename", Instant.now()));
            assertThat(renamed.instrumentId()).isEqualTo(initial.instrumentId());
            assertThat(renamed.changeId()).isEqualTo(initial.changeId());
            assertThat(storage.latest(initial.instrumentId(), line)).contains(renamed);
            assertThat(storage.list(line, null, null)).noneMatch(row -> row.symbol().equals(initial.symbol()));
            assertThat(renamed.riskLimitBrackets()).isEqualTo(initial.riskLimitBrackets());
            assertThat(renamed.indexSources()).isEqualTo(initial.indexSources());
            assertThat(audit.list(line, initial.instrumentId(), 0, 10)).hasSize(3);
            assertThatThrownBy(() -> tx.execute(status -> {
                jdbc.update("UPDATE instruments SET instrument_id=instrument_id+100000 WHERE product_line=? AND instrument_id=?",
                        line.name(), initial.instrumentId());
                return null;
            })).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(storage.latest(initial.instrumentId(), line)).contains(renamed);
            var otherLine = line == ProductLine.SPOT ? ProductLine.LINEAR_PERPETUAL : ProductLine.SPOT;
            assertThatThrownBy(() -> tx.execute(status -> {
                repository.lockForUpdate(initial.instrumentId(), otherLine);
                return null;
            })).isInstanceOf(IllegalArgumentException.class);
            var oldUnits=audit.tradeEncoding(line,initial.instrumentId(),initial.changeId());
            fields.put("priceTickUnits",Math.multiplyExact(initial.priceTickUnits(),2));
            assertThatThrownBy(() -> tx.execute(status -> storage.save(renamed.symbol(),
                    json.treeToValue(fields, InstrumentUpsertRequest.class), "operator-42", "unsafe unit change", Instant.now())))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("priceTickUnits");
            assertThat(storage.latest(initial.instrumentId(), line)).contains(renamed);
            fields.put("priceTickUnits", initial.priceTickUnits());
            fields.put("makerFeeRatePpm", initial.makerFeeRatePpm() + 1);
            var reconfigured=tx.execute(status -> storage.save(renamed.symbol(),json.treeToValue(fields,InstrumentUpsertRequest.class),
                    "operator-42","new fee policy",Instant.now()));
            assertThat(reconfigured.changeId()).isEqualTo(reconfigured.lastChangeId()).isGreaterThan(changed.lastChangeId());
            assertThat(audit.tradeEncoding(line,initial.instrumentId(),initial.changeId())).isEqualTo(oldUnits);
            assertThat(audit.tradeEncoding(line,initial.instrumentId(),reconfigured.changeId()).priceTickUnits()).isEqualTo(initial.priceTickUnits());
        }
    }
}
