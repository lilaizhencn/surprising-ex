package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.model.LoginVerificationModels;

import com.surprising.gateway.provider.auth.client.EmailMessageSender;
import com.surprising.gateway.provider.auth.client.SmsMessageSender;
import com.surprising.gateway.provider.auth.model.AuthModels;
import com.surprising.gateway.provider.auth.repository.GatewayUserMfaRepository;
import com.surprising.gateway.provider.auth.repository.GatewayUserRepository;
import com.surprising.gateway.provider.auth.service.AuthPersistenceService;
import com.surprising.gateway.provider.auth.service.LoginVerificationService;
import com.surprising.gateway.provider.auth.service.PasswordHasher;
import com.surprising.gateway.provider.auth.service.TotpService;

import com.surprising.gateway.provider.auth.repository.LoginFactorRepository;

import com.surprising.gateway.provider.auth.model.LoginVerificationRows;
import com.surprising.gateway.provider.auth.repository.LoginChallengeRepository;
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
    final LoginChallengeRepository db = mock(LoginChallengeRepository.class);
    final LoginFactorRepository factorRows = mock(LoginFactorRepository.class);
    final GatewayUserRepository userRows = mock(GatewayUserRepository.class);
    final GatewayUserMfaRepository mfaRows = mock(GatewayUserMfaRepository.class);
    final AuthPersistenceService users = mock(AuthPersistenceService.class);
    final GatewayProperties properties = new GatewayProperties();
    final TotpService totp = new TotpService(properties);
    final EmailMessageSender email = mock(EmailMessageSender.class);

    @SuppressWarnings("unchecked")
    final ObjectProvider<SmsMessageSender> sms = mock(ObjectProvider.class);

    final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    final LoginVerificationService service =
            new LoginVerificationService(db, factorRows, userRows, mfaRows, users, properties, totp, email, sms, passwordHasher);
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
        when(factorRows.factors(1)).thenReturn(List.of());
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

    void passwordless() {
        when(users.credential(1)).thenReturn(Optional.of(new GatewayUserRepository.UserCredential(
                1, null, null, null, null, "NORMAL", now)));
    }

    @Test
    void passwordlessPrimaryCheckRequiresRecentCompletedAuthentication() {
        passwordless();
        assertThatThrownBy(() -> service.verifySettingPassword(1, "EMAIL", null))
                .hasMessage("LOGIN_RECENT_AUTHENTICATION_REQUIRED");
        for (Instant invalid : List.of(now.minusSeconds(300), now.minusSeconds(3600), now.plusSeconds(1))) {
            assertThatThrownBy(() -> service.verifySettingPassword(1, "EMAIL", null, now, invalid))
                    .hasMessage("LOGIN_RECENT_AUTHENTICATION_REQUIRED");
        }
        service.verifySettingPassword(1, "EMAIL", null, now, now.minusSeconds(299));
        verifyNoInteractions(db, email, sms, passwordHasher);
    }

    @Test
    void aRecentSessionNeverReplacesAnExistingPassword() {
        assertThatThrownBy(() -> service.verifySettingPassword(1, "EMAIL", null, now, now))
                .hasMessage("LOGIN_PASSWORD_INVALID");
        assertThat(service.primaryAuthentication(1, now, now).passwordRequired()).isTrue();
    }

    @Test
    void passwordlessBindingStillVerifiesTheNewContactCodeAndRechecksPrimaryExpiry() {
        passwordless();
        properties.getSecurity().setSimulatedVerificationCodesEnabled(true);
        var stored = new java.util.concurrent.atomic.AtomicReference<LoginVerificationRows.Challenge>();
        when(db.issue(anyLong(), anyString(), anyString(), anyString(), anyString(),
                nullable(String.class), nullable(String.class), nullable(String.class), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    stored.set(new LoginVerificationRows.Challenge(1, inv.getArgument(1), inv.getArgument(2),
                            inv.getArgument(3), inv.getArgument(4), inv.getArgument(5), inv.getArgument(6),
                            inv.getArgument(7), now.plusSeconds(300), false, 0, true));
                    return true;
                });
        when(db.find(anyString())).thenAnswer(inv -> Optional.of(stored.get()));
        var binding = service.beginBinding(1, "EMAIL",
                new LoginVerificationModels.SettingRequest(null, "new@example.test", true), now, now);
        assertThat(binding.challenge().methods()).extracting(LoginVerificationModels.Method::type).containsExactly("EMAIL");
        var wrong = new LoginVerificationModels.SettingConfirmation(null,
                new LoginVerificationModels.VerifyRequest(binding.challenge().challengeToken(), "000000", null, null));
        assertThatThrownBy(() -> service.confirmBinding(1, "EMAIL", wrong, now, now))
                .hasMessage("LOGIN_VERIFICATION_INVALID");
        var correct = new LoginVerificationModels.SettingConfirmation(null,
                new LoginVerificationModels.VerifyRequest(binding.challenge().challengeToken(), "123456", null, null));
        assertThatThrownBy(() -> service.confirmBinding(1, "EMAIL", correct, now.plusSeconds(300), now))
                .hasMessage("LOGIN_CHALLENGE_EXPIRED");
        assertThatThrownBy(() -> service.confirmBinding(1, "EMAIL", correct, now, now.minusSeconds(300)))
                .hasMessage("LOGIN_RECENT_AUTHENTICATION_REQUIRED");
        verify(factorRows, never()).saveFactor(anyLong(), anyString(), anyString(), anyBoolean(), any());
        service.confirmBinding(1, "EMAIL", correct, now, now);
        verify(factorRows).saveFactor(1, "EMAIL", "new@example.test", true, now);
        verify(db).consume(stored.get().tokenHash());
    }

    @Test
    void expiredPrimaryProofCannotIssueContactOrTotpChallenges() {
        passwordless();
        for (String method : List.of("EMAIL", "PHONE", "TOTP")) {
            assertThatThrownBy(() -> service.beginBinding(1, method,
                    new LoginVerificationModels.SettingRequest(null, "new@example.test", true),
                    now, now.minusSeconds(300))).hasMessage("LOGIN_RECENT_AUTHENTICATION_REQUIRED");
        }
        verify(db, never()).issue(anyLong(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), anyBoolean(), any());
        verify(users, never()).upsertMfaSecret(anyLong(), anyString(), any());
    }

    @Test
    void passwordlessTotpStillRequiresAnExistingVerifiedContact() {
        passwordless();
        assertThatThrownBy(() -> service.beginBinding(1, "TOTP",
                new LoginVerificationModels.SettingRequest(null, null, true), now, now))
                .hasMessage("LOGIN_CONTACT_MISSING");
        verify(db, never()).issue(anyLong(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), anyBoolean(), any());
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
                new LoginVerificationModels.SettingRequest("password", null, true), now);

        assertThat(response.challenge().simulated()).isTrue();
        assertThat(response.challenge().methods()).extracting(LoginVerificationModels.Method::type)
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
                .thenReturn(Optional.of(new LoginVerificationRows.Challenge(
                        1, "TOTP", "old-hash", "fingerprint", "EMAIL,TOTP", "email-hash", null,
                        null, now.plusSeconds(300), false, 0, true)));

        var result = service.resendBindingCode(
                1, "TOTP", new LoginVerificationModels.ResendRequest(token), now.plusSeconds(60));

        assertThat(result.simulated()).isTrue();
        assertThat(result.methods()).extracting(LoginVerificationModels.Method::type)
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
                .thenReturn(Optional.of(new LoginVerificationRows.Challenge(
                        2, "TOTP", "old-hash", "fingerprint", "EMAIL,TOTP", "email-hash", null,
                        null, now.plusSeconds(300), false, 0, true)));

        assertThatThrownBy(() -> service.resendBindingCode(
                        1, "TOTP", new LoginVerificationModels.ResendRequest(token), now))
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

    LoginVerificationModels.ChallengeResponse begin() {
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
                                new LoginVerificationRows.Challenge(
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
        when(mfaRows.consumeVerifiedLoginCode(eq(1L), anyLong())).thenReturn(true);
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
                                new LoginVerificationModels.VerifyRequest(
                                        c.challengeToken(), null, null, totp.code(secret, now)),
                                now))
                .isEqualTo(1);
        verify(db).consume(anyString());
        verify(mfaRows).consumeVerifiedLoginCode(1, now.getEpochSecond() / 30);
    }

    @Test
    void missingCodeCountsFailureAndDoesNotConsume() {
        enableTotp(true, now);
        var c = begin();
        assertThatThrownBy(
                        () ->
                                service.verify(
                                        new LoginVerificationModels.VerifyRequest(
                                                c.challengeToken(), null, null, null),
                                        now))
                .hasMessage("LOGIN_VERIFICATION_INVALID");
        verify(db).failed(anyString());
        verify(db, never()).consume(anyString());
    }

    @Test
    void multipleFactorsRequireEveryCode() {
        enableTotp(true, now);
        when(factorRows.factors(1))
                .thenReturn(
                        List.of(
                                new LoginVerificationRows.Factor(
                                        "EMAIL", "u@example.test", true, now, now)));
        var c = begin();
        assertThat(c.methods())
                .extracting(LoginVerificationModels.Method::type)
                .containsExactly("EMAIL", "TOTP");
        assertThatThrownBy(
                        () ->
                                service.verify(
                                        new LoginVerificationModels.VerifyRequest(
                                                c.challengeToken(),
                                                null,
                                                null,
                                                totp.code(secret, now)),
                                        now))
                .hasMessage("LOGIN_VERIFICATION_INVALID");
        verify(mfaRows, never()).consumeVerifiedLoginCode(anyLong(), anyLong());
        var text = ArgumentCaptor.forClass(String.class);
        verify(email).send(eq("u@example.test"), anyString(), text.capture());
        var matcher = java.util.regex.Pattern.compile("[0-9]{6}").matcher(text.getValue());
        assertThat(matcher.find()).isTrue();
        assertThat(
                        service.verify(
                                new LoginVerificationModels.VerifyRequest(
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
        when(mfaRows.consumeVerifiedLoginCode(eq(1L), anyLong())).thenReturn(false);
        assertThatThrownBy(
                        () ->
                                service.verify(
                                        new LoginVerificationModels.VerifyRequest(
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
                                        new LoginVerificationModels.VerifyRequest(
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
                                        new LoginVerificationModels.VerifyRequest(
                                                c.challengeToken(),
                                                null,
                                                null,
                                                totp.code(secret, now)),
                                        now.plusSeconds(301)))
                .hasMessage("LOGIN_CHALLENGE_EXPIRED");
        verify(mfaRows, never()).consumeVerifiedLoginCode(anyLong(), anyLong());
    }

    @Test
    void unavailableSmsNeverSilentlySkipsPhone() {
        when(factorRows.factors(1))
                .thenReturn(
                        List.of(
                                new LoginVerificationRows.Factor(
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
        when(factorRows.factors(1))
                .thenReturn(
                        List.of(
                                new LoginVerificationRows.Factor(
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
        when(factorRows.factors(1))
                .thenReturn(
                        List.of(
                                new LoginVerificationRows.Factor(
                                        "EMAIL", "u@example.test", true, now, now),
                                new LoginVerificationRows.Factor(
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
                                new LoginVerificationModels.VerifyRequest(
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
                        new LoginVerificationModels.SettingRequest(
                                "password", "+12025550125", true),
                        now);
        assertThat(response.challenge().methods())
                .extracting(LoginVerificationModels.Method::type)
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
                        new LoginVerificationModels.SettingRequest(
                                "password", "new@example.test", true),
                        now);
        assertThat(response.challenge().methods())
                .extracting(LoginVerificationModels.Method::type)
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
                                        new LoginVerificationModels.SettingRequest(
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
                        new LoginVerificationModels.SettingRequest("password", null, false),
                        now);
        assertThat(response.challenge().methods())
                .extracting(LoginVerificationModels.Method::type)
                .containsExactly("EMAIL");
    }
}
