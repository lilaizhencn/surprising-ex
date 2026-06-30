package com.surprising.price.api.client;

import com.surprising.price.api.PriceApiPaths;
import com.surprising.price.api.model.ExchangeRateConvertResponse;
import com.surprising.price.api.model.ExchangeRateQueryResponse;
import com.surprising.price.api.model.ExchangeRateResponse;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "surprising-index-price-provider", contextId = "exchangeRateRpcApi")
@RequestMapping(PriceApiPaths.EXCHANGE_RATE_BASE_PATH)
public interface ExchangeRateRpcApi {

    @GetMapping("/latest")
    ExchangeRateResponse latest(
            @RequestParam("baseCurrency") @NotBlank String baseCurrency,
            @RequestParam("quoteCurrency") @NotBlank String quoteCurrency);

    @GetMapping("/rates")
    ExchangeRateQueryResponse rates(@RequestParam("baseCurrency") @NotBlank String baseCurrency);

    @GetMapping("/convert")
    ExchangeRateConvertResponse convert(
            @RequestParam("amount") @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount,
            @RequestParam("fromCurrency") @NotBlank String fromCurrency,
            @RequestParam("toCurrency") @NotBlank String toCurrency);
}
