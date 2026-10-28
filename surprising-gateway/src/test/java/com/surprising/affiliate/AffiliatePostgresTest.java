package com.surprising.affiliate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.affiliate.model.AffiliateModels.*;
import com.surprising.affiliate.repository.*;
import com.surprising.affiliate.service.*;
import com.surprising.gateway.provider.model.*;
import com.surprising.gateway.provider.model.ProductAccountAdjustment;
import com.surprising.gateway.provider.service.ProductTransferCoordinator;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(
  named = "INSTRUMENT_TEST_JDBC_URL",
  matches = ".+"
)
class AffiliatePostgresTest {

  @Configuration(proxyBeanMethods = false)
  @EnableTransactionManagement
  static class Transactions {}

  JdbcTemplate admin, jdbc;
  String schema;
  AnnotationConfigApplicationContext context;
  AffiliateService service;
  AffiliateAccrualService accrual;
  AffiliateLedgerRepository ledger;
  AffiliateRepository repository;
  DataSourceTransactionManager manager;

  @BeforeEach
  void setup() throws Exception {
    String url = System.getenv("INSTRUMENT_TEST_JDBC_URL"),
      user = System.getenv("INSTRUMENT_TEST_DB_USER"),
      password = System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
    admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    schema = "affiliate_" + UUID.randomUUID().toString().replace("-", "");
    admin.execute("CREATE SCHEMA " + schema);
    var source = new DriverManagerDataSource(
      url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
      user,
      password
    );
    jdbc = new JdbcTemplate(source);
    manager = new DataSourceTransactionManager(source);
    jdbc.execute(
      "CREATE TABLE gateway_users(user_id BIGINT PRIMARY KEY,status TEXT NOT NULL DEFAULT 'NORMAL')"
    );
    jdbc.execute(
      "CREATE TABLE gateway_roles(role_id BIGINT PRIMARY KEY,role_code TEXT)"
    );
    jdbc.execute(
      "CREATE TABLE gateway_permissions(permission_id BIGSERIAL PRIMARY KEY,permission_code TEXT UNIQUE,permission_name TEXT,description TEXT)"
    );
    jdbc.execute(
      "CREATE TABLE gateway_role_permissions(role_id BIGINT,permission_id BIGINT,PRIMARY KEY(role_id,permission_id))"
    );
    jdbc.execute(
      Files.readString(
        Path.of("../deployment/migrations/20261028-affiliate.sql")
      )
    );
    for (long id = 1; id <= 10; id++) jdbc.update(
      "INSERT INTO gateway_users(user_id) VALUES(?)",
      id
    );
    context = new AnnotationConfigApplicationContext();
    context.registerBean(JdbcTemplate.class, () -> jdbc);
    context.registerBean(DataSourceTransactionManager.class, () -> manager);
    context.register(
      Transactions.class,
      AffiliateRepository.class,
      AffiliateLedgerRepository.class,
      AffiliateService.class,
      AffiliateAccrualService.class
    );
    context.refresh();
    service = context.getBean(AffiliateService.class);
    accrual = context.getBean(AffiliateAccrualService.class);
    ledger = context.getBean(AffiliateLedgerRepository.class);
    repository = context.getBean(AffiliateRepository.class);
    for (long id = 1; id <= 10; id++) service.register(id, null);
  }

  @AfterEach
  void cleanup() {
    if (context != null) context.close();
    if (admin != null && schema != null) admin.execute(
      "DROP SCHEMA " + schema + " CASCADE"
    );
  }

  void kol(long user) {
    service.configure(user, new ProfileInput(true, true, null, "测试开通"), 10);
  }

  void partner(long parent, long child, int share) {
    var invite = service.invite(
      parent,
      new InviteInput(Long.toString(child), share)
    );
    service.accept(child, invite.code());
  }

  Fee fee(long source, long user, long amount) {
    return new Fee(
      source,
      "LINEAR_PERPETUAL",
      user,
      42,
      "USDT",
      amount,
      repository.now()
    );
  }

  @Test
  void ordinaryReferralWorksWithoutKolAndDuplicateFactsCannotPayTwice() {
    service.accept(2, repository.profile(1).inviteCode());
    var fee = fee(1, 2, 10000);
    accrual.accrue(fee);
    accrual.accrue(fee);
    assertThat(ledger.entries(1, "", 0, 10))
      .singleElement()
      .satisfies(row -> assertThat(row.get("retainedUnits")).isEqualTo("2000"));
    assertThatThrownBy(() ->
      accrual.accrue(
        new Fee(1, "LINEAR_PERPETUAL", 2, 42, "USDT", 10001, fee.occurredAt())
      )
    ).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() ->
      service.accept(1, repository.profile(2).inviteCode())
    ).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
      service.accept(2, repository.profile(3).inviteCode())
    ).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptedPartnershipAllocatesAttributedRevenueAndExcludesMemberOwnTrades() {
    kol(1);
    kol(2);
    partner(1, 2, 600000);
    partner(2, 3, 500000);
    service.accept(4, repository.profile(3).inviteCode());
    accrual.accrue(fee(1, 4, 10000));
    assertThat(
      ledger.entries(1, "", 0, 10).getFirst().get("retainedUnits")
    ).isEqualTo("800");
    assertThat(
      ledger.entries(2, "", 0, 10).getFirst().get("retainedUnits")
    ).isEqualTo("600");
    assertThat(
      ledger.entries(3, "", 0, 10).getFirst().get("retainedUnits")
    ).isEqualTo("600");
    accrual.accrue(fee(2, 3, 50000));
    assertThat(
      jdbc.queryForObject(
        "SELECT disposition FROM affiliate_fee_receipts WHERE cluster_position=2",
        String.class
      )
    ).isEqualTo("TEAM_MEMBER_TRADE");
    assertThat(
      jdbc.queryForObject(
        "SELECT sum(retained_units) FROM affiliate_commissions",
        Long.class
      )
    ).isEqualTo(2000);
    assertThat(service.members(1, 2, 0).items()).hasSize(1);
    assertThatThrownBy(() -> service.members(5, 2, 0)).isInstanceOf(
      IllegalArgumentException.class
    );
    assertThatThrownBy(() -> service.share(1, 3, 900000)).isInstanceOf(
      IllegalArgumentException.class
    );
    service.share(2, 3, 250000);
    accrual.accrue(fee(3, 4, 10000));
    assertThat(ledger.entries(3, "", 0, 10))
      .extracting(row -> row.get("retainedUnits"))
      .containsExactly("600", "300");
    assertThat(
      repository.summary(1, "").getFirst().get("teamFeeUnits")
    ).isEqualTo("20000");
  }

  @Test
  void pendingInvitationRequiresAcceptanceAndCannotBeTakenByAnotherUser() {
    kol(1);
    var invitation = service.invite(1, new InviteInput("2", 500000));
    assertThat(repository.parent(2, repository.now())).isEmpty();
    assertThatThrownBy(() -> service.accept(3, invitation.code())).isInstanceOf(
      IllegalArgumentException.class
    );
    service.accept(2, invitation.code());
    service.accept(2, invitation.code());
    assertThat(
      repository.parent(2, repository.now()).orElseThrow().sharePpm()
    ).isEqualTo(500000);
    assertThatThrownBy(() ->
      service.invite(2, new InviteInput("1", 500000))
    ).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fractionalFeesAccumulateAndConfigurationChangesDoNotRewriteHistory() {
    service.accept(2, repository.profile(1).inviteCode());
    for (int i = 1; i <= 5; i++) accrual.accrue(fee(i, 2, 1));
    assertThat(
      jdbc.queryForObject(
        "SELECT sum(retained_units) FROM affiliate_commissions",
        Long.class
      )
    ).isEqualTo(1);
    service.configure(
      new SettingsInput(400000, 200000, 24, "修改未来费率"),
      10
    );
    accrual.accrue(fee(6, 2, 10));
    assertThat(
      jdbc.queryForObject(
        "SELECT sum(retained_units) FROM affiliate_commissions",
        Long.class
      )
    ).isEqualTo(5);
  }

  @Test
  void acceptedManagersCanRecruitAndPlatformSuspensionBlocksTheWholeBranch() {
    kol(1);
    partner(1, 2, 600000);
    assertThat(repository.profile(2).kolEnabled()).isTrue();
    partner(2, 3, 500000);
    assertThat(repository.profile(3).kolEnabled()).isTrue();
    service.accept(4, repository.profile(3).inviteCode());
    accrual.accrue(fee(1, 4, 10000));
    var contribution = (java.util.List<?>) service
      .members(1, 1, 0)
      .items()
      .getFirst()
      .get("contributions");
    assertThat(contribution).hasSize(1);
    assertThat(((Map<?, ?>) contribution.getFirst()).get("feeUnits")).isEqualTo(
      "10000"
    );
    service.configure(
      2,
      new ProfileInput(false, true, null, "平台停用中间管理"),
      10
    );
    assertThatThrownBy(() ->
      service.invite(3, new InviteInput("5", 500000))
    ).isInstanceOf(IllegalArgumentException.class);
    accrual.accrue(fee(2, 4, 10000));
    assertThat(
      jdbc.queryForObject(
        "SELECT disposition FROM affiliate_fee_receipts WHERE cluster_position=2",
        String.class
      )
    ).isEqualTo("KOL_DISABLED");
    assertThat(service.members(1, 2, 0).items()).hasSize(1);
    assertThat(service.branchOverview(1, 2, "").get("invitations")).isEqualTo(
      List.of()
    );
    assertThat(
      repository.summary(1, "").getFirst().get("teamFeeUnits")
    ).isEqualTo("10000");
    service.configure(5, new ProfileInput(true, true, null, "开通后停用"), 10);
    service.configure(5, new ProfileInput(false, true, null, "停用"), 10);
    var token = service.invite(1, new InviteInput("5", 500000));
    assertThatThrownBy(() -> service.accept(5, token.code())).isInstanceOf(
      IllegalArgumentException.class
    );
    assertThat(repository.inviter(5, repository.now())).isEmpty();
  }

  @Test
  void ordinaryInviteShowsOnlyItsContributionAndCannotExposeIndependentDownstreamIncome() {
    service.accept(2, repository.profile(1).inviteCode());
    service.accept(3, repository.profile(2).inviteCode());
    accrual.accrue(fee(1, 2, 10000));
    accrual.accrue(fee(2, 3, 20000));
    var contributions = (List<?>) service
      .members(1, 1, 0)
      .items()
      .getFirst()
      .get("contributions");
    assertThat(contributions).hasSize(1);
    var row = (Map<?, ?>) contributions.getFirst();
    assertThat(row.get("feeUnits")).isEqualTo("10000");
    assertThat(row.get("receivedUnits")).isEqualTo("2000");
    assertThatThrownBy(() -> service.branchOverview(1, 2, "")).isInstanceOf(
      IllegalArgumentException.class
    );
  }

  @Test
  void cursorUsesCommittedLogPositionAndOrderIdentityRatherThanAnAutoIncrementId()
    throws Exception {
    jdbc.execute(
      Files.readString(
        Path.of("../deployment/migrations/20261028-committed-trade-fees.sql")
      )
    );
    service.accept(2, repository.profile(1).inviteCode());
    var now = repository.now();
    // One committed command settles both sides. Insert order deliberately differs from log/order sorting.
    for (long order : new long[] { 43, 42 })
      jdbc.update(
        "INSERT INTO core_trade_fee_facts(product_line,order_id,user_id,instrument_id,asset,fee_units,cumulative_fee_units,cluster_position,occurred_at) VALUES('LINEAR_PERPETUAL',?,2,'TEST','USDT',10000,10000,64,?)",
        order,
        java.sql.Timestamp.from(now)
      );
    var source = new AffiliateFeeSourceRepository(jdbc);
    var facts = source.read("LINEAR_PERPETUAL", new SourceCursor(0, 0));
    assertThat(facts).extracting(Fee::orderId).containsExactly(42L, 43L);
    facts.forEach(accrual::accrue);
    facts.forEach(accrual::accrue);
    assertThat(
      source.read("LINEAR_PERPETUAL", ledger.sourcePosition("LINEAR_PERPETUAL"))
    ).isEmpty();
    assertThat(
      repository.summary(1, "").getFirst().get("retainedUnits")
    ).isEqualTo("4000");
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_fee_receipts",
        Long.class
      )
    ).isEqualTo(2);
  }

  @Test
  void aShorterConfiguredCycleAppliesToExistingAndNewProfiles() {
    service.configure(new SettingsInput(200000, 200000, 1, "每小时结算"), 10);
    jdbc.update("INSERT INTO gateway_users(user_id) VALUES(99)");
    service.register(99, null);
    assertThat(
      jdbc.queryForObject(
        "SELECT bool_and(next_settlement_at <= clock_timestamp()+interval '1 hour') FROM affiliate_profiles",
        Boolean.class
      )
    ).isTrue();
    service.accept(2, repository.profile(1).inviteCode());
    accrual.accrue(fee(1, 2, 10000));
    jdbc.update(
      "UPDATE affiliate_fee_receipts SET occurred_at=occurred_at-interval '2 hours'"
    );
    service.budget("LINEAR_PERPETUAL", 9, 10, "自动结算预算");
    var settlement = new AffiliateSettlementService(
      repository,
      ledger,
      mock(ProductTransferCoordinator.class),
      manager
    );
    service.configure(1, new ProfileInput(false, false, null, "停止自动"), 10);
    jdbc.update(
      "UPDATE affiliate_profiles SET next_settlement_at=clock_timestamp()-interval '1 minute'"
    );
    settlement.prepare(false, 0);
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_payouts",
        Integer.class
      )
    ).isZero();
    service.configure(1, new ProfileInput(false, true, null, "恢复自动"), 10);
    settlement.prepare(false, 0);
    settlement.prepare(false, 0);
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_payouts",
        Integer.class
      )
    ).isEqualTo(1);
  }

  @Test
  void concurrentBindingsAndRepeatedAccrualKeepOneOwnerAndOneCommissionPool()
    throws Exception {
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var attempts = new ArrayList<java.util.concurrent.Future<Boolean>>();
      for (long parent : new long[] { 1, 2 }) {
        String code = repository.profile(parent).inviteCode();
        attempts.add(
          executor.submit(() -> {
            start.await();
            try {
              service.accept(4, code);
              return true;
            } catch (IllegalArgumentException expected) {
              return false;
            }
          })
        );
      }
      start.countDown();
      int accepted = 0;
      for (var result : attempts) if (result.get()) accepted++;
      assertThat(accepted).isEqualTo(1);
      var event = fee(64, 4, 10000);
      var a = executor.submit(() -> accrual.accrue(event));
      var b = executor.submit(() -> accrual.accrue(event));
      a.get();
      b.get();
      assertThat(
        jdbc.queryForObject(
          "SELECT count(*) FROM affiliate_fee_receipts",
          Integer.class
        )
      ).isEqualTo(1);
      assertThat(
        jdbc.queryForObject(
          "SELECT sum(retained_units) FROM affiliate_commissions",
          Long.class
        )
      ).isEqualTo(2000);
    }
  }

  @Test
  void commissionsAndPayoutsKeepEachProductAndAssetSeparate() {
    service.accept(2, repository.profile(1).inviteCode());
    var now = repository.now();
    accrual.accrue(new Fee(64, "SPOT", 2, 42, "USDT", 10000, now));
    accrual.accrue(new Fee(64, "LINEAR_PERPETUAL", 2, 42, "USDT", 10000, now));
    accrual.accrue(new Fee(64, "INVERSE_PERPETUAL", 2, 42, "BTC", 15000, now));
    assertThat(repository.summary(1, "")).hasSize(3);
    assertThat(
      repository.summary(1, "INVERSE_PERPETUAL").getFirst().get("retainedUnits")
    ).isEqualTo("3000");
    assertThat(
      repository.summary(1, "SPOT").getFirst().get("retainedUnits")
    ).isEqualTo("2000");
    jdbc.update(
      "UPDATE affiliate_fee_receipts SET occurred_at=occurred_at-interval '2 days'"
    );
    for (String line : List.of("SPOT", "LINEAR_PERPETUAL", "INVERSE_PERPETUAL"))
      service.budget(line, 9, 10, "分产品预算");
    var settlement = new AffiliateSettlementService(
      repository,
      ledger,
      mock(ProductTransferCoordinator.class),
      manager
    );
    settlement.prepare(true, 10);
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_payouts",
        Integer.class
      )
    ).isEqualTo(3);
    assertThat(
      jdbc.queryForObject(
        "SELECT amount_units FROM affiliate_payouts WHERE asset='BTC'",
        Long.class
      )
    ).isEqualTo(3000);
  }

  @Test
  void transactionRollbackDoesNotLeaveARegisteredAffiliateOrBinding() {
    jdbc.update("INSERT INTO gateway_users(user_id) VALUES(99)");
    new TransactionTemplate(manager).executeWithoutResult(tx -> {
      service.register(99, repository.profile(1).inviteCode());
      tx.setRollbackOnly();
    });
    assertThatThrownBy(() -> repository.profile(99)).isInstanceOf(
      IllegalArgumentException.class
    );
    assertThat(repository.inviter(99, repository.now())).isEmpty();
  }

  @Test
  void settlementReservesOnceAndUnknownFundsReuseTheSameIntentUntilPaid() {
    service.accept(2, repository.profile(1).inviteCode());
    accrual.accrue(fee(1, 2, 10000));
    jdbc.update(
      "UPDATE affiliate_fee_receipts SET occurred_at=occurred_at-interval '2 days'"
    );
    new TransactionTemplate(manager).executeWithoutResult(tx ->
      repository.budget("LINEAR_PERPETUAL", 9, 10, "测试预算")
    );
    var transfers = mock(ProductTransferCoordinator.class);
    when(transfers.executeCommittedTransfer(any())).thenAnswer(invocation -> {
      var command = (ProductTransferCommand) invocation.getArgument(0);
      return ProductTransferResult.from(
        123,
        command,
        ProductTransferStatus.PENDING,
        null,
        "unknown",
        Instant.now()
      );
    });
    var settlement = new AffiliateSettlementService(
      repository,
      ledger,
      transfers,
      manager
    );
    settlement.prepare(true, 10);
    settlement.prepare(true, 10);
    settlement.send();
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_payouts",
        Integer.class
      )
    ).isEqualTo(1);
    assertThat(
      jdbc.queryForObject("SELECT status FROM affiliate_payouts", String.class)
    ).isEqualTo("UNKNOWN");
    doAnswer(invocation -> {
      var command = (ProductTransferCommand) invocation.getArgument(0);
      return ProductTransferResult.from(
        123,
        command,
        ProductTransferStatus.COMPLETED,
        null,
        null,
        Instant.now()
      );
    })
      .when(transfers)
      .executeCommittedTransfer(any());
    settlement.send();
    settlement.send();
    var capture = org.mockito.ArgumentCaptor.forClass(
      ProductTransferCommand.class
    );
    verify(transfers, times(2)).executeCommittedTransfer(capture.capture());
    assertThat(capture.getAllValues().getFirst()).isEqualTo(
      capture.getAllValues().getLast()
    );
    assertThat(repository.summary(1, "").getFirst().get("paidUnits")).isEqualTo(
      "2000"
    );
  }

  @Test
  void repeatedRejectedPayoutRetryCreatesOnlyOneReplacementAndCannotLoseItsTerminalState() {
    service.accept(2, repository.profile(1).inviteCode());
    accrual.accrue(fee(1, 2, 10000));
    jdbc.update(
      "UPDATE affiliate_fee_receipts SET occurred_at=occurred_at-interval '2 days'"
    );
    service.budget("LINEAR_PERPETUAL", 9, 10, "测试预算");
    var settlement = new AffiliateSettlementService(
      repository,
      ledger,
      mock(ProductTransferCoordinator.class),
      manager
    );
    settlement.prepare(true, 10);
    UUID id = jdbc.queryForObject(
      "SELECT payout_id FROM affiliate_payouts",
      UUID.class
    );
    ledger.payoutResult(id, "REJECTED", "123", "预算不足，明确拒绝");
    settlement.retry(id, 10);
    settlement.retry(id, 10);
    ledger.payoutResult(id, "UNKNOWN", null, "迟到的响应不得覆盖终态");
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_payouts",
        Integer.class
      )
    ).isEqualTo(2);
    assertThat(
      jdbc.queryForObject(
        "SELECT status FROM affiliate_payouts WHERE payout_id=?",
        String.class,
        id
      )
    ).isEqualTo("REJECTED");
    UUID replacement = jdbc.queryForObject(
      "SELECT replacement_id FROM affiliate_payouts WHERE payout_id=?",
      UUID.class,
      id
    );
    assertThat(
      jdbc.queryForObject(
        "SELECT payout_id FROM affiliate_commissions",
        UUID.class
      )
    ).isEqualTo(replacement);
  }

  @Test
  void realCoreDebitsOnceAndCompletesAfterSnapshotRecoveryOfAnUnknownPayout() {
    service.accept(2, repository.profile(1).inviteCode());
    accrual.accrue(fee(1, 2, 10000));
    jdbc.update(
      "UPDATE affiliate_fee_receipts SET occurred_at=occurred_at-interval '2 days'"
    );
    service.budget("LINEAR_PERPETUAL", 9, 10, "真实 Core 资金验收");
    try (var accounts = new ReplayAccounts()) {
      accounts.adjust(9, 10000);
      accounts.adjust(1, 0);
      var settlement = new AffiliateSettlementService(
        repository,
        ledger,
        new ProductTransferCoordinator(accounts, null),
        manager
      );
      settlement.prepare(true, 10);
      settlement.send();
      assertThat(accounts.balances.get(9L)).isEqualTo(8000);
      assertThat(
        jdbc.queryForObject(
          "SELECT status FROM affiliate_payouts",
          String.class
        )
      ).isEqualTo("UNKNOWN");
      accounts.restart();
      settlement.send();
      settlement.send();
      assertThat(accounts.balances.get(9L)).isEqualTo(8000);
      assertThat(accounts.balances.get(1L)).isEqualTo(2000);
      assertThat(
        repository.summary(1, "").getFirst().get("paidUnits")
      ).isEqualTo("2000");
    }
  }

  /** Transport adapter to the real deterministic Core. Only the first debit response is lost. */
  static final class ReplayAccounts
    implements
      com.surprising.gateway.provider.service.ProductAccountClient,
      AutoCloseable
  {

    com.surprising.aeron.service.replay.CommittedTradeReplay replay =
      new com.surprising.aeron.service.replay.CommittedTradeReplay(
        com.surprising.product.api.ProductLine.LINEAR_PERPETUAL,
        null
      );
    final Map<Long, Long> balances = new HashMap<>();
    long sequence;
    boolean loseDebitResponse = true;

    void adjust(long user, long amount) {
      apply(
        com.surprising.aeron.protocol.CoreMessageType.ADJUST_BALANCE,
        user,
        UUID.randomUUID(),
        com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(
          new com.surprising.aeron.protocol.BalanceAdjustmentCommand(
            "USDT",
            amount
          )
        )
      );
    }

    void restart() {
      byte[] snapshot = replay.snapshot();
      replay.close();
      replay = new com.surprising.aeron.service.replay.CommittedTradeReplay(
        com.surprising.product.api.ProductLine.LINEAR_PERPETUAL,
        snapshot
      );
    }

    ProductAccountAdjustment apply(
      com.surprising.aeron.protocol.CoreMessageType type,
      long user,
      UUID id,
      byte[] payload
    ) {
      long n = ++sequence,
        now = 1793180000000L + n;
      var header = com.surprising.aeron.protocol.CoreMessageHeader.command(
        type,
        id,
        com.surprising.product.api.ProductLine.LINEAR_PERPETUAL,
        com.surprising.aeron.protocol.CommandSource.GATEWAY,
        71428,
        n,
        user,
        now,
        0
      );
      replay.apply(
        com.surprising.aeron.protocol.CoreMessageCodec.encode(
          new com.surprising.aeron.protocol.CoreMessage(header, payload)
        ),
        now,
        n * 64
      );
      if (replay.balanceFlow() != null) balances.put(
        replay.balanceFlow().userId(),
        Long.parseLong(replay.balanceFlow().balanceAfterUnits())
      );
      var receipt = replay.commandResult();
      return receipt.status() ==
        com.surprising.aeron.protocol.ResponseStatus.APPLIED
        ? ProductAccountAdjustment.applied("committed")
        : ProductAccountAdjustment.rejected(receipt.code().name());
    }

    ProductAccountAdjustment transfer(
      com.surprising.aeron.protocol.CoreMessageType type,
      com.surprising.account.api.model.ProductTransferOperationRequest value,
      long user,
      String phase
    ) {
      var command = new com.surprising.aeron.protocol.TransferFundsCommand(
        value.transferId(),
        value.sourceProductLine(),
        value.targetProductLine(),
        value.sourceAccountType().name(),
        value.targetAccountType().name(),
        value.asset(),
        value.amountUnits(),
        value.referenceId(),
        value.reason(),
        value.userId(),
        value.recipientUserId()
      );
      return apply(
        type,
        user,
        UUID.nameUUIDFromBytes(
          (phase + ":" + value.transferId()).getBytes(
            java.nio.charset.StandardCharsets.UTF_8
          )
        ),
        com.surprising.aeron.protocol.TradingCommandCodec.encodeTransferFunds(
          command
        )
      );
    }

    @Override
    public ProductAccountAdjustment transferOut(
      String account,
      com.surprising.account.api.model.ProductTransferOperationRequest value
    ) {
      var result = transfer(
        com.surprising.aeron.protocol.CoreMessageType.TRANSFER_OUT,
        value,
        value.userId(),
        "out"
      );
      if (
        loseDebitResponse &&
        result.status() == ProductAccountAdjustment.Status.APPLIED
      ) {
        loseDebitResponse = false;
        return ProductAccountAdjustment.unknown("lost response");
      }
      return result;
    }

    @Override
    public ProductAccountAdjustment transferIn(
      String account,
      com.surprising.account.api.model.ProductTransferOperationRequest value
    ) {
      return transfer(
        com.surprising.aeron.protocol.CoreMessageType.TRANSFER_IN,
        value,
        value.recipientUserId(),
        "in"
      );
    }

    @Override
    public ProductAccountAdjustment completeTransfer(
      String account,
      com.surprising.account.api.model.ProductTransferOperationRequest value
    ) {
      return apply(
        com.surprising.aeron.protocol.CoreMessageType.COMPLETE_TRANSFER,
        value.userId(),
        UUID.nameUUIDFromBytes(
          ("complete:" + value.transferId()).getBytes(
            java.nio.charset.StandardCharsets.UTF_8
          )
        ),
        com.surprising.aeron.protocol.TradingCommandCodec.encodeCompleteTransfer(
          new com.surprising.aeron.protocol.CompleteTransferCommand(
            value.transferId()
          )
        )
      );
    }

    @Override
    public List<com.surprising.account.api.model.ProductTransferOperationRequest> pendingTransfers(
      com.surprising.product.api.ProductLine product,
      int limit
    ) {
      return List.of();
    }

    @Override
    public void close() {
      replay.close();
    }
  }
}
