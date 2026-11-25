package com.surprising.affiliate.repository;

import com.surprising.affiliate.config.AffiliateDatabase;
import com.surprising.affiliate.model.AffiliateModels.Fee;
import com.surprising.affiliate.model.AffiliateModels.SourceCursor;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 隔离手续费队列仓储：负责延迟核算费用写入、重试查询与凭证核销。
 */
@Repository
public class AffiliateDeferredFeeRepository {

  private final JdbcTemplate jdbc;

  public AffiliateDeferredFeeRepository(AffiliateDatabase database) {
    this.jdbc = database.jdbc();
  }

  public int deferredCount(String product, int limit) {
    return jdbc.queryForObject(
      "SELECT count(*) FROM (SELECT 1 FROM affiliate_deferred_fees WHERE product_line=? AND resolved_at IS NULL LIMIT ?) d",
      Integer.class,
      product,
      limit
    );
  }

  public void deferFee(
    Fee fee,
    SourceCursor previous,
    String error,
    int retrySeconds
  ) {
    jdbc.update(
      """
      INSERT INTO affiliate_deferred_fees(product_line,cluster_position,order_id,user_id,asset,fee_units,occurred_at,previous_position,previous_order_id,error,next_attempt_at)
      VALUES(?,?,?,?,?,?,?,?,?,?,clock_timestamp()+(?*interval '1 second'))
      """,
      fee.productLine(),
      fee.clusterPosition(),
      fee.orderId(),
      fee.userId(),
      fee.asset(),
      fee.feeUnits(),
      Timestamp.from(fee.occurredAt()),
      previous.clusterPosition(),
      previous.orderId(),
      error.substring(0, Math.min(500, error.length())),
      retrySeconds
    );
  }

  public List<Fee> deferredFees(String product, int limit) {
    return jdbc.query(
      """
      SELECT d.*,f.user_id AS fact_user,f.asset AS fact_asset,f.fee_units AS fact_units,f.occurred_at AS fact_time
      FROM affiliate_deferred_fees d JOIN trade_fee_facts f
        ON f.product_line=d.product_line AND f.order_id=d.order_id AND f.cluster_position=d.cluster_position
      WHERE d.product_line=? AND d.resolved_at IS NULL AND d.next_attempt_at<=clock_timestamp()
      ORDER BY d.next_attempt_at,d.cluster_position,d.order_id LIMIT ?
      """,
      (r, n) -> {
        var fee = new Fee(
          r.getLong("cluster_position"),
          r.getString("product_line"),
          r.getLong("user_id"),
          r.getLong("order_id"),
          r.getString("asset"),
          r.getLong("fee_units"),
          r.getTimestamp("occurred_at").toInstant()
        );
        if (
          fee.userId() != r.getLong("fact_user") ||
          !fee.asset().equals(r.getString("fact_asset")) ||
          fee.feeUnits() != r.getLong("fact_units") ||
          !fee.occurredAt().equals(r.getTimestamp("fact_time").toInstant())
        ) throw new IllegalStateException(
          "隔离费用与权威来源身份冲突，产品核算已暂停"
        );
        return fee;
      },
      product,
      limit
    );
  }

  public void retryDeferredFee(Fee fee, String error, int seconds) {
    jdbc.update(
      "UPDATE affiliate_deferred_fees SET attempt_count=attempt_count+1,last_attempt_at=clock_timestamp(),next_attempt_at=clock_timestamp()+(?*interval '1 second'),error=? WHERE product_line=? AND cluster_position=? AND order_id=? AND resolved_at IS NULL",
      seconds,
      error.substring(0, Math.min(500, error.length())),
      fee.productLine(),
      fee.clusterPosition(),
      fee.orderId()
    );
  }

  public void resolveDeferredFee(Fee fee) {
    int changed = jdbc.update(
      """
      UPDATE affiliate_deferred_fees d SET resolved_at=clock_timestamp(),receipt_id=f.receipt_id,error='已恢复核算',last_attempt_at=clock_timestamp()
      FROM affiliate_fee_receipts f WHERE d.product_line=? AND d.cluster_position=? AND d.order_id=? AND d.resolved_at IS NULL
        AND f.product_line=d.product_line AND f.cluster_position=d.cluster_position AND f.order_id=d.order_id
      """,
      fee.productLine(),
      fee.clusterPosition(),
      fee.orderId()
    );
    if (changed != 1) throw new IllegalStateException(
      "隔离费用缺少唯一已提交核算凭证"
    );
  }
}
