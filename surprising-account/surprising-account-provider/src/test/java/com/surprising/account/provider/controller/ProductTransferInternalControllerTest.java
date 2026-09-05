package com.surprising.account.provider.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.surprising.account.api.AccountApiPaths;
import com.surprising.account.api.ProductTransferInternalAuth;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ProductTransferInternalControllerTest {

    private static final String SECRET = "account-internal-secret-for-tests-32";

    @Test
    void acceptsOnlyTheSignatureForTheExactTransferPhaseAudience() {
        AccountCommandGateway commands = mock(AccountCommandGateway.class);
        AccountProperties properties = new AccountProperties();
        properties.setInternalServiceSecret(SECRET);
        properties.getKafka().setProductLine(ProductLine.SPOT);
        ProductTransferInternalController controller = new ProductTransferInternalController(commands, properties);
        ProductTransferOperationRequest request = operation();
        long timestamp = Instant.now().getEpochSecond();
        String canonical = ProductTransferInternalAuth.canonical(
                AccountApiPaths.TRANSFER_OUT_PATH, timestamp, request);

        controller.transferOut(ProductTransferInternalAuth.SERVICE, Long.toString(timestamp), sign(canonical),
                AccountApiPaths.TRANSFER_OUT_PATH, request);

        verify(commands).transferOut(request);
        assertThatThrownBy(() -> controller.transferOut(ProductTransferInternalAuth.SERVICE,
                Long.toString(timestamp), sign(canonical), AccountApiPaths.TRANSFER_IN_PATH, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("identity is invalid");
    }

    private ProductTransferOperationRequest operation() {
        return new ProductTransferOperationRequest(7001L, 42L, ProductLine.SPOT,
                ProductLine.LINEAR_PERPETUAL, AccountType.FUNDING, AccountType.USDT_PERPETUAL,
                "USDT", 1_250L, "transfer-7001", "allocation");
    }

    private String sign(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "v1=" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
