package com.surprising.gateway.c2c;

import com.surprising.gateway.provider.auth.model.ComplianceModels;
import com.surprising.gateway.provider.auth.repository.GatewayUserRepository;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.ComplianceService;
import com.surprising.gateway.provider.auth.service.SensitiveActionVerificationService;

import com.surprising.gateway.c2c.service.C2cAccess;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.surprising.gateway.c2c.model.C2cMarket;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL", matches=".+")
class C2cAccessPostgresTest {
    private C2cDatabaseFixture db;
    private ComplianceService compliance;
    private SensitiveActionVerificationService security;
    private C2cAccess access;
    private static JwtPrincipal user() {
        return new JwtPrincipal(11,"c2c_security_qa","NORMAL",List.of(),Instant.now().plusSeconds(600));
    }
    @BeforeEach void setup() throws Exception {
        db = new C2cDatabaseFixture();
        db.jdbc.update("INSERT INTO gateway_users(user_id) VALUES(11)");
        db.jdbc.execute("CREATE TABLE c2c_otp_attempts(attempts INTEGER NOT NULL)");
        db.jdbc.update("INSERT INTO c2c_otp_attempts VALUES(0)");
        compliance = mock(ComplianceService.class);
        security = mock(SensitiveActionVerificationService.class);
        access = new C2cAccess(mock(AuthService.class), compliance, security, new com.surprising.gateway.c2c.repository.C2cProfileRepository(db.jdbc),
                new com.surprising.gateway.c2c.repository.C2cSecurityGrantRepository(db.jdbc),
                mock(GatewayUserRepository.class), db.transactions.getTransactionManager(),
                C2cDatabaseFixture.markets(db.jdbc),mock(com.surprising.gateway.provider.product.GatewayProductServices.class));
    }
    @AfterEach void cleanup() { if (db != null) db.close(); }

    @Test void verifiedKycUsesTheExistingContractAndRejectsExpiredOrUnverifiedProfiles() {
        var market = new C2cMarket(UUID.randomUUID(),"USDT",100000000,"CNY",2,true,
                BigDecimal.ONE,BigDecimal.TEN,BigDecimal.ONE,BigDecimal.TEN,0,900,3,true,100,1);
        when(compliance.kyc(11)).thenReturn(kyc("VERIFIED",Instant.now().plusSeconds(60)));
        assertThatCode(() -> access.requireTrading(user(),market)).doesNotThrowAnyException();
        when(compliance.kyc(11)).thenReturn(kyc("APPROVED",null));
        assertThatThrownBy(() -> access.requireTrading(user(),market)).isInstanceOf(IllegalStateException.class);
        when(compliance.kyc(11)).thenReturn(kyc("VERIFIED",Instant.now().minusSeconds(1)));
        assertThatThrownBy(() -> access.requireTrading(user(),market)).hasMessageContaining("有效身份认证");
    }

    @Test void failedCodeAttemptsSurviveTheRejectedBusinessTransaction() {
        when(security.verify(eq(11L),eq("WITHDRAWAL"),anyString(),anyString(),any())).thenAnswer(call -> {
            db.jdbc.update("UPDATE c2c_otp_attempts SET attempts=attempts+1");
            return false;
        });
        assertThatThrownBy(() -> db.transaction(() -> {
            access.verifyFunds(user(),"Bearer qa-session","000000","");
            return null;
        })).isInstanceOf(IllegalStateException.class);
        assertThat(db.jdbc.queryForObject("SELECT attempts FROM c2c_otp_attempts",Integer.class)).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM c2c_security_grants",Integer.class)).isZero();
    }

    @Test @Timeout(10)
    void cachedVerificationCannotDeadlockWithTheOrderQuotaLockOrCrossSessions() {
        db.jdbc.update("INSERT INTO c2c_profiles(user_id,nickname,verification_minutes) VALUES(11,'QA Security',5)");
        when(security.verify(eq(11L),eq("WITHDRAWAL"),eq("123456"),anyString(),any())).thenReturn(true);
        db.transaction(() -> {
            db.jdbc.queryForObject("SELECT user_id FROM gateway_users WHERE user_id=11 FOR NO KEY UPDATE",Long.class);
            access.verifyFunds(user(),"Bearer qa-session","123456","");
            return null;
        });
        access.verifyFunds(user(),"Bearer qa-session","","");
        verify(security,times(1)).verify(eq(11L),eq("WITHDRAWAL"),anyString(),anyString(),any());
        assertThatThrownBy(() -> access.verifyFunds(user(),"Bearer another-session","", ""))
                .isInstanceOf(IllegalStateException.class);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM c2c_security_grants",Integer.class)).isEqualTo(1);
    }

    @Test void alwaysVerifyAndSettingsNeverReuseTheFundsGrant() {
        db.jdbc.update("INSERT INTO c2c_profiles(user_id,nickname,verification_minutes) VALUES(11,'QA Always',0)");
        when(security.verify(eq(11L),eq("WITHDRAWAL"),anyString(),anyString(),any())).thenReturn(true);
        access.verifyFunds(user(),"Bearer qa","123456","");
        access.verifyFunds(user(),"Bearer qa","123456","");
        verify(security,times(2)).verify(eq(11L),eq("WITHDRAWAL"),anyString(),anyString(),any());
        assertThatThrownBy(() -> access.verifySettings(user(),"", "")).hasMessageContaining("本次安全设置");
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM c2c_security_grants",Integer.class)).isZero();
    }

    private static ComplianceModels.KycProfile kyc(String status,Instant expiry) {
        return new ComplianceModels.KycProfile(11,"STANDARD",status,"CN","IDENTITY_CARD","MANUAL",null,
                null,null,null,expiry,Instant.now(),Instant.now());
    }
}
