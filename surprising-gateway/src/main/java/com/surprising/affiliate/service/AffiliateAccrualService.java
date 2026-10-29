package com.surprising.affiliate.service;

import com.surprising.affiliate.config.AffiliateProperties;
import com.surprising.affiliate.model.AffiliateModels.*;
import com.surprising.affiliate.model.AffiliateRelationshipException;
import com.surprising.affiliate.repository.*;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AffiliateAccrualService {

  private final AffiliateRepository repository;
  private final AffiliateLedgerRepository ledger;
  private final AffiliateService affiliates;
  private final AffiliateFeeSourceRepository source;
  private final AffiliateProperties properties;

  public AffiliateAccrualService(
    AffiliateRepository repository,
    AffiliateLedgerRepository ledger,
    AffiliateService affiliates,
    AffiliateFeeSourceRepository source,
    AffiliateProperties properties
  ) {
    this.repository = repository;
    this.ledger = ledger;
    this.affiliates = affiliates;
    this.source = source;
    this.properties = properties;
  }

  @Transactional(
    timeoutString = "${surprising.affiliate.transaction-timeout-seconds:5}"
  )
  public void accrue(Fee fee) {
    repository.productLock(fee.productLine());
    repository.readLock();
    if (
      accrueLocked(fee, properties.getMaxAllocationRows()) < 0
    ) throw new IllegalStateException("处理预算不能覆盖完整核算路径");
    ledger.sourcePosition(
      fee.productLine(),
      new SourceCursor(fee.clusterPosition(), fee.orderId()),
      null
    );
  }

  @Transactional(
    timeoutString = "${surprising.affiliate.transaction-timeout-seconds:5}"
  )
  public BatchResult accrueBatch(String product) {
    if (
      !repository.tryProductLock(product) || !repository.tryReadLock()
    ) return new BatchResult(0, true, true);
    var position = ledger.readPosition(product);
    var facts = source.read(product, position, properties.getFeeBatchSize());
    long deadline =
      System.nanoTime() + properties.getBatchMillis() * 1_000_000L;
    int processed = 0,
      rows = 0,
      pending = -1;
    boolean capacity = false;
    for (var fee : facts) {
      int added;
      try {
        added = accrueLocked(fee, properties.getMaxAllocationRows() - rows);
      } catch (AffiliateRelationshipException failure) {
        if (pending < 0) pending = ledger.deferredCount(
          product,
          properties.getMaxDeferredFees() + 1
        );
        if (pending >= properties.getMaxDeferredFees()) {
          capacity = true;
          break;
        }
        ledger.deferFee(
          fee,
          position,
          failure.getMessage(),
          properties.getDeferredRetrySeconds()
        );
        pending++;
        added = 1;
      }
      if (added < 0) break;
      rows += added;
      processed++;
      position = new SourceCursor(fee.clusterPosition(), fee.orderId());
      if (
        rows >= properties.getMaxAllocationRows() ||
        System.nanoTime() >= deadline
      ) break;
    }
    ledger.sourcePosition(
      product,
      position,
      capacity ? "关系异常隔离队列已达容量，本笔未跳过，等待处理存量异常" : null
    );
    return new BatchResult(
      processed,
      !capacity &&
        (processed < facts.size() ||
          facts.size() == properties.getFeeBatchSize()),
      false
    );
  }

  /** Separate transaction/budget: a busy repair queue cannot consume healthy ingestion's allowance. */
  @Transactional(
    timeoutString = "${surprising.affiliate.transaction-timeout-seconds:5}"
  )
  public BatchResult retryDeferred(String product) {
    if (
      !repository.tryProductLock(product) || !repository.tryReadLock()
    ) return new BatchResult(0, true, true);
    var fees = ledger.deferredFees(product, properties.getDeferredBatchSize());
    long deadline =
      System.nanoTime() + properties.getBatchMillis() * 1_000_000L;
    int processed = 0,
      rows = 0;
    for (var fee : fees) {
      try {
        int added = accrueLocked(fee, properties.getMaxAllocationRows() - rows);
        if (added < 0) break;
        ledger.resolveDeferredFee(fee);
        rows += Math.max(1, added);
      } catch (AffiliateRelationshipException failure) {
        ledger.retryDeferredFee(
          fee,
          failure.getMessage(),
          properties.getDeferredRetrySeconds()
        );
        rows++;
      }
      processed++;
      if (
        rows >= properties.getMaxAllocationRows() ||
        System.nanoTime() >= deadline
      ) break;
    }
    if (processed > 0) ledger.sourcePosition(
      product,
      ledger.readPosition(product),
      null
    );
    return new BatchResult(
      processed,
      processed < fees.size() ||
        fees.size() == properties.getDeferredBatchSize(),
      false
    );
  }

  private int accrueLocked(Fee fee, int remainingRows) {
    if (fee.feeUnits() <= 0) throw new IllegalArgumentException(
      "计佣只接受实际正手续费"
    );
    if (ledger.received(fee)) return 0;
    var inviter = repository.inviter(fee.userId(), fee.occurredAt());
    if (inviter.isEmpty()) {
      record(
        fee,
        null,
        null,
        null,
        0,
        "NO_INVITER",
        List.of(),
        List.of(),
        Map.of()
      );
      return 0;
    }
    long direct = inviter.get();
    var ownTeam = repository.parent(fee.userId(), fee.occurredAt());
    if (ownTeam.isPresent()) {
      if (
        ownTeam.get().parentId() != direct
      ) throw new AffiliateRelationshipException(
        "合作归属与邀请归属冲突，核算已暂停"
      );
      record(
        fee,
        direct,
        null,
        null,
        0,
        "TEAM_MEMBER_TRADE",
        List.of(),
        List.of(),
        Map.of()
      );
      return 0;
    }
    var team = affiliates.team(direct, fee.occurredAt());
    var path = team.terms();
    long root = team.root().userId();
    var rootNode = team.root();
    if (!path.isEmpty() && !team.active()) {
      record(
        fee,
        direct,
        root,
        null,
        0,
        "KOL_DISABLED",
        List.of(),
        List.of(),
        Map.of()
      );
      return 0;
    }
    if (path.size() + 1 > remainingRows) return -1;
    var settings = repository.settings(fee.occurredAt());
    int rate =
      rootNode.ratePpm() != null
        ? rootNode.ratePpm()
        : rootNode.kolEnabled()
          ? settings.kolRatePpm()
          : settings.referralRatePpm();
    long pool = ledger.accruePool(fee, root, settings, rate);
    var prior = ledger.delegationBalances(fee, path);
    var allocations = CommissionAllocation.split(root, pool, path, prior);
    record(
      fee,
      direct,
      root,
      settings.version(),
      pool,
      "ACCRUED",
      allocations,
      path,
      prior
    );
    return allocations.size();
  }

  private void record(
    Fee fee,
    Long inviter,
    Long root,
    Long settings,
    long pool,
    String disposition,
    List<Allocation> rows,
    List<TeamTerm> path,
    Map<Long, BigInteger> prior
  ) {
    long receipt = ledger.receipt(
      fee,
      inviter,
      root,
      settings,
      pool,
      disposition
    );
    if (!rows.isEmpty()) ledger.allocations(receipt, fee, rows, path, prior);
  }
}
