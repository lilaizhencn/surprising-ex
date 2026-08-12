package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserSecurityService {

    private static final List<Scene> DEFAULT_SCENES = List.of(
            new Scene("LOGIN", "登录", true),
            new Scene("CHANGE_PASSWORD", "修改密码", true),
            new Scene("SECURITY_SETTINGS", "安全设置", true),
            new Scene("WITHDRAWAL", "提币", true),
            new Scene("API_WITHDRAWAL", "API 提币", true),
            new Scene("WHITELIST", "提币白名单", true),
            new Scene("LARGE_TRANSFER", "大额划转", true),
            new Scene("TRANSFER", "业务线划转", false));
    private static final Set<String> SCENE_CODES = DEFAULT_SCENES.stream()
            .map(Scene::sceneCode)
            .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> MANDATORY_SCENES = Set.of("WITHDRAWAL", "API_WITHDRAWAL");

    private final AuthPersistenceService persistence;
    private final GatewayUserSecuritySceneRepository sceneRepository;
    private final TotpService totpService;
    private final PasswordHasher passwordHasher;

    public UserSecurityService(AuthPersistenceService persistence,
                               GatewayUserSecuritySceneRepository sceneRepository,
                               TotpService totpService,
                               PasswordHasher passwordHasher) {
        this.persistence = persistence;
        this.sceneRepository = sceneRepository;
        this.totpService = totpService;
        this.passwordHasher = passwordHasher;
    }

    public UserMfaStatus status(long userId) {
        return persistence.mfaCredential(userId)
                .map(credential -> new UserMfaStatus(credential.enabled(), credential.verifiedAt()))
                .orElseGet(() -> new UserMfaStatus(false, null));
    }

    @Transactional
    public UserMfaEnrollment enrollMfa(long userId) {
        AuthenticatedUser user = requireUser(userId);
        String secret = totpService.newSecret();
        persistence.upsertMfaSecret(userId, totpService.encryptSecret(secret), Instant.now());
        return new UserMfaEnrollment(false, secret,
                totpService.provisioningUri(user.email() == null ? String.valueOf(userId) : user.email(), secret));
    }

    @Transactional
    public UserMfaStatus confirmMfa(long userId, String totpCode) {
        GatewayUserMfaRepository.MfaCredential credential = persistence.mfaCredential(userId)
                .orElseThrow(() -> new IllegalArgumentException("mfa enrollment not found"));
        String secret = totpService.decryptSecret(credential.totpSecretCiphertext());
        if (!totpService.verify(secret, totpCode, Instant.now())) {
            throw new IllegalArgumentException("invalid totp code");
        }
        Instant now = Instant.now();
        persistence.enableMfa(userId, now);
        return new UserMfaStatus(true, now);
    }

    @Transactional
    public UserMfaStatus disableMfa(long userId, String totpCode) {
        GatewayUserMfaRepository.MfaCredential credential = persistence.mfaCredential(userId).orElse(null);
        if (credential != null && credential.enabled()) {
            String secret = totpService.decryptSecret(credential.totpSecretCiphertext());
            if (!totpService.verify(secret, totpCode, Instant.now())) {
                throw new IllegalArgumentException("invalid totp code");
            }
        }
        persistence.disableMfa(userId, Instant.now());
        return new UserMfaStatus(false, null);
    }

    public List<Scene> scenes(long userId) {
        Map<String, GatewayUserSecuritySceneRepository.SceneRecord> configured = sceneRepository.find(userId).stream()
                .collect(Collectors.toMap(GatewayUserSecuritySceneRepository.SceneRecord::sceneCode, item -> item));
        return DEFAULT_SCENES.stream()
                .map(defaultScene -> configured.containsKey(defaultScene.sceneCode())
                        ? new Scene(defaultScene.sceneCode(), defaultScene.label(),
                        MANDATORY_SCENES.contains(defaultScene.sceneCode())
                                || configured.get(defaultScene.sceneCode()).enabled())
                        : defaultScene)
                .toList();
    }

    public boolean isSceneEnabled(long userId, String sceneCode) {
        String normalized = normalizeScene(sceneCode);
        return scenes(userId).stream()
                .filter(scene -> scene.sceneCode().equals(normalized))
                .findFirst()
                .map(Scene::enabled)
                .orElseThrow(() -> new IllegalArgumentException("unsupported security scene"));
    }

    public void requireCurrentPassword(long userId, String currentPassword) {
        var credential = persistence.credential(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
        if (!passwordHasher.matches(currentPassword, credential.passwordHash())) {
            throw new IllegalArgumentException("current password is invalid");
        }
    }

    @Transactional
    public void updatePassword(long userId, String newPassword) {
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 128) {
            throw new IllegalArgumentException("password length must be 8-128");
        }
        Instant now = Instant.now();
        if (persistence.updatePasswordHash(userId, passwordHasher.hash(newPassword), now) != 1) {
            throw new IllegalArgumentException("user not found");
        }
        persistence.revokeUserRefreshSessions(userId, now);
    }

    @Transactional
    public Scene updateScene(long userId, String sceneCode, boolean enabled, String currentTotpCode) {
        String normalized = normalizeScene(sceneCode);
        if (!enabled && MANDATORY_SCENES.contains(normalized)) {
            throw new IllegalArgumentException("withdrawal security scene cannot be disabled");
        }
        if (!enabled && status(userId).enabled()) {
            verifyCurrentTotp(userId, currentTotpCode);
        }
        GatewayUserSecuritySceneRepository.SceneRecord saved = sceneRepository.upsert(
                userId, normalized, enabled, Instant.now());
        return new Scene(normalized, label(normalized), saved.enabled());
    }

    private void verifyCurrentTotp(long userId, String code) {
        GatewayUserMfaRepository.MfaCredential credential = persistence.mfaCredential(userId)
                .orElseThrow(() -> new IllegalArgumentException("mfa enrollment not found"));
        String secret = totpService.decryptSecret(credential.totpSecretCiphertext());
        if (!totpService.verify(secret, code, Instant.now())) {
            throw new IllegalArgumentException("invalid totp code");
        }
    }

    private AuthenticatedUser requireUser(long userId) {
        return persistence.user(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
    }

    private String normalizeScene(String sceneCode) {
        String normalized = sceneCode == null ? "" : sceneCode.trim().toUpperCase();
        if (!SCENE_CODES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported security scene");
        }
        return normalized;
    }

    private String label(String sceneCode) {
        return DEFAULT_SCENES.stream().filter(scene -> scene.sceneCode().equals(sceneCode))
                .map(Scene::label).findFirst().orElse(sceneCode);
    }

    public record UserMfaStatus(boolean enabled, Instant verifiedAt) {
    }

    public record UserMfaEnrollment(boolean enabled, String secret, String provisioningUri) {
    }

    public record Scene(String sceneCode, String label, boolean enabled) {
    }
}
