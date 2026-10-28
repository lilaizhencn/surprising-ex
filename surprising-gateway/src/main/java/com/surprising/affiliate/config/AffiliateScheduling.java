package com.surprising.affiliate.config;

import com.surprising.affiliate.model.AffiliateModels.SourceCursor;
import com.surprising.affiliate.repository.*;
import com.surprising.affiliate.service.*;
import com.surprising.product.api.ProductLine;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** One bounded worker per Gateway; database identities arbitrate concurrent instances. */
@Configuration(proxyBeanMethods = false)
public class AffiliateScheduling {

  private final AffiliateFeeSourceRepository source;
  private final AffiliateLedgerRepository ledger;
  private final AffiliateAccrualService accrual;
  private final AffiliateSettlementService settlement;

  public AffiliateScheduling(
    AffiliateFeeSourceRepository source,
    AffiliateLedgerRepository ledger,
    AffiliateAccrualService accrual,
    AffiliateSettlementService settlement
  ) {
    this.source = source;
    this.ledger = ledger;
    this.accrual = accrual;
    this.settlement = settlement;
  }

  @Bean("affiliateScheduler")
  ThreadPoolTaskScheduler scheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("gateway-affiliate-");
    scheduler.setAwaitTerminationSeconds(5);
    return scheduler;
  }

  @Scheduled(
    scheduler = "affiliateScheduler",
    initialDelay = 15000,
    fixedDelay = 10000
  )
  public void run() {
    for (var product : ProductLine.values()) {
      var position = ledger.sourcePosition(product.name());
      try {
        for (var fee : source.read(product.name(), position)) {
          accrual.accrue(fee);
          position = new SourceCursor(fee.clusterPosition(), fee.orderId());
        }
        ledger.sourcePosition(product.name(), position, null);
      } catch (RuntimeException failure) {
        ledger.sourcePosition(
          product.name(),
          position,
          "费用来源或核算不可用，保留原进度等待恢复"
        );
        org.slf4j.LoggerFactory.getLogger(getClass()).warn(
          "Affiliate accrual deferred product={}",
          product,
          failure
        );
      }
    }
    try {
      settlement.prepare(false, 0);
      settlement.send();
    } catch (RuntimeException failure) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn(
        "Affiliate settlement deferred",
        failure
      );
    }
  }
}
