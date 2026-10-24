package com.surprising.realtime.provider.export.repository;

import com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedCommandResult;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.surprising.realtime.provider.export.utils.CommittedCommandResults.requireSameResult;

/** 仅访问core_command_result_projection，写入当前回执并在行锁内核对完整事实，冲突交原事务回滚。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class CommittedCommandResultRepository {
    private final JdbcTemplate jdbc;
    private final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();

    public CommittedCommandResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void persistCommandResults(ProductLine product, Map<UUID, CommittedCommandResult> receipts) {
        if (receipts.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO core_command_result_projection(product_line,command_id,user_id,command_type,
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
        // Replays can revisit already-persisted commands. Verify their immutable outcome;
        // a conflicting row aborts the same transaction as orders and their watermark.
        var arguments = new ArrayList<Object>();
        arguments.add(product.name()); arguments.addAll(receipts.keySet());
        var stored = jdbc.query("""
                SELECT * FROM core_command_result_projection WHERE product_line=? AND command_id IN (
                """ + String.join(",", Collections.nCopies(receipts.size(), "?")) + ") ORDER BY command_id FOR UPDATE", (r, row) ->
                new CommittedCommandResult(product, r.getLong("user_id"), r.getObject("command_id", UUID.class),
                    com.surprising.aeron.protocol.CoreMessageType.valueOf(r.getString("command_type")),
                    r.getString("client_order_id"), r.getString("instrument_id"),
                    com.surprising.aeron.protocol.ResponseStatus.valueOf(r.getString("command_status")),
                    com.surprising.aeron.protocol.CoreResultCode.valueOf(r.getString("result_code")),
                    r.getLong("applied_command_count"), r.getLong("cluster_position"), r.getLong("occurred_at_epoch_ms"),
                    cancelItems(r.getString("cancel_batch_results"))),
                arguments.toArray());
        if (stored.size() != receipts.size()) throw new IllegalStateException("incomplete committed command results");
        for (var receipt : stored) {
            var expected = receipts.get(receipt.commandId());
            requireSameResult(expected, receipt);
        }
    }
    private List<com.surprising.aeron.service.replay.CommittedTradeReplay.CancelBatchItem> cancelItems(String raw) {
        return raw == null ? List.of() : List.of(json.readValue(raw,
                com.surprising.aeron.service.replay.CommittedTradeReplay.CancelBatchItem[].class));
    }
}
