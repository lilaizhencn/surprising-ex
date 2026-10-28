package com.surprising.affiliate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.affiliate.config.AffiliateProperties;
import com.surprising.affiliate.model.AffiliateModels.*;
import com.surprising.affiliate.service.*;
import com.surprising.gateway.provider.service.ProductTransferCoordinator;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(
  named = "INSTRUMENT_TEST_JDBC_URL",
  matches = ".+"
)
@Timeout(30)
class AffiliateSafetyPostgresTest {

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

  AffiliateProperties properties() {
    return f.context.getBean(AffiliateProperties.class);
  }

  long count(String table) {
    return f.jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }

  AffiliateSettlementService settlement(ProductTransferCoordinator transfers) {
    return new AffiliateSettlementService(
      f.repository,
      f.ledger,
      transfers,
      f.manager,
      properties()
    );
  }

  void ordinaryMatureFee() {
    f.service.accept(2, f.repository.profile(1).inviteCode());
    f.accrual.accrue(f.matureFee(1, 2, 10000));
    f.service.budget("LINEAR_PERPETUAL", 9, 10, "安全验收预算");
  }

  void fact(Fee fee) {
    f.jdbc.update(
      "INSERT INTO core_trade_fee_facts(product_line,order_id,user_id,instrument_id,asset,fee_units,cumulative_fee_units,cluster_position,occurred_at) VALUES(?,?,?,'TEST',?,?,?,?,?)",
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

  @Test
  void corruptedCycleTerminatesAndCommitsNoMoneyOrCursor() {
    f.kol(1);
    f.partner(1, 2, 600000);
    f.partner(2, 3, 500000);
    f.service.accept(4, f.repository.profile(3).inviteCode());
    f.jdbc.update(
      "INSERT INTO affiliate_team_terms(child_id,parent_id,share_ppm,actor_id) VALUES(1,3,500000,10)"
    );
    var fee = f.fee(1, 4, 10000);
    assertThatThrownBy(() -> f.accrual.accrue(fee))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("循环");
    assertThat(count("affiliate_fee_receipts")).isZero();
    assertThat(count("affiliate_accrual_buckets")).isZero();
    assertThat(f.ledger.sourcePosition("LINEAR_PERPETUAL")).isEqualTo(
      new SourceCursor(0, 0)
    );
  }

  @Test
  void overlappingHistoricalParentsCannotSilentlyChooseOneForMoney() {
    f.kol(1);
    f.partner(1, 2, 600000);
    f.service.accept(4, f.repository.profile(2).inviteCode());
    f.jdbc.update(
      "INSERT INTO affiliate_team_terms(child_id,parent_id,share_ppm,actor_id,effective_until) VALUES(2,3,300000,10,clock_timestamp()+interval '1 day')"
    );
    assertThatThrownBy(() -> f.accrual.accrue(f.fee(1, 4, 10000))).isInstanceOf(
      IllegalStateException.class
    );
    assertThat(count("affiliate_fee_receipts")).isZero();
    assertThat(count("affiliate_commissions")).isZero();
  }

  @Test
  void exceedingOperationalPathBoundPausesWholeFeeWithoutTruncation() {
    f.seed(8, 0);
    properties().setMaxPathNodes(4);
    var fee = f.fee(1, 108, 10000);
    fact(fee);
    assertThatThrownBy(() ->
      f.accrual.accrueBatch("LINEAR_PERPETUAL")
    ).isInstanceOf(IllegalStateException.class);
    assertThat(count("affiliate_fee_receipts")).isZero();
    assertThat(count("affiliate_delegation_buckets")).isZero();
    assertThat(f.ledger.sourcePosition("LINEAR_PERPETUAL")).isEqualTo(
      new SourceCursor(0, 0)
    );
    properties().setMaxPathNodes(8);
    f.accrual.accrueBatch("LINEAR_PERPETUAL");
    assertThat(count("affiliate_commissions")).isEqualTo(8);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT sum(retained_units) FROM affiliate_commissions",
        Long.class
      )
    ).isEqualTo(2000);
  }

  @Test
  void fourThousandDeepPathWithTenThousandMembersUsesNoRecursiveJavaStack() {
    f.seed(4096, 10000);
    f.accrual.accrue(f.fee(1, 14196, 10000));
    assertThat(count("affiliate_commissions")).isEqualTo(4096);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT retained_units FROM affiliate_commissions WHERE beneficiary_id=4096",
        Long.class
      )
    ).isEqualTo(2000);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT sum(retained_units) FROM affiliate_commissions",
        Long.class
      )
    ).isEqualTo(2000);
  }

  @Test
  void fullPathRowBudgetYieldsBeforeNextFeeAndResumesAtExactCursor() {
    f.seed(8, 0);
    properties().setMaxAllocationRows(10);
    var now = f.repository.now();
    for (int i = 1; i <= 2; i++) fact(
      new Fee(i, "LINEAR_PERPETUAL", 108, i, "USDT", 10000, now)
    );
    var first = f.accrual.accrueBatch("LINEAR_PERPETUAL");
    assertThat(first.processed()).isEqualTo(1);
    assertThat(first.more()).isTrue();
    assertThat(count("affiliate_commissions")).isEqualTo(8);
    f.accrual.accrueBatch("LINEAR_PERPETUAL");
    assertThat(count("affiliate_commissions")).isEqualTo(16);
    assertThat(f.ledger.sourcePosition("LINEAR_PERPETUAL")).isEqualTo(
      new SourceCursor(2, 2)
    );
  }

  @Test
  void batchConflictRollsBackEarlierFeesAndAllRoundingState() {
    f.kol(1);
    f.partner(1, 2, 600000);
    f.service.accept(4, f.repository.profile(2).inviteCode());
    var now = f.repository.now();
    f.accrual.accrue(new Fee(2, "LINEAR_PERPETUAL", 4, 2, "USDT", 10, now));
    f.jdbc.update(
      "UPDATE affiliate_source_progress SET cluster_position=0,order_id=0"
    );
    fact(new Fee(1, "LINEAR_PERPETUAL", 4, 1, "USDT", 10000, now));
    fact(new Fee(2, "LINEAR_PERPETUAL", 4, 2, "USDT", 11, now));
    assertThatThrownBy(() ->
      f.accrual.accrueBatch("LINEAR_PERPETUAL")
    ).isInstanceOf(IllegalStateException.class);
    assertThat(count("affiliate_fee_receipts")).isEqualTo(1);
    assertThat(count("affiliate_accrual_buckets")).isEqualTo(1);
    assertThat(count("affiliate_delegation_buckets")).isEqualTo(1);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT incoming_units FROM affiliate_delegation_buckets",
        BigInteger.class
      )
    ).isEqualTo(BigInteger.TWO);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT sum(retained_units) FROM affiliate_commissions",
        Long.class
      )
    ).isEqualTo(2);
    assertThat(f.ledger.sourcePosition("LINEAR_PERPETUAL")).isEqualTo(
      new SourceCursor(0, 0)
    );
  }

  @Test
  void sameLogPositionPaginationNeverSkipsOrDuplicatesAnOrder() {
    f.service.accept(2, f.repository.profile(1).inviteCode());
    var now = f.repository.now();
    for (int order = 100; order >= 1; order--) fact(
      new Fee(64, "LINEAR_PERPETUAL", 2, order, "USDT", 10000, now)
    );
    int consumed = 0;
    for (int batch = 0; batch < 100; batch++) {
      var result = f.accrual.accrueBatch("LINEAR_PERPETUAL");
      assertThat(result.processed()).isLessThanOrEqualTo(64);
      consumed += result.processed();
      if (!result.more()) break;
    }
    assertThat(consumed).isEqualTo(100);
    assertThat(count("affiliate_fee_receipts")).isEqualTo(100);
    assertThat(f.ledger.sourcePosition("LINEAR_PERPETUAL")).isEqualTo(
      new SourceCursor(64, 100)
    );
    assertThat(
      f.jdbc.queryForObject(
        "SELECT sum(retained_units) FROM affiliate_commissions",
        Long.class
      )
    ).isEqualTo(200000);
  }

  @Test
  void lockedProductYieldsWhileAnotherProductContinues() throws Exception {
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var holder = executor.submit(() ->
        new TransactionTemplate(f.manager).executeWithoutResult(tx -> {
          f.repository.productLock("SPOT");
          f.repository.readLock();
          held.countDown();
          try {
            if (
              !release.await(10, TimeUnit.SECONDS)
            ) throw new IllegalStateException("test release timeout");
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
          }
        })
      );
      try {
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
        fact(f.fee(1, 2, 10000));
        assertThat(f.accrual.accrueBatch("SPOT").busy()).isTrue();
        assertThat(
          f.accrual.accrueBatch("LINEAR_PERPETUAL").processed()
        ).isEqualTo(1);
      } finally {
        release.countDown();
      }
      holder.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void expiredClaimCannotOverwriteNewClaimOrTerminalMoneyResult() {
    ordinaryMatureFee();
    settlement(mock(ProductTransferCoordinator.class)).prepare(true, 10);
    UUID first = UUID.randomUUID(),
      second = UUID.randomUUID();
    var old = f.ledger.claimPayout(first, 120).orElseThrow();
    UUID id = (UUID) old.get("payout_id");
    f.jdbc.update(
      "UPDATE affiliate_payouts SET claimed_until=clock_timestamp()-interval '1 second'"
    );
    assertThat(f.ledger.claimPayout(second, 120)).isPresent();
    f.ledger.payoutResult(id, first, "REJECTED", "old", "late", 10);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT status FROM affiliate_payouts",
        String.class
      )
    ).isEqualTo("PENDING");
    f.ledger.payoutResult(id, second, "PAID", "new", null, 10);
    f.ledger.payoutResult(id, first, "UNKNOWN", null, "late", 10);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT status FROM affiliate_payouts",
        String.class
      )
    ).isEqualTo("PAID");
    assertThat(
      f.jdbc.queryForObject(
        "SELECT transfer_id FROM affiliate_payouts",
        String.class
      )
    ).isEqualTo("new");
  }

  @Test
  void committedAmountsAndAssignmentsCannotBeMutatedAfterClaim() {
    ordinaryMatureFee();
    settlement(mock(ProductTransferCoordinator.class)).prepare(true, 10);
    assertThatThrownBy(() ->
      f.jdbc.update("DELETE FROM affiliate_commissions")
    ).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() ->
      f.jdbc.update("DELETE FROM affiliate_payouts")
    ).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() ->
      f.jdbc.update("DELETE FROM affiliate_fee_receipts")
    ).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() ->
      f.jdbc.update("UPDATE affiliate_payouts SET amount_units=amount_units+1")
    ).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() ->
      f.jdbc.update("UPDATE affiliate_fee_receipts SET fee_units=fee_units+1")
    ).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() ->
      f.jdbc.update(
        "UPDATE affiliate_commissions SET received_units=received_units+1,retained_units=retained_units+1"
      )
    ).isInstanceOf(DataAccessException.class);
    var claimed = f.ledger.claimPayout(UUID.randomUUID(), 120).orElseThrow();
    assertThatThrownBy(() ->
      f.jdbc.update("UPDATE affiliate_commissions SET payout_id=NULL")
    ).isInstanceOf(DataAccessException.class);
    f.accrual.accrue(f.fee(2, 2, 10000));
    assertThatThrownBy(() ->
      f.jdbc.update(
        "UPDATE affiliate_commissions SET payout_id=? WHERE payout_id IS NULL",
        claimed.get("payout_id")
      )
    ).isInstanceOf(DataAccessException.class);
    // Even a direct INSERT cannot attach a new money row to an already claimed intent.
    assertThatThrownBy(() ->
      f.jdbc.update(
        "INSERT INTO affiliate_commissions(receipt_id,beneficiary_id,received_units,delegated_units,retained_units,share_ppm,product_line,asset,occurred_at,payout_id) SELECT receipt_id,3,2000,0,2000,1000000,product_line,asset,occurred_at,? FROM affiliate_fee_receipts LIMIT 1",
        claimed.get("payout_id")
      )
    ).isInstanceOf(DataAccessException.class);
  }

  @Test
  void orphanMoneyIntentNeverCallsTheFundsCoordinator() {
    UUID id = UUID.randomUUID();
    f.jdbc.update(
      "INSERT INTO affiliate_payouts(payout_id,beneficiary_id,budget_user_id,product_line,asset,amount_units) VALUES(?,1,9,'LINEAR_PERPETUAL','USDT',2000)",
      id
    );
    var transfers = mock(ProductTransferCoordinator.class);
    settlement(transfers).send();
    verifyNoInteractions(transfers);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT status FROM affiliate_payouts",
        String.class
      )
    ).isEqualTo("UNKNOWN");
    assertThat(
      f.jdbc.queryForObject(
        "SELECT next_attempt_at>clock_timestamp()+interval '4 minutes' FROM affiliate_payouts",
        Boolean.class
      )
    ).isTrue();
  }

  @Test
  void preparationIsBoundedAndDoesNotRescheduleUnrelatedProfiles() {
    ordinaryMatureFee();
    var now = f.repository.now();
    for (int order = 2; order <= 7; order++) f.accrual.accrue(
      new Fee(
        order,
        "LINEAR_PERPETUAL",
        2,
        order,
        "USDT",
        10000,
        now.minusSeconds(172800)
      )
    );
    f.jdbc.update(
      "INSERT INTO gateway_users(user_id) SELECT generate_series(11,10010)"
    );
    f.jdbc.update(
      "INSERT INTO affiliate_profiles(user_id,invite_code) SELECT user_id,lpad(to_hex(user_id),32,'0') FROM gateway_users WHERE user_id>10"
    );
    var untouched = f.jdbc.queryForObject(
      "SELECT next_settlement_at FROM affiliate_profiles WHERE user_id=10010",
      Timestamp.class
    );
    f.jdbc.update(
      "UPDATE affiliate_profiles SET next_settlement_at=clock_timestamp()-interval '1 second' WHERE user_id=1"
    );
    f.jdbc.update(
      "UPDATE affiliate_settlement_groups SET available_at=clock_timestamp()-interval '1 second'"
    );
    properties().setPayoutRows(2);
    var worker = settlement(mock(ProductTransferCoordinator.class));
    worker.prepare(false, 0);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_commissions WHERE payout_id IS NOT NULL",
        Long.class
      )
    ).isEqualTo(2);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT next_settlement_at FROM affiliate_profiles WHERE user_id=10010",
        Timestamp.class
      )
    ).isEqualTo(untouched);
    for (int batch = 0; batch < 5; batch++) worker.prepare(false, 0);
    assertThat(count("affiliate_payouts")).isEqualTo(4);
    assertThat(count("affiliate_settlement_groups")).isZero();
    assertThat(
      f.jdbc.queryForObject(
        "SELECT sum(amount_units) FROM affiliate_payouts",
        Long.class
      )
    ).isEqualTo(14000);
  }

  @Test
  void unknownMoneyIsBackedOffAndOnlyOneIntentIsClaimedBeforeIo() {
    ordinaryMatureFee();
    var now = f.repository.now().minusSeconds(172800);
    f.accrual.accrue(new Fee(2, "LINEAR_PERPETUAL", 2, 43, "USDT", 10000, now));
    properties().setPayoutRows(1);
    var transfers = mock(ProductTransferCoordinator.class);
    when(transfers.executeCommittedTransfer(any())).thenAnswer(invocation -> {
      assertThat(
        f.jdbc.queryForObject(
          "SELECT count(*) FROM affiliate_payouts WHERE claimed_until>clock_timestamp()",
          Long.class
        )
      ).isEqualTo(1);
      throw new IllegalStateException("simulated lost response");
    });
    var worker = settlement(transfers);
    worker.prepare(true, 10);
    worker.prepare(true, 10);
    properties().setPayoutAttempts(1);
    worker.send();
    worker.send();
    worker.send();
    verify(transfers, times(2)).executeCommittedTransfer(any());
    assertThat(
      f.jdbc.queryForObject(
        "SELECT bool_and(status='UNKNOWN' AND attempt_count=1 AND next_attempt_at>clock_timestamp()) FROM affiliate_payouts",
        Boolean.class
      )
    ).isTrue();
  }

  @Test
  void safetyMigrationCanBeReappliedWithoutChangingMoneyOrRounding()
    throws Exception {
    f.kol(1);
    f.partner(1, 2, 600000);
    f.service.accept(4, f.repository.profile(2).inviteCode());
    f.accrual.accrue(f.fee(1, 4, 10003));
    var money = f.jdbc.queryForList(
      "SELECT beneficiary_id,received_units,delegated_units,retained_units FROM affiliate_commissions ORDER BY commission_id"
    );
    // Simulate upgrading a deployment which already has the original immutable ledger.
    f.jdbc.update("DELETE FROM affiliate_delegation_buckets");
    String migration = java.nio.file.Files.readString(
      java.nio.file.Path.of(
        "../deployment/migrations/20261028-affiliate-safety.sql"
      )
    );
    f.jdbc.execute(migration);
    f.jdbc.execute(migration);
    assertThat(
      f.jdbc.queryForList(
        "SELECT beneficiary_id,received_units,delegated_units,retained_units FROM affiliate_commissions ORDER BY commission_id"
      )
    ).isEqualTo(money);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT incoming_units FROM affiliate_delegation_buckets",
        BigInteger.class
      )
    ).isEqualTo(BigInteger.valueOf(2000));
    f.accrual.accrue(f.fee(2, 4, 2));
    assertThat(
      f.jdbc.queryForObject(
        "SELECT sum(retained_units) FROM affiliate_commissions WHERE beneficiary_id=2",
        Long.class
      )
    ).isEqualTo(1200);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT sum(retained_units) FROM affiliate_commissions",
        Long.class
      )
    ).isEqualTo(2001);
  }

  @Test
  void unavailableBudgetDoesNotBlockOtherProductsAndCanRecover() {
    ordinaryMatureFee();
    var fee = f.matureFee(2, 2, 10000);
    f.accrual.accrue(new Fee(2, "SPOT", 2, 43, "BTC", 10000, fee.occurredAt()));
    var worker = settlement(mock(ProductTransferCoordinator.class));
    worker.prepare(true, 10);
    assertThat(count("affiliate_payouts")).isEqualTo(1);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT blocked FROM affiliate_settlement_groups WHERE product_line='SPOT'",
        Boolean.class
      )
    ).isTrue();
    f.service.budget("SPOT", 9, 10, "补充预算配置");
    worker.prepare(true, 10);
    assertThat(count("affiliate_payouts")).isEqualTo(2);
    assertThat(count("affiliate_settlement_groups")).isZero();
  }

  @Test
  void hugePayablesSplitWithoutInt64Overflow() {
    f.service.configure(
      new SettingsInput(1000000, 1000000, 24, "整数边界测试"),
      10
    );
    f.service.accept(2, f.repository.profile(1).inviteCode());
    var fee = f.matureFee(1, 2, Long.MAX_VALUE);
    f.accrual.accrue(fee);
    f.accrual.accrue(
      new Fee(
        2,
        "LINEAR_PERPETUAL",
        2,
        43,
        "USDT",
        Long.MAX_VALUE,
        fee.occurredAt()
      )
    );
    f.service.budget("LINEAR_PERPETUAL", 9, 10, "边界预算");
    var worker = settlement(mock(ProductTransferCoordinator.class));
    worker.prepare(true, 10);
    worker.prepare(true, 10);
    assertThat(count("affiliate_payouts")).isEqualTo(2);
    assertThat(
      f.jdbc.queryForObject(
        "SELECT sum(amount_units) FROM affiliate_payouts",
        BigInteger.class
      )
    ).isEqualTo(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO));
    assertThat(
      f.jdbc.queryForObject(
        "SELECT bool_and(amount_units>0) FROM affiliate_payouts",
        Boolean.class
      )
    ).isTrue();
  }

  @Test
  void affiliateQueryDeadlineDoesNotChangeOtherGatewayQueries() {
    var affiliate = f.context
      .getBean(com.surprising.affiliate.config.AffiliateDatabase.class)
      .jdbc();
    assertThat(f.jdbc.getQueryTimeout()).isEqualTo(-1);
    assertThat(affiliate.getQueryTimeout()).isEqualTo(2);
    Assertions.assertTimeout(Duration.ofSeconds(4), () -> {
      assertThatThrownBy(() ->
        affiliate.queryForObject("SELECT pg_sleep(5)", String.class)
      ).isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
    });
  }

  @Test
  void realCoreInsufficientBudgetHasNoDebitAndReplacementPaysExactlyOnce() {
    ordinaryMatureFee();
    try (var accounts = new AffiliatePostgresTest.ReplayAccounts()) {
      accounts.loseDebitResponse = false;
      accounts.adjust(9, 1000);
      accounts.adjust(1, 0);
      var worker = settlement(new ProductTransferCoordinator(accounts, null));
      worker.prepare(true, 10);
      worker.send();
      assertThat(accounts.balances.get(9L)).isEqualTo(1000);
      assertThat(accounts.balances.get(1L)).isZero();
      UUID rejected = f.jdbc.queryForObject(
        "SELECT payout_id FROM affiliate_payouts WHERE status='REJECTED'",
        UUID.class
      );
      accounts.adjust(9, 10000);
      worker.retry(rejected, 10);
      worker.retry(rejected, 10);
      worker.send();
      worker.send();
      assertThat(accounts.balances.get(9L)).isEqualTo(9000);
      assertThat(accounts.balances.get(1L)).isEqualTo(2000);
      assertThat(
        accounts.balances.values().stream().mapToLong(Long::longValue).sum()
      ).isEqualTo(11000);
      assertThat(
        f.jdbc.queryForObject(
          "SELECT count(*) FROM affiliate_payouts WHERE status='PAID'",
          Long.class
        )
      ).isEqualTo(1);
      assertThat(
        f.repository.summary(1, "").getFirst().get("paidUnits")
      ).isEqualTo("2000");
    }
  }

  @Test
  void randomFragmentationMatchesOneShotDistributionAtEveryLevel() {
    var random = new Random(20261028);
    for (int scenario = 0; scenario < 100; scenario++) {
      var path = new ArrayList<TeamTerm>();
      for (int level = 1; level <= 30; level++) path.add(
        new TeamTerm(level, level, level + 1, random.nextInt(1_000_001))
      );
      var prior = new HashMap<Long, BigInteger>();
      var retained = new long[31];
      long total = 0;
      for (int piece = 0; piece < 100; piece++) {
        long units = random.nextInt(10000);
        total += units;
        var rows = CommissionAllocation.split(1, units, path, prior);
        for (int level = 0; level < rows.size(); level++) retained[level] +=
          rows.get(level).retainedUnits();
        for (int level = 0; level < path.size(); level++) prior.merge(
          path.get(level).termId(),
          BigInteger.valueOf(rows.get(level).receivedUnits()),
          BigInteger::add
        );
      }
      var once = CommissionAllocation.split(1, total, path);
      for (int level = 0; level < once.size(); level++) assertThat(
        retained[level]
      ).isEqualTo(once.get(level).retainedUnits());
      assertThat(Arrays.stream(retained).sum()).isEqualTo(total);
    }
  }
}
