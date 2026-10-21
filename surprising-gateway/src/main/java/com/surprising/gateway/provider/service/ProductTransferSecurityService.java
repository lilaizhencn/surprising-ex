package com.surprising.gateway.provider.service;


import com.surprising.gateway.provider.auth.service.SensitiveActionVerificationService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.asset.repository.AssetRepository;
import java.math.BigDecimal;
import java.time.Instant;
import com.surprising.gateway.provider.exception.ProductTransferSecurityException;
import org.springframework.stereotype.Service;

@Service
public class ProductTransferSecurityService {

    private final GatewayProperties properties;
    private final WithdrawalValuationClient valuationClient;
    private final SensitiveActionVerificationService verificationService;
    private final AssetRepository assets;

    public ProductTransferSecurityService(GatewayProperties properties,
                                          WithdrawalValuationClient valuationClient,
                                          SensitiveActionVerificationService verificationService,
                                          AssetRepository assets) {
        this.properties = properties;
        this.valuationClient = valuationClient;
        this.verificationService = verificationService;
        this.assets = assets;
    }

    public void requireIfNeeded(long userId, com.surprising.gateway.provider.model.ProductTransferAmount transfer, String emailCode, String totpCode, Instant now) {
        long scale = assets.byAccountingCode(transfer.asset())
                .orElseThrow(() -> new IllegalArgumentException("transfer asset is not supported"))
                .scaleUnits();
        BigDecimal threshold = properties.getProductTransfer().getVerificationThresholdUsdt();
        if (threshold.signum() > 0) {
            BigDecimal usdtValue;
            try {
                usdtValue = valuationClient.toUsdt(transfer.asset(), BigDecimal.valueOf(transfer.amountUnits())
                        .divide(BigDecimal.valueOf(scale)));
            } catch (WithdrawalValuationClient.ValuationUnavailableException ex) {
                throw new ProductTransferSecurityException(ProductTransferSecurityException.Reason.VALUATION_UNAVAILABLE,
                        "transfer valuation is unavailable", ex);
            }
            if (usdtValue.compareTo(threshold) < 0) return;
        }
        if (!verificationService.verify(userId, "LARGE_TRANSFER", emailCode, totpCode, now)) {
            throw new ProductTransferSecurityException(ProductTransferSecurityException.Reason.VERIFICATION_REQUIRED,
                    "large transfer security verification is required");
        }
    }

}
