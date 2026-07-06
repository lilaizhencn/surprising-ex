package com.surprising.trading.order.repository;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.InstrumentRuleLookup;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InstrumentRuleRepository implements InstrumentRuleLookup {

    private final JdbcTemplate jdbcTemplate;
    private final TradingOrderProperties properties;

    public InstrumentRuleRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new TradingOrderProperties());
    }

    public InstrumentRuleRepository(JdbcTemplate jdbcTemplate, TradingOrderProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Override
    public Optional<InstrumentRule> currentRule(String symbol) {
        // Instrument owns the exact long unit conversion used by exchange-core.
        StringBuilder sql = new StringBuilder("""
                SELECT i.symbol,
                       i.version,
                       i.status,
                       i.instrument_type,
                       i.contract_type,
                       i.base_asset,
                       i.quote_asset,
                       i.settle_asset,
                       i.supported_order_types,
                       i.supported_time_in_force,
                       i.market_order_enabled,
                       i.post_only_enabled,
                       i.reduce_only_enabled,
                       i.quantity_step_units,
                       i.min_quantity_steps,
                       i.max_quantity_steps,
                       i.min_notional_units,
                       i.max_notional_units,
                       i.notional_multiplier_units,
                       i.max_leverage_ppm,
                       i.initial_margin_rate_ppm
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                 WHERE i.symbol = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(symbol);
        productContractTypeFilter().ifPresent(contractType -> {
            sql.append("   AND i.contract_type = ?\n");
            args.add(contractType);
        });
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new InstrumentRule(
                rs.getString("symbol"),
                rs.getLong("version"),
                rs.getString("status"),
                InstrumentType.valueOf(rs.getString("instrument_type")),
                ContractType.valueOf(rs.getString("contract_type")),
                rs.getString("base_asset"),
                rs.getString("quote_asset"),
                rs.getString("settle_asset"),
                csv(rs.getString("supported_order_types")),
                csv(rs.getString("supported_time_in_force")),
                rs.getBoolean("market_order_enabled"),
                rs.getBoolean("post_only_enabled"),
                rs.getBoolean("reduce_only_enabled"),
                rs.getLong("quantity_step_units"),
                rs.getLong("min_quantity_steps"),
                rs.getLong("max_quantity_steps"),
                rs.getLong("min_notional_units"),
                rs.getLong("max_notional_units"),
                rs.getLong("notional_multiplier_units"),
                rs.getLong("max_leverage_ppm"),
                rs.getLong("initial_margin_rate_ppm")), args.toArray()).stream().findFirst();
    }

    private Set<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private Optional<String> productContractTypeFilter() {
        TradingOrderProperties.Kafka kafka = properties.getKafka();
        return kafka.isProductTopicsEnabled()
                ? Optional.of(kafka.getProductLine().contractTypeCode())
                : Optional.empty();
    }
}
