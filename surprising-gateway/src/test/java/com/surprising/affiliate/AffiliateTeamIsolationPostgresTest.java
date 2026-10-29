package com.surprising.affiliate;

import static org.assertj.core.api.Assertions.*;

import com.surprising.affiliate.config.AffiliateProperties;
import com.surprising.affiliate.model.AffiliateModels.*;
import java.sql.Timestamp;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(
  named = "INSTRUMENT_TEST_JDBC_URL",
  matches = ".+"
)
@Timeout(30)
class AffiliateTeamIsolationPostgresTest {

  AffiliateScaleProbe f;

  @BeforeEach
  void setup() throws Exception {
    f = new AffiliateScaleProbe();
    f.setup();
  }

  @AfterEach
  void cleanup() {
    if (f != null) f.cleanup();
  }

  void fact(Fee fee) {
    f.jdbc.update(
      "INSERT INTO core_trade_fee_facts(product_line,order_id,user_id,instrument_id,asset,fee_units,cumulative_fee_units,cluster_position,occurred_at) VALUES(?,?,?,'TEAM-TEST',?,?,?,?,?)",
      fee.productLine(),
      fee.orderId(),
      fee.userId(),
      fee.asset(),
      fee.feeUnits(),
      fee.feeUnits(),
      fee.clusterPosition(),
      Timestamp.from(fee.occurredAt())
    );
  }

  void teams() {
    f.seed(8, 0);
    f.jdbc.update("INSERT INTO gateway_users(user_id) VALUES(200),(201)");
    f.service.register(200, null);
    f.service.register(201, null);
    f.service.accept(201, f.repository.profile(200).inviteCode());
    f.context.getBean(AffiliateProperties.class).setMaxPathNodes(2);
    var now = f.repository.now();
    fact(new Fee(1, "LINEAR_PERPETUAL", 108, 42, "USDT", 10000, now));
    fact(new Fee(2, "LINEAR_PERPETUAL", 201, 43, "USDT", 10000, now));
  }

  @Test
  void brokenTeamDoesNotBlockHealthyTeamsInTheSameProduct() {
    teams();
    assertThatCode(() ->
      f.accrual.accrueBatch("LINEAR_PERPETUAL")
    ).doesNotThrowAnyException();
    assertThat(
      f.jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_fee_receipts",
        Long.class
      )
    ).isEqualTo(1);
    assertThat(
      f.repository.summary(200, "").getFirst().get("retainedUnits")
    ).isEqualTo("2000");
    assertThat(f.ledger.sourcePosition("LINEAR_PERPETUAL")).isEqualTo(
      new SourceCursor(0, 0)
    );
    assertThat(
      f.jdbc.queryForObject(
        "SELECT read_cluster_position FROM affiliate_source_progress",
        Long.class
      )
    ).isEqualTo(2);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_deferred_fees WHERE resolved_at IS NULL",
        Long.class
      )
    ).isEqualTo(1);
    f.context.getBean(AffiliateProperties.class).setMaxPathNodes(8);
    f.jdbc.update(
      "UPDATE affiliate_deferred_fees SET next_attempt_at='-infinity'"
    );
    f.accrual.retryDeferred("LINEAR_PERPETUAL");
    f.accrual.accrueBatch("LINEAR_PERPETUAL");
    assertThat(
      f.jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_fee_receipts",
        Long.class
      )
    ).isEqualTo(2);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_deferred_fees WHERE resolved_at IS NULL",
        Long.class
      )
    ).isZero();
    assertThat(
      f.jdbc.queryForObject(
        "SELECT sum(retained_units) FROM affiliate_commissions",
        Long.class
      )
    ).isEqualTo(4000);
    assertThat(f.ledger.sourcePosition("LINEAR_PERPETUAL")).isEqualTo(
      new SourceCursor(2, 43)
    );
  }

  @Test
  void interruptedCommitLeavesNeitherConsumedFeesNorQuarantineOrCursor() {
    teams();
    assertThatThrownBy(() ->
      new TransactionTemplate(f.manager).executeWithoutResult(tx -> {
        f.accrual.accrueBatch("LINEAR_PERPETUAL");
        throw new IllegalStateException(
          "simulated process failure before commit"
        );
      })
    ).isInstanceOf(IllegalStateException.class);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_fee_receipts",
        Long.class
      )
    ).isZero();
    assertThat(
      f.jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_deferred_fees",
        Long.class
      )
    ).isZero();
    assertThat(f.ledger.sourcePosition("LINEAR_PERPETUAL")).isEqualTo(
      new SourceCursor(0, 0)
    );
  }

  @Test
  void aFullQuarantineStopsBeforeTheUnstoredFeeAndResumesWithoutLoss() {
    teams();
    var properties = f.context.getBean(AffiliateProperties.class);
    properties.setMaxDeferredFees(1);
    var now = f.repository.now();
    fact(new Fee(3, "LINEAR_PERPETUAL", 108, 44, "USDT", 10000, now));
    fact(new Fee(4, "LINEAR_PERPETUAL", 201, 45, "USDT", 10000, now));
    var result = f.accrual.accrueBatch("LINEAR_PERPETUAL");
    assertThat(result.processed()).isEqualTo(2);
    assertThat(result.more()).isFalse();
    assertThat(f.ledger.readPosition("LINEAR_PERPETUAL")).isEqualTo(
      new SourceCursor(2, 43)
    );
    assertThat(
      f.jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_deferred_fees",
        Long.class
      )
    ).isEqualTo(1);
    f.accrual.accrueBatch("LINEAR_PERPETUAL");
    assertThat(
      f.jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_fee_receipts",
        Long.class
      )
    ).isEqualTo(1);
    properties.setMaxPathNodes(8);
    f.jdbc.update(
      "UPDATE affiliate_deferred_fees SET next_attempt_at='-infinity'"
    );
    f.accrual.retryDeferred("LINEAR_PERPETUAL");
    f.accrual.accrueBatch("LINEAR_PERPETUAL");
    assertThat(
      f.jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_fee_receipts",
        Long.class
      )
    ).isEqualTo(4);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT sum(retained_units) FROM affiliate_commissions",
        Long.class
      )
    ).isEqualTo(8000);
    assertThat(f.ledger.sourcePosition("LINEAR_PERPETUAL")).isEqualTo(
      new SourceCursor(4, 45)
    );
  }

  @Test
  void repairingALaterFeeCannotConfirmPastAnEarlierUnresolvedFee() {
    teams();
    var now = f.repository.now();
    fact(new Fee(3, "LINEAR_PERPETUAL", 108, 44, "USDT", 10000, now));
    fact(new Fee(4, "LINEAR_PERPETUAL", 201, 45, "USDT", 10000, now));
    f.accrual.accrueBatch("LINEAR_PERPETUAL");
    f.context.getBean(AffiliateProperties.class).setMaxPathNodes(8);
    f.jdbc.update(
      "UPDATE affiliate_deferred_fees SET next_attempt_at='-infinity' WHERE cluster_position=3"
    );
    f.accrual.retryDeferred("LINEAR_PERPETUAL");
    assertThat(f.ledger.sourcePosition("LINEAR_PERPETUAL")).isEqualTo(
      new SourceCursor(0, 0)
    );
    assertThat(f.ledger.readPosition("LINEAR_PERPETUAL")).isEqualTo(
      new SourceCursor(4, 45)
    );
    f.jdbc.update(
      "UPDATE affiliate_deferred_fees SET next_attempt_at='-infinity' WHERE cluster_position=1"
    );
    f.accrual.retryDeferred("LINEAR_PERPETUAL");
    assertThat(f.ledger.sourcePosition("LINEAR_PERPETUAL")).isEqualTo(
      new SourceCursor(4, 45)
    );
    assertThat(
      f.jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_fee_receipts",
        Long.class
      )
    ).isEqualTo(4);
  }

  @Test
  void capturedFactsCannotBeRewrittenDeletedOrResolvedWithoutAReceipt() {
    teams();
    f.accrual.accrueBatch("LINEAR_PERPETUAL");
    assertThatThrownBy(() ->
      f.jdbc.update("UPDATE affiliate_deferred_fees SET fee_units=1")
    ).isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() ->
      f.jdbc.update("DELETE FROM affiliate_deferred_fees")
    ).isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() ->
      f.jdbc.update(
        "UPDATE affiliate_deferred_fees SET resolved_at=clock_timestamp()"
      )
    ).isInstanceOf(org.springframework.dao.DataAccessException.class);
    f.jdbc.update(
      "UPDATE core_trade_fee_facts SET fee_units=10001 WHERE cluster_position=1"
    );
    f.jdbc.update(
      "UPDATE affiliate_deferred_fees SET next_attempt_at='-infinity'"
    );
    assertThatThrownBy(() -> f.accrual.retryDeferred("LINEAR_PERPETUAL"))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("身份冲突");
    assertThat(
      f.jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_fee_receipts",
        Long.class
      )
    ).isEqualTo(1);
    assertThat(f.ledger.sourcePosition("LINEAR_PERPETUAL")).isEqualTo(
      new SourceCursor(0, 0)
    );
  }

  @Test
  void repairedFeesUseTheirOriginalHistoricalRate() {
    teams();
    f.accrual.accrueBatch("LINEAR_PERPETUAL");
    f.service.configure(
      new SettingsInput(500000, 500000, 24, "新费率不追改隔离费用"),
      10
    );
    f.context.getBean(AffiliateProperties.class).setMaxPathNodes(8);
    f.jdbc.update(
      "UPDATE affiliate_deferred_fees SET next_attempt_at='-infinity'"
    );
    f.accrual.retryDeferred("LINEAR_PERPETUAL");
    assertThat(
      f.jdbc.queryForObject(
        "SELECT pool_units FROM affiliate_fee_receipts WHERE cluster_position=1",
        Long.class
      )
    ).isEqualTo(2000);
    fact(
      new Fee(3, "LINEAR_PERPETUAL", 201, 44, "USDT", 10000, f.repository.now())
    );
    f.accrual.accrueBatch("LINEAR_PERPETUAL");
    assertThat(
      f.jdbc.queryForObject(
        "SELECT pool_units FROM affiliate_fee_receipts WHERE cluster_position=3",
        Long.class
      )
    ).isEqualTo(5000);
  }

  @Test
  void corruptedCyclesAreQuarantinedWithoutWalkingOtherTeams() {
    teams();
    f.context.getBean(AffiliateProperties.class).setMaxPathNodes(4096);
    f.jdbc.update(
      "INSERT INTO affiliate_team_terms(child_id,parent_id,share_ppm,actor_id,effective_from) VALUES(1,8,1000000,10,clock_timestamp()-interval '1 day')"
    );
    f.accrual.accrueBatch("LINEAR_PERPETUAL");
    assertThat(
      f.jdbc.queryForObject(
        "SELECT error FROM affiliate_deferred_fees",
        String.class
      )
    ).contains("循环");
    assertThat(
      f.repository.summary(200, "").getFirst().get("retainedUnits")
    ).isEqualTo("2000");
    assertThat(
      f.jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_commissions WHERE beneficiary_id<>200",
        Long.class
      )
    ).isZero();
  }

  @Test
  void lateMatureFeesCatchUpWithoutPullingTheNextCycleForward() {
    f.service.accept(2, f.repository.profile(1).inviteCode());
    var old = f.matureFee(1, 2, 10000);
    f.accrual.accrue(old);
    f.service.budget("LINEAR_PERPETUAL", 9, 10, "自动周期预算");
    f.service.budget("SPOT", 9, 10, "自动周期预算");
    f.jdbc.update(
      "UPDATE affiliate_profiles SET next_settlement_at=clock_timestamp()-interval '1 second' WHERE user_id=1"
    );
    f.service.configure(1, new ProfileInput(false, true, null, "自动周期"), 10);
    var worker =
      new com.surprising.affiliate.service.AffiliateSettlementService(
        f.repository,
        f.ledger,
        org.mockito.Mockito.mock(
          com.surprising.gateway.provider.service.ProductTransferCoordinator.class
        ),
        f.manager,
        f.context.getBean(AffiliateProperties.class)
      );
    worker.prepare(false, 0);
    var next = f.jdbc.queryForObject(
      "SELECT next_settlement_at FROM affiliate_profiles WHERE user_id=1",
      Timestamp.class
    );
    var cutoff = f.jdbc.queryForObject("SELECT settlement_cutoff_at FROM affiliate_profiles WHERE user_id=1",Timestamp.class).toInstant();
    // Both rows are mature and share the same payee/product/asset, but only the old row belongs to the closed cycle.
    f.accrual.accrue(new Fee(3,"SPOT",2,44,"USDT",10000,cutoff.plusNanos(1000)));
    f.accrual.accrue(
      new Fee(2, "SPOT", 2, 43, "USDT", 10000, old.occurredAt())
    );
    worker.prepare(false, 0);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_payouts",
        Long.class
      )
    ).isEqualTo(2);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT next_settlement_at FROM affiliate_profiles WHERE user_id=1",
        Timestamp.class
      )
    ).isEqualTo(next);
    assertThat(f.jdbc.queryForObject("SELECT amount_units FROM affiliate_payouts WHERE product_line='SPOT'",Long.class)).isEqualTo(2000);
    assertThat(f.jdbc.queryForObject("SELECT count(*) FROM affiliate_commissions c JOIN affiliate_fee_receipts f USING(receipt_id) WHERE f.cluster_position=3 AND c.payout_id IS NOT NULL",Long.class)).isZero();
    worker.prepare(false, 0);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_payouts",
        Long.class
      )
    ).isEqualTo(2);
    f.jdbc.update(
      "UPDATE affiliate_profiles SET next_settlement_at=clock_timestamp()-interval '1 second' WHERE user_id=1"
    );
    f.service.configure(
      1,
      new ProfileInput(false, true, null, "推进下一个已到期周期"),
      10
    );
    worker.prepare(false, 0);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_payouts",
        Long.class
      )
    ).isEqualTo(3);
  }

  @Test
  void independentTeamsKeepTheirOwnRootRatesAndBranchShares() {
    var expected=new java.util.HashMap<Long,Long>();
    for(int team=0;team<12;team++) {
      long root=200+team*10;
      for(long user=root;user<=root+2;user++) {f.jdbc.update("INSERT INTO gateway_users(user_id) VALUES(?)",user);f.service.register(user,null);}
      int rate=new int[]{200000,300000,500000,0}[team%4];
      int share=team%2==0?333333:750000;
      f.service.configure(root,new ProfileInput(true,true,rate,"独立团队费率"),10);
      f.partner(root,root+1,share);f.service.accept(root+2,f.repository.profile(root+1).inviteCode());
      long pool=10000L*rate/1000000,child=pool*share/1000000;
      expected.put(root,2*(pool-child));expected.put(root+1,2*child);
      for(String product:new String[]{"SPOT","LINEAR_PERPETUAL"})fact(new Fee(team+1,product,root+2,team+100,"USDT",10000,f.repository.now()));
    }
    for(String product:new String[]{"SPOT","LINEAR_PERPETUAL"})for(int batch=0;batch<20;batch++)if(!f.accrual.accrueBatch(product).more())break;
    assertThat(f.jdbc.queryForObject("SELECT count(*) FROM affiliate_fee_receipts",Long.class)).isEqualTo(24);
    for(var row:expected.entrySet())assertThat(f.jdbc.queryForObject("SELECT sum(retained_units) FROM affiliate_commissions WHERE beneficiary_id=?",Long.class,row.getKey())).isEqualTo(row.getValue());
    assertThat(f.jdbc.queryForObject("SELECT count(*) FROM affiliate_deferred_fees",Long.class)).isZero();
  }
}
