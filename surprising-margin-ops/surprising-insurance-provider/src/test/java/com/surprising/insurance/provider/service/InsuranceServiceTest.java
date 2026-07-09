package com.surprising.insurance.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.insurance.api.model.AdminCursorPage;
import com.surprising.insurance.api.model.InsuranceCoverageResponse;
import com.surprising.insurance.api.model.InsuranceFundLedgerResponse;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.insurance.provider.repository.InsuranceRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class InsuranceServiceTest {

    @Test
    void coverDeficitsDoesNothingWhenCoverageIsDisabled() {
        InsuranceProperties properties = new InsuranceProperties();
        properties.getCoverage().setEnabled(false);
        FakeInsuranceRepository repository = new FakeInsuranceRepository();
        InsuranceService service = new InsuranceService(properties, repository);

        service.coverDeficits();

        assertThat(repository.coverCalls).isZero();
    }

    @Test
    void coverDeficitsUsesConfiguredBatchSizeWhenEnabled() {
        InsuranceProperties properties = new InsuranceProperties();
        properties.getCoverage().setBatchSize(17);
        FakeInsuranceRepository repository = new FakeInsuranceRepository();
        InsuranceService service = new InsuranceService(properties, repository);

        service.coverDeficits();

        assertThat(repository.coverCalls).isEqualTo(1);
        assertThat(repository.lastBatchSize).isEqualTo(17);
    }

    @Test
    void ledgerAndCoverageQueriesExposeCursorMetadata() {
        FakeInsuranceRepository repository = new FakeInsuranceRepository();
        InsuranceService service = new InsuranceService(new InsuranceProperties(), repository);

        var ledger = service.ledger("usdt", 50, "ledger-cursor", "createdAt.asc");
        var coverages = service.coverages(1001L, "usdt", 25, "coverage-cursor", "createdAt.asc");

        assertThat(ledger.entries()).hasSize(1);
        assertThat(ledger.nextCursor()).isEqualTo("next-ledger");
        assertThat(ledger.hasMore()).isTrue();
        assertThat(ledger.sort()).isEqualTo("createdAt.asc");
        assertThat(ledger.limit()).isEqualTo(50);
        assertThat(coverages.coverages()).hasSize(1);
        assertThat(coverages.nextCursor()).isEqualTo("next-coverage");
        assertThat(coverages.sort()).isEqualTo("createdAt.asc");
        assertThat(repository.lastLedgerCursor).isEqualTo("ledger-cursor");
        assertThat(repository.lastCoverageCursor).isEqualTo("coverage-cursor");
    }

    private static final class FakeInsuranceRepository extends InsuranceRepository {
        private int coverCalls;
        private int lastBatchSize;
        private String lastLedgerCursor;
        private String lastCoverageCursor;

        private FakeInsuranceRepository() {
            super(null);
        }

        @Override
        public int coverDeficits(int batchSize) {
            coverCalls++;
            lastBatchSize = batchSize;
            return 0;
        }

        @Override
        public AdminCursorPage.CursorPage<InsuranceFundLedgerResponse> ledgerPage(String asset,
                                                                                   int limit,
                                                                                   String cursor,
                                                                                   String sort) {
            lastLedgerCursor = cursor;
            return new AdminCursorPage.CursorPage<>(List.of(new InsuranceFundLedgerResponse(
                    10L, asset, 100L, 100L, "FUND_ADJUSTMENT", "ref-1", "seed",
                    Instant.parse("2026-07-01T00:00:00Z"))), "next-ledger", true, sort, limit);
        }

        @Override
        public AdminCursorPage.CursorPage<InsuranceCoverageResponse> coveragesPage(Long userId,
                                                                                    String asset,
                                                                                    int limit,
                                                                                    String cursor,
                                                                                    String sort) {
            lastCoverageCursor = cursor;
            Instant now = Instant.parse("2026-07-01T00:00:00Z");
            return new AdminCursorPage.CursorPage<>(List.of(new InsuranceCoverageResponse(
                    20L, userId, asset, 500L, 400L, 100L, "PARTIALLY_COVERED", "DEFICIT_COVERAGE",
                    now, now)), "next-coverage", true, sort, limit);
        }
    }
}
