package com.surprising.insurance.provider.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.insurance.provider.model.InsurancePendingCoverage;
import com.surprising.insurance.provider.repository.InsuranceCoverageRepository;
import com.surprising.insurance.provider.repository.InsuranceFundBalanceRepository;
import com.surprising.insurance.provider.repository.InsuranceFundLedgerRepository;
import com.surprising.insurance.provider.repository.InsurancePendingCoverageRepository;
import com.surprising.insurance.provider.repository.InsuranceSequenceRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class InsuranceCoverageReconcilerTest {

    @Test
    void finalAccountCommandConsumesReservationAndCompletesCoverageInOneServiceTransaction() {
        Fixture fixture = new Fixture();
        InsurancePendingCoverage coverage = coverage(
                "PENDING_FINALIZE", "APPLIED", "APPLIED", "{\"remainingDeficitUnits\":100}");
        when(fixture.pendingRepository.lock("USDT_PERPETUAL", 500)).thenReturn(List.of(coverage));
        when(fixture.balanceRepository.consumeReservation(
                eq("USDT_PERPETUAL"), eq("USDT"), eq(400L), any(Instant.class))).thenReturn(600L);
        when(fixture.sequenceRepository.next("insurance-ledger")).thenReturn(91L);
        when(fixture.ledgerRepository.insert(eq(91L), eq("USDT_PERPETUAL"), eq("USDT"),
                eq(-400L), eq(600L), eq("DEFICIT_COVERAGE"), eq("20"),
                eq("COVER_ACCOUNT_DEFICIT"), any(Instant.class))).thenReturn(true);

        fixture.reconciler.reconcile();

        verify(fixture.coverageRepository).markCompleted(eq(20L), eq(100L), any(Instant.class));
    }

    @Test
    void rejectedReserveReleasesFundBeforeMarkingCoverageFailed() {
        Fixture fixture = new Fixture();
        InsurancePendingCoverage coverage =
                coverage("PENDING_RESERVE", "REJECTED", null, null);
        when(fixture.pendingRepository.lock("USDT_PERPETUAL", 500)).thenReturn(List.of(coverage));

        fixture.reconciler.reconcile();

        verify(fixture.balanceRepository).release(
                eq("USDT_PERPETUAL"), eq("USDT"), eq(400L), any(Instant.class));
        verify(fixture.coverageRepository).markFailed(eq(coverage), any(Instant.class));
    }

    private static InsurancePendingCoverage coverage(String coverageStatus,
                                                     String reserveStatus,
                                                     String finalizeStatus,
                                                     String finalizeResult) {
        return new InsurancePendingCoverage(
                20L, "USDT_PERPETUAL", 1001L, "USDT", 400L,
                "reserve-20", "finalize-20", coverageStatus, reserveStatus, finalizeStatus,
                finalizeResult, "ACCOUNT_REJECTED", "rejected");
    }

    private static final class Fixture {
        private final InsurancePendingCoverageRepository pendingRepository =
                mock(InsurancePendingCoverageRepository.class);
        private final InsuranceCoverageRepository coverageRepository =
                mock(InsuranceCoverageRepository.class);
        private final InsuranceFundBalanceRepository balanceRepository =
                mock(InsuranceFundBalanceRepository.class);
        private final InsuranceFundLedgerRepository ledgerRepository =
                mock(InsuranceFundLedgerRepository.class);
        private final InsuranceSequenceRepository sequenceRepository =
                mock(InsuranceSequenceRepository.class);
        private final InsuranceCoverageReconciler reconciler =
                new InsuranceCoverageReconciler(new InsuranceProperties(), pendingRepository,
                        coverageRepository, balanceRepository, ledgerRepository, sequenceRepository,
                        new ObjectMapper());
    }
}
