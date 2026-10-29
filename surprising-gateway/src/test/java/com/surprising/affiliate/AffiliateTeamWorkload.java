package com.surprising.affiliate;

import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.affiliate.config.*;
import com.surprising.affiliate.repository.*;
import com.surprising.affiliate.service.*;
import com.surprising.gateway.provider.model.ProductAccountAdjustment;
import com.surprising.gateway.provider.service.*;
import com.surprising.product.api.ProductLine;
import com.zaxxer.hikari.*;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.nio.file.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Shared PostgreSQL, four independent business containers, six real deterministic Core replicas.
 * Controlled aggressive drain: no Gateway network stack or production scheduler throughput claim. */
public final class AffiliateTeamWorkload {

  static final long BASE = 10000,
    STRIDE = 1024,
    BUDGET = 9;

  record Balance(ProductLine product, long user, String asset) {}

  static final class Fixture extends AffiliatePostgresTest {

    HikariDataSource pool;
    DataSource original;

    @Override
    DataSource instrument(DataSource source) {
      original = source;
      return (pool = pool(source, "affiliate-observer", 2));
    }

    @Override
    void cleanup() {
      if (context != null) context.close();
      if (pool != null) pool.close();
      super.cleanup();
    }
  }

  static HikariDataSource pool(DataSource source, String name, int maximum) {
    var config = new HikariConfig();
    config.setDataSource(source);
    config.setPoolName(name);
    config.setMaximumPoolSize(maximum);
    config.setMinimumIdle(Math.min(2, maximum));
    config.setConnectionTimeout(3000);
    config.setValidationTimeout(1000);
    return new HikariDataSource(config);
  }

  static final class Node implements AutoCloseable {

    final HikariDataSource pool;
    final AnnotationConfigApplicationContext context;
    final AffiliateAccrualService accrual;
    final AffiliateSettlementService settlement;
    final AffiliateLedgerRepository ledger;

    Node(DataSource source, Funds funds, int index) {
      pool = pool(source, "affiliate-node-" + index, 6);
      context = new AnnotationConfigApplicationContext();
      context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(pool));
      context.registerBean(DataSourceTransactionManager.class, () ->
        new DataSourceTransactionManager(pool)
      );
      context.registerBean(ProductTransferCoordinator.class, () ->
        new ProductTransferCoordinator(funds, null)
      );
      context.register(
        AffiliatePostgresTest.Transactions.class,
        AffiliateProperties.class,
        AffiliateDatabase.class,
        AffiliateRepository.class,
        AffiliateLedgerRepository.class,
        AffiliateFeeSourceRepository.class,
        AffiliateService.class,
        AffiliateAccrualService.class,
        AffiliateSettlementService.class
      );
      context.refresh();
      accrual = context.getBean(AffiliateAccrualService.class);
      settlement = context.getBean(AffiliateSettlementService.class);
      ledger = context.getBean(AffiliateLedgerRepository.class);
    }

    public void close() {
      context.close();
      pool.close();
    }
  }

  static final class Core extends AffiliatePostgresTest.ReplayAccounts {

    long debits, credits;

    Core(ProductLine product) {
      super(product);
      loseDebitResponse = false;
    }

    @Override
    synchronized ProductAccountAdjustment apply(
      CoreMessageType type,
      long user,
      UUID id,
      byte[] payload
    ) {
      var result = super.apply(type, user, id, payload);
      if (replay.balanceFlow() != null) {
        if (type == CoreMessageType.TRANSFER_OUT) debits++;
        if (type == CoreMessageType.TRANSFER_IN) credits++;
      }
      return result;
    }

    synchronized Map<
      Long,
      com.surprising.aeron.service.replay.CommittedPortfolioSnapshot.UserEquity
    > portfolio() {
      return replay
        .portfolioSnapshot(1793180000000L + sequence, 1_000_000)
        .users();
    }
  }

  /** Core identities have a strict owner thread; synchronize alone must never move that ownership. */
  static final class ProductCore implements AutoCloseable {

    final ProductLine product;
    final ExecutorService owner;
    final Core core;

    ProductCore(ProductLine product) {
      this.product = product;
      owner = Executors.newSingleThreadExecutor(r ->
        new Thread(r, "affiliate-test-core-" + product)
      );
      try {
        core = call(() -> new Core(product));
      } catch (RuntimeException error) {
        owner.shutdownNow();
        throw error;
      }
    }

    <T> T call(Callable<T> task) {
      try {
        return owner.submit(task).get(15, TimeUnit.SECONDS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(error);
      } catch (ExecutionException error) {
        throw new IllegalStateException(error.getCause());
      } catch (TimeoutException error) {
        throw new IllegalStateException("Core owner did not complete", error);
      }
    }

    void adjust(long user, String asset, long units) {
      call(() -> {
        core.adjust(user, asset, units);
        return null;
      });
    }

    ProductAccountAdjustment transferOut(
      String account,
      ProductTransferOperationRequest value
    ) {
      return call(() -> core.transferOut(account, value));
    }

    ProductAccountAdjustment transferIn(
      String account,
      ProductTransferOperationRequest value
    ) {
      return call(() -> core.transferIn(account, value));
    }

    ProductAccountAdjustment completeTransfer(
      String account,
      ProductTransferOperationRequest value
    ) {
      return call(() -> core.completeTransfer(account, value));
    }

    Map<
      Long,
      com.surprising.aeron.service.replay.CommittedPortfolioSnapshot.UserEquity
    > portfolio() {
      return call(core::portfolio);
    }

    int restart() {
      return call(() -> {
        var before = core.portfolio();
        core.restart();
        if (!before.equals(core.portfolio())) throw new AssertionError(
          "Core snapshot changed portfolio"
        );
        return (int) before
          .values()
          .stream()
          .filter(v -> !v.complete())
          .count();
      });
    }

    long debits() {
      return call(() -> core.debits);
    }

    long credits() {
      return call(() -> core.credits);
    }

    public void close() {
      try {
        call(() -> {
          core.close();
          return null;
        });
      } finally {
        owner.shutdownNow();
      }
    }
  }

  static final class Funds implements ProductAccountClient, AutoCloseable {

    final EnumMap<ProductLine, ProductCore> cores = new EnumMap<>(
      ProductLine.class
    );
    final Set<ProductLine> firstDebit = ConcurrentHashMap.newKeySet();
    final Set<Long> heldOut = ConcurrentHashMap.newKeySet(),
      heldIn = ConcurrentHashMap.newKeySet();
    final LongAdder requests = new LongAdder();
    volatile boolean hold = true;

    Funds() {
      try {
        for (var p : ProductLine.values()) cores.put(p, new ProductCore(p));
      } catch (RuntimeException | Error error) {
        close();
        throw error;
      }
    }

    public ProductAccountAdjustment transferOut(
      String account,
      ProductTransferOperationRequest value
    ) {
      requests.increment();
      var p = value.sourceProductLine();
      var result = cores.get(p).transferOut(account, value);
      if (
        result.status() == ProductAccountAdjustment.Status.APPLIED &&
        hold &&
        (firstDebit.add(p) ||
          value.recipientUserId() % 97 == 0 ||
          heldOut.contains(value.transferId()))
      ) {
        heldOut.add(value.transferId());
        return ProductAccountAdjustment.unknown(
          "test: committed debit response unavailable"
        );
      }
      return result;
    }

    public ProductAccountAdjustment transferIn(
      String account,
      ProductTransferOperationRequest value
    ) {
      requests.increment();
      var result = cores
        .get(value.targetProductLine())
        .transferIn(account, value);
      if (
        result.status() == ProductAccountAdjustment.Status.APPLIED &&
        hold &&
        value.recipientUserId() % 131 == 0
      ) {
        heldIn.add(value.transferId());
        return ProductAccountAdjustment.unknown(
          "test: committed credit response unavailable"
        );
      }
      return result;
    }

    public ProductAccountAdjustment completeTransfer(
      String account,
      ProductTransferOperationRequest value
    ) {
      requests.increment();
      return cores
        .get(value.sourceProductLine())
        .completeTransfer(account, value);
    }

    public List<ProductTransferOperationRequest> pendingTransfers(
      ProductLine p,
      int limit
    ) {
      return List.of();
    }

    int restart() {
      int incomplete = 0;
      for (var core : cores.values()) incomplete += core.restart();
      hold = false;
      return incomplete;
    }

    public void close() {
      cores.values().forEach(ProductCore::close);
    }
  }

  static int depth(int team) {
    return team == 0
      ? 256
      : switch (team % 3) {
          case 0 -> 1;
          case 1 -> 3;
          default -> 6;
        };
  }

  static int share(int team, int level) {
    return team == 0
      ? 1000000
      : level == 1
        ? 600000
        : level == 2
          ? 500000
          : 800000;
  }

  static String asset(int team) {
    return team % 2 == 0 ? "USDT" : "BTC";
  }

  static void seed(Fixture f, int teams, int clients, ProductLine selected) {
    String depth =
      "CASE WHEN t=0 THEN 256 WHEN t%3=0 THEN 1 WHEN t%3=1 THEN 3 ELSE 6 END";
    f.jdbc.update(
      "INSERT INTO gateway_users(user_id) SELECT ?+t*?+s FROM generate_series(0,?-1)t CROSS JOIN LATERAL generate_series(0,(" +
        depth +
        ")-1)s UNION ALL SELECT ?+t*?+s FROM generate_series(0,?-1)t CROSS JOIN generate_series(512,?+511)s",
      BASE,
      STRIDE,
      teams,
      BASE,
      STRIDE,
      teams,
      clients
    );
    f.jdbc.update(
      "INSERT INTO affiliate_profiles(user_id,invite_code,kol_enabled,created_at,next_settlement_at) SELECT user_id,lpad(to_hex(user_id),32,'0'),(user_id-?)%?<512,clock_timestamp()-interval '3 days',clock_timestamp()-interval '1 day' FROM gateway_users WHERE user_id>=?",
      BASE,
      STRIDE,
      BASE
    );
    f.jdbc.update(
      "INSERT INTO affiliate_profile_history(user_id,kol_enabled,auto_settlement,effective_at,actor_id,reason) SELECT user_id,kol_enabled,TRUE,created_at,10,'multi-team fixture' FROM affiliate_profiles WHERE user_id>=?",
      BASE
    );
    f.jdbc.update(
      "INSERT INTO affiliate_referrals(user_id,inviter_id,accepted_at) SELECT ?+t*?+s,?+t*?+s-1,clock_timestamp()-interval '3 days' FROM generate_series(0,?-1)t CROSS JOIN LATERAL generate_series(1,(" +
        depth +
        ")-1)s UNION ALL SELECT ?+t*?+s,?+t*?+(" +
        depth +
        ")-1,clock_timestamp()-interval '3 days' FROM generate_series(0,?-1)t CROSS JOIN generate_series(512,?+511)s",
      BASE,
      STRIDE,
      BASE,
      STRIDE,
      teams,
      BASE,
      STRIDE,
      BASE,
      STRIDE,
      teams,
      clients
    );
    f.jdbc.update(
      "INSERT INTO affiliate_team_terms(child_id,parent_id,share_ppm,effective_from,actor_id) SELECT ?+t*?+s,?+t*?+s-1,CASE WHEN t=0 THEN 1000000 WHEN s=1 THEN 600000 WHEN s=2 THEN 500000 ELSE 800000 END,clock_timestamp()-interval '3 days',10 FROM generate_series(0,?-1)t CROSS JOIN LATERAL generate_series(1,(" +
        depth +
        ")-1)s",
      BASE,
      STRIDE,
      BASE,
      STRIDE,
      teams
    );
    f.jdbc.update(
      "UPDATE affiliate_settings SET effective_at=effective_at-interval '3 days'"
    );
    for (var p : ProductLine.values())
      f.service.budget(p.name(), BUDGET, 10, "multi-team funded budget");
    var occurred = Timestamp.from(f.repository.now().minusSeconds(172800));
    for (var p : ProductLine.values())
      if(selected==null || selected==p) f.jdbc.update(
        "INSERT INTO core_trade_fee_facts(product_line,order_id,user_id,instrument_id,asset,fee_units,cumulative_fee_units,cluster_position,occurred_at) SELECT ?,t+1,?+t*?+512,'TEAM-FIXTURE',CASE WHEN t%2=0 THEN 'USDT' ELSE 'BTC' END,CASE WHEN part=0 THEN 5003 ELSE 4997 END,CASE WHEN part=0 THEN 5003 ELSE 10000 END,(part*?+t+1)*64,?::timestamptz+part*interval '1 second' FROM generate_series(0,?-1)t CROSS JOIN generate_series(0,1)part",
        p.name(),
        BASE,
        STRIDE,
        teams,
        occurred,
        teams
      );
    f.jdbc.execute("ANALYZE");
  }

  static Map<Balance, Long> expected(int teams, ProductLine selected) {
    var values = new HashMap<Balance, Long>();
    for (var product : ProductLine.values())
      if(selected==null || selected==product) for (int team = 0; team < teams; team++) {
        long received = 2000;
        for (int level = 0; level < depth(team); level++) {
          long delegated =
            level == depth(team) - 1
              ? 0
              : (received * share(team, level + 1)) / 1000000;
          if (received - delegated > 0) values.put(
            new Balance(product, BASE + team * STRIDE + level, asset(team)),
            received - delegated
          );
          received = delegated;
        }
      }
    return values;
  }

  static final class Work {

    final Fixture f;
    final Funds funds;
    final ProductLine selected;
    List<Node> nodes = new ArrayList<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final AtomicBoolean stop = new AtomicBoolean();
    final AtomicIntegerArray complete = new AtomicIntegerArray(
      ProductLine.values().length
    );
    final Queue<Long> accrualLatencies = new ArrayBlockingQueue<>(200000),
      moneyLatencies = new ArrayBlockingQueue<>(200000);
    final LongAdder sampleLoss = new LongAdder();
    final LongAdder cpu = new LongAdder(),
      allocated = new LongAdder(),
      busy = new LongAdder();
    int peakActive, peakTotal, peakWaiting, peakDatabaseLockWaiting;
    long deadline;

    Work(Fixture f, Funds funds, ProductLine selected) {
      this.f = f;
      this.funds = funds;this.selected=selected;
      openNodes();
    }

    void openNodes() {
      for (int i = 0; i < 4; i++) nodes.add(new Node(f.original, funds, i));
    }

    void closeNodes() {
      nodes.forEach(Node::close);
      nodes.clear();
    }

    boolean ingested() {
      for (int i = 0; i < complete.length(); i++) if (
        complete.get(i) == 0
      ) return false;
      return true;
    }

    void ingest(Node n, int slot, int teams) {
      int next = slot;
      var bean =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
      long c = bean.getCurrentThreadCpuTime(),
        a = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
      try {
        while (!stop.get() && System.nanoTime() < deadline && !ingested()) {
          int index = next++ % ProductLine.values().length;
          if (complete.get(index) != 0) continue;
          var p = ProductLine.values()[index];
          long start = System.nanoTime();
          var r = n.accrual.accrueBatch(p.name());
          if (r.busy()) {
            busy.increment();
            LockSupport.parkNanos(1_000_000);
            continue;
          }
          n.accrual.retryDeferred(p.name());
          if (
            r.processed() > 0 &&
            !accrualLatencies.offer(System.nanoTime() - start)
          ) sampleLoss.increment();
          if (
            n.ledger.sourcePosition(p.name()).clusterPosition() ==
            (selected==null || selected==p ? teams * 2L * 64 : 0)
          ) complete.set(index, 1);
        }
      } catch (Throwable error) {
        failure.compareAndSet(null, error);
        stop.set(true);
      } finally {
        cpu.add(bean.getCurrentThreadCpuTime() - c);
        allocated.add(
          bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - a
        );
      }
    }

    void money(Node n) {
      try {
        while (!stop.get() && System.nanoTime() < deadline) {
          long start = System.nanoTime();
          n.settlement.prepare(false, 0);
          n.settlement.send();
          if (
            !moneyLatencies.offer(System.nanoTime() - start)
          ) sampleLoss.increment();
          LockSupport.parkNanos(1_000_000);
        }
      } catch (Throwable error) {
        failure.compareAndSet(null, error);
        stop.set(true);
      }
    }

    Map<String, Object> status() {
      return f.jdbc.queryForMap(
        "SELECT (SELECT count(*) FROM affiliate_fee_receipts) AS fees,(SELECT count(*) FROM affiliate_settlement_groups) AS groups,(SELECT count(*) FROM affiliate_payouts WHERE status='PENDING') AS pending,(SELECT count(*) FROM affiliate_payouts WHERE status='UNKNOWN') AS unknown,(SELECT count(*) FROM affiliate_payouts WHERE status='REJECTED') AS rejected,(SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock') AS locks"
      );
    }

    void sample(Map<String, Object> status, long started) {
      int active = 0,
        total = 0,
        waiting = 0;
      for (var n : nodes) {
        var b = n.pool.getHikariPoolMXBean();
        active += b.getActiveConnections();
        total += b.getTotalConnections();
        waiting += b.getThreadsAwaitingConnection();
      }
      peakActive = Math.max(peakActive, active);
      peakTotal = Math.max(peakTotal, total);
      peakWaiting = Math.max(peakWaiting, waiting);
      peakDatabaseLockWaiting = Math.max(
        peakDatabaseLockWaiting,
        ((Number) status.get("locks")).intValue()
      );
      System.out.println(
        "SAMPLE seconds=" +
          (System.nanoTime() - started) / 1e9 +
          " status=" +
          status +
          " active=" +
          active +
          " total=" +
          total +
          " waiting=" +
          waiting
      );
    }

    Map<String, Object> run(int teams, int seconds, boolean ingest)
      throws Exception {
      stop.set(false);
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
      long started = System.nanoTime();
      var futures = new ArrayList<Future<?>>();
      Map<String, Object> status = Map.of();
      try (var executor = Executors.newFixedThreadPool(8)) {
        for (int i = 0; i < nodes.size(); i++) {
          var n = nodes.get(i);
          int slot = i;
          if (ingest) futures.add(
            executor.submit(() -> ingest(n, slot, teams))
          );
          futures.add(executor.submit(() -> money(n)));
        }
        try {
          while (System.nanoTime() < deadline && failure.get() == null) {
            status = status();
            sample(status, started);
            if (
              (!ingest || ingested()) &&
              ((Number) status.get("groups")).longValue() == 0 &&
              ((Number) status.get("pending")).longValue() == 0 &&
              (funds.hold || ((Number) status.get("unknown")).longValue() == 0)
            ) break;
            Thread.sleep(500);
          }
        } finally {
          stop.set(true);
        }
        stop.set(true);
        for (var future : futures) future.get(30, TimeUnit.SECONDS);
      }
      if (failure.get() != null) throw new AssertionError(
        "worker failure",
        failure.get()
      );
      status = status();
      if (
        (ingest && !ingested()) ||
        ((Number) status.get("groups")).longValue() != 0 ||
        ((Number) status.get("pending")).longValue() != 0 ||
        (!funds.hold && ((Number) status.get("unknown")).longValue() != 0)
      ) throw new AssertionError("drain deadline/backlog " + status);
      return Map.of(
        "seconds",
        (System.nanoTime() - started) / 1e9,
        "status",
        status
      );
    }
  }

  static Map<String, Object> percentiles(Queue<Long> source) {
    var a = source.stream().mapToLong(Long::longValue).sorted().toArray();
    if (a.length == 0) return Map.of("samples", 0);
    var result = new LinkedHashMap<String, Object>();
    result.put("samples", a.length);
    for (var p : new double[] { .5, .9, .95, .99, .999, 1 })
      result.put(
        p == 1 ? "max_ms" : "p" + p * 100 + "_ms",
        a[Math.min(a.length - 1, (int) Math.ceil(a.length * p) - 1)] / 1e6
      );
    return result;
  }

  static void audit(
    Fixture f,
    Funds funds,
    Map<Balance, Long> expected,
    int teams,ProductLine selected
  ) {
    long facts = f.jdbc.queryForObject(
        "SELECT count(*) FROM core_trade_fee_facts",
        Long.class
      ),
      receipts = f.jdbc.queryForObject(
        "SELECT count(*) FROM affiliate_fee_receipts",
        Long.class
      );
    if (facts != teams * (selected==null ? 12L : 2L) || receipts != facts) throw new AssertionError(
      "fee terminal mismatch " + facts + "/" + receipts
    );
    long bad = f.jdbc.queryForObject(
      "SELECT count(*) FROM affiliate_fee_receipts f JOIN affiliate_commissions c USING(receipt_id) WHERE f.root_id<>?+(f.order_id-1)*? OR c.beneficiary_id<?+(f.order_id-1)*? OR c.beneficiary_id>=?+f.order_id*?",
      Long.class,
      BASE,
      STRIDE,
      BASE,
      STRIDE,
      BASE,
      STRIDE
    );
    if (bad != 0) throw new AssertionError("cross-team allocation " + bad);
    var actual = new HashMap<Balance, Long>();
    f.jdbc.query(
      "SELECT beneficiary_id,product_line,asset,sum(retained_units) AS units FROM affiliate_commissions GROUP BY beneficiary_id,product_line,asset HAVING sum(retained_units)>0",
      r -> {
        actual.put(
          new Balance(
            ProductLine.valueOf(r.getString(2)),
            r.getLong(1),
            r.getString(3)
          ),
          r.getLong(4)
        );
      }
    );
    if (!expected.equals(actual)) throw new AssertionError(
      "per-team/payee allocation mismatch"
    );
    long mismatched = f.jdbc.queryForObject(
      "SELECT count(*) FROM affiliate_fee_receipts f WHERE pool_units<>(SELECT COALESCE(sum(retained_units),0) FROM affiliate_commissions c WHERE c.receipt_id=f.receipt_id)",
      Long.class
    );
    if (mismatched != 0) throw new AssertionError(
      "pool difference " + mismatched
    );
    long unpaid = f.jdbc.queryForObject(
      "SELECT count(*) FROM affiliate_commissions c LEFT JOIN affiliate_payouts p USING(payout_id) WHERE c.retained_units>0 AND (p.status IS DISTINCT FROM 'PAID')",
      Long.class
    );
    long paid = f.jdbc.queryForObject(
      "SELECT count(*) FROM affiliate_payouts WHERE status='PAID'",
      Long.class
    );
    long debits = funds.cores
        .values()
        .stream()
        .mapToLong(ProductCore::debits)
        .sum(),
      credits = funds.cores
        .values()
        .stream()
        .mapToLong(ProductCore::credits)
        .sum();
    if (
      unpaid != 0 || paid != debits || paid != credits
    ) throw new AssertionError(
      "paid/debit/credit mismatch " +
        paid +
        "/" +
        debits +
        "/" +
        credits +
        " unpaid " +
        unpaid
    );
    for (var core : funds.cores.values()) {
      var portfolio = core.portfolio();
      var totals = new HashMap<String, BigInteger>();
      for (var entry : portfolio.entrySet()) {
        if (!entry.getValue().complete()) throw new AssertionError(
          "pending Core transfer " + entry.getKey()
        );
        for (var asset : entry.getValue().netAssetUnits().entrySet()) {
          var value = new BigInteger(asset.getValue());
          totals.merge(asset.getKey(), value, BigInteger::add);
          if (
            entry.getKey() != BUDGET &&
            !value.equals(
              BigInteger.valueOf(
                expected.getOrDefault(
                  new Balance(core.product, entry.getKey(), asset.getKey()),
                  0L
                )
              )
            )
          ) throw new AssertionError("actual Core balance mismatch");
        }
      }
      for (var asset : new String[] { "USDT", "BTC" })
        if (
          !totals.get(asset).equals(BigInteger.valueOf(teams * 2000L + 10000))
        ) throw new AssertionError(
          "Core funds difference " + core.product + "/" + asset + " " + totals
        );
    }
  }

  public static void main(String[] args) throws Exception {
    int teams = args.length > 0 ? Integer.parseInt(args[0]) : 1000,
      clients = args.length > 1 ? Integer.parseInt(args[1]) : 50,
      seconds = args.length > 3 ? Integer.parseInt(args[3]) : 600;
    if (
      teams < 2 ||
      teams > 10000 ||
      clients < 1 ||
      clients > 100 ||
      seconds < 1 ||
      seconds > 1200
    ) throw new IllegalArgumentException("bounded fixture dimensions");
    ProductLine selected=args.length>4?ProductLine.valueOf(args[4]):null;
    var f = new Fixture();
    Work work = null;
    try {
      f.setup();
      seed(f, teams, clients,selected);
      var expected = expected(teams,selected);
      try (var funds = new Funds()) {
        for (var core : funds.cores.values())
          for (var asset : new String[] { "USDT", "BTC" })
            core.adjust(BUDGET, asset, teams * 2000L + 10000);
        for (var value : expected.keySet())
          funds.cores
            .get(value.product())
            .adjust(value.user(), value.asset(), 0);
        work = new Work(f, funds,selected);
        for (int i = 0; i < 3; i++) work.nodes
          .getFirst()
          .accrual.accrueBatch(ProductLine.values()[i].name());
        long warm = f.jdbc.queryForObject(
          "SELECT count(*) FROM affiliate_fee_receipts",
          Long.class
        );
        var first = work.run(teams, seconds, true);
        if (
          (
            (Number) ((Map<?, ?>) first.get("status")).get("unknown")
          ).longValue() == 0
        ) throw new AssertionError("lost-response scenario did not occur");
        work.closeNodes();
        int pendingUsers = funds.restart();
        work.openNodes();
        f.jdbc.update(
          "UPDATE affiliate_payouts SET next_attempt_at='-infinity' WHERE status='UNKNOWN'"
        );
        var recovery = work.run(teams, seconds, false);
        audit(f, funds, expected, teams,selected);
        var result = new LinkedHashMap<String, Object>();
        result.put("teams", teams);
        result.put("clients_per_team", clients);
        result.put(
          "users",
          f.jdbc.queryForObject(
            "SELECT count(*) FROM affiliate_profiles",
            Long.class
          )
        );
        result.put("fee_facts", teams * (selected==null?12:2));
        result.put("active_products_per_team",selected==null?6:1);result.put("selected_product",selected==null?"ALL_SIX":selected.name());
        var coreStats=new LinkedHashMap<String,Object>();for(var c:funds.cores.values())coreStats.put(c.product.name(),c.call(()->Map.of("commands",c.core.sequence,"actual_debits",c.core.debits,"actual_credits",c.core.credits)));
        result.put("actual_core_stats",coreStats);result.put("paid_payouts",f.jdbc.queryForObject("SELECT count(*) FROM affiliate_payouts WHERE status='PAID'",Long.class));
        result.put("warmup_receipts", warm);
        result.put("containers", 4);
        result.put("first_drain", first);
        result.put("recovery", recovery);
        result.put("snapshot_pending_users", pendingUsers);
        result.put("source_batches", percentiles(work.accrualLatencies));
        result.put("money_calls", percentiles(work.moneyLatencies));
        result.put("accounting_cpu_ms", work.cpu.sum() / 1e6);
        result.put("accounting_allocated_bytes", work.allocated.sum());
        result.put("busy_product_claims", work.busy.sum());
        result.put("peak_active_connections", work.peakActive);
        result.put("peak_total_connections", work.peakTotal);
        result.put("peak_pool_waiters", work.peakWaiting);
        result.put("peak_database_lock_waiters", work.peakDatabaseLockWaiting);
        result.put("money_difference", 0);
        result.put("cross_team_allocations", 0);
        result.put("duplicate_actual_debits", 0);
        result.put("duplicate_actual_credits", 0);
        result.put("funds_requests_including_retries", funds.requests.sum());
        result.put("latency_samples_lost", work.sampleLoss.sum());
        String json =
          new tools.jackson.databind.ObjectMapper().writeValueAsString(result);
        if (args.length > 2) Files.writeString(Path.of(args[2]), json + "\n");
        System.out.println(json);
      }
    } finally {
      if (work != null) work.closeNodes();
      f.cleanup();
    }
  }
}
