package com.surprising.gateway.provider.auth.service;

import com.surprising.gateway.provider.auth.repository.TelegramIdentityRepository;
import com.surprising.gateway.provider.auth.state.AuthState;
import org.springframework.stereotype.Service;

/**
 * Telegram subject 到站内用户的解析。已绑定账号走本机缓存和 Valkey，不访问数据库；
 * 只有从未绑定过的 subject 才进入 {@link TelegramIdentityBinder}。
 */
@Service
public class TelegramIdentityService {

    private final TelegramIdentityRepository identities;
    private final AuthState authState;
    private final TelegramIdentityBinder binder;

    public TelegramIdentityService(TelegramIdentityRepository identities, AuthState authState,
                                   TelegramIdentityBinder binder) {
        this.identities = identities;
        this.authState = authState;
        this.binder = binder;
    }

    public long resolve(String subject) {
        return resolve(subject, null);
    }

    /** {@code nicknameIfNew} 只在本次新建账号时写入，已绑定账号的资料仍由原更新路径维护。 */
    public long resolve(String subject, String nicknameIfNew) {
        Long cached = authState.telegramUserId(subject);
        if (cached != null) return cached;
        var existing = identities.find(subject);
        if (existing.isPresent()) {
            authState.putTelegramIdentity(subject, existing.get());
            return existing.get();
        }
        return binder.bind(subject, nicknameIfNew);
    }
}
