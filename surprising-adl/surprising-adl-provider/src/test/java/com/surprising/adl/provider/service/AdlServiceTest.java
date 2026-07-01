package com.surprising.adl.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.adl.api.model.AdlEventResponse;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.adl.provider.model.AdlCandidate;
import com.surprising.adl.provider.model.DeficitRow;
import com.surprising.adl.provider.repository.AdlRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdlServiceTest {

    @Test
    void processResidualDeficitsDoesNothingWhenScannerIsDisabled() {
        AdlProperties properties = new AdlProperties();
        properties.getScanner().setEnabled(false);
        FakeAdlRepository repository = new FakeAdlRepository();
        AdlService service = new AdlService(properties, repository);

        service.processResidualDeficits();

        assertThat(repository.claimCalls).isZero();
        assertThat(repository.executions).isZero();
    }

    @Test
    void processResidualDeficitsClaimsWithConfiguredScannerLimitsWhenEnabled() {
        AdlProperties properties = new AdlProperties();
        properties.getScanner().setBatchSize(3);
        properties.getScanner().setMinDeficitAgeMs(2500L);
        FakeAdlRepository repository = new FakeAdlRepository();
        AdlService service = new AdlService(properties, repository);

        service.processResidualDeficits();

        assertThat(repository.claimCalls).isEqualTo(1);
        assertThat(repository.lastBatchSize).isEqualTo(3);
        assertThat(repository.lastMinAge).isEqualTo(Duration.ofMillis(2500L));
    }

    private static final class FakeAdlRepository extends AdlRepository {
        private int claimCalls;
        private int executions;
        private int lastBatchSize;
        private Duration lastMinAge;

        private FakeAdlRepository() {
            super(null);
        }

        @Override
        public List<DeficitRow> claimResidualDeficits(int batchSize, Duration minAge) {
            claimCalls++;
            lastBatchSize = batchSize;
            lastMinAge = minAge;
            return List.of();
        }

        @Override
        public List<AdlCandidate> queue(String asset, long excludedUserId, int limit, Duration maxMarkAge) {
            return List.of();
        }

        @Override
        public Optional<AdlCandidate> lockCandidate(long userId, String symbol, String asset, Duration maxMarkAge) {
            return Optional.empty();
        }

        @Override
        public long executeAdl(DeficitRow deficit, AdlCandidate candidate, long remainingDeficitUnits) {
            executions++;
            return remainingDeficitUnits;
        }

        @Override
        public List<AdlEventResponse> events(Long userId, String asset, String symbol, int limit) {
            return List.of();
        }
    }
}
