package com.surprising.gateway.provider.auth.service;

import com.surprising.gateway.provider.auth.repository.GatewayUserRepository;
import com.surprising.gateway.provider.auth.repository.TelegramIdentityRepository;
import com.surprising.gateway.provider.auth.state.AuthState;
import com.surprising.gateway.provider.auth.state.AuthStateSync;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 只在 subject 尚未绑定时进入。查找不加锁；并发注册靠主键冲突后回读已有记录。 */
@Service
public class TelegramIdentityBinder {

    private static final Logger log = LoggerFactory.getLogger(TelegramIdentityBinder.class);

    private final TelegramIdentityRepository identities;
    private final AuthPersistenceService users;
    private final GatewayUserRepository userRows;
    private final AuthState authState;
    private final AuthStateSync sync;

    public TelegramIdentityBinder(TelegramIdentityRepository identities, AuthPersistenceService users,
                                  GatewayUserRepository userRows, AuthState authState, AuthStateSync sync) {
        this.identities = identities;
        this.users = users;
        this.userRows = userRows;
        this.authState = authState;
        this.sync = sync;
    }

    @Transactional
    public long bind(String subject, String nicknameIfNew) {
        if (identities.revoked(subject)) throw new IllegalStateException("telegram identity was unbound");
        var existing = identities.find(subject);
        if (existing.isPresent()) {
            requireActiveUser(existing.get());
            rememberAfterCommit(subject, existing.get());
            return existing.get();
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            long userId = register(subject, nicknameIfNew);
            rememberAfterCommit(subject, userId);
            return userId;
        }
        Object savepoint = TransactionAspectSupport.currentTransactionStatus().createSavepoint();
        try {
            long userId = register(subject, nicknameIfNew);
            rememberAfterCommit(subject, userId);
            return userId;
        } catch (DataIntegrityViolationException conflict) {
            // 另一个请求已经插入同一 subject。回滚本次新建的用户，再按普通查询取已有映射。
            TransactionAspectSupport.currentTransactionStatus().rollbackToSavepoint(savepoint);
            long userId = identities.find(subject).orElseThrow(() -> conflict);
            requireActiveUser(userId);
            rememberAfterCommit(subject, userId);
            return userId;
        }
    }

    /** 明确解绑会保留持久撤销记录；/start 不会把已解绑关系自动复活。 */
    @Transactional
    public void unbind(String subject) {
        authState.deleteTelegramIdentity(subject); // 存储故障则不提交解绑
        var userId = identities.revoke(subject, Instant.now());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    authState.deleteTelegramIdentity(subject);
                    userId.ifPresent(sync::reloadCommittedUser);
                }
            });
        } else userId.ifPresent(sync::reloadCommittedUser);
    }

    private void requireActiveUser(long userId) {
        var user=userRows.find(userId).orElseThrow(()->new IllegalArgumentException("telegram user is unavailable"));
        if("FROZEN".equals(user.status())) throw new IllegalStateException("user is not active");
    }

    private long register(String subject, String nicknameIfNew) {
        Instant now = Instant.now();
        log.info("New Telegram user auto-registration: subject={}", subject);
        var user = users.createUser(null, null, null, now);
        users.ensureDefaultRole(user.userId(), now);
        identities.bind(subject, user.userId(), now);
        if (nicknameIfNew != null && !nicknameIfNew.isBlank()) {
            userRows.updateNickname(user.userId(), nicknameIfNew, now);
        }
        return user.userId();
    }

    private void rememberAfterCommit(String subject, long userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sync.reloadCommittedUser(userId);
                    authState.putTelegramIdentity(subject, userId);
                }
            });
            return;
        }
        sync.reloadCommittedUser(userId);
        authState.putTelegramIdentity(subject, userId);
    }
}
