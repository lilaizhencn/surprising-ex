package com.surprising.aeron.tools.instrument;

import lombok.extern.slf4j.Slf4j;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
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

@Slf4j
public final class ClusterInstrumentSeedMain {

    private ClusterInstrumentSeedMain() {
    }

    public static void main(String[] args) throws Exception {
        ProductLine productLine = ProductLine.requireExternalCode(required("PRODUCT_LINE"));
        List<String> hosts = Arrays.stream(value("AERON_HOSTNAMES", "localhost,localhost,localhost").split(","))
                .map(String::trim).filter(host -> !host.isEmpty()).toList();
        String egressHost = value("AERON_EGRESS_HOSTNAME", "localhost");
        if (args.length == 1 && "--risk-scan-only".equals(args[0])) {
            try (var clients = new AeronClientPool("local-risk-scan", productLine, hosts, egressHost,
                    Duration.ofSeconds(10), 1)) {
                configureRiskBudget(clients, Integer.parseInt(required("RISK_SCAN_BATCH_SIZE")));
            }
            return;
        }
        String databaseUrl = value("DATABASE_URL", "jdbc:postgresql://localhost:5432/postgres");
        String databaseUser = value("DATABASE_USER", "postgres");
        String databasePassword = value("DATABASE_PASSWORD", "postgres");
        List<InstrumentSeed> instruments = load(databaseUrl, databaseUser, databasePassword, productLine);
        if (instruments.isEmpty()) {
            throw new IllegalStateException("no current instruments for " + productLine);
        }
        try (var clients = new AeronClientPool("instrument-seed", productLine, hosts, egressHost,
                Duration.ofSeconds(10), Math.min(8, instruments.size()))) {
            String riskBudget = System.getenv("RISK_SCAN_BATCH_SIZE");
            if (riskBudget != null && !riskBudget.isBlank()) {
                int batchSize = Integer.parseInt(riskBudget);
                configureRiskBudget(clients, batchSize);
            }
            int applied = 0;
            for (InstrumentSeed instrument : instruments) {
                UUID commandId = UUID.nameUUIDFromBytes((productLine + ":instrument:"
                        + instrument.command().symbol())
                        .getBytes(StandardCharsets.UTF_8));
                var response = clients.command(CoreMessageType.REGISTER_INSTRUMENT, commandId, 0,
                        TradingCommandCodec.encodeRegisterInstrument(instrument.command()));
                if (response.commandStatus() != ResponseStatus.APPLIED) {
                    throw new IllegalStateException("instrument rejected symbol=" + instrument.command().symbol()
                            + " result=" + response.resultCode());
                }
                applied++;
            }
            log.info("instrumentSeed=PASS productLine={} count={} applied={}", productLine, instruments.size(), applied);
        }
    }

    /** Explicit startup setting; preserve the enabled flag and the scan interval. */
    static void configureRiskBudget(AeronClientPool clients, int batchSize) {
        if (batchSize < 1 || batchSize > 4096) throw new IllegalArgumentException("RISK_SCAN_BATCH_SIZE must be in [1,4096]");
        var query = clients.query(CoreMessageType.RISK_SCAN_CONTROL_QUERY, UUID.randomUUID(), 0, new byte[0]);
        if (query.status() != ResponseStatus.OK) throw new IllegalStateException("cannot read risk scan control");
        var control = com.surprising.aeron.protocol.CoreRiskScanControlCodec.decodeView(query.data());
        if (control.scanBatchSize() == batchSize) return;
        var update = new com.surprising.aeron.protocol.UpdateRiskScanControlCommand(control.version(),
                control.ruleName(), control.enabled(), control.scanDelayMs(), batchSize,
                "instrument-seed", "Configured bounded risk scan work for this product line");
        var result = clients.command(CoreMessageType.UPDATE_RISK_SCAN_CONTROL, UUID.randomUUID(), 0,
                com.surprising.aeron.protocol.CoreRiskScanControlCodec.encodeCommand(update));
        if (result.commandStatus() != ResponseStatus.APPLIED) throw new IllegalStateException("risk scan configuration rejected: " + result.resultCode());
    }

    private static List<InstrumentSeed> load(
            String url, String user, String password, ProductLine productLine) throws Exception {
        String sql = """
                SELECT i.symbol, a.scale_units AS settle_scale_units, i.contract_type, i.base_asset, i.quote_asset, i.settle_asset,
                       i.notional_multiplier_units, i.price_tick_units, i.initial_margin_rate_ppm,
                       i.maintenance_margin_rate_ppm, i.maker_fee_rate_ppm, i.taker_fee_rate_ppm,
                       i.expiry_time, i.option_type, i.strike_price_units, i.max_leverage_ppm,
                       i.max_position_notional_units, i.user_open_interest_limit_rate_ppm,
                       i.user_open_interest_limit_floor_units
                  FROM instruments i
                  JOIN account_asset_scales a ON a.asset=i.settle_asset
                 WHERE i.product_line=?
                 ORDER BY i.symbol
                """;
        List<InstrumentSeed> result = new ArrayList<>();
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, productLine.name());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    List<CoreRiskLimitBracket> brackets = loadBrackets(connection, rows.getString("symbol"),
                            productLine.name());
                    if (brackets.isEmpty()) {
                        brackets = List.of(new CoreRiskLimitBracket(1, 0,
                                rows.getLong("max_position_notional_units"), rows.getLong("max_leverage_ppm"),
                                rows.getLong("initial_margin_rate_ppm"), rows.getLong("maintenance_margin_rate_ppm")));
                    }
                    ContractType contractType = ContractType.valueOf(rows.getString("contract_type"));
                    var expiryTimestamp = rows.getTimestamp("expiry_time");
                    long expiry = expiryTimestamp == null ? 0 : expiryTimestamp.toInstant().toEpochMilli();
                    int optionType = rows.getString("option_type") == null ? -1
                            : "CALL".equals(rows.getString("option_type")) ? 0 : 1;
                    long strike = rows.getObject("strike_price_units") == null ? 0
                            : rows.getLong("strike_price_units") / rows.getLong("price_tick_units");
                    long settleScale = contractType.isInverse() ? rows.getLong("settle_scale_units") : 1L;
                    result.add(new InstrumentSeed(new RegisterInstrumentCommand(rows.getString("symbol"), contractType.ordinal(), rows.getString("base_asset"),
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
            java.sql.Connection connection, String symbol, String productLine) throws Exception {
        String sql = """
                SELECT bracket_no, notional_floor_units, notional_cap_units, max_leverage_ppm,
                       initial_margin_rate_ppm, maintenance_margin_rate_ppm, option_margin_factor_ppm
                  FROM instrument_risk_brackets
                 WHERE symbol=? AND product_line=? ORDER BY bracket_no
                """;
        List<CoreRiskLimitBracket> result = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setString(2, productLine);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new CoreRiskLimitBracket(rows.getInt("bracket_no"),
                            rows.getLong("notional_floor_units"), rows.getLong("notional_cap_units"),
                            rows.getLong("max_leverage_ppm"), rows.getLong("initial_margin_rate_ppm"),
                            rows.getLong("maintenance_margin_rate_ppm"),
                            rows.getLong("option_margin_factor_ppm")));
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

    private record InstrumentSeed(RegisterInstrumentCommand command) {
    }
}
