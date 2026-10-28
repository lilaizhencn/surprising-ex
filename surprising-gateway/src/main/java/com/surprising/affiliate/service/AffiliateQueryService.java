package com.surprising.affiliate.service;

import com.surprising.affiliate.model.AffiliateModels.Page;
import com.surprising.affiliate.repository.AffiliateLedgerRepository;
import com.surprising.affiliate.repository.AffiliateRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Bounded read models shared by the user pages and operations console. */
@Service
public class AffiliateQueryService {

  private final AffiliateRepository repository;
  private final AffiliateLedgerRepository ledger;
  private final AffiliateService affiliates;

  public AffiliateQueryService(
    AffiliateRepository repository,
    AffiliateLedgerRepository ledger,
    AffiliateService affiliates
  ) {
    this.repository = repository;
    this.ledger = ledger;
    this.affiliates = affiliates;
  }

  public Map<String, Object> adminOverview(long after) {
    return Map.of(
      "settings",
      affiliates.settings(),
      "profiles",
      repository.profiles(after, 51),
      "budgets",
      repository.budgets(),
      "sources",
      ledger.sources(),
      "payouts",
      ledger.payouts(null, 0, 50)
    );
  }

  public Page<Map<String, Object>> entries(
    long user,
    String product,
    long after
  ) {
    var rows = ledger.entries(user, product, after, 51);
    return new Page<>(
      List.copyOf(rows.subList(0, Math.min(50, rows.size()))),
      rows.size() > 50 ? rows.get(49).get("commissionId").toString() : null
    );
  }

  public Map<String, Object> payouts(long user) {
    return Map.of("items", ledger.payouts(user, 0, 50));
  }
}
