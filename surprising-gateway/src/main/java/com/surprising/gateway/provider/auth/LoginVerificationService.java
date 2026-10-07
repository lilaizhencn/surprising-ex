package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.config.GatewayProperties;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** 密码验证后的短时挑战。此处不签发会话，所有要求的方式一起通过后才交回 AuthService。 */
@Service
public class LoginVerificationService {
    private final LoginVerificationRepository challenges;
    private final AuthPersistenceService users;
    private final GatewayProperties properties;
    private final TotpService totp;
    private final EmailMessageSender email;
    private final ObjectProvider<SmsMessageSender> sms;
    private final PasswordHasher passwordHasher;
    private final SecureRandom random = new SecureRandom();

    public LoginVerificationService(
            LoginVerificationRepository challenges,
            AuthPersistenceService users,
            GatewayProperties properties,
            TotpService totp,
            EmailMessageSender email,
            ObjectProvider<SmsMessageSender> sms,
            PasswordHasher passwordHasher) {
        this.challenges = challenges;
        this.users = users;
        this.properties = properties;
        this.totp = totp;
        this.email = email;
        this.sms = sms;
        this.passwordHasher = passwordHasher;
    }

    @Transactional(noRollbackFor = VerificationFailure.class)
    public ChallengeResponse begin(long userId, String checkedPasswordHash, Instant now) {
        challenges.lockUser(userId);
        var currentCredential = users.credential(userId).orElseThrow();
        if ("FROZEN".equals(currentCredential.status())
                || !currentCredential.passwordHash().equals(checkedPasswordHash))
            throw new VerificationFailure("LOGIN_CHALLENGE_EXPIRED");
        List<Method> methods = methods(userId);
        if (methods.isEmpty()) return null;
        return issue(userId, "LOGIN", methods, null, true, now);
    }

    @Transactional(noRollbackFor = VerificationFailure.class)
    public long verify(VerifyRequest request, Instant now) {
        var challenge = active(request.challengeToken(), "LOGIN", now);
        if (!challenge.fingerprint().equals(fingerprint(challenge.userId()))) {
            challenges.consume(challenge.tokenHash());
            throw new VerificationFailure("LOGIN_CHALLENGE_EXPIRED");
        }
        verifyCodes(challenge, request, now, true);
        challenges.consume(challenge.tokenHash());
        return challenge.userId();
    }

    private void verifyCodes(
            LoginVerificationRepository.Challenge challenge,
            VerifyRequest request,
            Instant now,
            boolean login) {
        long step = -1;
        boolean valid = true;
        for (String method : challenge.methods().split(",")) {
            valid &=
                    switch (method) {
                        case "EMAIL" ->
                                matches(
                                        challenge.emailHash(),
                                        request.emailCode(),
                                        challenge.tokenHash(),
                                        method);
                        case "PHONE" ->
                                matches(
                                        challenge.phoneHash(),
                                        request.phoneCode(),
                                        challenge.tokenHash(),
                                        method);
                        case "TOTP" -> {
                            var credential = users.mfaCredential(challenge.userId()).orElseThrow();
                            step =
                                    totp.matchingStep(
                                            totp.decryptSecret(credential.totpSecretCiphertext()),
                                            request.totpCode(),
                                            now);
                            yield step >= 0;
                        }
                        default -> false;
                    };
        }
        if (valid && step >= 0)
            valid =
                    login
                            ? challenges.consumeTotp(challenge.userId(), step)
                            : users.consumeMfaCode(challenge.userId(), step);
        if (!valid) {
            challenges.failed(challenge.tokenHash());
            throw new VerificationFailure("LOGIN_VERIFICATION_INVALID");
        }
    }

    public List<Method> methods(long userId) {
        var credential =
                users.credential(userId)
                        .orElseThrow(() -> new VerificationFailure("LOGIN_CHALLENGE_EXPIRED"));
        List<Method> methods = new ArrayList<>();
        for (var factor : challenges.factors(userId)) {
            if (!factor.enabled() || factor.verifiedAt() == null) continue;
            String current =
                    "EMAIL".equals(factor.method()) ? credential.email() : credential.phone();
            // 修改账户联系方式后不可静默移除验证要求，需要先完成重新绑定。
            if (!factor.destination().equals(current))
                throw new VerificationFailure("LOGIN_SECURITY_CHANGED");
            methods.add(new Method(factor.method(), mask(current)));
        }
        var mfa = users.mfaCredential(userId).orElse(null);
        boolean enrolled = mfa != null && mfa.enabled() && mfa.verifiedAt() != null;
        var user = users.user(userId).orElseThrow();
        boolean admin =
                user.roles().stream().anyMatch(properties.getSecurity().getAdminRoles()::contains);
        if (admin && properties.getSecurity().isRequireAdminMfa() && !enrolled)
            throw new VerificationFailure("LOGIN_MFA_ENROLLMENT_REQUIRED");
        if (enrolled) methods.add(new Method("TOTP", null));
        return List.copyOf(methods);
    }

    public List<Setting> settings(long userId) {
        var factors = challenges.factors(userId);
        List<Setting> result = new ArrayList<>();
        var credential = users.credential(userId).orElseThrow();
        for (String method : List.of("EMAIL", "PHONE")) {
            var factor =
                    factors.stream()
                            .filter(f -> f.method().equals(method))
                            .findFirst()
                            .orElse(null);
            String destination =
                    factor != null
                            ? factor.destination()
                            : "EMAIL".equals(method) ? credential.email() : credential.phone();
            result.add(
                    new Setting(
                            method,
                            destination != null && !destination.isBlank(),
                            factor != null && factor.enabled(),
                            mask(destination)));
        }
        var mfa = users.mfaCredential(userId).orElse(null);
        result.add(
                new Setting(
                        "TOTP",
                        mfa != null && mfa.verifiedAt() != null,
                        mfa != null && mfa.verifiedAt() != null && mfa.enabled(),
                        null));
        return List.copyOf(result);
    }

    /** First UI step only; binding and confirmation independently check the password again. */
    public void verifySettingPassword(long userId, String method, String currentPassword) {
        requireSettingMethod(method);
        requirePassword(userId, currentPassword);
    }

    @Transactional(noRollbackFor = VerificationFailure.class)
    public BindingResponse beginBinding(
            long userId, String method, SettingRequest request, Instant now) {
        requireSettingMethod(method);
        challenges.lockUser(userId);
        requirePassword(userId, request.currentPassword());
        var account = users.credential(userId).orElseThrow();
        if ("TOTP".equals(method)
                && !request.enabled()
                && properties.getSecurity().isRequireAdminMfa()
                && users.roles(userId).stream()
                        .anyMatch(properties.getSecurity().getAdminRoles()::contains)) {
            throw new VerificationFailure("LOGIN_MFA_ENROLLMENT_REQUIRED");
        }
        var existing =
                challenges.factors(userId).stream()
                        .filter(f -> f.method().equals(method))
                        .findFirst()
                        .orElse(null);
        String destination = null;
        String enrollmentSecret = null;
        if ("TOTP".equals(method)) {
            var mfa = users.mfaCredential(userId).orElse(null);
            if (mfa == null || mfa.verifiedAt() == null) {
                if (!request.enabled()) throw new VerificationFailure("LOGIN_CONTACT_MISSING");
                enrollmentSecret = totp.newSecret();
                users.upsertMfaSecret(userId, totp.encryptSecret(enrollmentSecret), now);
            }
        } else {
            destination =
                    existing != null
                            ? existing.destination()
                            : "EMAIL".equals(method) ? account.email() : account.phone();
            if (request.enabled()
                    && request.destination() != null
                    && !request.destination().isBlank()) destination = request.destination().trim();
            if (destination == null || destination.isBlank())
                throw new VerificationFailure("LOGIN_CONTACT_MISSING");
            if ("EMAIL".equals(method)) {
                destination = destination.toLowerCase(Locale.ROOT);
                if (!destination.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                        || destination.length() > 254)
                    throw new VerificationFailure("LOGIN_CONTACT_INVALID");
            } else if (!destination.matches("\\+[1-9][0-9]{6,14}"))
                throw new VerificationFailure("LOGIN_CONTACT_INVALID");
        }
        // 绑定/开启时，注册联系方式与其他已绑定因素都要验证，与其登录开关无关。
        List<Method> required = new ArrayList<>();
        List<Setting> currentSettings = settings(userId);
        for (Setting setting : currentSettings) {
            if (setting.type().equals(method)) required.add(new Method(method, mask(destination)));
            else if (request.enabled() && setting.bound() && !"TOTP".equals(method))
                required.add(new Method(setting.type(), setting.destination()));
        }
        if ("TOTP".equals(method) && request.enabled()) {
            String contactMethod = availableContactMethod(currentSettings, account.email(), account.phone());
            if (required.stream().noneMatch(item -> item.type().equals(contactMethod))) {
                required.add(new Method(contactMethod,
                        mask("EMAIL".equals(contactMethod) ? account.email() : account.phone())));
            }
        }
        ChallengeResponse challenge = issue(userId, method, required, destination, request.enabled(), now);
        String uri = enrollmentSecret == null ? null
                : totp.provisioningUri(account.email() == null ? account.username() : account.email(), enrollmentSecret);
        return new BindingResponse(challenge, enrollmentSecret, uri,
                uri == null ? null : totp.qrCodeDataUrl(uri));
    }

    @Transactional(noRollbackFor = VerificationFailure.class)
    public void confirmBinding(
            long userId, String method, SettingConfirmation request, Instant now) {
        requireSettingMethod(method);
        var challenge = active(request.codes().challengeToken(), method, now);
        if (challenge.userId() != userId) throw new VerificationFailure("LOGIN_CHALLENGE_EXPIRED");
        try {
            requirePassword(userId, request.currentPassword());
        } catch (VerificationFailure failure) {
            challenges.failed(challenge.tokenHash());
            throw failure;
        }
        if (!challenge.fingerprint().equals(fingerprint(userId))) {
            challenges.consume(challenge.tokenHash());
            throw new VerificationFailure("LOGIN_CHALLENGE_EXPIRED");
        }
        verifyCodes(challenge, request.codes(), now, false);
        if ("TOTP".equals(method)) {
            if (challenge.targetEnabled()) users.enableMfa(userId, now);
            else users.disableMfa(userId, now);
        } else
            challenges.saveFactor(
                    userId, method, challenge.destination(), challenge.targetEnabled(), now);
        challenges.consume(challenge.tokenHash());
        users.recordSecurityChange(userId, now);
    }

    private String availableContactMethod(List<Setting> settings, String email, String phone) {
        boolean emailBound = settings.stream().anyMatch(setting -> setting.type().equals("EMAIL") && setting.bound());
        boolean phoneBound = settings.stream().anyMatch(setting -> setting.type().equals("PHONE") && setting.bound());
        if (emailBound && email != null && email.trim().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) return "EMAIL";
        if (phoneBound && phone != null && phone.trim().matches("\\+[1-9][0-9]{6,14}")) return "PHONE";
        if (email != null && email.trim().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) return "EMAIL";
        if (phone != null && phone.trim().matches("\\+[1-9][0-9]{6,14}")) return "PHONE";
        throw new VerificationFailure("LOGIN_CONTACT_MISSING");
    }

    private void requireSettingMethod(String method) {
        if (!List.of("EMAIL", "PHONE", "TOTP").contains(method))
            throw new IllegalArgumentException("unsupported verification method");
    }

    private void requirePassword(long userId, String password) {
        if (!passwordHasher.matches(
                password, users.credential(userId).orElseThrow().passwordHash()))
            throw new VerificationFailure("LOGIN_PASSWORD_INVALID");
    }

    private ChallengeResponse issue(
            long userId,
            String purpose,
            List<Method> methods,
            String destination,
            boolean targetEnabled,
            Instant now) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                hash = digest(token);
        boolean simulatedCodes = properties.getSecurity().isSimulatedVerificationCodesEnabled();
        String emailCode = methods.stream().anyMatch(m -> m.type().equals("EMAIL"))
                ? simulatedCodes ? "123456" : code() : null;
        String phoneCode = methods.stream().anyMatch(m -> m.type().equals("PHONE"))
                ? simulatedCodes ? "123456" : code() : null;
        SmsMessageSender sender = phoneCode == null || simulatedCodes ? null : sms.getIfAvailable();
        if (phoneCode != null && sender == null && !simulatedCodes)
            throw new VerificationFailure("LOGIN_SMS_UNAVAILABLE");
        if (!challenges.issue(
                userId,
                purpose,
                hash,
                fingerprint(userId),
                String.join(",", methods.stream().map(Method::type).toList()),
                emailCode == null ? null : codeHash(emailCode, hash, "EMAIL"),
                phoneCode == null ? null : codeHash(phoneCode, hash, "PHONE"),
                destination,
                targetEnabled,
                now)) throw new VerificationFailure("LOGIN_VERIFICATION_RATE_LIMITED");
        var user = users.credential(userId).orElseThrow();
        try {
            if (simulatedCodes) return new ChallengeResponse(true, token, now.plusSeconds(300), methods, true);
            if (emailCode != null)
                email.send(
                        "EMAIL".equals(purpose) ? destination : user.email(),
                        "Surprising EX verification / 安全验证",
                        "Verification code / 验证码: "
                                + emailCode
                                + ". Valid for 5 minutes / 5 分钟内有效。 Do not share this code /"
                                + " 请勿向他人透露。");
            if (phoneCode != null)
                sender.send("PHONE".equals(purpose) ? destination : user.phone(), phoneCode);
        } catch (RuntimeException ex) {
            challenges.consume(hash);
            throw new VerificationFailure("LOGIN_CODE_DELIVERY_FAILED");
        }
        return new ChallengeResponse(true, token, now.plusSeconds(300), methods, false);
    }

    private LoginVerificationRepository.Challenge active(
            String token, String purpose, Instant now) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
            throw new VerificationFailure("LOGIN_CHALLENGE_EXPIRED");
        String hash = digest(token);
        var initial =
                challenges
                        .find(hash)
                        .orElseThrow(() -> new VerificationFailure("LOGIN_CHALLENGE_EXPIRED"));
        challenges.lockUser(initial.userId());
        var c =
                challenges
                        .find(hash)
                        .orElseThrow(() -> new VerificationFailure("LOGIN_CHALLENGE_EXPIRED"));
        if (!c.purpose().equals(purpose)
                || c.consumed()
                || !c.expiresAt().isAfter(now)
                || c.attempts() >= 5) throw new VerificationFailure("LOGIN_CHALLENGE_EXPIRED");
        return c;
    }

    private String fingerprint(long userId) {
        var c = users.credential(userId).orElseThrow();
        var mfa = users.mfaCredential(userId).orElse(null);
        return digest(
                c.passwordHash()
                        + "|"
                        + c.status()
                        + "|"
                        + c.email()
                        + "|"
                        + c.phone()
                        + "|"
                        + users.roles(userId)
                        + "|"
                        + challenges.factors(userId)
                        + "|"
                        + (mfa == null
                                ? ""
                                : mfa.totpSecretCiphertext()
                                        + ":"
                                        + mfa.enabled()
                                        + ":"
                                        + mfa.verifiedAt()));
    }

    private String code() {
        return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }

    private String codeHash(String code, String tokenHash, String method) {
        return digest(
                properties.getSecurity().getVerificationCodePepper()
                        + "|"
                        + tokenHash
                        + "|"
                        + method
                        + "|"
                        + code);
    }

    private boolean matches(String expected, String code, String tokenHash, String method) {
        return expected != null
                && code != null
                && code.matches("[0-9]{6}")
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        codeHash(code, tokenHash, method).getBytes(StandardCharsets.UTF_8));
    }

    private String digest(String text) {
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String mask(String value) {
        if (value == null) return null;
        int at = value.indexOf('@');
        return at >= 0
                ? value.substring(0, 1) + "***" + value.substring(at)
                : "***" + value.substring(Math.max(0, value.length() - 4));
    }

    public record BindingResponse(
            ChallengeResponse challenge, String secret, String provisioningUri, String qrCodeDataUrl) {}

    public record Setting(String type, boolean bound, boolean enabled, String destination) {}

    public record SettingRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 128)
                    String currentPassword,
            @jakarta.validation.constraints.Size(max = 254) String destination,
            boolean enabled) {}

    public record SettingConfirmation(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 128)
                    String currentPassword,
            @jakarta.validation.Valid @jakarta.validation.constraints.NotNull
                    VerifyRequest codes) {}

    public record Method(String type, String destination) {}

    public record ChallengeResponse(
            boolean requiresVerification,
            String challengeToken,
            Instant expiresAt,
            List<Method> methods,
            boolean simulated)
            implements AuthModels.LoginResult {}

    public record VerifyRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 128)
                    String challengeToken,
            @jakarta.validation.constraints.Size(max = 6) String emailCode,
            @jakarta.validation.constraints.Size(max = 6) String phoneCode,
            @jakarta.validation.constraints.Size(max = 6) String totpCode) {}

    public static class VerificationFailure extends RuntimeException {
        public VerificationFailure(String code) {
            super(code);
        }
    }
}
