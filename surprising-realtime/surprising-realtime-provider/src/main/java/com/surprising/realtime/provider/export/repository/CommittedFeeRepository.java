package com.surprising.realtime.provider.export.repository;

import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreTradeFeeObservation;
import com.surprising.product.api.ProductLine;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persist exact differences of committed order fees; never recompute a fee from notional or rate. */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
  prefix = "surprising.trade-export",
  name = "enabled",
  havingValue = "true"
)
public class CommittedFeeRepository {

  private final JdbcTemplate jdbc;

  public CommittedFeeRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void baseline(ProductLine product, CoreTradeFeeObservation value) {
    jdbc.update(
      "INSERT INTO core_trade_fee_progress(product_line,order_id,cluster_position,cumulative_fee_units) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
      product.name(),
      value.orderId(),
      value.clusterPosition(),
      value.cumulativeFeeUnits()
    );
  }

  public void observe(
    ProductLine product,
    CoreTradeFeeObservation value,
    CoreOrderStateView baseline
  ) {
    var previous = jdbc.queryForList(
      "SELECT cluster_position,cumulative_fee_units FROM core_trade_fee_progress WHERE product_line=? AND order_id=? FOR UPDATE",
      product.name(),
      value.orderId()
    );
    long priorPosition = previous.isEmpty()
      ? baseline == null
        ? 0
        : baseline.clusterPosition()
      : ((Number) previous.getFirst().get("cluster_position")).longValue();
    long priorFee = previous.isEmpty()
      ? baseline == null
        ? 0
        : baseline.cumulativeFeeUnits()
      : ((Number) previous.getFirst().get("cumulative_fee_units")).longValue();
    if (priorPosition >= value.clusterPosition()) {
      if (
        priorPosition == value.clusterPosition() &&
        priorFee != value.cumulativeFeeUnits()
      ) throw new IllegalStateException(
        "conflicting committed fee observation"
      );
      return;
    }
    long delta = Math.subtractExact(value.cumulativeFeeUnits(), priorFee);
    if (delta != 0) {
      jdbc.update(
        """
        INSERT INTO core_trade_fee_facts(product_line,order_id,user_id,instrument_id,asset,fee_units,cumulative_fee_units,cluster_position,occurred_at)
        VALUES(?,?,?,?,?,?,?,?,?)
        """,
        product.name(),
        value.orderId(),
        value.userId(),
        value.instrumentId(),
        value.asset(),
        delta,
        value.cumulativeFeeUnits(),
        value.clusterPosition(),
        Timestamp.from(Instant.ofEpochMilli(value.occurredAt()))
      );
    }
    jdbc.update(
      """
      INSERT INTO core_trade_fee_progress(product_line,order_id,cluster_position,cumulative_fee_units) VALUES(?,?,?,?)
      ON CONFLICT(product_line,order_id) DO UPDATE SET cluster_position=EXCLUDED.cluster_position,cumulative_fee_units=EXCLUDED.cumulative_fee_units
      """,
      product.name(),
      value.orderId(),
      value.clusterPosition(),
      value.cumulativeFeeUnits()
    );
  }
}
