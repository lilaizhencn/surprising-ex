package com.surprising.gateway.provider.client;

import com.surprising.gateway.provider.config.GatewayHttpConfiguration;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CustodySubmissionReceiptTest {
    private final String key="custody-wallet-withdrawal:receipt-fixture";
    private final Map<String,Object> request=new java.util.LinkedHashMap<>(Map.of(
            "fundingSource","TENANT_POOL","subject","user:42","chain","ETH","assetSymbol","USDT",
            "toAddress","0xrecipient","amount","22.5","externalReference",key,"confirmed",true));
    { request.put("custodyAddressId",null); }
    private final ObjectMapper json=new ObjectMapper();
    private MockRestServiceServer server;
    private CustodyWalletClient client;

    @BeforeEach void setup() {
        var config=new GatewayProperties();config.getCustodyWallet().setEnabled(true);
        config.getCustodyWallet().setBaseUrl("https://wallet.example.test");
        config.getCustodyWallet().setApiKey("test-key");config.getCustodyWallet().setApiSecret("test-only-secret");
        var rest=new GatewayHttpConfiguration().custodyWalletRestTemplate(config);
        server=MockRestServiceServer.bindTo(rest).build();client=new CustodyWalletClient(config,rest,json);
    }
    private void failedCreate() {
        server.expect(outbound->{
            assertThat(outbound.getMethod()).isEqualTo(HttpMethod.POST);
            assertThat(outbound.getURI().getPath()).isEqualTo("/custody/api/v1/withdrawals");
            assertThat(outbound.getHeaders().getFirst("Idempotency-Key")).isEqualTo(key);
            assertThat(outbound.getHeaders().getFirst("X-Custody-Signature")).isNotBlank();
        }).andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":\"INVALID_STATE\",\"message\":\"new reorg deficit\"}}"));
    }
    private void resolution(Map<String,Object> receipt) {
        server.expect(outbound->{
            assertThat(outbound.getURI().getPath()).isEqualTo("/custody/api/v1/withdrawals/resolve");
            assertThat(outbound.getHeaders().getFirst("Idempotency-Key")).isEqualTo(key);
        }).andRespond(withSuccess(json.writeValueAsString(receipt),MediaType.APPLICATION_JSON));
    }
    @AfterEach void verify() {server.verify();}

    @Test void aLater409CannotRejectAnAlreadyAcceptedPayment() {
        failedCreate();
        resolution(Map.of("status","ACCEPTED","idempotencyKey",key,"requestHash",CustodyWalletClient.withdrawalRequestHash(request),
                "externalReference",key,"withdrawal",Map.of("id","original-payment","externalReference",key,"amount","22.5")));
        assertThat(client.createWithdrawal(42,request,key)).containsEntry("id","original-payment");
    }
    @Test void onlyAnIdentityBoundPermanentRefusalProducesARefundableException() {
        failedCreate();
        resolution(Map.of("status","REJECTED","idempotencyKey",key,"requestHash",CustodyWalletClient.withdrawalRequestHash(request),"externalReference",key));
        assertThatThrownBy(()->client.createWithdrawal(42,request,key)).isInstanceOf(CustodyWalletClient.CustodyWalletRejectedException.class)
                .satisfies(error->{
                    var refusal=(CustodyWalletClient.CustodyWalletRejectedException)error;
                    assertThat(refusal.matches(key,request)).isTrue(); assertThat(refusal.matches("different-key",request)).isFalse();
                    assertThat(refusal.responseBody()).contains("REJECTED",key);
                });
    }
    @Test void aReceiptForDifferentRequestParametersCannotAuthorizeARefund() {
        failedCreate();
        resolution(Map.of("status","REJECTED","idempotencyKey",key,"requestHash","wrong-request","externalReference",key));
        assertThatThrownBy(()->client.createWithdrawal(42,request,key)).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(CustodyWalletClient.CustodyWalletRejectedException.class);
    }
    @Test void untypedRestAmountsRemainExactAndOwnershipUsesAnExactSubjectParameter() {
        server.expect(outbound->{
            assertThat(outbound.getURI().getPath()).isEqualTo("/custody/api/v1/deposits");
            assertThat(outbound.getURI().getRawQuery()).contains("subject=user%3A42").doesNotContain("search=");
        }).andRespond(withSuccess("[{\"subject\":\"user:42\",\"amount\":100000000000.000001}]",MediaType.APPLICATION_JSON));
        Object amount=client.deposits(42,"ETH","USDT",50).getFirst().get("amount");
        assertThat(amount).isInstanceOf(BigDecimal.class);
        assertThat(((BigDecimal)amount).toPlainString()).isEqualTo("100000000000.000001");
    }
}
