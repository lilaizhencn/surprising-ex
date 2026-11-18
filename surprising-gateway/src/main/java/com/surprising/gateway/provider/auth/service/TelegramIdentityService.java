package com.surprising.gateway.provider.auth.service;

import com.surprising.gateway.provider.auth.state.AuthState;
import org.springframework.stereotype.Service;

/** 永久 Telegram 身份和短期网页会话分开；普通身份校验绝不读取 Postgres。 */
@Service
public class TelegramIdentityService {
    private final AuthState state;
    private final TelegramIdentityBinder binder;

    public TelegramIdentityService(AuthState state, TelegramIdentityBinder binder) {
        this.state = state;
        this.binder = binder;
    }

    public long resolve(String subject) { return resolve(subject, null); }

    public long resolve(String subject, String nicknameIfNew) {
        Long userId = state.telegramUserId(subject);
        if (userId == null) throw new IllegalArgumentException("telegram identity is unavailable");
        var user = state.user(userId);
        if (user == null || !user.telegramBound()) throw new IllegalArgumentException("telegram identity is unavailable");
        if ("FROZEN".equals(user.status())) throw new IllegalStateException("user is not active");
        return userId;
    }

    /** 仅限已验证的登录/明确的 /start 注册；不是身份缓存缺失时的自动回源。 */
    public long registerOrLogin(String subject, String nicknameIfNew) {
        Long userId = state.telegramUserId(subject); // 存储故障抛出，不能据此创建账号
        if (userId == null) return binder.bind(subject, nicknameIfNew);
        return resolve(subject);
    }
}
