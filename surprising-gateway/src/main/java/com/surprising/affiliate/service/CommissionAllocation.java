package com.surprising.affiliate.service;

import com.surprising.affiliate.model.AffiliateModels.*;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Pure integer allocation. Path is root -> direct inviter; all rates are shares of parent quota. */
public final class CommissionAllocation {

  private static final BigInteger SCALE = BigInteger.valueOf(1_000_000);

  private CommissionAllocation() {}

  public static long multiply(long units, int ppm) {
    if (
      units < 0 || ppm < 0 || ppm > 1_000_000
    ) throw new IllegalArgumentException("invalid allocation");
    return BigInteger.valueOf(units)
      .multiply(BigInteger.valueOf(ppm))
      .divide(SCALE)
      .longValueExact();
  }

  public static List<Allocation> split(
    long rootId,
    long pool,
    List<TeamTerm> path
  ) {
    if (rootId <= 0 || pool < 0) throw new IllegalArgumentException(
      "invalid commission pool"
    );
    var result = new ArrayList<Allocation>();
    var visited = new HashSet<Long>();
    long owner = rootId,
      received = pool;
    Long incomingTerm = null;
    int incomingShare = 1_000_000;
    for (var edge : path) {
      if (
        !visited.add(owner) ||
        edge.parentId() != owner ||
        edge.childId() == owner
      ) throw new IllegalArgumentException("invalid or cyclic commission path");
      long delegated = multiply(received, edge.sharePpm());
      result.add(
        new Allocation(
          owner,
          received,
          delegated,
          Math.subtractExact(received, delegated),
          incomingTerm,
          incomingShare
        )
      );
      owner = edge.childId();
      received = delegated;
      incomingTerm = edge.termId();
      incomingShare = edge.sharePpm();
    }
    if (!visited.add(owner)) throw new IllegalArgumentException(
      "cyclic commission path"
    );
    result.add(
      new Allocation(owner, received, 0, received, incomingTerm, incomingShare)
    );
    return List.copyOf(result);
  }
}
