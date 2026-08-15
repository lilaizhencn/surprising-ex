package com.surprising.insurance.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.insurance.api.model.AdminCursorPage;
import com.surprising.insurance.api.model.InsuranceCoverageResponse;
import com.surprising.insurance.api.model.InsuranceFundAdjustmentRequest;
import com.surprising.insurance.api.model.InsuranceFundLedgerResponse;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.insurance.provider.model.CoreLiquidationProjection;
import com.surprising.insurance.provider.repository.CoreInsuranceProjectionRepository;
import com.surprising.insurance.provider.repository.InsuranceCoverageRepository;
import com.surprising.insurance.provider.repository.InsuranceFundLedgerRepository;
import com.surprising.insurance.provider.repository.InsuranceSequenceRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class InsuranceServiceTest {

    @Test
    void disabledCoverageDoesNotReadProjection() {
        InsuranceProperties properties = new InsuranceProperties();
        properties.getCoverage().setEnabled(false);
        Fixture fixture = new Fixture(properties);

        fixture.service.coverDeficits();

        verify(fixture.projectionRepository, never()).pendingInsurance(any(), anyInt());
    }

    @Test
    void coversProjectionWithOneAeronCommandAndSynchronousAudit() {
        Fixture fixture = new Fixture(new InsuranceProperties());
        var deficit = new CoreLiquidationProjection(81, 4004, "USDT", 1_000);
        when(fixture.projectionRepository.pendingInsurance("LINEAR_PERPETUAL", 100))
                .thenReturn(List.of(deficit));
        when(fixture.aeron.balance("USDT")).thenReturn(600L, 0L);
        when(fixture.sequenceRepository.next("insurance-coverage")).thenReturn(9501L);
        when(fixture.sequenceRepository.next("insurance-ledger")).thenReturn(9502L);
        when(fixture.ledgerRepository.insert(anyLong(), any(), any(), anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(true);

        fixture.service.coverDeficits();

        verify(fixture.aeron).command(eq(CoreMessageType.RESOLVE_LIQUIDATION), any(), any());
        verify(fixture.coverageRepository).insertCompleted(eq(9501L), eq("USDT_PERPETUAL"), eq(deficit),
                eq(600L), eq(400L), any(Instant.class));
        verify(fixture.ledgerRepository).insert(eq(9502L), eq("USDT_PERPETUAL"), eq("USDT"), eq(-600L),
                eq(0L), eq("DEFICIT_COVERAGE"), eq("81"), eq("COVER_LIQUIDATION_DEFICIT"), any());
    }

    @Test
    void fundAdjustmentUsesAeronBalanceAndStableCommand() {
        Fixture fixture = new Fixture(new InsuranceProperties());
        when(fixture.aeron.balance("USDT")).thenReturn(1_000L, 1_500L);
        when(fixture.sequenceRepository.next("insurance-ledger")).thenReturn(101L);
        when(fixture.ledgerRepository.insert(anyLong(), any(), any(), anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(true);

        var response = fixture.service.adjustFund(
                new InsuranceFundAdjustmentRequest("USDT", 500, "ops-1", "INITIAL_FUND"));

        assertThat(response.balanceUnits()).isEqualTo(1_500L);
        verify(fixture.aeron).command(eq(CoreMessageType.ADJUST_INSURANCE_FUND), any(), any());
    }

    @Test
    void ledgerAndCoverageQueriesExposeCursorMetadata() {
        Fixture fixture = new Fixture(new InsuranceProperties());
        when(fixture.ledgerRepository.page("USDT_PERPETUAL", "USDT", 50,
                "ledger-cursor", "createdAt.asc")).thenReturn(new AdminCursorPage.CursorPage<>(
                List.of(new InsuranceFundLedgerResponse(10, "USDT", 100, 100,
                        "FUND_ADJUSTMENT", "ref-1", "seed", Instant.EPOCH)),
                "next-ledger", true, "createdAt.asc", 50));
        when(fixture.coverageRepository.page("USDT_PERPETUAL", 1001L, "USDT", 25,
                "coverage-cursor", "createdAt.asc")).thenReturn(new AdminCursorPage.CursorPage<>(
                List.of(new InsuranceCoverageResponse(20, 1001, "USDT", 500, 400, 100,
                        "PARTIALLY_COVERED", "AERON_LIQUIDATION_COVERAGE", Instant.EPOCH, Instant.EPOCH)),
                "next-coverage", true, "createdAt.asc", 25));

        assertThat(fixture.service.ledger("usdt", 50, "ledger-cursor", "createdAt.asc").nextCursor())
                .isEqualTo("next-ledger");
        assertThat(fixture.service.coverages(1001L, "usdt", 25,
                "coverage-cursor", "createdAt.asc").nextCursor()).isEqualTo("next-coverage");
    }

    private static final class Fixture {
        private final InsuranceSequenceRepository sequenceRepository = mock(InsuranceSequenceRepository.class);
        private final InsuranceFundLedgerRepository ledgerRepository = mock(InsuranceFundLedgerRepository.class);
        private final InsuranceCoverageRepository coverageRepository = mock(InsuranceCoverageRepository.class);
        private final CoreInsuranceProjectionRepository projectionRepository =
                mock(CoreInsuranceProjectionRepository.class);
        private final InsuranceAeronGateway aeron = mock(InsuranceAeronGateway.class);
        private final InsuranceService service;

        private Fixture(InsuranceProperties properties) {
            service = new InsuranceService(properties, sequenceRepository, ledgerRepository,
                    coverageRepository, projectionRepository, aeron);
        }
    }
}
