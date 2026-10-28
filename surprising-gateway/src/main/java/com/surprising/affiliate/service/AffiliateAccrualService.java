package com.surprising.affiliate.service;

import com.surprising.affiliate.model.AffiliateModels.*;
import com.surprising.affiliate.repository.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AffiliateAccrualService {

  private final AffiliateRepository repository;
  private final AffiliateLedgerRepository ledger;
  private final AffiliateService affiliates;

  public AffiliateAccrualService(
    AffiliateRepository repository,
    AffiliateLedgerRepository ledger,
    AffiliateService affiliates
  ) {
    this.repository = repository;
    this.ledger = ledger;
    this.affiliates = affiliates;
  }

  @Transactional
  public void accrue(Fee fee) {
    if (fee.feeUnits() <= 0) throw new IllegalArgumentException(
      "计佣只接受实际正手续费"
    );
    repository.lock();
    if (ledger.received(fee)) {
      ledger.sourcePosition(
        fee.productLine(),
        new SourceCursor(fee.clusterPosition(), fee.orderId()),
        null
      );
      return;
    }
    var inviter = repository.inviter(fee.userId(), fee.occurredAt());
    if (inviter.isEmpty()) {
      record(fee, null, null, null, 0, "NO_INVITER", List.of());
      return;
    }
    long direct = inviter.get();
    var path = affiliates.path(direct, fee.occurredAt());
    long root = path.isEmpty() ? direct : path.getFirst().parentId();
    var payerPath = affiliates.path(fee.userId(), fee.occurredAt());
    long payerRoot = payerPath.isEmpty()
      ? fee.userId()
      : payerPath.getFirst().parentId();
    if (
      payerRoot == root &&
      repository.teamParticipant(fee.userId(), fee.occurredAt())
    ) {
      record(fee, direct, root, null, 0, "TEAM_MEMBER_TRADE", List.of());
      return;
    }
    var rootNode = repository.node(root, fee.occurredAt());
    if (!path.isEmpty() && !affiliates.activeTeam(path, fee.occurredAt())) {
      record(fee, direct, root, null, 0, "KOL_DISABLED", List.of());
      return;
    }
    var settings = repository.settings(fee.occurredAt());
    int rate =
      rootNode.ratePpm() != null
        ? rootNode.ratePpm()
        : rootNode.kolEnabled()
          ? settings.kolRatePpm()
          : settings.referralRatePpm();
    long pool = ledger.accruePool(fee, root, settings, rate);
    record(
      fee,
      direct,
      root,
      settings.version(),
      pool,
      "ACCRUED",
      CommissionAllocation.split(root, pool, path)
    );
  }

  private void record(
    Fee fee,
    Long inviter,
    Long root,
    Long settings,
    long pool,
    String disposition,
    List<Allocation> rows
  ) {
    long receipt = ledger.receipt(
      fee,
      inviter,
      root,
      settings,
      pool,
      disposition
    );
    for (var row : rows) ledger.allocation(receipt, row);
    ledger.sourcePosition(
      fee.productLine(),
      new SourceCursor(fee.clusterPosition(), fee.orderId()),
      null
    );
  }
}
