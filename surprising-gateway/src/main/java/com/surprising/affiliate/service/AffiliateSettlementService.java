package com.surprising.affiliate.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.affiliate.repository.*;
import com.surprising.gateway.provider.model.ProductTransferCommand;
import com.surprising.gateway.provider.service.ProductTransferCoordinator;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AffiliateSettlementService {

  private final AffiliateRepository repository;
  private final AffiliateLedgerRepository ledger;
  private final ProductTransferCoordinator transfers;
  private final TransactionTemplate transaction;

  public AffiliateSettlementService(
    AffiliateRepository repository,
    AffiliateLedgerRepository ledger,
    ProductTransferCoordinator transfers,
    org.springframework.transaction.PlatformTransactionManager manager
  ) {
    this.repository = repository;
    this.ledger = ledger;
    this.transfers = transfers;
    this.transaction = new TransactionTemplate(manager);
  }

  public void prepare(boolean manual, long actor) {
    transaction.executeWithoutResult(tx -> {
      repository.lock();
      int hours = repository.settings(repository.now()).settlementHours();
      for (var group : ledger.payoutGroups(manual, hours))
        ledger.reserve(group, hours);
      if (!manual) ledger.deferAutomatic(hours);
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
    var claimed = transaction.execute(tx -> ledger.claimPayouts());
    for (var row : claimed) {
      UUID id = (UUID) row.get("payout_id");
      try {
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
          status,
          Long.toString(result.transferId()),
          result.errorMessage()
        );
      } catch (RuntimeException failure) {
        ledger.payoutResult(
          id,
          "UNKNOWN",
          null,
          "资金结果待确认，请检查预算账户、产品接入及资金链路；将按原结算身份重试"
        );
        org.slf4j.LoggerFactory.getLogger(getClass()).warn(
          "Affiliate payout pending id={}",
          id,
          failure
        );
      }
    }
  }
}
