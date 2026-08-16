package com.surprising.websocket.provider.service;

import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CoreEventAuditRepository {

    private static final String SELECT = """
            SELECT event_id, command_id, event_type, user_id, occurred_at_epoch_ms, raw_event
            FROM core_websocket_audit_projection
            WHERE product_line = ? AND export_sequence = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public CoreEventAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    public void requireProjected(ProductLine productLine, CoreExportEvent event, byte[] rawEvent,
                                 long occurredAtEpochMillis) {
        Objects.requireNonNull(productLine, "productLine");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(rawEvent, "rawEvent");
        UUID eventId = UUID.nameUUIDFromBytes((productLine.name() + ':' + event.exportSequence())
                .getBytes(StandardCharsets.UTF_8));
        AuditRow row;
        try {
            row = jdbcTemplate.queryForObject(SELECT, (result, ignored) -> new AuditRow(
                            result.getObject("event_id", UUID.class),
                            result.getObject("command_id", UUID.class),
                            result.getString("event_type"),
                            result.getLong("user_id"),
                            result.getLong("occurred_at_epoch_ms"),
                            result.getBytes("raw_event")),
                    productLine.name(), event.exportSequence());
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalStateException("Core event audit is not projected yet: productLine="
                    + productLine + " exportSequence=" + event.exportSequence(), exception);
        }
        if (row == null
                || !eventId.equals(row.eventId())
                || !event.commandId().equals(row.commandId())
                || !event.commandType().name().equals(row.eventType())
                || event.userId() != row.userId()
                || occurredAtEpochMillis != row.occurredAtEpochMillis()
                || !Arrays.equals(rawEvent, row.rawEvent())) {
            throw new IllegalStateException("Core event audit identity mismatch: productLine="
                    + productLine + " exportSequence=" + event.exportSequence());
        }
    }

    private record AuditRow(UUID eventId, UUID commandId, String eventType, long userId,
                            long occurredAtEpochMillis, byte[] rawEvent) {
    }
}
