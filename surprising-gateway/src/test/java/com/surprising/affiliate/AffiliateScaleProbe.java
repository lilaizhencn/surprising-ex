package com.surprising.affiliate;

import com.surprising.affiliate.model.AffiliateModels.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Real PostgreSQL side-path probe; no Core throughput or production-capacity inference. */
public final class AffiliateScaleProbe extends AffiliatePostgresTest {

  final AtomicLong statements = new AtomicLong();

  @Override
  DataSource instrument(DataSource original) {
    return new DelegatingDataSource(original) {
      @Override
      public Connection getConnection() throws java.sql.SQLException {
        return counted(super.getConnection());
      }

      Connection counted(Connection original) {
        return (Connection) Proxy.newProxyInstance(
          Connection.class.getClassLoader(),
          new Class<?>[] { Connection.class },
          (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (
              method.getName()
            ) {
              case "equals" -> proxy == args[0];
              case "hashCode" -> System.identityHashCode(proxy);
              default -> original.toString();
            };
            if (
              method.getName().startsWith("prepare") ||
              method.getName().equals("createStatement")
            ) statements.incrementAndGet();
            try {
              return method.invoke(original, args);
            } catch (InvocationTargetException failure) {
              throw failure.getCause();
            }
          }
        );
      }
    };
  }

  void seed(int depth, int width) {
    int customer = depth + width + 100;
    jdbc.update(
      "INSERT INTO gateway_users(user_id) SELECT generate_series(11,?)",
      customer
    );
    jdbc.update(
      "INSERT INTO affiliate_profiles(user_id,invite_code) SELECT user_id,lpad(to_hex(user_id),32,'0') FROM gateway_users WHERE user_id>10"
    );
    jdbc.update(
      "UPDATE affiliate_profiles SET kol_enabled=TRUE WHERE user_id<=?",
      depth + width
    );
    jdbc.update(
      "INSERT INTO affiliate_profile_history(user_id,kol_enabled,auto_settlement,actor_id,reason) SELECT user_id,TRUE,TRUE,1,'scale fixture' FROM affiliate_profiles WHERE user_id<=?",
      depth + width
    );
    jdbc.update(
      "INSERT INTO affiliate_referrals(user_id,inviter_id) SELECT n,n-1 FROM generate_series(2,?) n",
      depth
    );
    jdbc.update(
      "INSERT INTO affiliate_team_terms(child_id,parent_id,share_ppm,actor_id) SELECT n,n-1,1000000,1 FROM generate_series(2,?) n",
      depth
    );
    jdbc.update(
      "INSERT INTO affiliate_referrals(user_id,inviter_id) SELECT n,1 FROM generate_series(?,?) n",
      depth + 1,
      depth + width
    );
    jdbc.update(
      "INSERT INTO affiliate_team_terms(child_id,parent_id,share_ppm,actor_id) SELECT n,1,1000000,1 FROM generate_series(?,?) n",
      depth + 1,
      depth + width
    );
    jdbc.update(
      "INSERT INTO affiliate_referrals(user_id,inviter_id) VALUES(?,?)",
      customer,
      depth
    );
    jdbc.execute("ANALYZE");
  }

  void measure(int depth, int width, int count) {
    seed(depth, width);
    var occurred = repository.now();
    int customer = depth + width + 100;
    for (int i = 1; i <= 3; i++) accrual.accrue(
      new Fee(i, "LINEAR_PERPETUAL", customer, i, "USDT", 10000, occurred)
    );
    var bean =
      (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    long allocated = bean.getThreadAllocatedBytes(
        Thread.currentThread().threadId()
      ),
      cpu = bean.getCurrentThreadCpuTime(),
      before = statements.get();
    long[] latencies = new long[count];
    for (int i = 0; i < count; i++) {
      long start = System.nanoTime();
      accrual.accrue(
        new Fee(
          4L + i,
          "LINEAR_PERPETUAL",
          customer,
          4L + i,
          "USDT",
          10000,
          occurred
        )
      );
      latencies[i] = System.nanoTime() - start;
    }
    long bytes =
      bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) -
      allocated;
    long cpuNanos = bean.getCurrentThreadCpuTime() - cpu,
      sql = statements.get() - before;
    Arrays.sort(latencies);
    var totals = jdbc.queryForMap(
      "SELECT (SELECT sum(pool_units) FROM affiliate_fee_receipts) AS pool,(SELECT sum(retained_units) FROM affiliate_commissions) AS retained,(SELECT count(*) FROM affiliate_fee_receipts) AS receipts"
    );
    if (
      !totals
        .get("pool")
        .toString()
        .equals(totals.get("retained").toString()) ||
      ((Number) totals.get("receipts")).longValue() != count + 3
    ) throw new AssertionError("money/receipt mismatch " + totals);
    long start = System.nanoTime();
    service.overview(1, "");
    long overview = System.nanoTime() - start;
    System.out.printf(
      Locale.ROOT,
      "{\"depth\":%d,\"width\":%d,\"events\":%d,\"p50_ms\":%.3f,\"p90_ms\":%.3f,\"p99_ms\":%.3f,\"max_ms\":%.3f,\"jdbc_statements_per_fee\":%.1f,\"allocated_bytes_per_fee\":%d,\"thread_cpu_ms_per_fee\":%.3f,\"overview_ms\":%.3f,\"money_diff\":0}%n",
      depth,
      width,
      count,
      latencies[count / 2] / 1e6,
      latencies[Math.min(count - 1, (int) (count * .9))] / 1e6,
      latencies[count - 1] / 1e6,
      latencies[count - 1] / 1e6,
      sql / (double) count,
      bytes / count,
      cpuNanos / 1e6 / count,
      overview / 1e6
    );
  }

  public static void main(String[] args) throws Exception {
    for (int depth : new int[] { 8, 128, 1024 }) {
      var probe = new AffiliateScaleProbe();
      try {
        probe.setup();
        probe.measure(depth, 10000, 20);
      } finally {
        probe.cleanup();
      }
    }
  }
}
