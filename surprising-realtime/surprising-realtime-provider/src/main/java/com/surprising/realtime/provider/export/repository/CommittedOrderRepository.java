package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Collection;
import java.util.Collections;
import java.util.ArrayList;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.surprising.realtime.provider.export.model.CommittedProjectionRows.OrderWrite;

/** 仅访问core_order_projection；只保存提交批给出的最新订单，不查询在线Core。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class CommittedOrderRepository {
    private final JdbcTemplate jdbc;

    public CommittedOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String UPSERT = """
            INSERT INTO core_order_projection
              (product_line,order_id,user_id,client_order_id,instrument_id,status,
               created_at_epoch_ms,updated_at_epoch_ms,cluster_position,order_revision,
               export_sequence,raw_order_state)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (product_line,order_id) DO UPDATE SET
              client_order_id=EXCLUDED.client_order_id,status=EXCLUDED.status,
              updated_at_epoch_ms=EXCLUDED.updated_at_epoch_ms,
              cluster_position=EXCLUDED.cluster_position,order_revision=EXCLUDED.order_revision,
              export_sequence=EXCLUDED.export_sequence,raw_order_state=EXCLUDED.raw_order_state
            WHERE core_order_projection.order_revision < EXCLUDED.order_revision
            """;
    public List<Long> existingOrders(ProductLine product, List<Long> ids) {
        var args = new ArrayList<Object>(); args.add(product.name()); args.addAll(ids);
        return jdbc.queryForList("SELECT order_id FROM core_order_projection WHERE product_line=? AND order_id IN ("
                + String.join(",", Collections.nCopies(ids.size(), "?")) + ")", Long.class, args.toArray());
    }

    public void persist(ProductLine product, Collection<OrderWrite> writes, long exportSequence) {
        jdbc.batchUpdate(UPSERT, writes, 512, (statement, write) -> {
            var order = write.order();
            statement.setString(1, product.name());
            statement.setLong(2, order.orderId());
            statement.setLong(3, order.userId());
            statement.setString(4, order.clientOrderId().isEmpty() ? null : order.clientOrderId());
            statement.setString(5, order.instrumentId());
            statement.setString(6, order.status());
            statement.setLong(7, order.createdAtEpochMillis());
            statement.setLong(8, order.updatedAtEpochMillis());
            statement.setLong(9, order.clusterPosition());
            statement.setLong(10, order.revision());
            statement.setLong(11, exportSequence);
            statement.setBytes(12, write.raw());
        });
    }
}
