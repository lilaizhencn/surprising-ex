package com.surprising.affiliate.config;

import com.surprising.affiliate.repository.AffiliateLedgerRepository;
import com.surprising.affiliate.service.*;
import com.surprising.product.api.ProductLine;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Two fixed tasks: bounded accounting and independent money I/O. No task per member or fee. */
@Configuration(proxyBeanMethods = false)
public class AffiliateScheduling {

  private final AffiliateLedgerRepository ledger;
  private final AffiliateAccrualService accrual;
  private final AffiliateSettlementService settlement;
  private final long[] nextPoll = new long[ProductLine.values().length];
  private final int[] failures = new int[ProductLine.values().length];
  private int nextProduct;

  public AffiliateScheduling(
    AffiliateLedgerRepository ledger,
    AffiliateAccrualService accrual,
    AffiliateSettlementService settlement
  ) {
    this.ledger = ledger;
    this.accrual = accrual;
    this.settlement = settlement;
  }

  @Bean("affiliateScheduler")
  ThreadPoolTaskScheduler scheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("gateway-affiliate-");
    scheduler.setAwaitTerminationSeconds(5);
    return scheduler;
  }

  @Scheduled(
    scheduler = "affiliateScheduler",
    initialDelay = 15000,
    fixedDelay = 250
  )
  public void accrue() {
    var products = ProductLine.values();
    long now = System.nanoTime();
    for (int scanned = 0; scanned < products.length; scanned++) {
      int slot = nextProduct;
      nextProduct = (nextProduct + 1) % products.length;
      if (nextPoll[slot] != 0 && now - nextPoll[slot] < 0) continue;
      var product = products[slot];
      try {
        var result = accrual.accrueBatch(product.name());
        failures[slot] = 0;
        nextPoll[slot] =
          System.nanoTime() + (result.more() ? 0 : 10_000_000_000L);
      } catch (RuntimeException failure) {
        failures[slot] = Math.min(6, failures[slot] + 1);
        nextPoll[slot] =
          System.nanoTime() +
          Math.min(60, 1L << failures[slot]) * 1_000_000_000L;
        try {
          ledger.sourceError(
            product.name(),
            "核算已暂停并保留游标：来源、历史关系、处理上限或数据库需要核对"
          );
        } catch (RuntimeException unavailable) {
          failure.addSuppressed(unavailable);
        }
        org.slf4j.LoggerFactory.getLogger(getClass()).warn(
          "Affiliate accrual deferred product={}",
          product,
          failure
        );
      }
      return;
    }
  }

  @Scheduled(
    scheduler = "affiliateScheduler",
    initialDelay = 15000,
    fixedDelay = 1000
  )
  public void settle() {
    try {
      settlement.prepare(false, 0);
    } catch (RuntimeException failure) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn(
        "Affiliate settlement preparation deferred",
        failure
      );
    }
    try {
      settlement.send();
    } catch (RuntimeException failure) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn(
        "Affiliate committed payout delivery deferred",
        failure
      );
    }
  }
}
