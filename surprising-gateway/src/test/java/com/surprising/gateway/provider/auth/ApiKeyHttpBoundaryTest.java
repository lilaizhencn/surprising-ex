package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.gateway.provider.auth.controller.ApiKeyRequestBinding;
import com.surprising.gateway.provider.auth.model.ApiKeyAuthenticationRequest;
import com.surprising.gateway.provider.auth.repository.GatewayApiKeyRepository;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.GatewayApiKeyService;
import com.surprising.gateway.provider.auth.service.TotpService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.controller.BinanceApiController;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class ApiKeyHttpBoundaryTest {
    String apiKey="sx_"+"a".repeat(24);
    GatewayProperties properties=new GatewayProperties();
    GatewayApiKeyRepository repository=mock(GatewayApiKeyRepository.class);
    AuthService auth=mock(AuthService.class);
    TotpService totp=new TotpService(properties);
    GatewayApiKeyService service=new GatewayApiKeyService(repository,auth,totp,null,properties, org.mockito.Mockito.mock(com.surprising.gateway.provider.auth.port.CurrentAuthClient.class));

    @Test
    void blockedIpMapsToOriginal403WithoutMarkingTheKeyUsed() {
        var request=request();
        when(repository.active(apiKey)).thenReturn(Optional.of(record()));
        doThrow(new IllegalStateException("device or IP is blocked for this account"))
                .when(auth).requireApiKeyAccess(42L,"203.0.113.7","READ");
        var response=controller(service).handle(request,null);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(new String(response.getBody(),java.nio.charset.StandardCharsets.UTF_8)).contains("blocked", "-1000");
        verify(repository,never()).markUsed(any(),any());
    }

    @ParameterizedTest
    @ValueSource(strings={"bad-timestamp", "0-window", "60001-window", "missing-signature"})
    void malformedWireFieldsNeverReachKeyBusiness(String invalid) {
        var request=request();var business=mock(GatewayApiKeyService.class);
        switch(invalid) {
            case "bad-timestamp" -> request.setParameter("timestamp","9223372036854775808");
            case "0-window" -> request.setParameter("recvWindow","0");
            case "60001-window" -> request.setParameter("recvWindow","60001");
            default -> request.removeParameter("signature");
        }
        request.setQueryString(null);
        assertThat(controller(business).handle(request,null).getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(business);
    }

    @ParameterizedTest
    @ValueSource(longs={Long.MIN_VALUE,Long.MAX_VALUE,0})
    void extremeTimestampsCannotPassFreshnessByOverflow(long timestamp) {
        when(repository.active(apiKey)).thenReturn(Optional.of(record()));
        String canonical="timestamp="+timestamp;
        var input=new ApiKeyAuthenticationRequest(apiKey,"203.0.113.7",timestamp,5000,
                service.sign("secret",canonical),canonical);
        assertThatThrownBy(()->service.authenticate(input,"READ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside recvWindow");
        verify(repository,never()).markUsed(any(),any());
    }

    @Test
    void rawEncodingDuplicateKeysAndQueryBodyConcatenationRemainSignedExactly() {
        var request=request();String query="timestamp="+System.currentTimeMillis()+"&symbol=BTC%2FUSDT&symbol=ETH%2fUSDT";
        String body="side=BUY&note=a+b";
        String canonical=query+"side=BUY&note=a+b";
        String signature=service.sign("secret",canonical);
        request.setQueryString(query+"&signature="+signature);request.setParameter("signature",signature);
        var input=new ApiKeyRequestBinding(properties).bind(request,body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(input.canonical()).isEqualTo(canonical);
        when(repository.active(apiKey)).thenReturn(Optional.of(record()));
        assertThat(service.authenticate(input,"READ")).isEqualTo(42L);
        verify(repository).markUsed(any(),any());
    }

    @Test
    void authenticatesFormOnlyRequestsAfterTheServletInputHasBeenConsumed() {
        var request = new MockHttpServletRequest("POST", "/api/v3/order");
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-MBX-APIKEY", apiKey);
        String canonical = "symbol=BTCUSDT&timestamp=" + System.currentTimeMillis();
        byte[] body = (canonical + "&signature=" + service.sign("secret", canonical))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(repository.active(apiKey)).thenReturn(Optional.of(record()));
        assertThat(service.authenticate(new ApiKeyRequestBinding(properties).bind(request, body), "READ")).isEqualTo(42L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"timestamp=1", "recvWindow=2&recvWindow=3", "signature=another"})
    void ambiguousAuthenticationFieldsAreRejected(String body) {
        assertThatThrownBy(() -> new ApiKeyRequestBinding(properties).bind(request(),
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly once");
    }

    @Test
    void theSpecificSubtractionOverflowTimestampIsRejected() {
        when(repository.active(apiKey)).thenReturn(Optional.of(record()));
        long timestamp = System.currentTimeMillis() + Long.MIN_VALUE;
        String canonical = "timestamp=" + timestamp;
        var input = new ApiKeyAuthenticationRequest(apiKey, "203.0.113.7", timestamp, 5000,
                service.sign("secret", canonical), canonical);
        assertThatThrownBy(() -> service.authenticate(input, "READ"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside recvWindow");
        verify(repository, never()).markUsed(any(), any());
    }

    private GatewayApiKeyRepository.ApiKeyRecord record() {
        return new GatewayApiKeyRepository.ApiKeyRecord(UUID.randomUUID(),42L,apiKey,totp.encryptSecret("secret"),
                "test","READ","","ACTIVE",Instant.now(),null,null);
    }
    private MockHttpServletRequest request() {
        var request=new MockHttpServletRequest("GET","/api/v3/account/status");request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-MBX-APIKEY",apiKey);String timestamp=String.valueOf(System.currentTimeMillis());
        request.addParameter("timestamp",timestamp);request.addParameter("signature",service.sign("secret","timestamp="+timestamp));
        request.setQueryString("timestamp="+timestamp+"&signature="+request.getParameter("signature"));return request;
    }
    private BinanceApiController controller(GatewayApiKeyService business) {
        properties.getBinanceApi().setEnabled(true);
        return new BinanceApiController(properties,null,business,null,auth,null,null,null,new ObjectMapper());
    }
}
