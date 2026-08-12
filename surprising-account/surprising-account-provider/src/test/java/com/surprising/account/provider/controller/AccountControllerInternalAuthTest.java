package com.surprising.account.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.ProductTransferRecordQueryResponse;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.account.provider.service.AccountService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AccountControllerInternalAuthTest {

    private static final String SERVICE = "surprising-gateway";
    private static final String SECRET = "account-internal-secret-for-tests-32";

    private final AccountCommandGateway commandGateway = mock(AccountCommandGateway.class);
    private final AccountService accountService = mock(AccountService.class);
    private final AccountProperties properties = new AccountProperties();
    private final BalanceAdjustmentRequest request = new BalanceAdjustmentRequest(
            42L, "USDT", 1_250_000L, "custody-wallet:event-1:deposit.confirmed", "custody wallet deposit");
    private AccountController controller;

    @BeforeEach
    void setUp() {
        properties.setInternalServiceSecret(SECRET);
        controller = new AccountController(accountService, commandGateway, properties);
    }

    @Test
    void acceptsMatchingInternalSignature() {
        long timestamp = Instant.now().getEpochSecond();

        controller.adjustBalance(SERVICE, Long.toString(timestamp),
                signature(timestamp, request), request);

        verify(commandGateway).adjustBalance(request, null, null);
    }

    @Test
    void rejectsExpiredInternalSignature() {
        long timestamp = Instant.now().minusSeconds(301).getEpochSecond();

        assertThatThrownBy(() -> controller.adjustBalance(SERVICE, Long.toString(timestamp),
                signature(timestamp, request), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("timestamp is expired");
    }

    @Test
    void rejectsTamperedInternalSignature() {
        long timestamp = Instant.now().getEpochSecond();

        assertThatThrownBy(() -> controller.adjustBalance(SERVICE, Long.toString(timestamp),
                signature(timestamp, request) + "x", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("signature is invalid");
    }

    @Test
    void rejectsWhitespaceAroundInternalIdentityHeaders() {
        long timestamp = Instant.now().getEpochSecond();

        assertThatThrownBy(() -> controller.adjustBalance(" " + SERVICE,
                Long.toString(timestamp), signature(timestamp, request), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authentication is required");
    }

    @Test
    void exposesUserProductTransferRecordsThroughAccountGateway() {
        ProductTransferRecordQueryResponse expected = new ProductTransferRecordQueryResponse(
                0, List.of(), null, false, "createdAt.desc", 50);
        when(accountService.productTransfers(42L, AccountType.SPOT, "USDT", 50, null, null))
                .thenReturn(expected);

        ProductTransferRecordQueryResponse actual = controller.userProductTransfers(
                42L, AccountType.SPOT, "USDT", 50, null, null);

        assertThat(actual).isSameAs(expected);
        verify(accountService).productTransfers(42L, AccountType.SPOT, "USDT", 50, null, null);
    }

    private String signature(long timestamp, BalanceAdjustmentRequest value) {
        String canonical = SERVICE + "\n" + timestamp + "\n" + value.userId() + "\n"
                + value.asset().toUpperCase() + "\n" + value.amountUnits() + "\n"
                + value.referenceId() + "\n" + value.reason();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "v1=" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
