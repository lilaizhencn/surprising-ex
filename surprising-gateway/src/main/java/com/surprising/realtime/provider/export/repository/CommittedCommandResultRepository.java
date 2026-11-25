package com.surprising.realtime.provider.export.repository;

import com.surprising.aeron.protocol.export.CommittedCommandResult;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 仅访问core_command_result_projection，按 (product_line, command_id) 幂等写入命令回执。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CommittedCommandResultRepository {
    private final JdbcTemplate jdbc;
    private final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();

    public CommittedCommandResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void persistCommandResults(ProductLine product, Map<UUID, CommittedCommandResult> receipts) {
        if (receipts.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO trading_command_results(product_line,command_id,user_id,command_type,
                  client_order_id,instrument_id,command_status,result_code,applied_command_count,
                  cluster_position,occurred_at_epoch_ms,cancel_batch_results) VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                ON CONFLICT (product_line,command_id) DO NOTHING
                """, receipts.values(), 256, (s, receipt) -> {
            s.setString(1, product.name()); s.setObject(2, receipt.commandId()); s.setLong(3, receipt.userId());
            s.setString(4, receipt.commandType().name()); s.setString(5, receipt.clientOrderId());
            s.setString(6, receipt.instrumentId()); s.setString(7, receipt.status().name());
            s.setString(8, receipt.code().name()); s.setLong(9, receipt.appliedCommandCount());
            s.setLong(10, receipt.clusterPosition()); s.setLong(11, receipt.occurredAt());
            s.setString(12, receipt.cancelResults().isEmpty() ? null : json.writeValueAsString(receipt.cancelResults()));
        });
    }
}
