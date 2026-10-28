package com.surprising.affiliate;

import static org.assertj.core.api.Assertions.*;

import com.surprising.affiliate.model.AffiliateModels.TeamTerm;
import com.surprising.affiliate.service.CommissionAllocation;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommissionAllocationTest {

  @Test
  void delegatesOnlyTheAttributedBranchAndConservesThePool() {
    var rows = CommissionAllocation.split(
      1,
      20,
      List.of(new TeamTerm(7, 1, 2, 600000), new TeamTerm(8, 2, 3, 500000))
    );
    assertThat(rows)
      .extracting(r -> r.retainedUnits())
      .containsExactly(8L, 6L, 6L);
    assertThat(
      rows
        .stream()
        .mapToLong(r -> r.retainedUnits())
        .sum()
    ).isEqualTo(20);
  }

  @Test
  void roundingRemainsWithTheParentAndMultiplicationCannotOverflow() {
    var rows = CommissionAllocation.split(
      1,
      7,
      List.of(new TeamTerm(7, 1, 2, 500000))
    );
    assertThat(rows)
      .extracting(r -> r.retainedUnits())
      .containsExactly(4L, 3L);
    assertThat(
      CommissionAllocation.multiply(Long.MAX_VALUE, 1000000)
    ).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void fullDelegationZeroDelegationAndCyclesAreExplicit() {
    assertThat(
      CommissionAllocation.split(1, 10, List.of(new TeamTerm(1, 1, 2, 1000000)))
        .getFirst()
        .retainedUnits()
    ).isZero();
    assertThat(
      CommissionAllocation.split(1, 10, List.of(new TeamTerm(1, 1, 2, 0)))
        .getLast()
        .receivedUnits()
    ).isZero();
    assertThatThrownBy(() ->
      CommissionAllocation.split(
        1,
        10,
        List.of(new TeamTerm(1, 1, 2, 500000), new TeamTerm(2, 2, 1, 500000))
      )
    ).isInstanceOf(IllegalArgumentException.class);
  }
}
