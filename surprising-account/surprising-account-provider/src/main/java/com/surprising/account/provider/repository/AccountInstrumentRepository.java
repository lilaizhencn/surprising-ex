package com.surprising.account.provider.repository;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.account.provider.model.SpotInstrumentSpec;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.product.api.ProductLine;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountInstrumentRepository {

    private final AccountProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    public AccountInstrumentRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new AccountProperties(), null, false);
    }

    public AccountInstrumentRepository(JdbcTemplate jdbcTemplate, AccountProperties properties) {
        this(jdbcTemplate, properties, null, false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AccountInstrumentRepository(JdbcTemplate jdbcTemplate,
                                       AccountProperties properties,
                                       InstrumentSnapshotCache snapshotCache) {
        this(jdbcTemplate, properties, snapshotCache, true);
    }

    private AccountInstrumentRepository(JdbcTemplate jdbcTemplate,
                                        AccountProperties properties,
                                        InstrumentSnapshotCache snapshotCache,
                                        boolean springManaged) {
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    public Optional<String> findSettleAsset(String symbol, long instrumentVersion) {
        return requiredSnapshot(symbol, instrumentVersion).map(InstrumentResponse::settleAsset);
    }

    public Optional<SpotInstrumentSpec> findSpotSpec(String symbol, long instrumentVersion) {
        return requiredSnapshot(symbol, instrumentVersion)
                    .filter(value -> value.instrumentType() == InstrumentType.SPOT
                            && value.contractType() == ContractType.SPOT)
                    .map(value -> new SpotInstrumentSpec(value.version(), value.baseAsset(), value.quoteAsset(),
                            value.quantityStepUnits(), value.notionalMultiplierUnits()));
    }

    public Optional<ContractInstrumentRow> findContractSpec(String symbol, long instrumentVersion) {
        return requiredSnapshot(symbol, instrumentVersion)
                    .filter(value -> value.contractType() != ContractType.SPOT)
                    .map(value -> new ContractInstrumentRow(value.version(), value.contractType(), value.settleAsset(),
                            value.notionalMultiplierUnits(), value.priceTickUnits(), value.initialMarginRatePpm(),
                            value.makerFeeRatePpm(), value.takerFeeRatePpm()));
    }

    public Optional<InstrumentType> findInstrumentType(String symbol, long instrumentVersion) {
        return requiredSnapshot(symbol, instrumentVersion).map(InstrumentResponse::instrumentType);
    }

    private Optional<InstrumentResponse> requiredSnapshot(String symbol, long version) {
        if (snapshotCache == null) {
            return Optional.empty();
        }
        ProductLine productLine = properties.getKafka().getProductLine();
        if (!snapshotCache.initialized(productLine)) {
            throw new IllegalStateException("账户合约 JVM 快照尚未就绪");
        }
        return snapshotCache.version(productLine, symbol, version);
    }

    public record ContractInstrumentRow(
            long version,
            ContractType contractType,
            String settleAsset,
            long notionalMultiplierUnits,
            long priceTickUnits,
            long initialMarginRatePpm,
            long makerFeeRatePpm,
            long takerFeeRatePpm) {
    }
}
