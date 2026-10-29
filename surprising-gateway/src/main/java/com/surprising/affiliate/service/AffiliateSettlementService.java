package com.surprising.affiliate.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.affiliate.config.AffiliateProperties;
import com.surprising.affiliate.repository.*;
import com.surprising.gateway.provider.model.ProductTransferCommand;
import com.surprising.gateway.provider.service.ProductTransferCoordinator;
import com.surprising.product.api.ProductLine;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AffiliateSettlementService {

  private final AffiliateRepository repository;
  private final AffiliateLedgerRepository ledger;
  private final ProductTransferCoordinator transfers;
  private final TransactionTemplate transaction;
  private final AffiliateProperties properties;

  public AffiliateSettlementService(
    AffiliateRepository repository,
    AffiliateLedgerRepository ledger,
    ProductTransferCoordinator transfers,
    PlatformTransactionManager manager,
    AffiliateProperties properties
  ) {
    this.repository = repository;
    this.ledger = ledger;
    this.transfers = transfers;
    this.transaction = new TransactionTemplate(manager);
    this.transaction.setTimeout(properties.getTransactionTimeoutSeconds());
    this.properties = properties;
  }

  public void prepare(boolean manual, long actor) {
    transaction.executeWithoutResult(tx -> {
      if (!repository.trySettlementLock() || !repository.tryReadLock()) {
        if (manual) throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "结算任务繁忙，请稍后重试"
        );
        return;
      }
      int hours = repository.settings(repository.now()).settlementHours();
      var users = new HashSet<Long>();
      int remaining = properties.getMaxAllocationRows();
      long deadline =
        System.nanoTime() + properties.getBatchMillis() * 1_000_000L;
      for (var group : ledger.payoutGroups(
        manual,
        hours,
        properties.getPayoutGroups()
      )) {
        remaining -= ledger.reserve(
          group,
          Math.min(remaining, properties.getPayoutRows())
        );
        users.add(((Number) group.get("beneficiary")).longValue());
        if (remaining <= 0 || System.nanoTime() >= deadline) break;
      }
      if (!manual) ledger.deferAutomatic(users, hours);
      if (manual) repository.audit(actor, "SETTLE", null, "手动结算成熟佣金");
    });
  }

  public void retry(UUID id, long actor) {
    transaction.executeWithoutResult(tx -> {
      repository.lock();
      ledger.retry(id);
      repository.audit(actor, "RETRY_PAYOUT", null, id.toString());
    });
  }

  public void send() {
    long deadline =
      System.nanoTime() + properties.getBatchMillis() * 1_000_000L;
    for (int attempt = 0; attempt < properties.getPayoutAttempts(); attempt++) {
      UUID token = UUID.randomUUID();
      var claimed = transaction.execute(tx ->
        ledger.claimPayout(token, properties.getClaimSeconds())
      );
      if (claimed.isEmpty()) break;
      var row = claimed.get();
      UUID id = (UUID) row.get("payout_id");
      int retrySeconds = (int) Math.min(
        300,
        10L << Math.min(5, ((Number) row.get("attempt_count")).intValue() - 1)
      );
      try {
        if (!ledger.payoutBalanced(row)) {
          ledger.payoutResult(
            id,
            token,
            "UNKNOWN",
            null,
            "结算金额与关联应付不一致，已阻止资金请求，请核对",
            300
          );
          if (System.nanoTime() >= deadline) break;
          continue;
        }
        var product = ProductLine.valueOf(row.get("product_line").toString());
        var account = AccountType.valueOf(product.accountTypeCode());
        var result = transfers.executeCommittedTransfer(
          new ProductTransferCommand(
            ((Number) row.get("budget_user_id")).longValue(),
            "affiliate:" + id,
            account,
            account,
            row.get("asset").toString(),
            ((Number) row.get("amount_units")).longValue(),
            "affiliate:" + id,
            "邀请返佣结算",
            ((Number) row.get("beneficiary_id")).longValue()
          )
        );
        String status = switch (result.status()) {
          case COMPLETED -> "PAID";
          case FAILED -> "REJECTED";
          default -> "UNKNOWN";
        };
        ledger.payoutResult(
          id,
          token,
          status,
          Long.toString(result.transferId()),
          result.errorMessage(),
          retrySeconds
        );
      } catch (RuntimeException failure) {
        ledger.payoutResult(
          id,
          token,
          "UNKNOWN",
          null,
          "资金结果待确认，请检查预算账户、产品接入及资金链路；将按原结算身份重试",
          retrySeconds
        );
        org.slf4j.LoggerFactory.getLogger(getClass()).warn(
          "Affiliate payout pending id={}",
          id,
          failure
        );
      }
      if (System.nanoTime() >= deadline) break;
    }
  }
}
