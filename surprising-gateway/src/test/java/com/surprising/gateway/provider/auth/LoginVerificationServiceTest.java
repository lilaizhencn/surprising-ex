package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.surprising.gateway.provider.config.GatewayProperties;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.*;

class LoginVerificationServiceTest {
    final LoginVerificationRepository db = mock(LoginVerificationRepository.class);
    final AuthPersistenceService users = mock(AuthPersistenceService.class);
    final GatewayProperties properties = new GatewayProperties();
    final TotpService totp = new TotpService(properties);
    final EmailMessageSender email = mock(EmailMessageSender.class);

    @SuppressWarnings("unchecked")
    final ObjectProvider<SmsMessageSender> sms = mock(ObjectProvider.class);

    final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    final LoginVerificationService service =
            new LoginVerificationService(db, users, properties, totp, email, sms, passwordHasher);
    final Instant now = Instant.now();
    final String secret = totp.newSecret();

    LoginVerificationServiceTest() {
        when(users.credential(1))
                .thenReturn(
                        Optional.of(
                                new GatewayUserRepository.UserCredential(
                                        1,
                                        "user",
                                        "u@example.test",
                                        "+12025550123",
                                        "hash",
                                        "NORMAL",
                                        now)));
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
        when(passwordHasher.matches("password", "hash")).thenReturn(true);
        when(users.roles(1)).thenReturn(List.of("USER"));
        when(db.factors(1)).thenReturn(List.of());
        when(users.mfaCredential(1)).thenReturn(Optional.empty());
        when(db.issue(
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        nullable(String.class),
                        nullable(String.class),
                        nullable(String.class),
                        anyBoolean(),
                        any()))
                .thenReturn(true);
    }

    @Test
    void passwordStepDoesNotSendCodesOrChangeSecurityState() {
        service.verifySettingPassword(1, "EMAIL", "password");
        verifyNoInteractions(db, email, sms);
        verify(users, never()).upsertMfaSecret(anyLong(), anyString(), any());
    }

    @Test
    void passwordStepRejectsIncorrectPasswordBeforeIssuingAnyCode() {
        assertThatThrownBy(() -> service.verifySettingPassword(1, "EMAIL", "wrong"))
                .isInstanceOf(LoginVerificationService.VerificationFailure.class)
                .hasMessage("LOGIN_PASSWORD_INVALID");
        verifyNoInteractions(db, email, sms);
    }

    @Test
    void passwordStepRejectsUnsupportedMethod() {
        assertThatThrownBy(() -> service.verifySettingPassword(1, "UNKNOWN", "password"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(passwordHasher, db, email, sms);
    }

    @Test
    void testSimulationUsesFixedCodeAndDoesNotRequireDeliveryProviders() {
        properties.getSecurity().setSimulatedVerificationCodesEnabled(true);

        var response = service.beginBinding(1, "EMAIL",
                new LoginVerificationService.SettingRequest("password", null, true), now);

        assertThat(response.challenge().simulated()).isTrue();
        assertThat(response.challenge().methods()).extracting(LoginVerificationService.Method::type)
                .containsExactly("EMAIL", "PHONE");
        verify(db).issue(eq(1L), eq("EMAIL"), anyString(), anyString(), eq("EMAIL,PHONE"),
                anyString(), anyString(), eq("u@example.test"), eq(true), eq(now));
        verifyNoInteractions(email, sms);
    }

    @Test
    void resendTotpEnrollmentCodeKeepsTheExistingPendingEnrollment() {
        properties.getSecurity().setSimulatedVerificationCodesEnabled(true);
        var token = "a".repeat(43);
        when(db.find(anyString()))
                .thenReturn(Optional.of(new LoginVerificationRepository.Challenge(
                        1, "TOTP", "old-hash", "fingerprint", "EMAIL,TOTP", "email-hash", null,
                        null, now.plusSeconds(300), false, 0, true)));

        var result = service.resendBindingCode(
                1, "TOTP", new LoginVerificationService.ResendRequest(token), now.plusSeconds(60));

        assertThat(result.simulated()).isTrue();
        assertThat(result.methods()).extracting(LoginVerificationService.Method::type)
                .containsExactly("EMAIL", "TOTP");
        verify(db).issue(eq(1L), eq("TOTP"), anyString(), anyString(), eq("EMAIL,TOTP"),
                anyString(), isNull(), isNull(), eq(true), eq(now.plusSeconds(60)));
        verify(users, never()).upsertMfaSecret(anyLong(), anyString(), any());
        verifyNoInteractions(email, sms);
    }

    @Test
    void resendTotpEnrollmentCodeRejectsAnotherUsersChallenge() {
        var token = "a".repeat(43);
        when(db.find(anyString()))
                .thenReturn(Optional.of(new LoginVerificationRepository.Challenge(
                        2, "TOTP", "old-hash", "fingerprint", "EMAIL,TOTP", "email-hash", null,
                        null, now.plusSeconds(300), false, 0, true)));

        assertThatThrownBy(() -> service.resendBindingCode(
                        1, "TOTP", new LoginVerificationService.ResendRequest(token), now))
                .hasMessage("LOGIN_CHALLENGE_EXPIRED");
        verify(db, never()).issue(anyLong(), anyString(), anyString(), anyString(), anyString(),
                nullable(String.class), nullable(String.class), nullable(String.class), anyBoolean(), any());
    }

    void enableTotp(boolean enabled, Instant verified) {
        when(users.mfaCredential(1))
                .thenReturn(
                        Optional.of(
                                new GatewayUserMfaRepository.MfaCredential(
                                        1,
                                        totp.encryptSecret(secret),
                                        enabled,
                                        verified,
                                        now,
                                        now)));
    }

    LoginVerificationService.ChallengeResponse begin() {
        var response = service.begin(1, "hash", now);
        var hash = ArgumentCaptor.forClass(String.class);
        var fingerprint = ArgumentCaptor.forClass(String.class);
        var methods = ArgumentCaptor.forClass(String.class);
        var eh = ArgumentCaptor.forClass(String.class);
        var ph = ArgumentCaptor.forClass(String.class);
        verify(db)
                .issue(
                        eq(1L),
                        eq("LOGIN"),
                        hash.capture(),
                        fingerprint.capture(),
                        methods.capture(),
                        eh.capture(),
                        ph.capture(),
                        isNull(),
                        eq(true),
                        eq(now));
        when(db.find(hash.getValue()))
                .thenReturn(
                        Optional.of(
                                new LoginVerificationRepository.Challenge(
                                        1,
                                        "LOGIN",
                                        hash.getValue(),
                                        fingerprint.getValue(),
                                        methods.getValue(),
                                        eh.getValue(),
                                        ph.getValue(),
                                        null,
                                        now.plusSeconds(300),
                                        false,
                                        0,
                                        true)));
        when(db.consumeTotp(eq(1L), anyLong())).thenReturn(true);
        return response;
    }

    @Test
    void noFactorsReturnsNoChallenge() {
        assertThat(service.begin(1, "hash", now)).isNull();
        verifyNoInteractions(email);
    }

    @Test
    void unconfirmedOrDisabledTotpDoesNotBecomeRequired() {
        enableTotp(true, null);
        assertThat(service.begin(1, "hash", now)).isNull();
        enableTotp(false, now);
        assertThat(service.begin(1, "hash", now)).isNull();
    }

    @Test
    void validTotpCompletesAndConsumesChallenge() {
        enableTotp(true, now);
        var c = begin();
        assertThat(
                        service.verify(
                                new LoginVerificationService.VerifyRequest(
                                        c.challengeToken(), null, null, totp.code(secret, now)),
                                now))
                .isEqualTo(1);
        verify(db).consume(anyString());
        verify(db).consumeTotp(1, now.getEpochSecond() / 30);
    }

    @Test
    void missingCodeCountsFailureAndDoesNotConsume() {
        enableTotp(true, now);
        var c = begin();
        assertThatThrownBy(
                        () ->
                                service.verify(
                                        new LoginVerificationService.VerifyRequest(
                                                c.challengeToken(), null, null, null),
                                        now))
                .hasMessage("LOGIN_VERIFICATION_INVALID");
        verify(db).failed(anyString());
        verify(db, never()).consume(anyString());
    }

    @Test
    void multipleFactorsRequireEveryCode() {
        enableTotp(true, now);
        when(db.factors(1))
                .thenReturn(
                        List.of(
                                new LoginVerificationRepository.Factor(
                                        "EMAIL", "u@example.test", true, now, now)));
        var c = begin();
        assertThat(c.methods())
                .extracting(LoginVerificationService.Method::type)
                .containsExactly("EMAIL", "TOTP");
        assertThatThrownBy(
                        () ->
                                service.verify(
                                        new LoginVerificationService.VerifyRequest(
                                                c.challengeToken(),
                                                null,
                                                null,
                                                totp.code(secret, now)),
                                        now))
                .hasMessage("LOGIN_VERIFICATION_INVALID");
        verify(db, never()).consumeTotp(anyLong(), anyLong());
        var text = ArgumentCaptor.forClass(String.class);
        verify(email).send(eq("u@example.test"), anyString(), text.capture());
        var matcher = java.util.regex.Pattern.compile("[0-9]{6}").matcher(text.getValue());
        assertThat(matcher.find()).isTrue();
        assertThat(
                        service.verify(
                                new LoginVerificationService.VerifyRequest(
                                        c.challengeToken(),
                                        matcher.group(),
                                        null,
                                        totp.code(secret, now)),
                                now))
                .isEqualTo(1);
    }

    @Test
    void totpReplayRejectedEvenWithAnotherChallenge() {
        enableTotp(true, now);
        var c = begin();
        when(db.consumeTotp(eq(1L), anyLong())).thenReturn(false);
        assertThatThrownBy(
                        () ->
                                service.verify(
                                        new LoginVerificationService.VerifyRequest(
                                                c.challengeToken(),
                                                null,
                                                null,
                                                totp.code(secret, now)),
                                        now))
                .hasMessage("LOGIN_VERIFICATION_INVALID");
        verify(db, never()).consume(anyString());
    }

    @Test
    void passwordChangeInvalidatesChallenge() {
        enableTotp(true, now);
        var c = begin();
        when(users.credential(1))
                .thenReturn(
                        Optional.of(
                                new GatewayUserRepository.UserCredential(
                                        1,
                                        "user",
                                        "u@example.test",
                                        "+12025550123",
                                        "new-hash",
                                        "NORMAL",
                                        now)));
        assertThatThrownBy(
                        () ->
                                service.verify(
                                        new LoginVerificationService.VerifyRequest(
                                                c.challengeToken(),
                                                null,
                                                null,
                                                totp.code(secret, now)),
                                        now))
                .hasMessage("LOGIN_CHALLENGE_EXPIRED");
        verify(db).consume(anyString());
    }

    @Test
    void changingPasswordBetweenPasswordCheckAndChallengeIsRejected() {
        assertThatThrownBy(() -> service.begin(1, "old-hash", now))
                .hasMessage("LOGIN_CHALLENGE_EXPIRED");
    }

    @Test
    void expiredOrExhaustedChallengeCannotVerify() {
        enableTotp(true, now);
        var c = begin();
        assertThatThrownBy(
                        () ->
                                service.verify(
                                        new LoginVerificationService.VerifyRequest(
                                                c.challengeToken(),
                                                null,
                                                null,
                                                totp.code(secret, now)),
                                        now.plusSeconds(301)))
                .hasMessage("LOGIN_CHALLENGE_EXPIRED");
        verify(db, never()).consumeTotp(anyLong(), anyLong());
    }

    @Test
    void unavailableSmsNeverSilentlySkipsPhone() {
        when(db.factors(1))
                .thenReturn(
                        List.of(
                                new LoginVerificationRepository.Factor(
                                        "PHONE", "+12025550123", true, now, now)));
        assertThatThrownBy(() -> service.begin(1, "hash", now)).hasMessage("LOGIN_SMS_UNAVAILABLE");
        verify(db, never())
                .issue(
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        anyBoolean(),
                        any());
    }

    @Test
    void deliveryFailureInvalidatesChallenge() {
        when(db.factors(1))
                .thenReturn(
                        List.of(
                                new LoginVerificationRepository.Factor(
                                        "EMAIL", "u@example.test", true, now, now)));
        doThrow(new IllegalStateException("provider down"))
                .when(email)
                .send(anyString(), anyString(), anyString());
        assertThatThrownBy(() -> service.begin(1, "hash", now))
                .hasMessage("LOGIN_CODE_DELIVERY_FAILED");
        verify(db).consume(anyString());
    }

    @Test
    void configuredSmsValidatesWithEmailAndTotp() {
        enableTotp(true, now);
        var sender = mock(SmsMessageSender.class);
        when(sms.getIfAvailable()).thenReturn(sender);
        when(db.factors(1))
                .thenReturn(
                        List.of(
                                new LoginVerificationRepository.Factor(
                                        "EMAIL", "u@example.test", true, now, now),
                                new LoginVerificationRepository.Factor(
                                        "PHONE", "+12025550123", true, now, now)));
        var c = begin();
        var phone = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq("+12025550123"), phone.capture());
        var body = ArgumentCaptor.forClass(String.class);
        verify(email).send(anyString(), anyString(), body.capture());
        var matcher = java.util.regex.Pattern.compile("[0-9]{6}").matcher(body.getValue());
        assertThat(matcher.find()).isTrue();
        assertThat(
                        service.verify(
                                new LoginVerificationService.VerifyRequest(
                                        c.challengeToken(),
                                        matcher.group(),
                                        phone.getValue(),
                                        totp.code(secret, now)),
                                now))
                .isEqualTo(1);
    }

    @Test
    void bindingPhoneRequiresRegisteredEmailEvenWhenEmailLoginIsOff() {
        var sender = mock(SmsMessageSender.class);
        when(sms.getIfAvailable()).thenReturn(sender);
        var response =
                service.beginBinding(
                        1,
                        "PHONE",
                        new LoginVerificationService.SettingRequest(
                                "password", "+12025550125", true),
                        now);
        assertThat(response.challenge().methods())
                .extracting(LoginVerificationService.Method::type)
                .containsExactly("EMAIL", "PHONE");
        verify(email).send(eq("u@example.test"), anyString(), anyString());
        verify(sender).send(eq("+12025550125"), anyString());
    }

    @Test
    void bindingEmailRequiresBoundDisabledGoogleAndPhone() {
        enableTotp(false, now);
        var sender = mock(SmsMessageSender.class);
        when(sms.getIfAvailable()).thenReturn(sender);
        var response =
                service.beginBinding(
                        1,
                        "EMAIL",
                        new LoginVerificationService.SettingRequest(
                                "password", "new@example.test", true),
                        now);
        assertThat(response.challenge().methods())
                .extracting(LoginVerificationService.Method::type)
                .containsExactly("EMAIL", "PHONE", "TOTP");
        verify(email).send(eq("new@example.test"), anyString(), anyString());
    }

    @Test
    void wrongPasswordCannotIssueBindingCodes() {
        assertThatThrownBy(
                        () ->
                                service.beginBinding(
                                        1,
                                        "EMAIL",
                                        new LoginVerificationService.SettingRequest(
                                                "bad", "new@example.test", true),
                                        now))
                .hasMessage("LOGIN_PASSWORD_INVALID");
        verifyNoInteractions(email);
    }

    @Test
    void disablingOnlyRequiresTargetButStillRequiresPassword() {
        var response =
                service.beginBinding(
                        1,
                        "EMAIL",
                        new LoginVerificationService.SettingRequest("password", null, false),
                        now);
        assertThat(response.challenge().methods())
                .extracting(LoginVerificationService.Method::type)
                .containsExactly("EMAIL");
    }
}
