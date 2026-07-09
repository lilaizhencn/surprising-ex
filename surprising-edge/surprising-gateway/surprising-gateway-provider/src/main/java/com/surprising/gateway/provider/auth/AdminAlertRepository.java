package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminAlertRepository {

    private static final int MAX_ALERT_LIST_LIMIT = 500;
    private static final AdminCursorPage.SortSpec ALERT_EVENT_LAST_SEEN_DESC =
            new AdminCursorPage.SortSpec("lastSeenAt", "last_seen_at", "alert_event_id", true);
    private static final List<AdminCursorPage.SortSpec> ALERT_EVENT_SORTS = List.of(
            ALERT_EVENT_LAST_SEEN_DESC,
            new AdminCursorPage.SortSpec("lastSeenAt", "last_seen_at", "alert_event_id", false),
            new AdminCursorPage.SortSpec("createdAt", "created_at", "alert_event_id", true),
            new AdminCursorPage.SortSpec("createdAt", "created_at", "alert_event_id", false));
    private static final AdminCursorPage.SortSpec ALERT_DELIVERY_CREATED_DESC =
            new AdminCursorPage.SortSpec("createdAt", "d.created_at", "d.alert_delivery_id", true);
    private static final List<AdminCursorPage.SortSpec> ALERT_DELIVERY_SORTS = List.of(
            ALERT_DELIVERY_CREATED_DESC,
            new AdminCursorPage.SortSpec("createdAt", "d.created_at", "d.alert_delivery_id", false),
            new AdminCursorPage.SortSpec("updatedAt", "d.updated_at", "d.alert_delivery_id", true),
            new AdminCursorPage.SortSpec("updatedAt", "d.updated_at", "d.alert_delivery_id", false));
    private static final AdminCursorPage.SortSpec ALERT_RULE_UPDATED_DESC =
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "alert_rule_id", true);
    private static final List<AdminCursorPage.SortSpec> ALERT_RULE_SORTS = List.of(
            ALERT_RULE_UPDATED_DESC,
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "alert_rule_id", false),
            new AdminCursorPage.SortSpec("createdAt", "created_at", "alert_rule_id", true),
            new AdminCursorPage.SortSpec("createdAt", "created_at", "alert_rule_id", false));
    private static final AdminCursorPage.SortSpec ALERT_CHANNEL_UPDATED_DESC =
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "alert_channel_id", true);
    private static final List<AdminCursorPage.SortSpec> ALERT_CHANNEL_SORTS = List.of(
            ALERT_CHANNEL_UPDATED_DESC,
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "alert_channel_id", false),
            new AdminCursorPage.SortSpec("createdAt", "created_at", "alert_channel_id", true),
            new AdminCursorPage.SortSpec("createdAt", "created_at", "alert_channel_id", false));

    private final JdbcTemplate jdbcTemplate;

    public AdminAlertRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AlertRule> rules(String domain, Boolean enabled, int limit) {
        return rulesPage(domain, enabled, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<AlertRule> rulesPage(String domain,
                                                           Boolean enabled,
                                                           int limit,
                                                           String cursor,
                                                           String sort) {
        String normalizedDomain = normalizeNullableDomain(domain);
        int safeLimit = AdminCursorPage.limit(limit, MAX_ALERT_LIST_LIMIT);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(sort, ALERT_RULE_UPDATED_DESC, ALERT_RULE_SORTS);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(normalizedDomain);
        args.add(normalizedDomain);
        args.add(enabled);
        args.add(enabled);
        String sql = """
                SELECT alert_rule_id, rule_code, rule_name, domain, metric_key, target,
                       condition_operator, threshold_value, severity, enabled, window_seconds,
                       cooldown_seconds, description, created_by_user_id, updated_by_user_id,
                       created_at, updated_at
                  FROM gateway_admin_alert_rules
                 WHERE (CAST(? AS text) IS NULL OR domain = ?)
                   AND (CAST(? AS text) IS NULL OR enabled = ?)
                """ + AdminCursorPage.seekCondition(sortSpec, decodedCursor) + """
                 ORDER BY %s %s, alert_rule_id %s
                 LIMIT ?
                """.formatted(sortSpec.column(), sortSpec.directionSql(), sortSpec.directionSql());
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<AlertRule> fetchedRows = jdbcTemplate.query(sql, this::toRule, args.toArray());
        return AdminCursorPage.page(fetchedRows, safeLimit, sortSpec, ruleTimestampExtractor(sortSpec),
                AlertRule::alertRuleId);
    }

    public AlertRule createRule(JwtPrincipal principal, AlertRuleRequest request, Instant now) {
        NormalizedRule normalized = normalize(request);
        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_admin_alert_rules (
                    rule_code, rule_name, domain, metric_key, target, condition_operator,
                    threshold_value, severity, enabled, window_seconds, cooldown_seconds,
                    description, created_by_user_id, updated_by_user_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING alert_rule_id, rule_code, rule_name, domain, metric_key, target,
                          condition_operator, threshold_value, severity, enabled, window_seconds,
                          cooldown_seconds, description, created_by_user_id, updated_by_user_id,
                          created_at, updated_at
                """, this::toRule,
                normalized.ruleCode(),
                normalized.ruleName(),
                normalized.domain(),
                normalized.metricKey(),
                normalized.target(),
                normalized.operator(),
                normalized.threshold(),
                normalized.severity(),
                normalized.enabled(),
                normalized.windowSeconds(),
                normalized.cooldownSeconds(),
                normalized.description(),
                principal.userId(),
                principal.userId(),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    public AlertRule updateRule(long ruleId, JwtPrincipal principal, AlertRuleRequest request, Instant now) {
        NormalizedRule normalized = normalize(request);
        return jdbcTemplate.query("""
                UPDATE gateway_admin_alert_rules
                   SET rule_code = ?,
                       rule_name = ?,
                       domain = ?,
                       metric_key = ?,
                       target = ?,
                       condition_operator = ?,
                       threshold_value = ?,
                       severity = ?,
                       enabled = ?,
                       window_seconds = ?,
                       cooldown_seconds = ?,
                       description = ?,
                       updated_by_user_id = ?,
                       updated_at = ?
                 WHERE alert_rule_id = ?
                RETURNING alert_rule_id, rule_code, rule_name, domain, metric_key, target,
                          condition_operator, threshold_value, severity, enabled, window_seconds,
                          cooldown_seconds, description, created_by_user_id, updated_by_user_id,
                          created_at, updated_at
                """, this::toRule,
                normalized.ruleCode(),
                normalized.ruleName(),
                normalized.domain(),
                normalized.metricKey(),
                normalized.target(),
                normalized.operator(),
                normalized.threshold(),
                normalized.severity(),
                normalized.enabled(),
                normalized.windowSeconds(),
                normalized.cooldownSeconds(),
                normalized.description(),
                principal.userId(),
                Timestamp.from(now),
                ruleId).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("alert rule not found"));
    }

    public AlertRule setEnabled(long ruleId, JwtPrincipal principal, boolean enabled, Instant now) {
        return jdbcTemplate.query("""
                UPDATE gateway_admin_alert_rules
                   SET enabled = ?,
                       updated_by_user_id = ?,
                       updated_at = ?
                 WHERE alert_rule_id = ?
                RETURNING alert_rule_id, rule_code, rule_name, domain, metric_key, target,
                          condition_operator, threshold_value, severity, enabled, window_seconds,
                          cooldown_seconds, description, created_by_user_id, updated_by_user_id,
                          created_at, updated_at
        """, this::toRule, enabled, principal.userId(), Timestamp.from(now), ruleId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("alert rule not found"));
    }

    public List<AlertChannel> channels(String domain, Boolean enabled, int limit) {
        return channelsPage(domain, enabled, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<AlertChannel> channelsPage(String domain,
                                                                 Boolean enabled,
                                                                 int limit,
                                                                 String cursor,
                                                                 String sort) {
        String normalizedDomain = normalizeNullableDomain(domain);
        int safeLimit = AdminCursorPage.limit(limit, MAX_ALERT_LIST_LIMIT);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(
                sort, ALERT_CHANNEL_UPDATED_DESC, ALERT_CHANNEL_SORTS);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(normalizedDomain);
        args.add(normalizedDomain);
        args.add(enabled);
        args.add(enabled);
        String sql = """
                SELECT alert_channel_id, channel_code, channel_name, channel_type, enabled,
                       domain, min_severity, endpoint, description, created_by_user_id,
                       updated_by_user_id, created_at, updated_at
                  FROM gateway_admin_alert_channels
                 WHERE (CAST(? AS text) IS NULL OR domain = ? OR domain IS NULL)
                   AND (CAST(? AS text) IS NULL OR enabled = ?)
                """ + AdminCursorPage.seekCondition(sortSpec, decodedCursor) + """
                 ORDER BY %s %s, alert_channel_id %s
                 LIMIT ?
                """.formatted(sortSpec.column(), sortSpec.directionSql(), sortSpec.directionSql());
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<AlertChannel> fetchedRows = jdbcTemplate.query(sql, this::toChannel, args.toArray());
        return AdminCursorPage.page(fetchedRows, safeLimit, sortSpec, channelTimestampExtractor(sortSpec),
                AlertChannel::alertChannelId);
    }

    public AlertChannel createChannel(JwtPrincipal principal, AlertChannelRequest request, Instant now) {
        NormalizedChannel normalized = normalizeChannel(request);
        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_admin_alert_channels (
                    channel_code, channel_name, channel_type, enabled, domain, min_severity,
                    endpoint, description, created_by_user_id, updated_by_user_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING alert_channel_id, channel_code, channel_name, channel_type, enabled,
                          domain, min_severity, endpoint, description, created_by_user_id,
                          updated_by_user_id, created_at, updated_at
                """, this::toChannel,
                normalized.channelCode(),
                normalized.channelName(),
                normalized.channelType(),
                normalized.enabled(),
                normalized.domain(),
                normalized.minSeverity(),
                normalized.endpoint(),
                normalized.description(),
                principal.userId(),
                principal.userId(),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    public AlertChannel updateChannel(long channelId,
                                      JwtPrincipal principal,
                                      AlertChannelRequest request,
                                      Instant now) {
        NormalizedChannel normalized = normalizeChannel(request);
        return jdbcTemplate.query("""
                UPDATE gateway_admin_alert_channels
                   SET channel_code = ?,
                       channel_name = ?,
                       channel_type = ?,
                       enabled = ?,
                       domain = ?,
                       min_severity = ?,
                       endpoint = ?,
                       description = ?,
                       updated_by_user_id = ?,
                       updated_at = ?
                 WHERE alert_channel_id = ?
                RETURNING alert_channel_id, channel_code, channel_name, channel_type, enabled,
                          domain, min_severity, endpoint, description, created_by_user_id,
                          updated_by_user_id, created_at, updated_at
                """, this::toChannel,
                normalized.channelCode(),
                normalized.channelName(),
                normalized.channelType(),
                normalized.enabled(),
                normalized.domain(),
                normalized.minSeverity(),
                normalized.endpoint(),
                normalized.description(),
                principal.userId(),
                Timestamp.from(now),
                channelId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("alert channel not found"));
    }

    public AlertChannel setChannelEnabled(long channelId, JwtPrincipal principal, boolean enabled, Instant now) {
        return jdbcTemplate.query("""
                UPDATE gateway_admin_alert_channels
                   SET enabled = ?,
                       updated_by_user_id = ?,
                       updated_at = ?
                 WHERE alert_channel_id = ?
                RETURNING alert_channel_id, channel_code, channel_name, channel_type, enabled,
                          domain, min_severity, endpoint, description, created_by_user_id,
                          updated_by_user_id, created_at, updated_at
                """, this::toChannel, enabled, principal.userId(), Timestamp.from(now), channelId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("alert channel not found"));
    }

    public List<AlertEvent> events(String status, String severity, String domain, int limit) {
        String normalizedStatus = normalizeNullableStatus(status);
        String normalizedSeverity = normalizeNullableSeverity(severity);
        String normalizedDomain = normalizeNullableDomain(domain);
        int safeLimit = AdminCursorPage.limit(limit, MAX_ALERT_LIST_LIMIT);
        return jdbcTemplate.query("""
                SELECT alert_event_id, alert_rule_id, rule_code, domain, metric_key, target,
                       severity, status, condition_operator, threshold_value, current_value,
                       fingerprint, message, occurrences, first_seen_at, last_seen_at,
                       acknowledged_by_user_id, acknowledged_at, resolved_at, created_at, updated_at
                  FROM gateway_admin_alert_events
                 WHERE (CAST(? AS text) IS NULL OR status = ?)
                   AND (CAST(? AS text) IS NULL OR severity = ?)
                   AND (CAST(? AS text) IS NULL OR domain = ?)
                 ORDER BY CASE status WHEN 'OPEN' THEN 0 WHEN 'ACKNOWLEDGED' THEN 1 ELSE 2 END,
                          last_seen_at DESC, alert_event_id DESC
                 LIMIT ?
                """, this::toEvent,
                normalizedStatus, normalizedStatus,
                normalizedSeverity, normalizedSeverity,
                normalizedDomain, normalizedDomain,
                safeLimit);
    }

    public AdminCursorPage.CursorPage<AlertEvent> eventsPage(String status,
                                                            String severity,
                                                            String domain,
                                                            int limit,
                                                            String cursor,
                                                            String sort) {
        String normalizedStatus = normalizeNullableStatus(status);
        String normalizedSeverity = normalizeNullableSeverity(severity);
        String normalizedDomain = normalizeNullableDomain(domain);
        int safeLimit = AdminCursorPage.limit(limit, MAX_ALERT_LIST_LIMIT);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(
                sort, ALERT_EVENT_LAST_SEEN_DESC, ALERT_EVENT_SORTS);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(normalizedStatus);
        args.add(normalizedStatus);
        args.add(normalizedSeverity);
        args.add(normalizedSeverity);
        args.add(normalizedDomain);
        args.add(normalizedDomain);
        String sql = """
                SELECT alert_event_id, alert_rule_id, rule_code, domain, metric_key, target,
                       severity, status, condition_operator, threshold_value, current_value,
                       fingerprint, message, occurrences, first_seen_at, last_seen_at,
                       acknowledged_by_user_id, acknowledged_at, resolved_at, created_at, updated_at
                  FROM gateway_admin_alert_events
                 WHERE (CAST(? AS text) IS NULL OR status = ?)
                   AND (CAST(? AS text) IS NULL OR severity = ?)
                   AND (CAST(? AS text) IS NULL OR domain = ?)
                """ + AdminCursorPage.seekCondition(sortSpec, decodedCursor) + """
                 ORDER BY %s %s, alert_event_id %s
                 LIMIT ?
                """.formatted(sortSpec.column(), sortSpec.directionSql(), sortSpec.directionSql());
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<AlertEvent> fetchedRows = jdbcTemplate.query(sql, this::toEvent, args.toArray());
        return AdminCursorPage.page(fetchedRows, safeLimit, sortSpec,
                eventTimestampExtractor(sortSpec), AlertEvent::alertEventId);
    }

    public AlertEvent acknowledge(long eventId, JwtPrincipal principal, Instant now) {
        return jdbcTemplate.query("""
                UPDATE gateway_admin_alert_events
                   SET status = 'ACKNOWLEDGED',
                       acknowledged_by_user_id = ?,
                       acknowledged_at = ?,
                       updated_at = ?
                 WHERE alert_event_id = ?
                   AND status = 'OPEN'
                RETURNING alert_event_id, alert_rule_id, rule_code, domain, metric_key, target,
                          severity, status, condition_operator, threshold_value, current_value,
                          fingerprint, message, occurrences, first_seen_at, last_seen_at,
                          acknowledged_by_user_id, acknowledged_at, resolved_at, created_at, updated_at
                """, this::toEvent, principal.userId(), Timestamp.from(now), Timestamp.from(now), eventId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("open alert event not found"));
    }

    public List<AlertDelivery> deliveries(String status, Long channelId, Long eventId, int limit) {
        String normalizedStatus = normalizeNullableDeliveryStatus(status);
        int safeLimit = AdminCursorPage.limit(limit, MAX_ALERT_LIST_LIMIT);
        return jdbcTemplate.query("""
                SELECT d.alert_delivery_id, d.alert_event_id, d.alert_channel_id, d.channel_code,
                       d.channel_type, d.delivery_status, d.attempt_count, d.next_attempt_at,
                       d.last_attempt_at, d.delivered_at, d.error_message, d.created_at, d.updated_at,
                       e.rule_code, e.domain, e.severity, e.status AS event_status, e.message
                  FROM gateway_admin_alert_deliveries d
                  JOIN gateway_admin_alert_events e ON e.alert_event_id = d.alert_event_id
                 WHERE (CAST(? AS text) IS NULL OR d.delivery_status = ?)
                   AND (CAST(? AS text) IS NULL OR d.alert_channel_id = ?)
                   AND (CAST(? AS text) IS NULL OR d.alert_event_id = ?)
                 ORDER BY CASE d.delivery_status WHEN 'PENDING' THEN 0 WHEN 'FAILED' THEN 1 ELSE 2 END,
                          d.created_at DESC, d.alert_delivery_id DESC
                 LIMIT ?
                """, this::toDelivery,
                normalizedStatus, normalizedStatus,
                channelId, channelId,
                eventId, eventId,
                safeLimit);
    }

    public AdminCursorPage.CursorPage<AlertDelivery> deliveriesPage(String status,
                                                                    Long channelId,
                                                                    Long eventId,
                                                                    int limit,
                                                                    String cursor,
                                                                    String sort) {
        String normalizedStatus = normalizeNullableDeliveryStatus(status);
        int safeLimit = AdminCursorPage.limit(limit, MAX_ALERT_LIST_LIMIT);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(
                sort, ALERT_DELIVERY_CREATED_DESC, ALERT_DELIVERY_SORTS);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(normalizedStatus);
        args.add(normalizedStatus);
        args.add(channelId);
        args.add(channelId);
        args.add(eventId);
        args.add(eventId);
        String sql = """
                SELECT d.alert_delivery_id, d.alert_event_id, d.alert_channel_id, d.channel_code,
                       d.channel_type, d.delivery_status, d.attempt_count, d.next_attempt_at,
                       d.last_attempt_at, d.delivered_at, d.error_message, d.created_at, d.updated_at,
                       e.rule_code, e.domain, e.severity, e.status AS event_status, e.message
                  FROM gateway_admin_alert_deliveries d
                  JOIN gateway_admin_alert_events e ON e.alert_event_id = d.alert_event_id
                 WHERE (CAST(? AS text) IS NULL OR d.delivery_status = ?)
                   AND (CAST(? AS text) IS NULL OR d.alert_channel_id = ?)
                   AND (CAST(? AS text) IS NULL OR d.alert_event_id = ?)
                """ + AdminCursorPage.seekCondition(sortSpec, decodedCursor) + """
                 ORDER BY %s %s, d.alert_delivery_id %s
                 LIMIT ?
                """.formatted(sortSpec.column(), sortSpec.directionSql(), sortSpec.directionSql());
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<AlertDelivery> fetchedRows = jdbcTemplate.query(sql, this::toDelivery, args.toArray());
        return AdminCursorPage.page(fetchedRows, safeLimit, sortSpec,
                deliveryTimestampExtractor(sortSpec), AlertDelivery::alertDeliveryId);
    }

    public AlertDelivery retryDelivery(long deliveryId, Instant now) {
        return jdbcTemplate.query("""
                WITH updated AS (
                    UPDATE gateway_admin_alert_deliveries
                       SET delivery_status = 'PENDING',
                           next_attempt_at = ?,
                           error_message = NULL,
                           updated_at = ?
                     WHERE alert_delivery_id = ?
                       AND delivery_status IN ('FAILED', 'SKIPPED')
                    RETURNING *
                )
                SELECT d.alert_delivery_id, d.alert_event_id, d.alert_channel_id, d.channel_code,
                       d.channel_type, d.delivery_status, d.attempt_count, d.next_attempt_at,
                       d.last_attempt_at, d.delivered_at, d.error_message, d.created_at, d.updated_at,
                       e.rule_code, e.domain, e.severity, e.status AS event_status, e.message
                  FROM updated d
                  JOIN gateway_admin_alert_events e ON e.alert_event_id = d.alert_event_id
                """, this::toDelivery, Timestamp.from(now), Timestamp.from(now), deliveryId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("retryable alert delivery not found"));
    }

    public AlertEvent upsertSystemAlert(String ruleCode,
                                        String metricKey,
                                        String target,
                                        String severity,
                                        BigDecimal thresholdValue,
                                        BigDecimal currentValue,
                                        String message,
                                        Instant now) {
        String normalizedRuleCode = requiredUpper(ruleCode, "ruleCode");
        String normalizedMetricKey = requiredUpper(metricKey, "metricKey");
        String normalizedTarget = normalizeNullableTarget(target);
        String normalizedSeverity = normalizeSeverity(severity);
        BigDecimal threshold = thresholdValue == null ? BigDecimal.ZERO : thresholdValue;
        BigDecimal current = currentValue == null ? BigDecimal.ZERO : currentValue;
        String fingerprint = normalizedRuleCode + ":" + (normalizedTarget == null ? "all" : normalizedTarget);
        AlertEvent event = jdbcTemplate.queryForObject("""
                INSERT INTO gateway_admin_alert_events (
                    alert_rule_id, rule_code, domain, metric_key, target, severity, status,
                    condition_operator, threshold_value, current_value, fingerprint, message,
                    first_seen_at, last_seen_at, created_at, updated_at
                ) VALUES (NULL, ?, 'SYSTEM', ?, ?, ?, 'OPEN', 'GTE', ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (fingerprint) WHERE status IN ('OPEN', 'ACKNOWLEDGED') DO UPDATE
                   SET current_value = EXCLUDED.current_value,
                       message = EXCLUDED.message,
                       occurrences = gateway_admin_alert_events.occurrences + 1,
                       last_seen_at = EXCLUDED.last_seen_at,
                       updated_at = EXCLUDED.updated_at
                RETURNING alert_event_id, alert_rule_id, rule_code, domain, metric_key, target,
                          severity, status, condition_operator, threshold_value, current_value,
                          fingerprint, message, occurrences, first_seen_at, last_seen_at,
                          acknowledged_by_user_id, acknowledged_at, resolved_at, created_at, updated_at
                """, this::toEvent,
                normalizedRuleCode,
                normalizedMetricKey,
                normalizedTarget,
                normalizedSeverity,
                threshold,
                current,
                fingerprint,
                message == null || message.isBlank() ? normalizedRuleCode : message,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now));
        enqueueDeliveries(event, now);
        return event;
    }

    public int resolveSystemAlerts(String ruleCode, Set<String> activeTargets, Instant now) {
        String normalizedRuleCode = requiredUpper(ruleCode, "ruleCode");
        Set<String> normalizedTargets = activeTargets == null ? Set.of() : activeTargets;
        List<ActiveEvent> activeEvents = jdbcTemplate.query("""
                SELECT alert_event_id, COALESCE(target, 'all') AS fingerprint
                  FROM gateway_admin_alert_events
                 WHERE rule_code = ?
                   AND domain = 'SYSTEM'
                   AND status IN ('OPEN', 'ACKNOWLEDGED')
                """, (rs, rowNum) -> new ActiveEvent(rs.getLong("alert_event_id"), rs.getString("fingerprint")),
                normalizedRuleCode);
        int resolved = 0;
        for (ActiveEvent event : activeEvents) {
            if (normalizedTargets.contains(event.fingerprint())) {
                continue;
            }
            resolved += jdbcTemplate.update("""
                    UPDATE gateway_admin_alert_events
                       SET status = 'RESOLVED',
                           resolved_at = ?,
                           updated_at = ?
                     WHERE alert_event_id = ?
                    """, Timestamp.from(now), Timestamp.from(now), event.alertEventId());
        }
        return resolved;
    }

    public List<AlertDeliveryWorkItem> claimPendingDeliveries(Instant now,
                                                               int limit,
                                                               int maxAttempts,
                                                               Duration claimLease) {
        Instant safeNow = now == null ? Instant.now() : now;
        int safeLimit = Math.max(1, Math.min(limit, 500));
        int safeMaxAttempts = Math.max(1, maxAttempts);
        Duration safeLease = claimLease == null || claimLease.isNegative() || claimLease.isZero()
                ? Duration.ofMinutes(2)
                : claimLease;
        Instant leaseUntil = safeNow.plus(safeLease);
        return jdbcTemplate.query("""
                WITH picked AS (
                    SELECT d.alert_delivery_id
                      FROM gateway_admin_alert_deliveries d
                     WHERE d.delivery_status = 'PENDING'
                       AND (d.next_attempt_at IS NULL OR d.next_attempt_at <= ?)
                       AND d.attempt_count < ?
                     ORDER BY d.next_attempt_at NULLS FIRST, d.created_at, d.alert_delivery_id
                     LIMIT ?
                     FOR UPDATE OF d SKIP LOCKED
                ),
                claimed AS (
                    UPDATE gateway_admin_alert_deliveries d
                       SET attempt_count = d.attempt_count + 1,
                           last_attempt_at = ?,
                           next_attempt_at = ?,
                           error_message = NULL,
                           updated_at = ?
                      FROM picked
                     WHERE d.alert_delivery_id = picked.alert_delivery_id
                    RETURNING d.*
                )
                SELECT d.alert_delivery_id, d.alert_event_id, d.alert_channel_id, d.channel_code,
                       d.channel_type, d.delivery_status, d.attempt_count, d.next_attempt_at,
                       d.last_attempt_at, d.created_at, d.updated_at,
                       c.channel_name, c.enabled AS channel_enabled, c.endpoint,
                       e.rule_code, e.domain, e.severity, e.status AS event_status, e.message
                  FROM claimed d
                  JOIN gateway_admin_alert_channels c ON c.alert_channel_id = d.alert_channel_id
                  JOIN gateway_admin_alert_events e ON e.alert_event_id = d.alert_event_id
                 ORDER BY d.last_attempt_at, d.alert_delivery_id
                """, this::toWorkItem,
                Timestamp.from(safeNow),
                safeMaxAttempts,
                safeLimit,
                Timestamp.from(safeNow),
                Timestamp.from(leaseUntil),
                Timestamp.from(safeNow));
    }

    public void markDeliverySent(long deliveryId, Instant now) {
        jdbcTemplate.update("""
                UPDATE gateway_admin_alert_deliveries
                   SET delivery_status = 'SENT',
                       next_attempt_at = NULL,
                       delivered_at = ?,
                       error_message = NULL,
                       updated_at = ?
                 WHERE alert_delivery_id = ?
                """, Timestamp.from(now), Timestamp.from(now), deliveryId);
    }

    public void markDeliverySkipped(long deliveryId, String reason, Instant now) {
        jdbcTemplate.update("""
                UPDATE gateway_admin_alert_deliveries
                   SET delivery_status = 'SKIPPED',
                       next_attempt_at = NULL,
                       error_message = ?,
                       updated_at = ?
                 WHERE alert_delivery_id = ?
                """, reason, Timestamp.from(now), deliveryId);
    }

    public void markDeliveryFailed(long deliveryId,
                                   String errorMessage,
                                   boolean exhausted,
                                   Instant nextAttemptAt,
                                   Instant now) {
        if (exhausted) {
            jdbcTemplate.update("""
                    UPDATE gateway_admin_alert_deliveries
                       SET delivery_status = 'FAILED',
                           next_attempt_at = NULL,
                           error_message = ?,
                           updated_at = ?
                     WHERE alert_delivery_id = ?
                    """, errorMessage, Timestamp.from(now), deliveryId);
            return;
        }
        jdbcTemplate.update("""
                UPDATE gateway_admin_alert_deliveries
                   SET delivery_status = 'PENDING',
                       next_attempt_at = ?,
                       error_message = ?,
                       updated_at = ?
                 WHERE alert_delivery_id = ?
                """, Timestamp.from(nextAttemptAt), errorMessage, Timestamp.from(now), deliveryId);
    }

    public AlertEvaluationResponse evaluate(Instant now) {
        List<AlertRule> activeRules = rules(null, true, 500);
        List<AlertEvent> opened = new ArrayList<>();
        int resolved = 0;
        for (AlertRule rule : activeRules) {
            Set<String> triggeredFingerprints = new LinkedHashSet<>();
            for (MetricSample sample : samples(rule, now)) {
                String fingerprint = fingerprint(rule, sample.target());
                if (!matches(rule.conditionOperator(), sample.value(), rule.thresholdValue())) {
                    continue;
                }
                triggeredFingerprints.add(fingerprint);
                AlertEvent event = upsertEvent(rule, sample, fingerprint, now);
                opened.add(event);
                enqueueDeliveries(event, now);
            }
            resolved += resolveInactive(rule.alertRuleId(), triggeredFingerprints, now);
        }
        return new AlertEvaluationResponse(activeRules.size(), opened.size(), resolved, opened);
    }

    private List<MetricSample> samples(AlertRule rule, Instant now) {
        return switch (rule.domain() + ":" + rule.metricKey()) {
            case "SYSTEM:OUTBOX_PENDING" -> systemOutbox("published_at IS NULL", "outbox pending");
            case "SYSTEM:OUTBOX_FAILED" -> systemOutbox("published_at IS NULL AND last_error IS NOT NULL", "outbox failed");
            case "SYSTEM:APPROVAL_PENDING" -> scalar("gateway-admin", "approval pending", """
                    SELECT 'gateway-admin' AS target, COUNT(*)::numeric AS value
                      FROM gateway_admin_approval_requests
                     WHERE status = 'PENDING'
                    """);
            case "SYSTEM:ADMIN_API_FAILURE_RATE_PPM" -> scalar("gateway-admin", "admin API failure rate", """
                    SELECT 'gateway-admin' AS target,
                           CASE WHEN COUNT(*) = 0 THEN 0
                                ELSE ROUND(COUNT(*) FILTER (WHERE success = FALSE) * 1000000.0 / COUNT(*))
                           END AS value
                      FROM gateway_admin_operation_logs
                     WHERE created_at >= ?
                    """, Timestamp.from(now.minusSeconds(rule.windowSeconds())));
            case "MARKET:MARK_INDEX_DEVIATION_PPM" -> marketMarkIndexDeviation(rule.target());
            case "MARKET:MARK_AGE_SECONDS" -> marketAge(rule.target(), "price_mark_ticks", now);
            case "MARKET:INDEX_AGE_SECONDS" -> marketAge(rule.target(), "price_index_ticks", now);
            case "MARKET:SOURCE_LATENCY_MILLIS" -> marketSourceLatency(rule.target());
            case "MARKET:UNHEALTHY_SOURCES" -> marketUnhealthySources(rule.target());
            case "MARKET:CANDLE_AGE_SECONDS" -> marketCandleAge(rule.target(), now);
            case "TRADING:ORDER_REJECT_RATE_PPM" -> scalar("trading", "order reject rate", """
                    SELECT 'trading' AS target,
                           CASE WHEN COUNT(*) = 0 THEN 0
                                ELSE ROUND(COUNT(*) FILTER (WHERE status = 'REJECTED') * 1000000.0 / COUNT(*))
                           END AS value
                      FROM trading_orders
                     WHERE created_at >= ?
                    """, Timestamp.from(now.minusSeconds(rule.windowSeconds())));
            case "TRADING:MATCHING_REJECT_RATE_PPM" -> scalar("matching", "matching reject rate", """
                    SELECT 'matching' AS target,
                           CASE WHEN COUNT(*) = 0 THEN 0
                                ELSE ROUND(COUNT(*) FILTER (WHERE result_code <> 'SUCCESS') * 1000000.0 / COUNT(*))
                           END AS value
                      FROM trading_match_results
                     WHERE event_time >= ?
                    """, Timestamp.from(now.minusSeconds(rule.windowSeconds())));
            default -> throw new IllegalArgumentException("unsupported alert metric: " + rule.domain() + ":" + rule.metricKey());
        };
    }

    private List<MetricSample> systemOutbox(String predicate, String label) {
        String sql = """
                SELECT 'all' AS target, SUM(value)::numeric AS value
                  FROM (
                        SELECT COUNT(*) AS value FROM funding_outbox_events WHERE %s
                        UNION ALL SELECT COUNT(*) AS value FROM trading_outbox_events WHERE %s
                        UNION ALL SELECT COUNT(*) AS value FROM account_outbox_events WHERE %s
                        UNION ALL SELECT COUNT(*) AS value FROM risk_outbox_events WHERE %s
                  ) rows
                """.formatted(predicate, predicate, predicate, predicate);
        return scalar("all", label, sql);
    }

    private List<MetricSample> marketMarkIndexDeviation(String target) {
        String symbol = normalizeNullableTarget(target);
        return querySamples("""
                WITH latest_index AS (
                    SELECT DISTINCT ON (symbol) symbol, index_price
                      FROM price_index_ticks
                     WHERE (CAST(? AS text) IS NULL OR symbol = ?)
                     ORDER BY symbol, event_time DESC, sequence DESC
                ),
                latest_mark AS (
                    SELECT DISTINCT ON (symbol) symbol, mark_price
                      FROM price_mark_ticks
                     WHERE (CAST(? AS text) IS NULL OR symbol = ?)
                     ORDER BY symbol, event_time DESC, sequence DESC
                )
                SELECT m.symbol AS target,
                       CASE WHEN i.index_price IS NULL OR i.index_price = 0 THEN 0
                            ELSE ROUND(ABS(m.mark_price - i.index_price) * 1000000.0 / ABS(i.index_price))
                       END AS value
                  FROM latest_mark m
                  JOIN latest_index i ON i.symbol = m.symbol
                """, symbol, symbol, symbol, symbol);
    }

    private List<MetricSample> marketAge(String target, String table, Instant now) {
        String symbol = normalizeNullableTarget(target);
        String sql = """
                SELECT symbol AS target, EXTRACT(EPOCH FROM (? - event_time))::numeric AS value
                  FROM (
                        SELECT DISTINCT ON (symbol) symbol, event_time
                          FROM %s
                         WHERE (CAST(? AS text) IS NULL OR symbol = ?)
                         ORDER BY symbol, event_time DESC, sequence DESC
                  ) latest
                """.formatted(table);
        return querySamples(sql, Timestamp.from(now), symbol, symbol);
    }

    private List<MetricSample> marketSourceLatency(String target) {
        String symbol = normalizeNullableTarget(target);
        return querySamples("""
                WITH latest_index AS (
                    SELECT DISTINCT ON (symbol) symbol, sequence
                      FROM price_index_ticks
                     WHERE (CAST(? AS text) IS NULL OR symbol = ?)
                     ORDER BY symbol, event_time DESC, sequence DESC
                )
                SELECT c.symbol AS target, COALESCE(MAX(c.latency_millis), 0)::numeric AS value
                  FROM price_index_components c
                  JOIN latest_index li ON li.symbol = c.symbol AND li.sequence = c.sequence
                 GROUP BY c.symbol
                """, symbol, symbol);
    }

    private List<MetricSample> marketUnhealthySources(String target) {
        String symbol = normalizeNullableTarget(target);
        return querySamples("""
                WITH latest_index AS (
                    SELECT DISTINCT ON (symbol) symbol, sequence
                      FROM price_index_ticks
                     WHERE (CAST(? AS text) IS NULL OR symbol = ?)
                     ORDER BY symbol, event_time DESC, sequence DESC
                )
                SELECT c.symbol AS target,
                       COUNT(*) FILTER (WHERE c.status <> 'HEALTHY')::numeric AS value
                  FROM price_index_components c
                  JOIN latest_index li ON li.symbol = c.symbol AND li.sequence = c.sequence
                 GROUP BY c.symbol
                """, symbol, symbol);
    }

    private List<MetricSample> marketCandleAge(String target, Instant now) {
        String symbol = normalizeNullableTarget(target);
        return querySamples("""
                SELECT symbol AS target, EXTRACT(EPOCH FROM (? - updated_at))::numeric AS value
                  FROM (
                        SELECT DISTINCT ON (symbol) symbol, updated_at
                          FROM candlestick_candles
                         WHERE period = '1m'
                           AND (CAST(? AS text) IS NULL OR symbol = ?)
                         ORDER BY symbol, updated_at DESC, open_time DESC
                  ) latest
                """, Timestamp.from(now), symbol, symbol);
    }

    private List<MetricSample> scalar(String target, String label, String sql, Object... args) {
        return querySamples(sql, args).stream()
                .map(sample -> new MetricSample(sample.target() == null ? target : sample.target(), sample.label() == null ? label : sample.label(), sample.value()))
                .toList();
    }

    private List<MetricSample> querySamples(String sql, Object... args) {
        return jdbcTemplate.queryForList(sql, args).stream()
                .map(row -> new MetricSample(stringValue(row.get("target")), null, decimalValue(row.get("value"))))
                .toList();
    }

    private AlertEvent upsertEvent(AlertRule rule, MetricSample sample, String fingerprint, Instant now) {
        String message = "%s %s %s threshold %s, current %s".formatted(
                rule.ruleCode(), sample.label() == null ? sample.target() : sample.label(),
                rule.conditionOperator(), rule.thresholdValue(), sample.value());
        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_admin_alert_events (
                    alert_rule_id, rule_code, domain, metric_key, target, severity, status,
                    condition_operator, threshold_value, current_value, fingerprint, message,
                    first_seen_at, last_seen_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (fingerprint) WHERE status IN ('OPEN', 'ACKNOWLEDGED') DO UPDATE
                   SET current_value = EXCLUDED.current_value,
                       message = EXCLUDED.message,
                       occurrences = gateway_admin_alert_events.occurrences + 1,
                       last_seen_at = EXCLUDED.last_seen_at,
                       updated_at = EXCLUDED.updated_at
                RETURNING alert_event_id, alert_rule_id, rule_code, domain, metric_key, target,
                          severity, status, condition_operator, threshold_value, current_value,
                          fingerprint, message, occurrences, first_seen_at, last_seen_at,
                          acknowledged_by_user_id, acknowledged_at, resolved_at, created_at, updated_at
                """, this::toEvent,
                rule.alertRuleId(),
                rule.ruleCode(),
                rule.domain(),
                rule.metricKey(),
                sample.target(),
                rule.severity(),
                rule.conditionOperator(),
                rule.thresholdValue(),
                sample.value(),
                fingerprint,
                message,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    private int resolveInactive(long ruleId, Set<String> triggeredFingerprints, Instant now) {
        List<ActiveEvent> activeEvents = jdbcTemplate.query("""
                SELECT alert_event_id, fingerprint
                  FROM gateway_admin_alert_events
                 WHERE alert_rule_id = ?
                   AND status IN ('OPEN', 'ACKNOWLEDGED')
                """, (rs, rowNum) -> new ActiveEvent(rs.getLong("alert_event_id"), rs.getString("fingerprint")), ruleId);
        int resolved = 0;
        for (ActiveEvent event : activeEvents) {
            if (triggeredFingerprints.contains(event.fingerprint())) {
                continue;
            }
            resolved += jdbcTemplate.update("""
                    UPDATE gateway_admin_alert_events
                       SET status = 'RESOLVED',
                           resolved_at = ?,
                           updated_at = ?
                     WHERE alert_event_id = ?
                    """, Timestamp.from(now), Timestamp.from(now), event.alertEventId());
        }
        return resolved;
    }

    private boolean matches(String operator, BigDecimal current, BigDecimal threshold) {
        if (current == null || threshold == null) {
            return false;
        }
        int compare = current.compareTo(threshold);
        return switch (operator) {
            case "GT" -> compare > 0;
            case "GTE" -> compare >= 0;
            case "LT" -> compare < 0;
            case "LTE" -> compare <= 0;
            case "EQ" -> compare == 0;
            case "NE" -> compare != 0;
            default -> false;
        };
    }

    private String fingerprint(AlertRule rule, String target) {
        return rule.ruleCode() + ":" + (target == null || target.isBlank() ? "all" : target);
    }

    private AlertRule toRule(ResultSet rs, int rowNum) throws SQLException {
        return new AlertRule(
                rs.getLong("alert_rule_id"),
                rs.getString("rule_code"),
                rs.getString("rule_name"),
                rs.getString("domain"),
                rs.getString("metric_key"),
                rs.getString("target"),
                rs.getString("condition_operator"),
                rs.getBigDecimal("threshold_value"),
                rs.getString("severity"),
                rs.getBoolean("enabled"),
                rs.getLong("window_seconds"),
                rs.getLong("cooldown_seconds"),
                rs.getString("description"),
                nullableLong(rs, "created_by_user_id"),
                nullableLong(rs, "updated_by_user_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private AlertEvent toEvent(ResultSet rs, int rowNum) throws SQLException {
        return new AlertEvent(
                rs.getLong("alert_event_id"),
                nullableLong(rs, "alert_rule_id"),
                rs.getString("rule_code"),
                rs.getString("domain"),
                rs.getString("metric_key"),
                rs.getString("target"),
                rs.getString("severity"),
                rs.getString("status"),
                rs.getString("condition_operator"),
                rs.getBigDecimal("threshold_value"),
                rs.getBigDecimal("current_value"),
                rs.getString("fingerprint"),
                rs.getString("message"),
                rs.getLong("occurrences"),
                rs.getTimestamp("first_seen_at").toInstant(),
                rs.getTimestamp("last_seen_at").toInstant(),
                nullableLong(rs, "acknowledged_by_user_id"),
                nullableInstant(rs, "acknowledged_at"),
                nullableInstant(rs, "resolved_at"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private AlertChannel toChannel(ResultSet rs, int rowNum) throws SQLException {
        return new AlertChannel(
                rs.getLong("alert_channel_id"),
                rs.getString("channel_code"),
                rs.getString("channel_name"),
                rs.getString("channel_type"),
                rs.getBoolean("enabled"),
                rs.getString("domain"),
                rs.getString("min_severity"),
                rs.getString("endpoint"),
                rs.getString("description"),
                nullableLong(rs, "created_by_user_id"),
                nullableLong(rs, "updated_by_user_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private AlertDelivery toDelivery(ResultSet rs, int rowNum) throws SQLException {
        return new AlertDelivery(
                rs.getLong("alert_delivery_id"),
                rs.getLong("alert_event_id"),
                rs.getLong("alert_channel_id"),
                rs.getString("channel_code"),
                rs.getString("channel_type"),
                rs.getString("delivery_status"),
                rs.getInt("attempt_count"),
                nullableInstant(rs, "next_attempt_at"),
                nullableInstant(rs, "last_attempt_at"),
                nullableInstant(rs, "delivered_at"),
                rs.getString("error_message"),
                rs.getString("rule_code"),
                rs.getString("domain"),
                rs.getString("severity"),
                rs.getString("event_status"),
                rs.getString("message"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private AlertDeliveryWorkItem toWorkItem(ResultSet rs, int rowNum) throws SQLException {
        return new AlertDeliveryWorkItem(
                rs.getLong("alert_delivery_id"),
                rs.getLong("alert_event_id"),
                rs.getLong("alert_channel_id"),
                rs.getString("channel_code"),
                rs.getString("channel_name"),
                rs.getString("channel_type"),
                rs.getBoolean("channel_enabled"),
                rs.getString("endpoint"),
                rs.getString("delivery_status"),
                rs.getInt("attempt_count"),
                nullableInstant(rs, "next_attempt_at"),
                nullableInstant(rs, "last_attempt_at"),
                rs.getString("rule_code"),
                rs.getString("domain"),
                rs.getString("severity"),
                rs.getString("event_status"),
                rs.getString("message"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private void enqueueDeliveries(AlertEvent event, Instant now) {
        for (AlertChannel channel : notificationChannels(event.domain(), event.severity())) {
            jdbcTemplate.update("""
                    INSERT INTO gateway_admin_alert_deliveries (
                        alert_event_id, alert_channel_id, channel_code, channel_type,
                        delivery_status, next_attempt_at, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?)
                    ON CONFLICT (alert_event_id, alert_channel_id) DO NOTHING
                    """, event.alertEventId(), channel.alertChannelId(), channel.channelCode(), channel.channelType(),
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        }
    }

    private List<AlertChannel> notificationChannels(String domain, String severity) {
        int eventSeverity = severityRank(severity);
        return jdbcTemplate.query("""
                SELECT alert_channel_id, channel_code, channel_name, channel_type, enabled,
                       domain, min_severity, endpoint, description, created_by_user_id,
                       updated_by_user_id, created_at, updated_at
                  FROM gateway_admin_alert_channels
                 WHERE enabled = TRUE
                   AND (domain IS NULL OR domain = ?)
                 ORDER BY domain NULLS FIRST, min_severity DESC, channel_code
                """, this::toChannel, domain).stream()
                .filter(channel -> eventSeverity >= severityRank(channel.minSeverity()))
                .toList();
    }

    private NormalizedRule normalize(AlertRuleRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        return new NormalizedRule(
                requiredUpper(request.ruleCode(), "ruleCode"),
                requiredText(request.ruleName(), "ruleName"),
                normalizeDomain(request.domain()),
                requiredUpper(request.metricKey(), "metricKey"),
                normalizeNullableTarget(request.target()),
                normalizeOperator(request.conditionOperator()),
                request.thresholdValue() == null ? BigDecimal.ZERO : request.thresholdValue(),
                normalizeSeverity(request.severity()),
                request.enabled() == null || request.enabled(),
                request.windowSeconds() == null ? 300L : Math.max(1L, Math.min(request.windowSeconds(), 86_400L)),
                request.cooldownSeconds() == null ? 300L : Math.max(0L, Math.min(request.cooldownSeconds(), 86_400L)),
                blankToNull(request.description()));
    }

    private NormalizedChannel normalizeChannel(AlertChannelRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        String channelType = normalizeChannelType(request.channelType());
        String endpoint = blankToNull(request.endpoint());
        if (!"OPS".equals(channelType) && endpoint == null) {
            throw new IllegalArgumentException("endpoint is required for " + channelType + " alert channel");
        }
        return new NormalizedChannel(
                requiredUpper(request.channelCode(), "channelCode"),
                requiredText(request.channelName(), "channelName"),
                channelType,
                request.enabled() == null || request.enabled(),
                normalizeNullableDomain(request.domain()),
                request.minSeverity() == null || request.minSeverity().isBlank()
                        ? "WARN"
                        : normalizeSeverity(request.minSeverity()),
                endpoint,
                blankToNull(request.description()));
    }

    private String normalizeChannelType(String value) {
        String normalized = requiredUpper(value, "channelType");
        if (!List.of("WEBHOOK", "EMAIL", "SLACK", "PAGERDUTY", "OPS").contains(normalized)) {
            throw new IllegalArgumentException("unsupported alert channel type: " + value);
        }
        return normalized;
    }

    private String normalizeNullableDeliveryStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PENDING", "SENT", "FAILED", "SKIPPED").contains(normalized)) {
            throw new IllegalArgumentException("unsupported alert delivery status: " + value);
        }
        return normalized;
    }

    private int severityRank(String severity) {
        return switch (normalizeSeverity(severity)) {
            case "INFO" -> 1;
            case "WARN" -> 2;
            case "CRITICAL" -> 3;
            default -> 0;
        };
    }

    private String normalizeDomain(String value) {
        String normalized = requiredUpper(value, "domain");
        if (!List.of("SYSTEM", "MARKET", "TRADING", "RISK", "WALLET").contains(normalized)) {
            throw new IllegalArgumentException("unsupported alert domain: " + value);
        }
        return normalized;
    }

    private String normalizeNullableDomain(String value) {
        return value == null || value.isBlank() ? null : normalizeDomain(value);
    }

    private String normalizeSeverity(String value) {
        String normalized = requiredUpper(value, "severity");
        if (!List.of("INFO", "WARN", "CRITICAL").contains(normalized)) {
            throw new IllegalArgumentException("unsupported alert severity: " + value);
        }
        return normalized;
    }

    private String normalizeNullableSeverity(String value) {
        return value == null || value.isBlank() ? null : normalizeSeverity(value);
    }

    private String normalizeNullableStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("OPEN", "ACKNOWLEDGED", "RESOLVED").contains(normalized)) {
            throw new IllegalArgumentException("unsupported alert event status: " + value);
        }
        return normalized;
    }

    private String normalizeOperator(String value) {
        String normalized = requiredUpper(value, "conditionOperator");
        if (!List.of("GT", "GTE", "LT", "LTE", "EQ", "NE").contains(normalized)) {
            throw new IllegalArgumentException("unsupported alert operator: " + value);
        }
        return normalized;
    }

    private String normalizeNullableTarget(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String requiredUpper(String value, String field) {
        return requiredText(value, field).toUpperCase(Locale.ROOT);
    }

    private String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            return new BigDecimal(text);
        }
        return BigDecimal.ZERO;
    }

    private Function<AlertEvent, Instant> eventTimestampExtractor(AdminCursorPage.SortSpec sort) {
        return switch (sort.field()) {
            case "createdAt" -> AlertEvent::createdAt;
            case "lastSeenAt" -> AlertEvent::lastSeenAt;
            default -> throw new IllegalArgumentException("unsupported sort: " + sort.token());
        };
    }

    private Function<AlertDelivery, Instant> deliveryTimestampExtractor(AdminCursorPage.SortSpec sort) {
        return switch (sort.field()) {
            case "createdAt" -> AlertDelivery::createdAt;
            case "updatedAt" -> AlertDelivery::updatedAt;
            default -> throw new IllegalArgumentException("unsupported sort: " + sort.token());
        };
    }

    private Function<AlertRule, Instant> ruleTimestampExtractor(AdminCursorPage.SortSpec sort) {
        return switch (sort.field()) {
            case "createdAt" -> AlertRule::createdAt;
            case "updatedAt" -> AlertRule::updatedAt;
            default -> throw new IllegalArgumentException("unsupported sort: " + sort.token());
        };
    }

    private Function<AlertChannel, Instant> channelTimestampExtractor(AdminCursorPage.SortSpec sort) {
        return switch (sort.field()) {
            case "createdAt" -> AlertChannel::createdAt;
            case "updatedAt" -> AlertChannel::updatedAt;
            default -> throw new IllegalArgumentException("unsupported sort: " + sort.token());
        };
    }

    public record AlertRuleRequest(
            String ruleCode,
            String ruleName,
            String domain,
            String metricKey,
            String target,
            String conditionOperator,
            BigDecimal thresholdValue,
            String severity,
            Boolean enabled,
            Long windowSeconds,
            Long cooldownSeconds,
            String description) {
    }

    public record AlertChannelRequest(
            String channelCode,
            String channelName,
            String channelType,
            Boolean enabled,
            String domain,
            String minSeverity,
            String endpoint,
            String description) {
    }

    private record NormalizedRule(
            String ruleCode,
            String ruleName,
            String domain,
            String metricKey,
            String target,
            String operator,
            BigDecimal threshold,
            String severity,
            boolean enabled,
            long windowSeconds,
            long cooldownSeconds,
            String description) {
    }

    private record NormalizedChannel(
            String channelCode,
            String channelName,
            String channelType,
            boolean enabled,
            String domain,
            String minSeverity,
            String endpoint,
            String description) {
    }

    private record MetricSample(
            String target,
            String label,
            BigDecimal value) {
    }

    private record ActiveEvent(
            long alertEventId,
            String fingerprint) {
    }

    public record AlertRule(
            long alertRuleId,
            String ruleCode,
            String ruleName,
            String domain,
            String metricKey,
            String target,
            String conditionOperator,
            BigDecimal thresholdValue,
            String severity,
            boolean enabled,
            long windowSeconds,
            long cooldownSeconds,
            String description,
            Long createdByUserId,
            Long updatedByUserId,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record AlertEvent(
            long alertEventId,
            Long alertRuleId,
            String ruleCode,
            String domain,
            String metricKey,
            String target,
            String severity,
            String status,
            String conditionOperator,
            BigDecimal thresholdValue,
            BigDecimal currentValue,
            String fingerprint,
            String message,
            long occurrences,
            Instant firstSeenAt,
            Instant lastSeenAt,
            Long acknowledgedByUserId,
            Instant acknowledgedAt,
            Instant resolvedAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record AlertChannel(
            long alertChannelId,
            String channelCode,
            String channelName,
            String channelType,
            boolean enabled,
            String domain,
            String minSeverity,
            String endpoint,
            String description,
            Long createdByUserId,
            Long updatedByUserId,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record AlertDelivery(
            long alertDeliveryId,
            long alertEventId,
            long alertChannelId,
            String channelCode,
            String channelType,
            String deliveryStatus,
            int attemptCount,
            Instant nextAttemptAt,
            Instant lastAttemptAt,
            Instant deliveredAt,
            String errorMessage,
            String ruleCode,
            String domain,
            String severity,
            String eventStatus,
            String message,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record AlertDeliveryWorkItem(
            long alertDeliveryId,
            long alertEventId,
            long alertChannelId,
            String channelCode,
            String channelName,
            String channelType,
            boolean channelEnabled,
            String endpoint,
            String deliveryStatus,
            int attemptCount,
            Instant nextAttemptAt,
            Instant lastAttemptAt,
            String ruleCode,
            String domain,
            String severity,
            String eventStatus,
            String message,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record AlertRuleQueryResponse(
            int count,
            List<AlertRule> rules,
            String nextCursor,
            boolean hasMore,
            String sort,
            int limit) {
        public AlertRuleQueryResponse(int count, List<AlertRule> rules) {
            this(count, rules, null, false, "updatedAt.desc", count);
        }
    }

    public record AlertEventQueryResponse(
            int count,
            List<AlertEvent> events,
            String nextCursor,
            boolean hasMore,
            String sort,
            int limit) {

        public AlertEventQueryResponse(int count, List<AlertEvent> events) {
            this(count, events, null, false, null, count);
        }
    }

    public record AlertChannelQueryResponse(
            int count,
            List<AlertChannel> channels,
            String nextCursor,
            boolean hasMore,
            String sort,
            int limit) {
        public AlertChannelQueryResponse(int count, List<AlertChannel> channels) {
            this(count, channels, null, false, "updatedAt.desc", count);
        }
    }

    public record AlertDeliveryQueryResponse(
            int count,
            List<AlertDelivery> deliveries,
            String nextCursor,
            boolean hasMore,
            String sort,
            int limit) {

        public AlertDeliveryQueryResponse(int count, List<AlertDelivery> deliveries) {
            this(count, deliveries, null, false, null, count);
        }
    }

    public record AlertEvaluationResponse(
            int evaluatedRules,
            int triggeredEvents,
            int resolvedEvents,
            List<AlertEvent> events) {
    }
}
