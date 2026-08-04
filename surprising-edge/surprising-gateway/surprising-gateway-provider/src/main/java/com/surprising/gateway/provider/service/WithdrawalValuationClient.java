package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class WithdrawalValuationClient {

    private final GatewayProperties properties;
    private final RestTemplate restTemplate;

    public WithdrawalValuationClient(GatewayProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    public BigDecimal toUsdt(String asset, BigDecimal amount) {
        if (asset == null || asset.isBlank() || amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("asset and positive amount are required");
        }
        GatewayProperties.Withdrawal config = properties.getWithdrawal();
        String baseUrl = config.getValuationBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ValuationUnavailableException("withdrawal valuation endpoint is not configured");
        }
        String url = trimTrailingSlash(baseUrl) + "/api/v1/price/fx/convert?amount="
                + java.net.URLEncoder.encode(amount.toPlainString(), java.nio.charset.StandardCharsets.UTF_8)
                + "&fromCurrency="
                + java.net.URLEncoder.encode(asset.trim().toUpperCase(), java.nio.charset.StandardCharsets.UTF_8)
                + "&toCurrency=USDT";
        try {
            Map<String, Object> response = restTemplate.exchange(
                    URI.create(url), HttpMethod.GET, null,
                    new ParameterizedTypeReference<Map<String, Object>>() {}).getBody();
            if (response == null || response.get("convertedAmount") == null) {
                throw new ValuationUnavailableException("withdrawal valuation response is empty");
            }
            BigDecimal converted = new BigDecimal(String.valueOf(response.get("convertedAmount")));
            if (converted.signum() <= 0) {
                throw new ValuationUnavailableException("withdrawal valuation is not positive");
            }
            Object rateTime = response.get("rateTime");
            if (rateTime == null || Instant.parse(String.valueOf(rateTime))
                    .plus(config.getValuationMaxAge()).isBefore(Instant.now())) {
                throw new ValuationUnavailableException("withdrawal valuation is stale");
            }
            return converted;
        } catch (ValuationUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ValuationUnavailableException("withdrawal valuation is unavailable", ex);
        }
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public static class ValuationUnavailableException extends IllegalStateException {
        public ValuationUnavailableException(String message) {
            super(message);
        }

        public ValuationUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
