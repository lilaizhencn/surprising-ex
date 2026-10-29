package com.surprising.websocket.provider.service;

import com.surprising.gateway.provider.auth.service.AuthService;
import org.springframework.stereotype.Service;

/** HTTP 与 WebSocket 共用交易登录检查，包括会话撤销、账户状态与有效期。 */
@Service
public class WebSocketAuthenticator {
    private final AuthService auth;
    private final com.surprising.gateway.provider.auth.service.GatewayApiKeyService keys;
    public WebSocketAuthenticator(AuthService auth, com.surprising.gateway.provider.auth.service.GatewayApiKeyService keys) {
        this.auth=auth;
        this.keys=keys;
    }
    public com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal principal(String token, String ip) {
        if(token==null || token.isBlank() || token.length()>4096) throw new IllegalArgumentException("missing websocket token");
        var principal = auth.authenticateBearer("Bearer "+token);
        auth.requireIpAccessAllowed(principal.userId(), ip);
        return principal;
    }

    public long authenticateApiKey(String apiKey, String ip, long timestamp, String signature, String challenge) {
        if (apiKey == null || !apiKey.matches("sx_[A-Za-z0-9_-]{20,80}")
                || signature == null || !signature.matches("[a-fA-F0-9]{64}") || ip == null || ip.isBlank())
            throw new IllegalArgumentException("invalid websocket API key proof");
        return keys.authenticate(new com.surprising.gateway.provider.auth.model.ApiKeyAuthenticationRequest(
                apiKey, ip, timestamp, 5000, signature,
                com.surprising.gateway.provider.auth.utils.ApiSignatureFormats.websocketRequest(challenge, apiKey, timestamp)), "READ");
    }

    public long apiKeyUser(String apiKey, String ip) { return keys.requireWebSocketAccess(apiKey, ip); }
}
