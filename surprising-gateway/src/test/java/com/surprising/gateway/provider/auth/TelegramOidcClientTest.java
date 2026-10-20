package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class TelegramOidcClientTest {
    HttpServer server;
    RSAKey key;
    NimbusJwtDecoder decoder;
    Instant now;

    @BeforeEach
    void setup() throws Exception {
        now = Instant.now();
        key = new RSAKeyGenerator(2048).keyID("telegram-test").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            exchange.getResponseBody().write(jwks);
            exchange.close();
        });
        server.start();
        decoder = TelegramOidcClient.decoder("12345",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks",
                new SimpleClientHttpRequestFactory());
    }

    @AfterEach
    void cleanup() { if (server != null) server.stop(0); }

    JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer(TelegramOidcClient.ISSUER).audience("12345")
                .subject("123456789").issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                .claim("nonce", "expected-nonce").claim("name", "小李 🚀");
    }

    String signed(JWTClaimsSet claims, RSAKey signingKey) throws Exception {
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        token.sign(new RSASSASigner(signingKey));
        return token.serialize();
    }

    @Test
    void verifiesSignatureAndPreservesTelegramNickname() throws Exception {
        var jwt = decoder.decode(signed(claims().build(), key));
        assertThat(TelegramOidcClient.validate(jwt, "expected-nonce", now))
                .isEqualTo(new TelegramOidcClient.Profile("123456789", "小李 🚀"));
    }

    @Test
    void preservesCombinedEmojiAndNeverSplitsUnicodeNickname() throws Exception {
        var emoji = decoder.decode(signed(claims().claim("name", "开发者 👩‍💻").build(), key));
        assertThat(TelegramOidcClient.validate(emoji, "expected-nonce", now).displayName()).isEqualTo("开发者 👩‍💻");
        var longName = decoder.decode(signed(claims().claim("name", "🚀".repeat(129)).build(), key));
        assertThat(TelegramOidcClient.validate(longName, "expected-nonce", now).displayName()).isEqualTo("🚀".repeat(128));
    }

    @Test
    void rejectsForgedSignature() throws Exception {
        String forged = signed(claims().build(), new RSAKeyGenerator(2048).generate());
        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsWrongIssuerAndAudience() throws Exception {
        String issuer = signed(claims().issuer("https://attacker.example").build(), key);
        String audience = signed(claims().audience("another-bot").build(), key);
        assertThatThrownBy(() -> decoder.decode(issuer)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(audience)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsMissingExpiryNonceAndStaleAuthentication() throws Exception {
        String missingExpiry = signed(claims().expirationTime(null).build(), key);
        assertThatThrownBy(() -> decoder.decode(missingExpiry)).isInstanceOf(JwtException.class);
        var valid = decoder.decode(signed(claims().build(), key));
        assertThatThrownBy(() -> TelegramOidcClient.validate(valid, "other-nonce", now))
                .isInstanceOf(IllegalArgumentException.class);
        var stale = decoder.decode(signed(claims().issueTime(Date.from(now.minusSeconds(601))).build(), key));
        assertThatThrownBy(() -> TelegramOidcClient.validate(stale, "expected-nonce", now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsExpiredTokenEvenWithinLibraryClockSkew() throws Exception {
        var token = decoder.decode(signed(claims().issueTime(Date.from(now.minusSeconds(100))).expirationTime(Date.from(now.minusSeconds(1))).build(), key));
        assertThatThrownBy(() -> TelegramOidcClient.validate(token, "expected-nonce", now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authorizationUsesPkceStateNonceAndConfiguredCallback() {
        var client = new TelegramOidcClient("12345", "test-secret", "https://login.example/api/v1/auth/telegram/callback");
        String url = client.authorizationUrl("state", "nonce", "challenge");
        assertThat(url).startsWith("https://oauth.telegram.org/auth?").contains("code_challenge_method=S256", "nonce=nonce", "state=state", "scope=openid%20profile");
        assertThat(url).doesNotContain("test-secret");
        assertThat(new TelegramOidcClient("12345", "", "").enabled()).isFalse();
    }
}
