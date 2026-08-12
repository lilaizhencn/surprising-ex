package com.surprising.insurance.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.insurance.api.model.AdminCursorPage;
import com.surprising.insurance.api.model.InsuranceCoverageResponse;
import com.surprising.insurance.api.model.InsuranceFundAdjustmentRequest;
import com.surprising.insurance.api.model.InsuranceFundBalanceResponse;
import com.surprising.insurance.api.model.InsuranceFundLedgerResponse;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.insurance.provider.model.InsuranceDeficitRow;
import com.surprising.insurance.provider.model.InsuranceFundBalanceState;
import com.surprising.insurance.provider.model.InsuranceLedgerReference;
import com.surprising.insurance.provider.repository.InsuranceAccountOutboxRepository;
import com.surprising.insurance.provider.repository.InsuranceCoverageRepository;
import com.surprising.insurance.provider.repository.InsuranceFundBalanceRepository;
import com.surprising.insurance.provider.repository.InsuranceFundLedgerRepository;
import com.surprising.insurance.provider.repository.InsuranceLegacyDeficitRepository;
import com.surprising.insurance.provider.repository.InsuranceProductDeficitRepository;
import com.surprising.insurance.provider.repository.InsuranceSequenceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class InsuranceServiceTest {

    @Test
    void coverDeficitsDoesNothingWhenCoverageIsDisabled() {
        InsuranceProperties properties = new InsuranceProperties();
        properties.getCoverage().setEnabled(false);
        Fixture fixture = new Fixture(properties);

        fixture.service.coverDeficits();

        verify(fixture.legacyDeficitRepository, never()).findPositive(any(), anyInt());
    }

    @Test
    void coverDeficitsUsesConfiguredBatchSizeWhenEnabled() {
        InsuranceProperties properties = new InsuranceProperties();
        properties.getCoverage().setBatchSize(17);
        Fixture fixture = new Fixture(properties);
        when(fixture.legacyDeficitRepository.findPositive("USDT_PERPETUAL", 17)).thenReturn(List.of());

        fixture.service.coverDeficits();

        verify(fixture.legacyDeficitRepository).findPositive("USDT_PERPETUAL", 17);
    }

    @Test
    void coverDeficitsAggregatesBalanceCoverageAndOutboxRepositories() {
        Fixture fixture = new Fixture(new InsuranceProperties());
        InsuranceDeficitRow deficit = new InsuranceDeficitRow("USDT_PERPETUAL", 4004L, "USDT", 1_000L);
        when(fixture.legacyDeficitRepository.findPositive("USDT_PERPETUAL", 100))
                .thenReturn(List.of(deficit));
        when(fixture.balanceRepository.lock("USDT_PERPETUAL", "USDT"))
                .thenReturn(new InsuranceFundBalanceState(600L, 0L));
        when(fixture.sequenceRepository.next("insurance-coverage")).thenReturn(9501L);

        fixture.service.coverDeficits();

        verify(fixture.balanceRepository).reserve(
                eq("USDT_PERPETUAL"), eq("USDT"), eq(600L), any(Instant.class));
        verify(fixture.coverageRepository).insert(eq(9501L), eq(deficit), eq(600L), eq(400L),
                eq("INSURANCE_RESERVE:LINEAR_PERPETUAL:9501"),
                eq("INSURANCE_FINALIZE:LINEAR_PERPETUAL:9501"), any(Instant.class));
        verify(fixture.accountOutboxRepository, times(2)).enqueue(
                eq(9501L), any(), any(Instant.class));
    }

    @Test
    void duplicateFundAdjustmentDoesNotUpdateBalanceAgain() {
        Fixture fixture = new Fixture(new InsuranceProperties());
        when(fixture.balanceRepository.lock("USDT_PERPETUAL", "USDT"))
                .thenReturn(new InsuranceFundBalanceState(1_000L, 0L));
        when(fixture.sequenceRepository.next("insurance-ledger")).thenReturn(101L);
        when(fixture.ledgerRepository.insert(anyLong(), any(), any(), anyLong(),
                anyLong(), any(), any(), any(), any())).thenReturn(false);
        when(fixture.ledgerRepository.findReference(
                "FUND_ADJUSTMENT", "ops-1", "USDT_PERPETUAL", "USDT"))
                .thenReturn(Optional.of(new InsuranceLedgerReference(500L, "REPLAYED_REQUEST")));
        when(fixture.balanceRepository.findOne("USDT_PERPETUAL", "USDT"))
                .thenReturn(Optional.of(new InsuranceFundBalanceResponse(
                        "USDT", 1_000L, Instant.parse("2026-07-01T00:00:00Z"))));

        var response = fixture.service.adjustFund(
                new InsuranceFundAdjustmentRequest("USDT", 500L, "ops-1", "REPLAYED_REQUEST"));

        assertThat(response.balanceUnits()).isEqualTo(1_000L);
        verify(fixture.balanceRepository, never()).updateBalance(any(), any(), anyLong(), any());
    }

    @Test
    void duplicateFundAdjustmentWithDifferentPayloadFailsClosed() {
        Fixture fixture = new Fixture(new InsuranceProperties());
        when(fixture.balanceRepository.lock("USDT_PERPETUAL", "USDT"))
                .thenReturn(new InsuranceFundBalanceState(1_000L, 0L));
        when(fixture.sequenceRepository.next("insurance-ledger")).thenReturn(101L);
        when(fixture.ledgerRepository.insert(anyLong(), any(), any(), anyLong(),
                anyLong(), any(), any(), any(), any())).thenReturn(false);
        when(fixture.ledgerRepository.findReference(
                "FUND_ADJUSTMENT", "ops-1", "USDT_PERPETUAL", "USDT"))
                .thenReturn(Optional.of(new InsuranceLedgerReference(500L, "INITIAL_FUND")));

        assertThatThrownBy(() -> fixture.service.adjustFund(
                new InsuranceFundAdjustmentRequest("USDT", 600L, "ops-1", "INITIAL_FUND")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicting duplicate insurance fund reference");
        verify(fixture.balanceRepository, never()).updateBalance(any(), any(), anyLong(), any());
    }

    @Test
    void ledgerAndCoverageQueriesExposeCursorMetadata() {
        Fixture fixture = new Fixture(new InsuranceProperties());
        when(fixture.ledgerRepository.page(
                "USDT_PERPETUAL", "USDT", 50, "ledger-cursor", "createdAt.asc"))
                .thenReturn(new AdminCursorPage.CursorPage<>(List.of(new InsuranceFundLedgerResponse(
                        10L, "USDT", 100L, 100L, "FUND_ADJUSTMENT", "ref-1", "seed",
                        Instant.parse("2026-07-01T00:00:00Z"))),
                        "next-ledger", true, "createdAt.asc", 50));
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(fixture.coverageRepository.page(
                "USDT_PERPETUAL", 1001L, "USDT", 25, "coverage-cursor", "createdAt.asc"))
                .thenReturn(new AdminCursorPage.CursorPage<>(List.of(new InsuranceCoverageResponse(
                        20L, 1001L, "USDT", 500L, 400L, 100L, "PARTIALLY_COVERED",
                        "DEFICIT_COVERAGE", now, now)), "next-coverage", true, "createdAt.asc", 25));

        var ledger = fixture.service.ledger("usdt", 50, "ledger-cursor", "createdAt.asc");
        var coverages = fixture.service.coverages(1001L, "usdt", 25, "coverage-cursor", "createdAt.asc");

        assertThat(ledger.nextCursor()).isEqualTo("next-ledger");
        assertThat(ledger.hasMore()).isTrue();
        assertThat(coverages.nextCursor()).isEqualTo("next-coverage");
    }

    private static final class Fixture {
        private final InsuranceSequenceRepository sequenceRepository = mock(InsuranceSequenceRepository.class);
        private final InsuranceFundBalanceRepository balanceRepository =
                mock(InsuranceFundBalanceRepository.class);
        private final InsuranceFundLedgerRepository ledgerRepository =
                mock(InsuranceFundLedgerRepository.class);
        private final InsuranceCoverageRepository coverageRepository =
                mock(InsuranceCoverageRepository.class);
        private final InsuranceProductDeficitRepository productDeficitRepository =
                mock(InsuranceProductDeficitRepository.class);
        private final InsuranceLegacyDeficitRepository legacyDeficitRepository =
                mock(InsuranceLegacyDeficitRepository.class);
        private final InsuranceAccountOutboxRepository accountOutboxRepository =
                mock(InsuranceAccountOutboxRepository.class);
        private final InsuranceService service;

        private Fixture(InsuranceProperties properties) {
            service = new InsuranceService(properties, sequenceRepository, balanceRepository,
                    ledgerRepository, coverageRepository, productDeficitRepository,
                    legacyDeficitRepository, accountOutboxRepository, new ObjectMapper());
        }
    }
}
