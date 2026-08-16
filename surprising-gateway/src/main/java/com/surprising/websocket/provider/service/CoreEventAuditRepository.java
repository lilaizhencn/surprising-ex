package com.surprising.websocket.provider.service;

import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CoreEventAuditRepository {

    private static final String INSERT = """
            INSERT INTO core_websocket_audit_projection (
                product_line, export_sequence, event_id, command_id, event_type,
                user_id, occurred_at_epoch_ms, raw_event
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (product_line, export_sequence) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public CoreEventAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    public void record(ProductLine productLine, CoreExportEvent event, byte[] rawEvent,
                       long occurredAtEpochMillis) {
        Objects.requireNonNull(productLine, "productLine");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(rawEvent, "rawEvent");
        UUID eventId = UUID.nameUUIDFromBytes((productLine.name() + ':' + event.exportSequence())
                .getBytes(StandardCharsets.UTF_8));
        jdbcTemplate.update(INSERT, productLine.name(), event.exportSequence(), eventId, event.commandId(),
                event.commandType().name(), event.userId(), occurredAtEpochMillis, rawEvent.clone());
    }
}
