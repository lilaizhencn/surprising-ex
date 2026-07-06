package com.surprising.instrument.provider.repository;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.ContractSettlementMethod;
import com.surprising.instrument.api.model.IndexSourceConfig;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.api.model.OptionExerciseStyle;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.instrument.api.model.RiskLimitBracket;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InstrumentRepository {

    private static final String INSERT_INSTRUMENT_SQL = """
            INSERT INTO instruments (
                symbol, version, instrument_type, contract_type, base_asset, quote_asset, settle_asset,
                contract_multiplier_ppm, contract_value_asset, price_tick_units, quantity_step_units,
                min_quantity_steps, max_quantity_steps, min_notional_units, max_notional_units,
                notional_multiplier_units,
                price_precision, quantity_precision, supported_order_types, supported_time_in_force,
                post_only_enabled, reduce_only_enabled, market_order_enabled,
                max_leverage_ppm, initial_margin_rate_ppm, maintenance_margin_rate_ppm,
                maker_fee_rate_ppm, taker_fee_rate_ppm, max_position_notional_units,
                user_open_interest_limit_rate_ppm, user_open_interest_limit_floor_units,
                funding_interval_hours, interest_rate_ppm, funding_rate_cap_ppm, funding_rate_floor_ppm,
                impact_notional_units, min_valid_index_sources,
                expiry_time, delivery_time, underlying_symbol, strike_price_units,
                option_type, option_exercise_style, settlement_method,
                status, effective_time, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_BRACKET_SQL = """
            INSERT INTO instrument_risk_brackets (
                symbol, version, bracket_no, notional_floor_units, notional_cap_units,
                max_leverage_ppm, initial_margin_rate_ppm, maintenance_margin_rate_ppm
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_SOURCE_SQL = """
            INSERT INTO instrument_index_sources (
                symbol, version, source, enabled, base_url, path, source_symbol, parser,
                quote_currency, target_quote_currency, conversion_base_url, conversion_path,
                conversion_parser, conversion_mode, conversion_operation, fallback_weight_multiplier_ppm,
                websocket_enabled, websocket_url, websocket_subscribe_message, websocket_parser, weight_ppm
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final int MAX_PAGE_LIMIT = 1000;
    private static final InstrumentSort INSTRUMENT_SYMBOL_ASC =
            new InstrumentSort("symbol.asc", "symbol", "i.symbol", false);
    private static final List<InstrumentSort> INSTRUMENT_SORTS = List.of(
            INSTRUMENT_SYMBOL_ASC,
            new InstrumentSort("symbol.desc", "symbol", "i.symbol", true),
            new InstrumentSort("updatedAt.desc", "updatedAt", "i.updated_at", true),
            new InstrumentSort("updatedAt.asc", "updatedAt", "i.updated_at", false),
            new InstrumentSort("createdAt.desc", "createdAt", "i.created_at", true),
            new InstrumentSort("createdAt.asc", "createdAt", "i.created_at", false));
    private static final VersionSort VERSION_DESC = new VersionSort("version.desc", true);
    private static final List<VersionSort> VERSION_SORTS = List.of(
            VERSION_DESC,
            new VersionSort("version.asc", false));

    private final JdbcTemplate jdbcTemplate;

    public InstrumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long nextVersion(String symbol) {
        Long version = jdbcTemplate.queryForObject("""
                INSERT INTO instrument_symbol_sequences (symbol, version, updated_at)
                VALUES (?, COALESCE((SELECT MAX(i.version) FROM instruments i WHERE i.symbol = ?), 0) + 1, now())
                ON CONFLICT (symbol) DO UPDATE SET
                    version = instrument_symbol_sequences.version + 1,
                    updated_at = now()
                RETURNING version
                """, Long.class, symbol, symbol);
        if (version == null) {
            throw new IllegalStateException("Failed to allocate instrument version for " + symbol);
        }
        return version;
    }

    public void insert(String symbol, long version, InstrumentUpsertRequest request, Instant now) {
        Instant effectiveTime = request.effectiveTime() == null ? now : request.effectiveTime();
        jdbcTemplate.update(INSERT_INSTRUMENT_SQL,
                symbol, version, request.instrumentType().name(), request.contractType().name(),
                asset(request.baseAsset()), asset(request.quoteAsset()), asset(request.settleAsset()),
                request.contractMultiplierPpm(), asset(request.contractValueAsset()),
                request.priceTickUnits(), request.quantityStepUnits(), request.minQuantitySteps(),
                request.maxQuantitySteps(), request.minNotionalUnits(), request.maxNotionalUnits(),
                request.notionalMultiplierUnits(), request.pricePrecision(), request.quantityPrecision(),
                csv(request.supportedOrderTypes()), csv(request.supportedTimeInForce()),
                request.postOnlyEnabled(), request.reduceOnlyEnabled(), request.marketOrderEnabled(),
                request.maxLeveragePpm(), request.initialMarginRatePpm(), request.maintenanceMarginRatePpm(),
                request.makerFeeRatePpm(), request.takerFeeRatePpm(), request.maxPositionNotionalUnits(),
                request.userOpenInterestLimitRatePpm(), request.userOpenInterestLimitFloorUnits(),
                request.fundingIntervalHours(), request.interestRatePpm(), request.fundingRateCapPpm(),
                request.fundingRateFloorPpm(), request.impactNotionalUnits(), request.minValidIndexSources(),
                timestampOrNull(request.expiryTime()), timestampOrNull(request.deliveryTime()),
                symbolOrNull(request.underlyingSymbol()), request.strikePriceUnits(),
                enumName(request.optionType()), enumName(request.optionExerciseStyle()),
                enumName(request.settlementMethod()),
                request.status().name(), Timestamp.from(effectiveTime), Timestamp.from(now), Timestamp.from(now));
        insertBrackets(symbol, version, request.riskLimitBrackets());
        insertSources(symbol, version, request.indexSources());
    }

    public void setCurrentVersion(String symbol, long version, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO instrument_current_versions (symbol, version, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT (symbol) DO UPDATE SET
                    version = EXCLUDED.version,
                    updated_at = EXCLUDED.updated_at
                """, symbol, version, Timestamp.from(now));
    }

    public Optional<InstrumentResponse> latest(String symbol) {
        String sql = """
                SELECT i.*
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                 WHERE i.symbol = ?
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> toResponse(rs), symbol).stream().findFirst();
    }

    public Optional<InstrumentResponse> version(String symbol, long version) {
        return jdbcTemplate.query("SELECT * FROM instruments WHERE symbol = ? AND version = ?",
                (rs, rowNum) -> toResponse(rs), symbol, version).stream().findFirst();
    }

    public List<InstrumentResponse> list(InstrumentType type, InstrumentStatus status) {
        StringBuilder sql = new StringBuilder("""
                SELECT i.*
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                 WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (type != null) {
            sql.append(" AND i.instrument_type = ?");
            args.add(type.name());
        }
        if (status != null) {
            sql.append(" AND i.status = ?");
            args.add(status.name());
        }
        sql.append(" ORDER BY i.symbol ASC");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> toResponse(rs), args.toArray());
    }

    public InstrumentPage listPage(InstrumentType type, InstrumentStatus status, int limit, String cursor, String sort) {
        int safeLimit = limit(limit);
        InstrumentSort sortSpec = parseInstrumentSort(sort);
        InstrumentCursor decodedCursor = decodeInstrumentCursor(cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT i.*
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                 WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (type != null) {
            sql.append(" AND i.instrument_type = ?");
            args.add(type.name());
        }
        if (status != null) {
            sql.append(" AND i.status = ?");
            args.add(status.name());
        }
        if (decodedCursor != null) {
            sql.append(listSeekCondition(sortSpec));
            addInstrumentCursorArgs(args, decodedCursor, sortSpec);
        }
        sql.append(" ORDER BY ").append(sortSpec.orderBy()).append(" LIMIT ?");
        args.add(safeLimit + 1);
        List<InstrumentResponse> fetchedRows = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> toResponse(rs),
                args.toArray());
        return page(fetchedRows, safeLimit, sortSpec.token(), row -> encodeInstrumentCursor(row, sortSpec));
    }

    public InstrumentPage versionsPage(String symbol, int limit, String cursor, String sort) {
        int safeLimit = limit(limit);
        VersionSort sortSpec = parseVersionSort(sort);
        Long decodedCursor = decodeVersionCursor(cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT *
                  FROM instruments
                 WHERE symbol = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(symbol);
        if (decodedCursor != null) {
            sql.append(" AND version ").append(sortSpec.descending() ? "<" : ">").append(" ?");
            args.add(decodedCursor);
        }
        sql.append(" ORDER BY version ").append(sortSpec.directionSql()).append(" LIMIT ?");
        args.add(safeLimit + 1);
        List<InstrumentResponse> fetchedRows = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> toResponse(rs),
                args.toArray());
        return page(fetchedRows, safeLimit, sortSpec.token(), row -> encodeVersionCursor(row.version()));
    }

    public List<InstrumentResponse> expiringContractsDue(Instant now, int limit) {
        return jdbcTemplate.query("""
                SELECT i.*
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                 WHERE i.instrument_type IN ('DELIVERY', 'OPTION')
                   AND i.status IN ('PRE_TRADING', 'TRADING', 'HALT')
                   AND i.expiry_time IS NOT NULL
                   AND i.expiry_time <= ?
                 ORDER BY i.expiry_time ASC, i.symbol ASC
                 LIMIT ?
                """, (rs, rowNum) -> toResponse(rs), Timestamp.from(now), limit(limit));
    }

    public List<InstrumentResponse> settlingContractsDue(Instant now, int limit) {
        return jdbcTemplate.query("""
                SELECT i.*
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                 WHERE i.instrument_type IN ('DELIVERY', 'OPTION')
                   AND i.status = 'SETTLING'
                   AND i.delivery_time IS NOT NULL
                   AND i.delivery_time <= ?
                 ORDER BY i.delivery_time ASC, i.symbol ASC
                 LIMIT ?
                """, (rs, rowNum) -> toResponse(rs), Timestamp.from(now), limit(limit));
    }

    private void insertBrackets(String symbol, long version, List<RiskLimitBracket> brackets) {
        if (brackets == null || brackets.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_BRACKET_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
                RiskLimitBracket bracket = brackets.get(i);
                ps.setString(1, symbol);
                ps.setLong(2, version);
                ps.setInt(3, bracket.bracketNo());
                ps.setLong(4, bracket.notionalFloorUnits());
                ps.setLong(5, bracket.notionalCapUnits());
                ps.setLong(6, bracket.maxLeveragePpm());
                ps.setLong(7, bracket.initialMarginRatePpm());
                ps.setLong(8, bracket.maintenanceMarginRatePpm());
            }

            @Override
            public int getBatchSize() {
                return brackets.size();
            }
        });
    }

    private void insertSources(String symbol, long version, List<IndexSourceConfig> sources) {
        if (sources == null || sources.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SOURCE_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
                IndexSourceConfig source = sources.get(i);
                ps.setString(1, symbol);
                ps.setLong(2, version);
                ps.setString(3, source.source());
                ps.setBoolean(4, source.enabled());
                ps.setString(5, source.baseUrl());
                ps.setString(6, source.path());
                ps.setString(7, source.sourceSymbol());
                ps.setString(8, source.parser());
                ps.setString(9, defaultText(source.quoteCurrency(), "USDT"));
                ps.setString(10, defaultText(source.targetQuoteCurrency(), "USDT"));
                ps.setString(11, source.conversionBaseUrl());
                ps.setString(12, source.conversionPath());
                ps.setString(13, source.conversionParser());
                ps.setString(14, defaultText(source.conversionMode(), "DISCOUNT"));
                ps.setString(15, defaultText(source.conversionOperation(), "MULTIPLY"));
                ps.setLong(16, positiveOrDefault(source.fallbackWeightMultiplierPpm(), 500_000L));
                ps.setBoolean(17, source.websocketEnabled());
                ps.setString(18, source.websocketUrl());
                ps.setString(19, source.websocketSubscribeMessage());
                ps.setString(20, source.websocketParser());
                ps.setLong(21, source.weightPpm());
            }

            @Override
            public int getBatchSize() {
                return sources.size();
            }
        });
    }

    private InstrumentResponse toResponse(java.sql.ResultSet rs) throws java.sql.SQLException {
        String symbol = rs.getString("symbol");
        long version = rs.getLong("version");
        return new InstrumentResponse(
                symbol,
                version,
                InstrumentType.valueOf(rs.getString("instrument_type")),
                ContractType.valueOf(rs.getString("contract_type")),
                rs.getString("base_asset"),
                rs.getString("quote_asset"),
                rs.getString("settle_asset"),
                rs.getLong("contract_multiplier_ppm"),
                rs.getString("contract_value_asset"),
                rs.getLong("price_tick_units"),
                rs.getLong("quantity_step_units"),
                rs.getLong("min_quantity_steps"),
                rs.getLong("max_quantity_steps"),
                rs.getLong("min_notional_units"),
                rs.getLong("max_notional_units"),
                rs.getLong("notional_multiplier_units"),
                rs.getInt("price_precision"),
                rs.getInt("quantity_precision"),
                list(rs.getString("supported_order_types")),
                list(rs.getString("supported_time_in_force")),
                rs.getBoolean("post_only_enabled"),
                rs.getBoolean("reduce_only_enabled"),
                rs.getBoolean("market_order_enabled"),
                rs.getLong("max_leverage_ppm"),
                rs.getLong("initial_margin_rate_ppm"),
                rs.getLong("maintenance_margin_rate_ppm"),
                rs.getLong("maker_fee_rate_ppm"),
                rs.getLong("taker_fee_rate_ppm"),
                rs.getLong("max_position_notional_units"),
                rs.getLong("user_open_interest_limit_rate_ppm"),
                rs.getLong("user_open_interest_limit_floor_units"),
                rs.getInt("funding_interval_hours"),
                rs.getLong("interest_rate_ppm"),
                rs.getLong("funding_rate_cap_ppm"),
                rs.getLong("funding_rate_floor_ppm"),
                rs.getLong("impact_notional_units"),
                rs.getInt("min_valid_index_sources"),
                instantOrNull(rs, "expiry_time"),
                instantOrNull(rs, "delivery_time"),
                rs.getString("underlying_symbol"),
                longOrNull(rs, "strike_price_units"),
                enumOrNull(rs, "option_type", OptionType.class),
                enumOrNull(rs, "option_exercise_style", OptionExerciseStyle.class),
                enumOrNull(rs, "settlement_method", ContractSettlementMethod.class),
                InstrumentStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("effective_time").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                riskLimitBrackets(symbol, version),
                indexSources(symbol, version));
    }

    private List<RiskLimitBracket> riskLimitBrackets(String symbol, long version) {
        return jdbcTemplate.query("""
                SELECT bracket_no, notional_floor_units, notional_cap_units, max_leverage_ppm,
                       initial_margin_rate_ppm, maintenance_margin_rate_ppm
                  FROM instrument_risk_brackets
                 WHERE symbol = ? AND version = ?
                 ORDER BY bracket_no ASC
                """, (rs, rowNum) -> new RiskLimitBracket(
                rs.getInt("bracket_no"),
                rs.getLong("notional_floor_units"),
                rs.getLong("notional_cap_units"),
                rs.getLong("max_leverage_ppm"),
                rs.getLong("initial_margin_rate_ppm"),
                rs.getLong("maintenance_margin_rate_ppm")), symbol, version);
    }

    private List<IndexSourceConfig> indexSources(String symbol, long version) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM instrument_index_sources
                 WHERE symbol = ? AND version = ?
                 ORDER BY source ASC
                """, (rs, rowNum) -> new IndexSourceConfig(
                rs.getString("source"),
                rs.getBoolean("enabled"),
                rs.getString("base_url"),
                rs.getString("path"),
                rs.getString("source_symbol"),
                rs.getString("parser"),
                rs.getString("quote_currency"),
                rs.getString("target_quote_currency"),
                rs.getString("conversion_base_url"),
                rs.getString("conversion_path"),
                rs.getString("conversion_parser"),
                rs.getString("conversion_mode"),
                rs.getString("conversion_operation"),
                rs.getLong("fallback_weight_multiplier_ppm"),
                rs.getBoolean("websocket_enabled"),
                rs.getString("websocket_url"),
                rs.getString("websocket_subscribe_message"),
                rs.getString("websocket_parser"),
                rs.getLong("weight_ppm")), symbol, version);
    }

    private InstrumentPage page(List<InstrumentResponse> fetchedRows,
                                int limit,
                                String sort,
                                Function<InstrumentResponse, String> cursorEncoder) {
        boolean hasMore = fetchedRows.size() > limit;
        List<InstrumentResponse> rows = hasMore
                ? List.copyOf(fetchedRows.subList(0, limit))
                : List.copyOf(fetchedRows);
        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            nextCursor = cursorEncoder.apply(rows.get(rows.size() - 1));
        }
        return new InstrumentPage(rows, nextCursor, hasMore, sort, limit);
    }

    private int limit(int value) {
        return Math.max(1, Math.min(value, MAX_PAGE_LIMIT));
    }

    private InstrumentSort parseInstrumentSort(String value) {
        if (value == null || value.isBlank()) {
            return INSTRUMENT_SYMBOL_ASC;
        }
        String normalized = value.trim();
        return INSTRUMENT_SORTS.stream()
                .filter(item -> item.token().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported sort: " + value));
    }

    private VersionSort parseVersionSort(String value) {
        if (value == null || value.isBlank()) {
            return VERSION_DESC;
        }
        String normalized = value.trim();
        return VERSION_SORTS.stream()
                .filter(item -> item.token().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported sort: " + value));
    }

    private String listSeekCondition(InstrumentSort sort) {
        String operator = sort.descending() ? "<" : ">";
        if (sort.symbolSort()) {
            return " AND i.symbol " + operator + " ?";
        }
        return " AND (" + sort.column() + " " + operator + " ? OR ("
                + sort.column() + " = ? AND i.symbol " + operator + " ?))";
    }

    private void addInstrumentCursorArgs(List<Object> args, InstrumentCursor cursor, InstrumentSort sort) {
        if (sort.symbolSort()) {
            args.add(cursor.symbol());
            return;
        }
        try {
            Timestamp timestamp = Timestamp.from(Instant.parse(cursor.sortValue()));
            args.add(timestamp);
            args.add(timestamp);
            args.add(cursor.symbol());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid cursor", ex);
        }
    }

    private InstrumentCursor decodeInstrumentCursor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value.trim()), StandardCharsets.UTF_8);
            int split = decoded.lastIndexOf('|');
            if (split <= 0 || split == decoded.length() - 1) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return new InstrumentCursor(decoded.substring(0, split), decoded.substring(split + 1));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid cursor", ex);
        }
    }

    private Long decodeVersionCursor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value.trim()), StandardCharsets.UTF_8);
            return Long.parseLong(decoded);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid cursor", ex);
        }
    }

    private String encodeInstrumentCursor(InstrumentResponse response, InstrumentSort sort) {
        String sortValue = switch (sort.field()) {
            case "symbol" -> response.symbol();
            case "updatedAt" -> response.updatedAt().toString();
            case "createdAt" -> response.createdAt().toString();
            default -> throw new IllegalArgumentException("unsupported sort: " + sort.token());
        };
        return encode(sortValue + "|" + response.symbol());
    }

    private String encodeVersionCursor(long version) {
        return encode(String.valueOf(version));
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> list(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private String csv(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join(",", values.stream().map(String::trim).map(String::toUpperCase).toList());
    }

    private String asset(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    private String symbolOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    private Timestamp timestampOrNull(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private Instant instantOrNull(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Long longOrNull(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private <T extends Enum<T>> T enumOrNull(java.sql.ResultSet rs, String column, Class<T> type)
            throws java.sql.SQLException {
        String value = rs.getString(column);
        return value == null ? null : Enum.valueOf(type, value);
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private long positiveOrDefault(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    public record InstrumentPage(List<InstrumentResponse> instruments,
                                 String nextCursor,
                                 boolean hasMore,
                                 String sort,
                                 int limit) {
    }

    private record InstrumentSort(String token, String field, String column, boolean descending) {

        String orderBy() {
            if (symbolSort()) {
                return column + " " + directionSql();
            }
            return column + " " + directionSql() + ", i.symbol " + directionSql();
        }

        String directionSql() {
            return descending ? "DESC" : "ASC";
        }

        boolean symbolSort() {
            return "symbol".equals(field);
        }
    }

    private record VersionSort(String token, boolean descending) {

        String directionSql() {
            return descending ? "DESC" : "ASC";
        }
    }

    private record InstrumentCursor(String sortValue, String symbol) {
    }
}
