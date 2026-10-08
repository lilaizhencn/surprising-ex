package com.surprising.asset;

import static org.assertj.core.api.Assertions.*;
import com.surprising.asset.model.AssetConfiguration.*;
import com.surprising.asset.repository.*;
import com.surprising.asset.service.AssetConfigurationService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL", matches=".+")
class AssetConfigurationIntegrationTest {
    @Test
    void networksShareOneAssetAndConfigurationIsAuditedAndGuarded() {
        var source = new DriverManagerDataSource(System.getenv("INSTRUMENT_TEST_JDBC_URL"),
                System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var service = new AssetConfigurationService(new AssetRepository(jdbc), new AssetNetworkRepository(jdbc),
                new AssetConfigurationChangeRepository(jdbc), JsonMapper.builder().findAndAddModules().build());
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var asset = service.save(new AssetRequest(null, "UNITTEST", "测试币", "", 100000000L,
                    true, true, 0, "create test asset"), 42);
            var eth = service.saveNetwork(asset.assetId(), network(null,"ETH",0,false),42);
            var tron = service.saveNetwork(asset.assetId(), network(null,"TRON",0,false),42);
            assertThat(service.networks(asset.assetId())).hasSize(2).allMatch(n -> n.assetId()==asset.assetId());
            assertThat(eth.networkId()).isNotEqualTo(tron.networkId());
            assertThat(service.amountUnits("UNITTEST","0.00000001")).isEqualTo(1);
            assertThatThrownBy(() -> service.amountUnits("UNITTEST","0.000000001"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.amountUnits("UNITTEST","99999999999999999"))
                    .isInstanceOf(IllegalArgumentException.class);
            var renamed=service.save(new AssetRequest(asset.assetId(),asset.asset(),"新显示名","",asset.scaleUnits(),
                    true,true,asset.revision(),"rename display"),42);
            assertThat(renamed.assetId()).isEqualTo(asset.assetId());
            assertThat(service.networks(asset.assetId())).extracting(Network::networkId).containsExactly(eth.networkId(),tron.networkId());
            assertThatThrownBy(() -> service.save(new AssetRequest(asset.assetId(),asset.asset(),"outdated","",asset.scaleUnits(),
                    true,true,asset.revision(),"stale update"),42)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.save(new AssetRequest(asset.assetId(),asset.asset(),"new units","",1000,
                    true,true,renamed.revision(),"unsafe units"),42)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.saveNetwork(asset.assetId(),network(eth.networkId(),"OTHER",eth.revision(),false),42))
                    .isInstanceOf(IllegalArgumentException.class);
            var enabled=service.saveNetwork(asset.assetId(),network(eth.networkId(),"ETH",eth.revision(),true),42);
            assertThat(enabled.depositEnabled()).isTrue();
            assertThatThrownBy(() -> service.save(new AssetRequest(asset.assetId(),asset.asset(),"delist","",asset.scaleUnits(),
                    false,false,renamed.revision(),"delist"),42)).isInstanceOf(IllegalStateException.class);
            service.saveNetwork(asset.assetId(),network(eth.networkId(),"ETH",enabled.revision(),false),42);
            service.save(new AssetRequest(asset.assetId(),asset.asset(),"delisted","",asset.scaleUnits(),false,false,
                    renamed.revision(),"delist after closing networks"),42);
            assertThatThrownBy(() -> service.saveNetwork(asset.assetId(),network(null,"BSC",0,true),42))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(service.list(true)).noneMatch(a -> a.assetId()==asset.assetId());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_configuration_changes WHERE asset_id=?",
                    Integer.class,asset.assetId())).isEqualTo(7);
        });
    }

    @Test
    void databaseRejectsIdentityChangesAndUnknownAssetReferences() {
        var source = new DriverManagerDataSource(System.getenv("INSTRUMENT_TEST_JDBC_URL"),
                System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        assertThatThrownBy(() -> jdbc.update("UPDATE assets SET scale_units=1 WHERE asset='BTC'"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE assets SET asset_id=2147483647 WHERE asset='BTC'"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        Long instrumentId = jdbc.queryForObject(
                "SELECT instrument_id FROM instruments ORDER BY instrument_id LIMIT 1", Long.class);
        assertThat(instrumentId).isNotNull();
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE instruments SET base_asset_id=2147483647 WHERE instrument_id=?", instrumentId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private static NetworkRequest network(Integer id,String code,long revision,boolean enabled) {
        return new NetworkRequest(id,code,code,"contract-"+code,false,6,enabled,enabled,
                new BigDecimal("0.01"),new BigDecimal("1"),new BigDecimal("0.1"),12,revision,"test network configuration");
    }
}
