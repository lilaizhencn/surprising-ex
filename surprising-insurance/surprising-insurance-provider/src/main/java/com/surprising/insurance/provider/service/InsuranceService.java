package com.surprising.insurance.provider.service;

import com.surprising.insurance.api.model.InsuranceCoverageQueryResponse;
import com.surprising.insurance.api.model.InsuranceFundAdjustmentRequest;
import com.surprising.insurance.api.model.InsuranceFundBalanceQueryResponse;
import com.surprising.insurance.api.model.InsuranceFundBalanceResponse;
import com.surprising.insurance.api.model.InsuranceLedgerQueryResponse;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.insurance.provider.repository.InsuranceRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InsuranceService {

    private final InsuranceProperties properties;
    private final InsuranceRepository insuranceRepository;

    public InsuranceService(InsuranceProperties properties, InsuranceRepository insuranceRepository) {
        this.properties = properties;
        this.insuranceRepository = insuranceRepository;
    }

    /**
     * Periodically covers bankruptcy deficits created by account settlement.
     * The transaction boundary must include both the deficit row lock and fund update.
     */
    @Transactional
    @Scheduled(fixedDelayString = "${surprising.insurance.coverage.scan-delay-ms:1000}")
    public void coverDeficits() {
        if (!properties.getCoverage().isEnabled()) {
            return;
        }
        insuranceRepository.coverDeficits(properties.getCoverage().getBatchSize());
    }

    @Transactional
    public InsuranceFundBalanceResponse adjustFund(InsuranceFundAdjustmentRequest request) {
        if (request.amountUnits() == 0) {
            throw new IllegalArgumentException("amountUnits must not be zero");
        }
        return insuranceRepository.adjustFund(normalizeAsset(request.asset()), request.amountUnits(),
                normalizeReferenceId(request.referenceId()), request.reason());
    }

    public InsuranceFundBalanceQueryResponse balances(String asset) {
        var rows = insuranceRepository.balances(asset == null || asset.isBlank() ? null : normalizeAsset(asset));
        return new InsuranceFundBalanceQueryResponse(rows.size(), rows);
    }

    public InsuranceLedgerQueryResponse ledger(String asset, int limit) {
        int capped = Math.max(1, Math.min(1000, limit));
        var rows = insuranceRepository.ledger(asset == null || asset.isBlank() ? null : normalizeAsset(asset), capped);
        return new InsuranceLedgerQueryResponse(rows.size(), rows);
    }

    public InsuranceCoverageQueryResponse coverages(Long userId, String asset, int limit) {
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        int capped = Math.max(1, Math.min(1000, limit));
        var rows = insuranceRepository.coverages(userId,
                asset == null || asset.isBlank() ? null : normalizeAsset(asset), capped);
        return new InsuranceCoverageQueryResponse(rows.size(), rows);
    }

    private String normalizeAsset(String asset) {
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("asset is required");
        }
        String normalized = asset.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("invalid asset: " + asset);
        }
        return normalized;
    }

    private String normalizeReferenceId(String referenceId) {
        if (referenceId == null || referenceId.isBlank()) {
            throw new IllegalArgumentException("referenceId is required");
        }
        String normalized = referenceId.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("referenceId length must be <= 128");
        }
        return normalized;
    }
}
