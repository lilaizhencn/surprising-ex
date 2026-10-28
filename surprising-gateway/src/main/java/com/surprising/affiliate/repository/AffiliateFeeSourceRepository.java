package com.surprising.affiliate.repository;

import com.surprising.affiliate.model.AffiliateModels.Fee;
import com.surprising.affiliate.model.AffiliateModels.SourceCursor;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads only the generic, committed financial projection, using a bounded product cursor. */
@Repository
public class AffiliateFeeSourceRepository {

  private final JdbcTemplate jdbc;

  public AffiliateFeeSourceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Fee> read(String product, SourceCursor after) {
    return jdbc.query(
      """
      SELECT cluster_position,product_line,user_id,order_id,asset,fee_units,occurred_at FROM core_trade_fee_facts
      WHERE product_line=? AND (cluster_position,order_id)>(?,?) AND fee_units>0 ORDER BY cluster_position,order_id LIMIT 100
      """,
      (r, n) ->
        new Fee(
          r.getLong(1),
          r.getString(2),
          r.getLong(3),
          r.getLong(4),
          r.getString(5),
          r.getLong(6),
          r.getTimestamp(7).toInstant()
        ),
      product,
      after.clusterPosition(),
      after.orderId()
    );
  }
}
