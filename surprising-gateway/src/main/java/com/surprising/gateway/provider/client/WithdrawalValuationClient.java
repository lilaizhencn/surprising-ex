package com.surprising.gateway.provider.client;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.price.api.model.ExchangeRateConvertResponse;
import com.surprising.price.index.service.ExchangeRateService;
import com.surprising.price.index.utils.CurrencyFormats;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 直接调用进程内 ExchangeRateService 换算 USDT 估值，不再发起本地 HTTP 自调用。 */
@Component
public class WithdrawalValuationClient {

    private final GatewayProperties properties;
    private final ObjectProvider<ExchangeRateService> exchangeRateServiceProvider;

    public WithdrawalValuationClient(GatewayProperties properties,
                                     ObjectProvider<ExchangeRateService> exchangeRateServiceProvider) {
        this.properties = properties;
        this.exchangeRateServiceProvider = exchangeRateServiceProvider;
    }

    public BigDecimal toUsdt(String asset, BigDecimal amount) {
        if (asset == null || asset.isBlank() || amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("asset and positive amount are required");
        }
        ExchangeRateService exchangeRateService = exchangeRateServiceProvider.getIfAvailable();
        if (exchangeRateService == null) {
            throw new ValuationUnavailableException("withdrawal valuation service is unavailable");
        }
        try {
            ExchangeRateConvertResponse response = exchangeRateService.convert(
                    amount, CurrencyFormats.normalize(asset), "USDT");
            if (response == null || response.convertedAmount() == null) {
                throw new ValuationUnavailableException("withdrawal valuation response is empty");
            }
            if (response.convertedAmount().signum() <= 0) {
                throw new ValuationUnavailableException("withdrawal valuation is not positive");
            }
            if (response.rateTime() == null || response.rateTime()
                    .plus(properties.getWithdrawal().getValuationMaxAge()).isBefore(Instant.now())) {
                throw new ValuationUnavailableException("withdrawal valuation is stale");
            }
            return response.convertedAmount();
        } catch (ValuationUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ValuationUnavailableException("withdrawal valuation is unavailable", ex);
        }
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
