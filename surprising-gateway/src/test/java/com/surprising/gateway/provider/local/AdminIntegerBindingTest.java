package com.surprising.gateway.provider.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.api.model.ProductBalanceAdjustmentRequest;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.trading.api.model.AdminBatchCancelOrdersRequest;
import com.surprising.trading.api.model.FeeScheduleUpsertRequest;
import com.surprising.trading.api.model.LeverageSettingRequest;
import jakarta.validation.Validation;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 使用应用真实YAML与Mapper验证管理端的十进制字符串契约，不构造平行wire模型。 */
class AdminIntegerBindingTest {
    private static final long LARGE_ID = 9_007_199_254_740_993L;
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    @ParameterizedTest
    @ValueSource(strings = {"9007199254740993", "9223372036854775807", "-9223372036854775808"})
    void accountAmountStringsAreExactThroughTheActualLocalRequestBinder(String units) {
        context.run(application -> {
            var json = application.getBean(ObjectMapper.class);
            var headers = new HttpHeaders();
            headers.set("X-Admin-User-Id", "123");
            String body = """
                    {"userId":"9007199254740993","asset":"USDT","amountUnits":"%s",
                     "referenceId":"admin-integer-boundary","reason":"test"}
                    """.formatted(units);
            try (var validators = Validation.buildDefaultValidatorFactory()) {
                var request = new LocalApiRequest(URI.create("/api/v1/admin/accounts/balance-adjustments"),
                        HttpMethod.POST, headers, body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        json, validators.getValidator());
                var adjustment = request.body(BalanceAdjustmentRequest.class, true, true);
                assertThat(adjustment.userId()).isEqualTo(LARGE_ID);
                assertThat(adjustment.amountUnits()).isEqualTo(Long.parseLong(units));
                var product = json.readValue(body.replace("\"asset\"", "\"accountType\":\"USDT_PERPETUAL\",\"asset\""),
                        ProductBalanceAdjustmentRequest.class);
                assertThat(product.userId()).isEqualTo(LARGE_ID);
                assertThat(product.amountUnits()).isEqualTo(Long.parseLong(units));
            }
        });
    }

    @Test
    void orderLeverageFeeAndInstrumentDtosPreserveStringIntegers() {
        context.run(application -> {
            var json = application.getBean(ObjectMapper.class);
            var cancel = json.readValue("{\"userId\":\"9007199254740993\",\"instrumentId\":\"604\",\"limit\":100}",
                    AdminBatchCancelOrdersRequest.class);
            assertThat(cancel.userId()).isEqualTo(LARGE_ID);
            var leverage = json.readValue("{\"userId\":\"9007199254740993\",\"instrumentId\":\"604\",\"leveragePpm\":1000000}",
                    LeverageSettingRequest.class);
            assertThat(leverage.userId()).isEqualTo(LARGE_ID);
            var fee = json.readValue("{\"feeScheduleId\":\"9223372036854775807\",\"userId\":\"9007199254740993\",\"makerFeeRatePpm\":0,\"takerFeeRatePpm\":0}",
                    FeeScheduleUpsertRequest.class);
            assertThat(fee.feeScheduleId()).isEqualTo(Long.MAX_VALUE);
            assertThat(fee.userId()).isEqualTo(LARGE_ID);
            var instrumentBody = json.createObjectNode()
                    .put("instrumentId", "604").put("baseAssetId", "1")
                    .put("priceTickUnits", "9007199254740993").put("strikePriceUnits", "9223372036854775807");
            // 这里只验证绑定精度，补齐其余必需原生字段；业务规则由已有合约边界测试覆盖。
            for (var component : InstrumentUpsertRequest.class.getRecordComponents()) {
                if (!component.getType().isPrimitive() || instrumentBody.has(component.getName())) continue;
                if (component.getType() == boolean.class) instrumentBody.put(component.getName(), false);
                else instrumentBody.put(component.getName(), 0);
            }
            var instrument = json.treeToValue(instrumentBody, InstrumentUpsertRequest.class);
            assertThat(instrument.instrumentId()).isEqualTo(604);
            assertThat(instrument.baseAssetId()).isEqualTo(1);
            assertThat(instrument.priceTickUnits()).isEqualTo(LARGE_ID);
            assertThat(instrument.strikePriceUnits()).isEqualTo(Long.MAX_VALUE);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "\"1.5\"", "9223372036854775808", "\"9223372036854775808\"",
            "-9223372036854775809", "\"-9223372036854775809\""})
    void fractionalOrOverflowingIntegerFieldsAreRejected(String invalid) {
        context.run(application -> {
            var json = application.getBean(ObjectMapper.class);
            String body = "{\"userId\":\"9007199254740993\",\"asset\":\"USDT\",\"amountUnits\":" + invalid
                    + ",\"referenceId\":\"admin-rejected-integer\"}";
            assertThatThrownBy(() -> json.readValue(body, BalanceAdjustmentRequest.class))
                    .isInstanceOf(JacksonException.class);
            assertThatThrownBy(() -> json.readValue("{\"userId\":" + invalid + "}", AdminBatchCancelOrdersRequest.class))
                    .isInstanceOf(JacksonException.class);
            try (var validators = Validation.buildDefaultValidatorFactory()) {
                var request = new LocalApiRequest(URI.create("/api/v1/admin/accounts/balance-adjustments"),
                        HttpMethod.POST, new HttpHeaders(), body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        json, validators.getValidator());
                assertThatThrownBy(() -> request.body(BalanceAdjustmentRequest.class, true, true))
                        .isInstanceOfSatisfying(ResponseStatusException.class,
                                rejected -> assertThat(rejected.getStatusCode().value()).isEqualTo(400));
            }
        });
    }
}
