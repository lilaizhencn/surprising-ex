package com.surprising.aeron.tools;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class ClusterInstrumentSeedMain {

    private ClusterInstrumentSeedMain() {
    }

    public static void main(String[] args) throws Exception {
        ProductLine productLine = ProductLine.requireExternalCode(required("PRODUCT_LINE"));
        List<String> hosts = Arrays.stream(value("AERON_HOSTNAMES", "localhost,localhost,localhost").split(","))
                .map(String::trim).filter(host -> !host.isEmpty()).toList();
        String egressHost = value("AERON_EGRESS_HOSTNAME", "localhost");
        String databaseUrl = value("DATABASE_URL", "jdbc:postgresql://localhost:5432/postgres");
        String databaseUser = value("DATABASE_USER", "postgres");
        String databasePassword = value("DATABASE_PASSWORD", "postgres");
        List<InstrumentSeed> instruments = load(databaseUrl, databaseUser, databasePassword, productLine);
        if (instruments.isEmpty()) {
            throw new IllegalStateException("no current instruments for " + productLine);
        }
        try (var clients = new AeronClientPool("instrument-seed", productLine, hosts, egressHost,
                Duration.ofSeconds(10), Math.min(8, instruments.size()))) {
            int applied = 0;
            int alreadyApplied = 0;
            for (InstrumentSeed instrument : instruments) {
                UUID commandId = UUID.nameUUIDFromBytes((productLine + ":instrument:"
                        + instrument.command().symbol() + ':' + instrument.command().instrumentVersion())
                        .getBytes(StandardCharsets.UTF_8));
                var response = clients.command(CoreMessageType.UPSERT_INSTRUMENT, commandId, 0,
                        TradingCommandCodec.encodeUpsertInstrument(instrument.command()));
                if (response.commandStatus() == ResponseStatus.REJECTED
                        && response.resultCode() == CoreResultCode.STALE_INSTRUMENT_VERSION) {
                    alreadyApplied++;
                    continue;
                }
                if (response.commandStatus() != ResponseStatus.APPLIED) {
                    throw new IllegalStateException("instrument rejected symbol=" + instrument.command().symbol()
                            + " result=" + response.resultCode());
                }
                applied++;
            }
            System.out.printf("instrumentSeed=PASS productLine=%s count=%d applied=%d alreadyApplied=%d%n",
                    productLine, instruments.size(), applied, alreadyApplied);
        }
    }

    private static List<InstrumentSeed> load(
            String url, String user, String password, ProductLine productLine) throws Exception {
        String sql = """
                SELECT i.symbol, i.version, i.contract_type, i.base_asset, i.quote_asset, i.settle_asset,
                       i.notional_multiplier_units, i.price_tick_units, i.initial_margin_rate_ppm,
                       i.maintenance_margin_rate_ppm, i.maker_fee_rate_ppm, i.taker_fee_rate_ppm,
                       i.expiry_time, i.option_type, i.strike_price_units, i.max_leverage_ppm,
                       i.max_position_notional_units, i.user_open_interest_limit_rate_ppm,
                       i.user_open_interest_limit_floor_units
                  FROM instrument_product_current_versions current
                  JOIN instruments i ON i.symbol=current.symbol AND i.version=current.version
                 WHERE current.product_line=? AND i.status='TRADING'
                 ORDER BY i.symbol
                """;
        List<InstrumentSeed> result = new ArrayList<>();
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, productLine.name());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    List<CoreRiskLimitBracket> brackets = loadBrackets(connection, rows.getString("symbol"),
                            rows.getLong("version"));
                    ContractType contractType = ContractType.valueOf(rows.getString("contract_type"));
                    var expiryTimestamp = rows.getTimestamp("expiry_time");
                    long expiry = expiryTimestamp == null ? 0 : expiryTimestamp.toInstant().toEpochMilli();
                    int optionType = rows.getString("option_type") == null ? -1
                            : "CALL".equals(rows.getString("option_type")) ? 0 : 1;
                    long strike = rows.getObject("strike_price_units") == null ? 0
                            : rows.getLong("strike_price_units") / rows.getLong("price_tick_units");
                    long settleScale = contractType.isInverse() ? 1_000L : 1L;
                    result.add(new InstrumentSeed(new UpsertInstrumentCommand(rows.getString("symbol"),
                            rows.getLong("version"), contractType.ordinal(), rows.getString("base_asset"),
                            rows.getString("quote_asset"), rows.getString("settle_asset"),
                            rows.getLong("notional_multiplier_units"), rows.getLong("price_tick_units"),
                            settleScale, rows.getLong("initial_margin_rate_ppm"),
                            rows.getLong("maintenance_margin_rate_ppm"), rows.getLong("maker_fee_rate_ppm"),
                            rows.getLong("taker_fee_rate_ppm"), expiry, optionType, strike,
                            rows.getLong("max_leverage_ppm"), rows.getLong("max_position_notional_units"),
                            rows.getLong("user_open_interest_limit_rate_ppm"),
                            rows.getLong("user_open_interest_limit_floor_units"), brackets)));
                }
            }
        }
        return result;
    }

    private static List<CoreRiskLimitBracket> loadBrackets(
            java.sql.Connection connection, String symbol, long version) throws Exception {
        String sql = """
                SELECT bracket_no, notional_floor_units, notional_cap_units, max_leverage_ppm,
                       initial_margin_rate_ppm, maintenance_margin_rate_ppm
                  FROM instrument_risk_brackets
                 WHERE symbol=? AND version=? ORDER BY bracket_no
                """;
        List<CoreRiskLimitBracket> result = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setLong(2, version);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new CoreRiskLimitBracket(rows.getInt("bracket_no"),
                            rows.getLong("notional_floor_units"), rows.getLong("notional_cap_units"),
                            rows.getLong("max_leverage_ppm"), rows.getLong("initial_margin_rate_ppm"),
                            rows.getLong("maintenance_margin_rate_ppm")));
                }
            }
        }
        return result;
    }

    private static String required(String name) {
        String configured = System.getenv(name);
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return configured.trim();
    }

    private static String value(String name, String fallback) {
        String configured = System.getenv(name);
        return configured == null || configured.isBlank() ? fallback : configured.trim();
    }

    private record InstrumentSeed(UpsertInstrumentCommand command) {
    }
}
