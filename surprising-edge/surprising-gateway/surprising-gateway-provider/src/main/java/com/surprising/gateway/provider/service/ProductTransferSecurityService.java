package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.auth.SensitiveActionVerificationService;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProductTransferSecurityService {

    private static final BigDecimal ACCOUNT_UNIT = new BigDecimal("100000000");
    private final GatewayProperties properties;
    private final WithdrawalValuationClient valuationClient;
    private final SensitiveActionVerificationService verificationService;
    private final ObjectMapper objectMapper;

    public ProductTransferSecurityService(GatewayProperties properties,
                                          WithdrawalValuationClient valuationClient,
                                          SensitiveActionVerificationService verificationService,
                                          ObjectMapper objectMapper) {
        this.properties = properties;
        this.valuationClient = valuationClient;
        this.verificationService = verificationService;
        this.objectMapper = objectMapper;
    }

    public void requireIfNeeded(long userId, byte[] body, String emailCode, String totpCode, Instant now) {
        TransferAmount transfer = transferAmount(body);
        BigDecimal threshold = properties.getProductTransfer().getVerificationThresholdUsdt();
        if (threshold.signum() > 0) {
            BigDecimal usdtValue;
            try {
                usdtValue = valuationClient.toUsdt(transfer.asset(), transfer.amountUnits()
                        .divide(ACCOUNT_UNIT));
            } catch (WithdrawalValuationClient.ValuationUnavailableException ex) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "transfer valuation is unavailable", ex);
            }
            if (usdtValue.compareTo(threshold) < 0) return;
        }
        if (!verificationService.verify(userId, "LARGE_TRANSFER", emailCode, totpCode, now)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "large transfer security verification is required");
        }
    }

    private TransferAmount transferAmount(byte[] body) {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("transfer body is required");
        }
        try {
            Map<?, ?> payload = objectMapper.readValue(body, Map.class);
            String asset = payload.get("asset") == null ? "" : String.valueOf(payload.get("asset")).trim();
            if (asset.isBlank()) throw new IllegalArgumentException("transfer asset is required");
            BigDecimal amountUnits = new BigDecimal(String.valueOf(payload.get("amountUnits")));
            if (amountUnits.signum() <= 0 || amountUnits.scale() > 0) {
                throw new IllegalArgumentException("transfer amountUnits must be a positive integer");
            }
            return new TransferAmount(asset, amountUnits);
        } catch (JacksonException | NumberFormatException ex) {
            throw new IllegalArgumentException("transfer amount is invalid", ex);
        }
    }

    private record TransferAmount(String asset, BigDecimal amountUnits) {
    }
}
