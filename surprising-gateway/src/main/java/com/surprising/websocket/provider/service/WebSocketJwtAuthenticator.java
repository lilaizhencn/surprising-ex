package com.surprising.websocket.provider.service;

import com.surprising.gateway.provider.auth.service.AuthService;
import org.springframework.stereotype.Service;

/** HTTP 与 WebSocket 共用交易登录检查，包括会话撤销、账户状态与有效期。 */
@Service
public class WebSocketJwtAuthenticator {
    private final AuthService auth;
    public WebSocketJwtAuthenticator(AuthService auth) {this.auth=auth;}
    public long authenticate(String token) {
        if(token==null || token.isBlank() || token.length()>4096) throw new IllegalArgumentException("missing websocket token");
        return auth.authenticateBearer("Bearer "+token).userId();
    }
}
