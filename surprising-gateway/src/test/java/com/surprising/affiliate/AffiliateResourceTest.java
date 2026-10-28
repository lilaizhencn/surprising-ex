package com.surprising.affiliate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.affiliate.config.*;
import com.surprising.affiliate.model.AffiliateModels.*;
import com.surprising.affiliate.repository.*;
import com.surprising.affiliate.service.*;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.server.ResponseStatusException;

@Timeout(15)
class AffiliateResourceTest {

  @Test
  void failedProductBacksOffWhileOtherProductsMakeProgress() {
    var ledger = mock(AffiliateLedgerRepository.class);
    var accrual = mock(AffiliateAccrualService.class);
    when(accrual.accrueBatch(anyString())).thenReturn(
      new BatchResult(0, false, false)
    );
    when(accrual.accrueBatch(ProductLine.values()[0].name())).thenThrow(
      new IllegalStateException("corrupt history")
    );
    var scheduler = new AffiliateScheduling(
      ledger,
      accrual,
      mock(AffiliateSettlementService.class)
    );
    for (
      int invocation = 0;
      invocation < ProductLine.values().length + 10;
      invocation++
    ) scheduler.accrue();
    verify(accrual, times(ProductLine.values().length)).accrueBatch(
      anyString()
    );
    verify(ledger).sourceError(
      eq(ProductLine.values()[0].name()),
      contains("保留游标")
    );
  }

  @Test
  void failedPreparationDoesNotStopPreviouslyCommittedPayments() {
    var settlement = mock(AffiliateSettlementService.class);
    doThrow(new IllegalStateException("prepare unavailable"))
      .when(settlement)
      .prepare(false, 0);
    var scheduler = new AffiliateScheduling(
      mock(AffiliateLedgerRepository.class),
      mock(AffiliateAccrualService.class),
      settlement
    );
    scheduler.settle();
    verify(settlement).send();
  }

  @Test
  void invalidResourceConfigurationCannotTruncateAPathOrDisableTimeouts() {
    try (
      var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()
    ) {
      var properties = new AffiliateProperties();
      assertThat(factory.getValidator().validate(properties)).isEmpty();
      properties.setMaxAllocationRows(100);
      properties.setQueryTimeoutSeconds(0);
      assertThat(factory.getValidator().validate(properties)).hasSize(2);
    }
  }

  @Test
  void expensiveReadsAreBoundedAndReleasePermitsAfterCompletion()
    throws Exception {
    var repository = mock(AffiliateRepository.class);
    var entered = new CountDownLatch(4);
    var release = new CountDownLatch(1);
    when(repository.counts(1)).thenAnswer(invocation -> {
      entered.countDown();
      if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(
        "test release timeout"
      );
      return List.of(Map.of("directInviteCount", "0", "teamInviteCount", "0"));
    });
    when(repository.profile(1)).thenReturn(
      new Profile("1", "TEST", true, true, null, Instant.EPOCH)
    );
    when(repository.lineage(eq(1L), any(), anyInt())).thenReturn(
      List.of(new PathNode(new Node(1, true, null), null))
    );
    when(repository.settings(any())).thenReturn(
      new Settings(1, 200000, 200000, 24)
    );
    var service = new AffiliateService(repository, new AffiliateProperties());
    try (var executor = Executors.newFixedThreadPool(4)) {
      var pending = new ArrayList<Future<?>>();
      for (int request = 0; request < 4; request++) pending.add(
        executor.submit(() -> service.overview(1, ""))
      );
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() ->
          service.overview(1, "")
        ).isInstanceOfSatisfying(ResponseStatusException.class, error ->
          assertThat(error.getStatusCode().value()).isEqualTo(503)
        );
      } finally {
        release.countDown();
      }
      for (var request : pending) request.get(5, TimeUnit.SECONDS);
    }
    assertThat(service.overview(1, "")).containsKey("profile");
  }
}
