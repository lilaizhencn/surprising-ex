package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.gateway.provider.config.GatewayProperties;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@EnabledIfEnvironmentVariable(named = "LOGIN_TEST_JDBC_URL", matches = ".+")
class LoginVerificationDatabaseTest {
    JdbcTemplate jdbc, admin;
    String schema;
    LoginVerificationService service;
    TotpService totp;
    String secret;
    Instant now;
    EmailMessageSender emailSender = mock(EmailMessageSender.class);

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("LOGIN_TEST_JDBC_URL"),
                user = System.getenv("LOGIN_TEST_DB_USER"),
                password = System.getenv("LOGIN_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "login_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE TABLE gateway_users(user_id bigint PRIMARY KEY,email text,phone"
                    + " text,updated_at timestamptz,email_verified_at timestamptz)");
        jdbc.execute(
                "CREATE TABLE gateway_user_mfa(user_id bigint PRIMARY KEY,enabled"
                    + " boolean,verified_at timestamptz)");
        String sql =
                Files.readString(
                        Path.of("../deployment/migrations/20260928-login-verification.sql"));
        for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        jdbc.update("INSERT INTO gateway_users(user_id) VALUES(1)");
        jdbc.update(
                "INSERT INTO gateway_user_mfa(user_id,enabled,verified_at) VALUES(1,true,now())");
        var users = mock(AuthPersistenceService.class);
        var properties = new GatewayProperties();
        totp = new TotpService(properties);
        secret = totp.newSecret();
        now = Instant.now();
        when(users.credential(1))
                .thenReturn(
                        Optional.of(
                                new GatewayUserRepository.UserCredential(
                                        1, "user", "u@example.test", "hash", "NORMAL", now)));
        when(users.user(1))
                .thenReturn(
                        Optional.of(
                                new AuthModels.AuthenticatedUser(
                                        1,
                                        "user",
                                        "u@example.test",
                                        "NORMAL",
                                        List.of("USER"),
                                        now)));
        when(users.roles(1)).thenReturn(List.of("USER"));
        when(users.mfaCredential(1))
                .thenReturn(
                        Optional.of(
                                new GatewayUserMfaRepository.MfaCredential(
                                        1, totp.encryptSecret(secret), true, now, now, now)));
        @SuppressWarnings("unchecked")
        ObjectProvider<SmsMessageSender> sms = mock(ObjectProvider.class);
        var passwords = mock(PasswordHasher.class);
        when(passwords.matches("password", "hash")).thenReturn(true);
        when(users.consumeMfaCode(
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(
                        invocation ->
                                jdbc.update(
                                                "UPDATE gateway_user_mfa SET last_login_step=?"
                                                    + " WHERE user_id=1 AND last_login_step<?",
                                                invocation.getArgument(1, Long.class),
                                                invocation.getArgument(1, Long.class))
                                        == 1);
        var target =
                new LoginVerificationService(
                        new LoginVerificationRepository(jdbc),
                        users,
                        properties,
                        totp,
                        emailSender,
                        sms,
                        passwords);
        var factory = new ProxyFactory(target);
        factory.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(ds),
                        new AnnotationTransactionAttributeSource()));
        service = (LoginVerificationService) factory.getProxy();
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    LoginVerificationService.VerifyRequest request(
            LoginVerificationService.ChallengeResponse challenge, String code) {
        return new LoginVerificationService.VerifyRequest(
                challenge.challengeToken(), null, null, code);
    }

    @Test
    void failedAttemptsSurviveRollbackAndCannotResetByRequestingAnotherChallenge() {
        var challenge = service.begin(1, "hash", now);
        for (int i = 0; i < 5; i++)
            assertThatThrownBy(() -> service.verify(request(challenge, null), now))
                    .hasMessage("LOGIN_VERIFICATION_INVALID");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempts FROM gateway_login_challenges", Integer.class))
                .isEqualTo(5);
        assertThatThrownBy(() -> service.verify(request(challenge, totp.code(secret, now)), now))
                .hasMessage("LOGIN_CHALLENGE_EXPIRED");
        assertThatThrownBy(() -> service.begin(1, "hash", now.plusSeconds(61)))
                .hasMessage("LOGIN_VERIFICATION_RATE_LIMITED");
        assertThat(service.begin(1, "hash", now.plusSeconds(601))).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM gateway_login_challenges", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentSubmissionsCreateOnlyOneSuccessfulVerification() throws Exception {
        var challenge = service.begin(1, "hash", now);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        return service.verify(
                                                        request(challenge, totp.code(secret, now)),
                                                        now)
                                                == 1;
                                    } catch (LoginVerificationService.VerificationFailure e) {
                                        return false;
                                    }
                                }));
            start.countDown();
            int successes = 0;
            for (var f : futures) if (f.get(10, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT consumed FROM gateway_login_challenges", Boolean.class))
                .isTrue();
    }

    @Test
    void replacingChallengeInvalidatesOldTokenAndEnforcesCooldown() {
        var first = service.begin(1, "hash", now);
        assertThatThrownBy(() -> service.begin(1, "hash", now.plusSeconds(5)))
                .hasMessage("LOGIN_VERIFICATION_RATE_LIMITED");
        var second = service.begin(1, "hash", now.plusSeconds(61));
        assertThatThrownBy(() -> service.verify(request(first, totp.code(secret, now)), now))
                .hasMessage("LOGIN_CHALLENGE_EXPIRED");
        assertThat(second.challengeToken()).isNotEqualTo(first.challengeToken());
    }

    String latestEmailCode() {
        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(emailSender, atLeastOnce())
                .send(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        body.capture());
        var matcher = java.util.regex.Pattern.compile("[0-9]{6}").matcher(body.getValue());
        assertThat(matcher.find()).isTrue();
        return matcher.group();
    }

    @Test
    void bindingAndDisablingRequirePasswordAndAllChallengeCodes() {
        var binding =
                service.beginBinding(
                        1,
                        "EMAIL",
                        new LoginVerificationService.SettingRequest(
                                "password", "u@example.test", true),
                        now);
        var token = binding.challenge().challengeToken();
        String code = latestEmailCode();
        var incomplete = new LoginVerificationService.VerifyRequest(token, code, null, null);
        assertThatThrownBy(
                        () ->
                                service.confirmBinding(
                                        1,
                                        "EMAIL",
                                        new LoginVerificationService.SettingConfirmation(
                                                "password", incomplete),
                                        now))
                .hasMessage("LOGIN_VERIFICATION_INVALID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_login_factors", Integer.class))
                .isZero();
        var codes =
                new LoginVerificationService.VerifyRequest(
                        token, code, null, totp.code(secret, now));
        assertThatThrownBy(
                        () ->
                                service.confirmBinding(
                                        1,
                                        "EMAIL",
                                        new LoginVerificationService.SettingConfirmation(
                                                "wrong", codes),
                                        now))
                .hasMessage("LOGIN_PASSWORD_INVALID");
        service.confirmBinding(
                1,
                "EMAIL",
                new LoginVerificationService.SettingConfirmation("password", codes),
                now);
        assertThat(jdbc.queryForObject("SELECT enabled FROM gateway_login_factors", Boolean.class))
                .isTrue();
        assertThatThrownBy(
                        () ->
                                service.confirmBinding(
                                        1,
                                        "EMAIL",
                                        new LoginVerificationService.SettingConfirmation(
                                                "password", codes),
                                        now))
                .hasMessage("LOGIN_CHALLENGE_EXPIRED");
        var disabling =
                service.beginBinding(
                        1,
                        "EMAIL",
                        new LoginVerificationService.SettingRequest("password", null, false),
                        now.plusSeconds(61));
        var disableCodes =
                new LoginVerificationService.VerifyRequest(
                        disabling.challenge().challengeToken(), latestEmailCode(), null, null);
        service.confirmBinding(
                1,
                "EMAIL",
                new LoginVerificationService.SettingConfirmation("password", disableCodes),
                now.plusSeconds(61));
        assertThat(jdbc.queryForObject("SELECT enabled FROM gateway_login_factors", Boolean.class))
                .isFalse();
        assertThat(service.settings(1))
                .filteredOn(x -> x.type().equals("EMAIL"))
                .allMatch(x -> x.bound() && !x.enabled());
    }
}
