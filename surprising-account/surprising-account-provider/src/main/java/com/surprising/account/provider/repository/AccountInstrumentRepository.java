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

    private final JdbcTemplate jdbcTemplate;
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
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    public Optional<String> findSettleAsset(String symbol, long instrumentVersion) {
        Optional<InstrumentResponse> cached = cached(symbol, instrumentVersion);
        if (cached != null) {
            return cached.map(InstrumentResponse::settleAsset);
        }
        return jdbcTemplate.query("""
                SELECT settle_asset
                  FROM instruments
                 WHERE symbol = ?
                   AND version = ?
                """, (rs, rowNum) -> rs.getString("settle_asset"), symbol, instrumentVersion)
                .stream().findFirst();
    }

    public Optional<SpotInstrumentSpec> findSpotSpec(String symbol, long instrumentVersion) {
        Optional<InstrumentResponse> cached = cached(symbol, instrumentVersion);
        if (cached != null) {
            return cached.filter(value -> value.instrumentType() == InstrumentType.SPOT
                            && value.contractType() == ContractType.SPOT)
                    .map(value -> new SpotInstrumentSpec(value.version(), value.baseAsset(), value.quoteAsset(),
                            value.quantityStepUnits(), value.notionalMultiplierUnits()));
        }
        return jdbcTemplate.query("""
                SELECT version, base_asset, quote_asset, quantity_step_units, notional_multiplier_units
                  FROM instruments
                 WHERE symbol = ?
                   AND version = ?
                   AND instrument_type = 'SPOT'
                   AND contract_type = 'SPOT'
                """, (rs, rowNum) -> new SpotInstrumentSpec(
                rs.getLong("version"),
                rs.getString("base_asset"),
                rs.getString("quote_asset"),
                rs.getLong("quantity_step_units"),
                rs.getLong("notional_multiplier_units")), symbol, instrumentVersion)
                .stream().findFirst();
    }

    public Optional<ContractInstrumentRow> findContractSpec(String symbol, long instrumentVersion) {
        Optional<InstrumentResponse> cached = cached(symbol, instrumentVersion);
        if (cached != null) {
            return cached.filter(value -> value.contractType() != ContractType.SPOT)
                    .map(value -> new ContractInstrumentRow(value.version(), value.contractType(), value.settleAsset(),
                            value.notionalMultiplierUnits(), value.priceTickUnits(), value.initialMarginRatePpm(),
                            value.makerFeeRatePpm(), value.takerFeeRatePpm()));
        }
        return jdbcTemplate.query("""
                SELECT version, contract_type, settle_asset, notional_multiplier_units,
                       price_tick_units, initial_margin_rate_ppm, maker_fee_rate_ppm,
                       taker_fee_rate_ppm
                  FROM instruments
                 WHERE symbol = ?
                   AND version = ?
                """, (rs, rowNum) -> new ContractInstrumentRow(
                        rs.getLong("version"),
                        ContractType.valueOf(rs.getString("contract_type")),
                        rs.getString("settle_asset"),
                        rs.getLong("notional_multiplier_units"),
                        rs.getLong("price_tick_units"),
                        rs.getLong("initial_margin_rate_ppm"),
                        rs.getLong("maker_fee_rate_ppm"),
                        rs.getLong("taker_fee_rate_ppm")), symbol, instrumentVersion)
                .stream().findFirst();
    }

    public Optional<InstrumentType> findInstrumentType(String symbol, long instrumentVersion) {
        Optional<InstrumentResponse> cached = cached(symbol, instrumentVersion);
        if (cached != null) {
            return cached.map(InstrumentResponse::instrumentType);
        }
        return jdbcTemplate.query("""
                SELECT instrument_type
                  FROM instruments
                 WHERE symbol = ?
                   AND version = ?
                """, (rs, rowNum) -> InstrumentType.valueOf(rs.getString("instrument_type")),
                        symbol, instrumentVersion)
                .stream().findFirst();
    }

    private Optional<InstrumentResponse> cached(String symbol, long version) {
        if (snapshotCache == null) {
            return null;
        }
        ProductLine productLine = properties.getKafka().getProductLine();
        if (!snapshotCache.initialized(productLine)) {
            return null;
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
