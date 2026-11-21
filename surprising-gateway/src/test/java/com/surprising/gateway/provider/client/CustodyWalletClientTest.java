package com.surprising.gateway.provider.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

class CustodyWalletClientTest {

    @Test
    void historyRequiresTheExactAuthenticatedUserSubject() {
        GatewayProperties properties = walletProperties("https://wallet.example.test");
        RestClientStub rest = RestClientStub.create();
        Map<String, Object> own = Map.of("subject", "user:42", "amount", "1");
        rest.respond(ResponseEntity.ok(List.of(
                own, Map.of("subject", "user:420", "amount", "99"), Map.of("amount", "5"))));
        CustodyWalletClient client = new CustodyWalletClient(properties, rest.client(), new ObjectMapper());

        assertThat(client.deposits(42L, "ETH", "USDT", 50)).containsExactly(own);
        assertThat(client.withdrawals(42L, "ETH", "USDT", 50)).containsExactly(own);
    }

    private final CustodyWalletClient client = new CustodyWalletClient(
            new GatewayProperties(), RestClientStub.create().client(), new ObjectMapper());

    @Test
    void buildsWalletCompatibleCanonicalRequestAndSignature() {
        String canonical = client.canonicalRequest(1_754_320_000L, "nonce-1234567890AB", "post",
                "/custody/api/v1/withdrawals", "{}".getBytes());

        assertThat(canonical).contains("1754320000\nnonce-1234567890AB\nPOST\n/custody/api/v1/withdrawals\n");
        assertThat(client.sign("secret", canonical)).isNotBlank();
    }

    @Test
    void rejectsInvalidWalletSubjectAndIdempotencyKey() {
        assertThatThrownBy(() -> client.subject(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.createWithdrawal(42L, java.util.Map.of(), "bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
    }

    @Test
    void mapsDeterministicWalletHttpRejectionToRejectedException() {
        RestClientStub rest = RestClientStub.create();
        rest.respond(ResponseEntity.badRequest().body(Map.of("error", "rejected")));
        CustodyWalletClient rejectedClient = new CustodyWalletClient(walletProperties("https://wallet.example.com"), rest.client(), new ObjectMapper());

        assertThatThrownBy(() -> rejectedClient.createWithdrawal(42L, Map.of("amount", "1"), "withdraw-1"))
                .isInstanceOf(IllegalStateException.class).isNotInstanceOf(CustodyWalletClient.CustodyWalletRejectedException.class);
    }

    @Test
    void invalidState409WithoutAPermanentReceiptRemainsUnknown() {
        RestClientStub rest = RestClientStub.create();
        rest.respond(ResponseEntity.status(409).body(Map.of("error", Map.of(
                "code", "INVALID_STATE", "message", "insufficient gas balance"))));
        CustodyWalletClient rejectedClient = new CustodyWalletClient(walletProperties("https://wallet.example.com"), rest.client(), new ObjectMapper());

        assertThatThrownBy(() -> rejectedClient.createWithdrawal(42L,Map.of("amount","1"),"withdraw-1"))
                .isInstanceOf(IllegalStateException.class).isNotInstanceOf(CustodyWalletClient.CustodyWalletRejectedException.class);
    }

    @Test
    void keepsCustodyServerErrorAsUnknownInsteadOfRejected() {
        RestClientStub rest = RestClientStub.create();
        rest.respond(ResponseEntity.internalServerError().body(Map.of("error", "unknown")));
        CustodyWalletClient unknownClient = new CustodyWalletClient(walletProperties("https://wallet.example.com"), rest.client(), new ObjectMapper());

        assertThatThrownBy(() -> unknownClient.createWithdrawal(42L, Map.of("amount", "1"), "withdraw-1"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(CustodyWalletClient.CustodyWalletRejectedException.class);
    }

    @Test
    void keepsAmbiguousClientErrorsAsUnknownInsteadOfRefundableRejections() {
        RestClientStub rest = RestClientStub.create();
        CustodyWalletClient unknownClient = new CustodyWalletClient(walletProperties("https://wallet.example.com"), rest.client(), new ObjectMapper());
        for (int status : List.of(401, 403, 409, 429)) {
            rest.respond(ResponseEntity.status(status).body(Map.of("error", "ambiguous")));
            assertThatThrownBy(() -> unknownClient.createWithdrawal(42L, Map.of("amount", "1"), "withdraw-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(CustodyWalletClient.CustodyWalletRejectedException.class);
        }
    }

    @Test
    void businessInvalidStateIsNotRefundEvidence() {
        RestClientStub rest = RestClientStub.create();
        rest.fail(new org.springframework.web.client.HttpClientErrorException(
                org.springframework.http.HttpStatus.CONFLICT, "Conflict", """
                {"error":{"code":"INVALID_STATE","message":"insufficient gas balance"}}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8));
        CustodyWalletClient rejectedClient = new CustodyWalletClient(walletProperties("https://wallet.example.com"), rest.client(), new ObjectMapper());

        assertThatThrownBy(() -> rejectedClient.createWithdrawal(42L, Map.of("amount", "1"), "withdraw-1"))
                .isInstanceOf(IllegalStateException.class).isNotInstanceOf(CustodyWalletClient.CustodyWalletRejectedException.class);
    }

    @Test
    void keepsWalletIdempotencyProcessing409AsUnknown() {
        RestClientStub rest = RestClientStub.create();
        rest.fail(new org.springframework.web.client.HttpClientErrorException(
                org.springframework.http.HttpStatus.CONFLICT, "Conflict", """
                {"error":{"code":"WITHDRAWAL_ALREADY_PROCESSING","message":"still processing"}}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8));
        CustodyWalletClient unknownClient = new CustodyWalletClient(walletProperties("https://wallet.example.com"), rest.client(), new ObjectMapper());

        assertThatThrownBy(() -> unknownClient.createWithdrawal(42L, Map.of("amount", "1"), "withdraw-1"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(CustodyWalletClient.CustodyWalletRejectedException.class);
    }

    private static GatewayProperties walletProperties(String baseUrl) {
        GatewayProperties properties = new GatewayProperties();
        properties.getCustodyWallet().setEnabled(true);
        properties.getCustodyWallet().setBaseUrl(baseUrl);
        properties.getCustodyWallet().setApiKey("wallet-key");
        properties.getCustodyWallet().setApiSecret("wallet-secret");
        return properties;
    }
}
