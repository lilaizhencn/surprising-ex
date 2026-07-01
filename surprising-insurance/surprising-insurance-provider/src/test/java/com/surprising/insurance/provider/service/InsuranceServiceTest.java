package com.surprising.insurance.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.insurance.provider.repository.InsuranceRepository;
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

    private static final class FakeInsuranceRepository extends InsuranceRepository {
        private int coverCalls;
        private int lastBatchSize;

        private FakeInsuranceRepository() {
            super(null);
        }

        @Override
        public int coverDeficits(int batchSize) {
            coverCalls++;
            lastBatchSize = batchSize;
            return 0;
        }
    }
}
