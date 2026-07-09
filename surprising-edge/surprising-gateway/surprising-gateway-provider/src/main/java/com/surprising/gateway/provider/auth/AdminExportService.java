package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AdminExportCreateRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminExportJobResponse;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class AdminExportService {

    private static final String CSV_CONTENT_TYPE = "text/csv; charset=utf-8";

    private final AdminExportRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TaskExecutor taskExecutor;

    @Autowired
    public AdminExportService(AdminExportRepository repository,
                              JdbcTemplate jdbcTemplate,
                              ObjectMapper objectMapper,
                              @Qualifier(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
                              TaskExecutor taskExecutor) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
    }

    public AdminExportJobResponse create(JwtPrincipal principal,
                                         AdminExportCreateRequest request,
                                         Instant now) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        Map<String, String> params = normalizeParams(request.params());
        AdminExportJobResponse job = repository.create(principal,
                request.exportType(), writeParams(params), now);
        taskExecutor.execute(() -> run(job.exportId(), job.exportType(), params));
        return job;
    }

    void run(long exportId, String exportType, Map<String, String> params) {
        try {
            repository.markRunning(exportId, Instant.now());
            ExportResult result = switch (normalizeExportType(exportType)) {
                case "USERS" -> exportUsers(params);
                case "LOGIN_LOGS" -> exportLoginLogs(params);
                case "ADMIN_OPERATIONS" -> exportAdminOperations(params);
                case "COMPLIANCE_USERS" -> exportComplianceUsers(params);
                case "ORDERS" -> exportOrders(params);
                case "TRIGGER_ORDERS" -> exportTriggerOrders(params);
                case "MATCH_TRADES" -> exportMatchTrades(params);
                case "ACCOUNT_BALANCES" -> exportAccountBalances(params);
                case "PRODUCT_BALANCES" -> exportProductBalances(params);
                case "POSITIONS" -> exportPositions(params);
                case "ACCOUNT_LEDGER" -> exportAccountLedger(params);
                case "PRODUCT_LEDGER" -> exportProductLedger(params);
                case "PRODUCT_TRANSFERS" -> exportProductTransfers(params);
                case "ACCOUNT_ADJUSTMENTS" -> exportAccountAdjustments(params);
                default -> throw new IllegalArgumentException("unsupported exportType: " + exportType);
            };
            String csv = toCsv(result.columns(), result.rows());
            repository.markSucceeded(exportId, fileName(exportType), CSV_CONTENT_TYPE,
                    result.rows().size(), csv, Instant.now());
        } catch (Exception ex) {
            repository.markFailed(exportId, ex.getMessage(), Instant.now());
        }
    }

    private ExportResult exportUsers(Map<String, String> params) {
        String query = likeQuery(params.get("query"));
        String exactId = exactId(params.get("query"));
        String status = upperOrNull(params.get("status"));
        int limit = limit(params, 1000, 10000);
        List<String> columns = List.of("user_id", "username", "email", "status", "roles", "created_at");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT u.user_id, u.username, u.email, u.status,
                       COALESCE(string_agg(r.role_code, ',' ORDER BY r.role_code), 'USER') AS roles,
                       u.created_at
                  FROM gateway_users u
                  LEFT JOIN gateway_user_roles ur ON ur.user_id = u.user_id
                  LEFT JOIN gateway_roles r ON r.role_id = ur.role_id
                 WHERE (CAST(? AS text) IS NULL OR lower(u.username) LIKE ? OR CAST(u.user_id AS TEXT) = ? OR lower(u.email) LIKE ?)
                   AND (CAST(? AS text) IS NULL OR u.status = ?)
                 GROUP BY u.user_id, u.username, u.email, u.status, u.created_at
                 ORDER BY u.created_at DESC, u.user_id DESC
                 LIMIT ?
                """, query, query, exactId, query, status, status, limit);
        return new ExportResult(columns, rows);
    }

    private ExportResult exportLoginLogs(Map<String, String> params) {
        Long userId = longParam(params.get("userId"));
        String result = upperOrNull(params.get("result"));
        int limit = limit(params, 1000, 10000);
        List<String> columns = List.of("login_id", "user_id", "result", "reason", "user_agent", "ip_address", "created_at");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT login_id, user_id, result, reason, user_agent, ip_address, created_at
                  FROM gateway_login_logs
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR result = ?)
                 ORDER BY created_at DESC, login_id DESC
                 LIMIT ?
                """, userId, userId, result, result, limit);
        return new ExportResult(columns, rows);
    }

    private ExportResult exportAdminOperations(Map<String, String> params) {
        Long adminUserId = longParam(params.get("adminUserId"));
        String service = lowerOrNull(params.get("service"));
        String method = upperOrNull(params.get("method"));
        Boolean success = boolParam(params.get("success"));
        int limit = limit(params, 1000, 10000);
        List<String> columns = List.of(
                "operation_id", "admin_user_id", "admin_username", "admin_roles", "service",
                "http_method", "request_path", "query_string", "target_uri", "request_body_sha256",
                "response_status", "duration_ms", "success", "error_message", "trace_id", "ip_address", "created_at");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT operation_id, admin_user_id, admin_username, admin_roles, service, http_method,
                       request_path, query_string, target_uri, request_body_sha256, response_status,
                       duration_ms, success, error_message, trace_id, ip_address, created_at
                  FROM gateway_admin_operation_logs
                 WHERE (CAST(? AS text) IS NULL OR admin_user_id = ?)
                   AND (CAST(? AS text) IS NULL OR service = ?)
                   AND (CAST(? AS text) IS NULL OR http_method = ?)
                   AND (CAST(? AS text) IS NULL OR success = ?)
                 ORDER BY created_at DESC, operation_id DESC
                 LIMIT ?
                """, adminUserId, adminUserId, service, service, method, method, success, success, limit);
        return new ExportResult(columns, rows);
    }

    private ExportResult exportComplianceUsers(Map<String, String> params) {
        Long userId = longParam(params.get("userId"));
        String kycStatus = upperOrNull(params.get("kycStatus"));
        String tagCode = upperOrNull(params.get("tagCode"));
        int limit = limit(params, 1000, 10000);
        List<String> columns = List.of(
                "user_id", "username", "status", "kyc_level", "kyc_status", "country",
                "active_risk_tags", "open_aml_cases", "last_compliance_update");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT u.user_id, u.username, u.status,
                       COALESCE(k.kyc_level, 'NONE') AS kyc_level,
                       COALESCE(k.status, 'UNVERIFIED') AS kyc_status,
                       k.country,
                       COUNT(DISTINCT rt.tag_id) FILTER (WHERE rt.status = 'ACTIVE') AS active_risk_tags,
                       COUNT(DISTINCT ac.case_id) FILTER (WHERE ac.status NOT IN ('CLOSED', 'CLEARED')) AS open_aml_cases,
                       GREATEST(
                           COALESCE(k.updated_at, u.created_at),
                           COALESCE(MAX(rt.updated_at), u.created_at),
                           COALESCE(MAX(ac.updated_at), u.created_at)
                       ) AS last_compliance_update
                  FROM gateway_users u
                  LEFT JOIN gateway_user_kyc_profiles k ON k.user_id = u.user_id
                  LEFT JOIN gateway_user_risk_tags rt ON rt.user_id = u.user_id
                  LEFT JOIN gateway_user_aml_cases ac ON ac.user_id = u.user_id
                 WHERE (CAST(? AS text) IS NULL OR u.user_id = ?)
                   AND (CAST(? AS text) IS NULL OR k.status = ?)
                   AND (CAST(? AS text) IS NULL OR EXISTS (
                       SELECT 1
                         FROM gateway_user_risk_tags rt_filter
                        WHERE rt_filter.user_id = u.user_id
                          AND rt_filter.status = 'ACTIVE'
                          AND rt_filter.tag_code = ?
                   ))
                 GROUP BY u.user_id, u.username, u.status, k.kyc_level, k.status, k.country, k.updated_at, u.created_at
                 ORDER BY last_compliance_update DESC, u.user_id DESC
                 LIMIT ?
                """, userId, userId, kycStatus, kycStatus, tagCode, tagCode, limit);
        return new ExportResult(columns, rows);
    }

    private ExportResult exportOrders(Map<String, String> params) {
        Long userId = longParam(params.get("userId"));
        Long orderId = longParam(params.get("orderId"));
        String symbol = symbolParam(params.get("symbol"));
        String status = upperOrNull(params.get("status"));
        Timestamp createdAfter = timestampParam(params.get("createdAfter"));
        Timestamp createdBefore = timestampParam(params.get("createdBefore"));
        int limit = limit(params, 1000, 50000);
        List<String> columns = List.of(
                "order_id", "user_id", "client_order_id", "symbol", "instrument_version", "side",
                "order_type", "time_in_force", "price_ticks", "quantity_steps", "executed_quantity_steps",
                "remaining_quantity_steps", "margin_mode", "maker_fee_rate_ppm", "taker_fee_rate_ppm",
                "reduce_only", "post_only", "status", "reject_reason", "created_at", "updated_at");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT order_id, user_id, client_order_id, symbol, instrument_version, side,
                       order_type, time_in_force, price_ticks, quantity_steps, executed_quantity_steps,
                       remaining_quantity_steps, margin_mode, maker_fee_rate_ppm, taker_fee_rate_ppm,
                       reduce_only, post_only, status, reject_reason, created_at, updated_at
                  FROM trading_orders
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR order_id = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR status = ?)
                   AND (CAST(? AS text) IS NULL OR created_at >= ?)
                   AND (CAST(? AS text) IS NULL OR created_at < ?)
                 ORDER BY created_at DESC, order_id DESC
                 LIMIT ?
                """, userId, userId, orderId, orderId, symbol, symbol, status, status,
                createdAfter, createdAfter, createdBefore, createdBefore, limit);
        return new ExportResult(columns, rows);
    }

    private ExportResult exportTriggerOrders(Map<String, String> params) {
        Long userId = longParam(params.get("userId"));
        Long triggerOrderId = longParam(params.get("triggerOrderId"));
        String symbol = symbolParam(params.get("symbol"));
        String status = upperOrNull(params.get("status"));
        Timestamp createdAfter = timestampParam(params.get("createdAfter"));
        Timestamp createdBefore = timestampParam(params.get("createdBefore"));
        int limit = limit(params, 1000, 50000);
        List<String> columns = List.of(
                "trigger_order_id", "user_id", "client_trigger_order_id", "oco_group_id", "symbol",
                "side", "trigger_type", "trigger_price_type", "trigger_condition", "trigger_price_ticks",
                "order_type", "time_in_force", "price_ticks", "quantity_steps", "margin_mode",
                "status", "placed_order_id", "trigger_sequence", "triggered_price_ticks", "reject_reason",
                "trace_id", "expires_at", "triggered_at", "created_at", "updated_at");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT trigger_order_id, user_id, client_trigger_order_id, oco_group_id, symbol,
                       side, trigger_type, trigger_price_type, trigger_condition, trigger_price_ticks,
                       order_type, time_in_force, price_ticks, quantity_steps, margin_mode,
                       status, placed_order_id, trigger_sequence, triggered_price_ticks, reject_reason,
                       trace_id, expires_at, triggered_at, created_at, updated_at
                  FROM trading_trigger_orders
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR trigger_order_id = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR status = ?)
                   AND (CAST(? AS text) IS NULL OR created_at >= ?)
                   AND (CAST(? AS text) IS NULL OR created_at < ?)
                 ORDER BY created_at DESC, trigger_order_id DESC
                 LIMIT ?
                """, userId, userId, triggerOrderId, triggerOrderId, symbol, symbol, status, status,
                createdAfter, createdAfter, createdBefore, createdBefore, limit);
        return new ExportResult(columns, rows);
    }

    private ExportResult exportMatchTrades(Map<String, String> params) {
        Long userId = longParam(params.get("userId"));
        Long orderId = longParam(params.get("orderId"));
        String symbol = symbolParam(params.get("symbol"));
        String takerSide = upperOrNull(params.get("takerSide"));
        Timestamp createdAfter = timestampParam(params.get("createdAfter"));
        Timestamp createdBefore = timestampParam(params.get("createdBefore"));
        int limit = limit(params, 1000, 50000);
        List<String> columns = List.of(
                "symbol", "trade_id", "command_id", "taker_order_id", "taker_user_id", "taker_side",
                "taker_margin_mode", "maker_order_id", "maker_user_id", "maker_margin_mode", "price_ticks",
                "quantity_steps", "taker_order_completed", "maker_order_completed", "trace_id", "event_time",
                "created_at");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT symbol, trade_id, command_id, taker_order_id, taker_user_id, taker_side,
                       taker_margin_mode, maker_order_id, maker_user_id, maker_margin_mode, price_ticks,
                       quantity_steps, taker_order_completed, maker_order_completed, trace_id, event_time,
                       created_at
                  FROM trading_match_trades
                 WHERE (CAST(? AS text) IS NULL OR taker_user_id = ? OR maker_user_id = ?)
                   AND (CAST(? AS text) IS NULL OR taker_order_id = ? OR maker_order_id = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR taker_side = ?)
                   AND (CAST(? AS text) IS NULL OR event_time >= ?)
                   AND (CAST(? AS text) IS NULL OR event_time < ?)
                 ORDER BY event_time DESC, symbol, trade_id DESC
                 LIMIT ?
                """, userId, userId, userId, orderId, orderId, orderId, symbol, symbol, takerSide, takerSide,
                createdAfter, createdAfter, createdBefore, createdBefore, limit);
        return new ExportResult(columns, rows);
    }

    private ExportResult exportAccountBalances(Map<String, String> params) {
        Long userId = longParam(params.get("userId"));
        String asset = assetParam(params.get("asset"));
        Boolean nonZeroOnly = boolParam(params.get("nonZeroOnly"));
        int limit = limit(params, 1000, 50000);
        List<String> columns = List.of("user_id", "asset", "available_units", "locked_units", "equity_units", "updated_at");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT user_id, asset, available_units, locked_units,
                       available_units + locked_units AS equity_units,
                       updated_at
                  FROM account_balances
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                   AND (CAST(? AS text) IS NULL OR ? = FALSE OR available_units <> 0 OR locked_units <> 0)
                 ORDER BY updated_at DESC, user_id DESC, asset
                 LIMIT ?
                """, userId, userId, asset, asset, nonZeroOnly, nonZeroOnly, limit);
        return new ExportResult(columns, rows);
    }

    private ExportResult exportProductBalances(Map<String, String> params) {
        Long userId = longParam(params.get("userId"));
        String accountType = upperOrNull(params.get("accountType"));
        String asset = assetParam(params.get("asset"));
        Boolean nonZeroOnly = boolParam(params.get("nonZeroOnly"));
        int limit = limit(params, 1000, 50000);
        List<String> columns = List.of(
                "account_type", "user_id", "asset", "available_units", "locked_units", "equity_units", "updated_at");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT account_type, user_id, asset, available_units, locked_units,
                       available_units + locked_units AS equity_units,
                       updated_at
                  FROM account_product_balances
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR account_type = ?)
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                   AND (CAST(? AS text) IS NULL OR ? = FALSE OR available_units <> 0 OR locked_units <> 0)
                 ORDER BY updated_at DESC, user_id DESC, account_type, asset
                 LIMIT ?
                """, userId, userId, accountType, accountType, asset, asset, nonZeroOnly, nonZeroOnly, limit);
        return new ExportResult(columns, rows);
    }

    private ExportResult exportPositions(Map<String, String> params) {
        Long userId = longParam(params.get("userId"));
        String symbol = symbolParam(params.get("symbol"));
        String marginMode = upperOrNull(params.get("marginMode"));
        Boolean openOnly = boolParam(params.get("openOnly"));
        int limit = limit(params, 1000, 50000);
        List<String> columns = List.of(
                "user_id", "symbol", "margin_mode", "instrument_version", "signed_quantity_steps",
                "entry_price_ticks", "realized_pnl_units", "updated_at");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT user_id, symbol, margin_mode, instrument_version, signed_quantity_steps,
                       entry_price_ticks, realized_pnl_units, updated_at
                  FROM account_positions
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR margin_mode = ?)
                   AND (CAST(? AS text) IS NULL OR ? = FALSE OR signed_quantity_steps <> 0)
                 ORDER BY updated_at DESC, user_id DESC, symbol, margin_mode
                 LIMIT ?
                """, userId, userId, symbol, symbol, marginMode, marginMode, openOnly, openOnly, limit);
        return new ExportResult(columns, rows);
    }

    private ExportResult exportAccountLedger(Map<String, String> params) {
        Long userId = longParam(params.get("userId"));
        Long orderId = longParam(params.get("orderId"));
        String asset = assetParam(params.get("asset"));
        String symbol = symbolParam(params.get("symbol"));
        String referenceType = upperOrNull(params.get("referenceType"));
        Timestamp createdAfter = timestampParam(params.get("createdAfter"));
        Timestamp createdBefore = timestampParam(params.get("createdBefore"));
        int limit = limit(params, 1000, 50000);
        List<String> columns = List.of(
                "entry_id", "user_id", "asset", "amount_units", "balance_after_units",
                "reference_type", "reference_id", "reason", "trade_id", "order_id",
                "symbol", "fee_rate_ppm", "created_at");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT entry_id, user_id, asset, amount_units, balance_after_units,
                       reference_type, reference_id, reason, trade_id, order_id,
                       symbol, fee_rate_ppm, created_at
                  FROM account_ledger_entries
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                   AND (CAST(? AS text) IS NULL OR reference_type = ?)
                   AND (CAST(? AS text) IS NULL OR order_id = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR created_at >= ?)
                   AND (CAST(? AS text) IS NULL OR created_at < ?)
                 ORDER BY created_at DESC, entry_id DESC
                 LIMIT ?
                """, userId, userId, asset, asset, referenceType, referenceType, orderId, orderId,
                symbol, symbol, createdAfter, createdAfter, createdBefore, createdBefore, limit);
        return new ExportResult(columns, rows);
    }

    private ExportResult exportProductLedger(Map<String, String> params) {
        Long userId = longParam(params.get("userId"));
        String accountType = upperOrNull(params.get("accountType"));
        String asset = assetParam(params.get("asset"));
        String referenceType = upperOrNull(params.get("referenceType"));
        Timestamp createdAfter = timestampParam(params.get("createdAfter"));
        Timestamp createdBefore = timestampParam(params.get("createdBefore"));
        int limit = limit(params, 1000, 50000);
        List<String> columns = List.of(
                "entry_id", "user_id", "account_type", "asset", "amount_units", "balance_after_units",
                "reference_type", "reference_id", "reason", "created_at");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                       reference_type, reference_id, reason, created_at
                  FROM account_product_ledger_entries
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR account_type = ?)
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                   AND (CAST(? AS text) IS NULL OR reference_type = ?)
                   AND (CAST(? AS text) IS NULL OR created_at >= ?)
                   AND (CAST(? AS text) IS NULL OR created_at < ?)
                 ORDER BY created_at DESC, entry_id DESC
                 LIMIT ?
                """, userId, userId, accountType, accountType, asset, asset, referenceType, referenceType,
                createdAfter, createdAfter, createdBefore, createdBefore, limit);
        return new ExportResult(columns, rows);
    }

    private ExportResult exportProductTransfers(Map<String, String> params) {
        Long userId = longParam(params.get("userId"));
        String accountType = upperOrNull(params.get("accountType"));
        String asset = assetParam(params.get("asset"));
        String status = upperOrNull(params.get("status"));
        Timestamp createdAfter = timestampParam(params.get("createdAfter"));
        Timestamp createdBefore = timestampParam(params.get("createdBefore"));
        int limit = limit(params, 1000, 50000);
        List<String> columns = List.of(
                "transfer_id", "user_id", "source_account_type", "target_account_type", "asset",
                "amount_units", "reference_id", "status", "reason", "created_at", "updated_at");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT transfer_id, user_id, source_account_type, target_account_type, asset,
                       amount_units, reference_id, status, reason, created_at, updated_at
                  FROM account_product_transfers
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR source_account_type = ? OR target_account_type = ?)
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                   AND (CAST(? AS text) IS NULL OR status = ?)
                   AND (CAST(? AS text) IS NULL OR created_at >= ?)
                   AND (CAST(? AS text) IS NULL OR created_at < ?)
                 ORDER BY created_at DESC, transfer_id DESC
                 LIMIT ?
                """, userId, userId, accountType, accountType, accountType, asset, asset, status, status,
                createdAfter, createdAfter, createdBefore, createdBefore, limit);
        return new ExportResult(columns, rows);
    }

    private ExportResult exportAccountAdjustments(Map<String, String> params) {
        Long adminUserId = longParam(params.get("adminUserId"));
        Long userId = longParam(params.get("userId"));
        String adjustmentKind = upperOrNull(params.get("adjustmentKind"));
        String accountType = upperOrNull(params.get("accountType"));
        String asset = assetParam(params.get("asset"));
        String referenceId = params.get("referenceId");
        Timestamp createdAfter = timestampParam(params.get("createdAfter"));
        Timestamp createdBefore = timestampParam(params.get("createdBefore"));
        int limit = limit(params, 1000, 50000);
        List<String> columns = List.of(
                "adjustment_id", "adjustment_kind", "admin_user_id", "admin_username", "user_id",
                "account_type", "asset", "amount_units", "balance_after_units", "reference_id", "reason",
                "created_at");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT adjustment_id, adjustment_kind, admin_user_id, admin_username, user_id, account_type,
                       asset, amount_units, balance_after_units, reference_id, reason, created_at
                  FROM account_admin_balance_adjustments
                 WHERE (CAST(? AS text) IS NULL OR admin_user_id = ?)
                   AND (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR adjustment_kind = ?)
                   AND (CAST(? AS text) IS NULL OR account_type = ?)
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                   AND (CAST(? AS text) IS NULL OR reference_id = ?)
                   AND (CAST(? AS text) IS NULL OR created_at >= ?)
                   AND (CAST(? AS text) IS NULL OR created_at < ?)
                 ORDER BY created_at DESC, adjustment_id DESC
                 LIMIT ?
                """, adminUserId, adminUserId, userId, userId, adjustmentKind, adjustmentKind,
                accountType, accountType, asset, asset, referenceId, referenceId,
                createdAfter, createdAfter, createdBefore, createdBefore, limit);
        return new ExportResult(columns, rows);
    }

    private String toCsv(List<String> columns, List<Map<String, Object>> rows) {
        StringBuilder csv = new StringBuilder();
        appendCsvLine(csv, columns);
        for (Map<String, Object> row : rows) {
            appendCsvLine(csv, columns.stream().map(column -> value(row.get(column))).toList());
        }
        return csv.toString();
    }

    private void appendCsvLine(StringBuilder csv, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(escape(values.get(i)));
        }
        csv.append('\n');
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean quote = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!quote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private String value(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        return String.valueOf(value);
    }

    private String fileName(String exportType) {
        return normalizeExportType(exportType).toLowerCase(Locale.ROOT)
                + "-" + DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-')
                + ".csv";
    }

    private String writeParams(Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("invalid export params", ex);
        }
    }

    private Map<String, String> normalizeParams(Map<String, String> params) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (params == null) {
            return normalized;
        }
        params.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                normalized.put(key.trim(), value.trim());
            }
        });
        return normalized;
    }

    private String normalizeExportType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("exportType is required");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String likeQuery(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private String exactId(String value) {
        if (value == null || value.isBlank() || !value.trim().matches("\\d+")) {
            return null;
        }
        return value.trim();
    }

    private String upperOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String lowerOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String symbolParam(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String assetParam(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private Timestamp timestampParam(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Timestamp.from(Instant.parse(value.trim()));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid timestamp parameter: " + value, ex);
        }
    }

    private Long longParam(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid numeric parameter: " + value, ex);
        }
    }

    private Boolean boolParam(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private int limit(Map<String, String> params, int defaultLimit, int maxLimit) {
        String value = params.get("limit");
        if (value == null || value.isBlank()) {
            return defaultLimit;
        }
        try {
            return Math.max(1, Math.min(Integer.parseInt(value.trim()), maxLimit));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid limit", ex);
        }
    }

    private record ExportResult(
            List<String> columns,
            List<Map<String, Object>> rows) {
    }
}
