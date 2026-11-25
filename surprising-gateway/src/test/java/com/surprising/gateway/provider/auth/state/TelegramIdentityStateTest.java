package com.surprising.gateway.provider.auth.state;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.gateway.provider.auth.repository.TelegramIdentityRepository;
import com.surprising.gateway.provider.auth.service.TelegramIdentityBinder;
import com.surprising.gateway.provider.auth.service.TelegramIdentityService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import org.junit.jupiter.api.*;

class TelegramIdentityStateTest {
    AuthStateFixture fixture=new AuthStateFixture();
    TelegramIdentityRepository rows=mock(TelegramIdentityRepository.class);
    TelegramIdentityBinder binder=mock(TelegramIdentityBinder.class);
    TelegramIdentityService service=new TelegramIdentityService(fixture.state,binder);
    UserState user(String status,List<String> roles) {
        return new UserState(7,"telegram",null,null,Instant.EPOCH,status,roles,List.of(),Set.of(),Set.of(),false,true);
    }
    void bind() { fixture.state.putUser(user("NORMAL",List.of("USER")));fixture.state.putTelegramIdentity("123",7); }

    @Test void permanentIdentityAndUserSurviveIdleAndNewGatewayWithoutPostgresReads() {
        bind();assertThat(service.resolve("123")).isEqualTo(7);
        fixture.backend.clock.addAndGet(Duration.ofDays(800).toMillis());
        assertThat(fixture.backend.ttlMillis("auth:telegram:123")).isEqualTo(-1);
        assertThat(fixture.backend.ttlMillis("auth:user:7")).isEqualTo(-1);
        var restarted=new AuthState(fixture.backend,fixture.properties,fixture.backend.clock::get);
        assertThat(new TelegramIdentityService(restarted,binder).resolve("123")).isEqualTo(7);
        verifyNoInteractions(rows,binder);
    }
    @Test void missingIdentityOrMissingUserNeverReadsOrBindsFromPostgres() {
        assertThatThrownBy(()->service.resolve("123")).isInstanceOf(IllegalArgumentException.class);
        fixture.state.putTelegramIdentity("123",7);
        assertThatThrownBy(()->service.resolve("123")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(rows,binder);
    }
    @Test void outageAllowsKnownIdentityOnlyWithinFiniteGraceAndNeverUnknownOrRegistrationFallback() {
        bind();service.resolve("123");fixture.backend.down=true;
        fixture.backend.clock.addAndGet(Duration.ofSeconds(30).toMillis());
        assertThat(service.resolve("123")).isEqualTo(7);
        assertThatThrownBy(()->service.resolve("unknown")).isInstanceOf(AuthStateUnavailableException.class);
        assertThatThrownBy(()->service.registerOrLogin("unknown",null)).isInstanceOf(AuthStateUnavailableException.class);
        fixture.backend.clock.addAndGet(Duration.ofSeconds(31).toMillis());
        assertThatThrownBy(()->service.resolve("123")).isInstanceOf(AuthStateUnavailableException.class);
        verifyNoInteractions(rows,binder);
    }
    @Test void freezeUnfreezeRolesAndUnbindInvalidateAllGatewayCaches() {
        bind();
        var otherState=new AuthState(fixture.backend,fixture.properties,fixture.backend.clock::get);
        var other=new TelegramIdentityService(otherState,binder);
        assertThat(other.resolve("123")).isEqualTo(7);
        fixture.state.putUser(user("FROZEN",List.of("USER")));
        assertThatThrownBy(()->other.resolve("123")).isInstanceOf(IllegalStateException.class);
        fixture.state.putUser(user("NORMAL",List.of("SUPPORT")));
        assertThat(other.resolve("123")).isEqualTo(7);
        assertThat(otherState.user(7).roles()).containsExactly("SUPPORT");
        fixture.state.deleteTelegramIdentity("123");
        assertThatThrownBy(()->other.resolve("123")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.resolve("123")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(rows,binder);
    }
    @Test void explicitRegistrationIsSeparateFromOrdinaryIdentityLookup() {
        when(binder.bind("new","name")).thenReturn(9L);
        assertThat(service.registerOrLogin("new","name")).isEqualTo(9);
        verify(binder).bind("new","name");verifyNoInteractions(rows);
    }
    @Test void explicitStartCannotRestoreFrozenIdentityWhenMappingIsMissing() {
        when(rows.find("123")).thenReturn(Optional.of(7L));
        var userRows=mock(com.surprising.gateway.provider.auth.repository.GatewayUserRepository.class);
        when(userRows.find(7)).thenReturn(Optional.of(new com.surprising.gateway.provider.auth.repository.GatewayUserRepository.UserRecord(7,null,null,"FROZEN",Instant.EPOCH)));
        var actual=new TelegramIdentityBinder(rows,mock(com.surprising.gateway.provider.auth.service.AuthPersistenceService.class),userRows,fixture.state,mock(AuthStateSync.class));
        assertThatThrownBy(()->actual.bind("123",null)).isInstanceOf(IllegalStateException.class);
        assertThat(fixture.state.telegramUserId("123")).isNull();
    }
    @Test void newBindingPublishesPermanentIdentityOnlyAfterCommitAndRollbackPublishesNothing() {
        var users=mock(com.surprising.gateway.provider.auth.service.AuthPersistenceService.class);
        var sync=mock(AuthStateSync.class);
        when(users.createUser(isNull(),isNull(),isNull(),any())).thenReturn(new com.surprising.gateway.provider.auth.model.AuthenticatedUser(7,null,null,"NORMAL",List.of("USER"),Instant.EPOCH));
        doAnswer(call->{fixture.state.putUser(user("NORMAL",List.of("USER")));return null;}).when(sync).reloadCommittedUser(7);
        var actual=new TelegramIdentityBinder(rows,users,mock(com.surprising.gateway.provider.auth.repository.GatewayUserRepository.class),fixture.state,sync);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            actual.bind("123",null);
            assertThat(fixture.state.telegramUserId("123")).isNull();verifyNoInteractions(sync);
            for(var callback:org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) callback.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
        } finally {org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();}
        assertThatThrownBy(()->service.resolve("123")).isInstanceOf(IllegalArgumentException.class);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            actual.bind("123",null);
            for(var callback:org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) callback.afterCommit();
        } finally {org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();}
        assertThat(service.resolve("123")).isEqualTo(7);
        assertThat(fixture.backend.ttlMillis("auth:telegram:123")).isEqualTo(-1);
        assertThat(fixture.backend.ttlMillis("auth:user:7")).isEqualTo(-1);
    }

    @Test void revokedDurableBindingCannotBeAutomaticallyRegisteredAgain() {
        when(rows.revoked("123")).thenReturn(true);
        var actual=new TelegramIdentityBinder(rows,mock(com.surprising.gateway.provider.auth.service.AuthPersistenceService.class),
                mock(com.surprising.gateway.provider.auth.repository.GatewayUserRepository.class),fixture.state,mock(AuthStateSync.class));
        assertThatThrownBy(()->actual.bind("123",null)).isInstanceOf(IllegalStateException.class);
        verify(rows,never()).bind(anyString(),anyLong(),any());
    }
}
