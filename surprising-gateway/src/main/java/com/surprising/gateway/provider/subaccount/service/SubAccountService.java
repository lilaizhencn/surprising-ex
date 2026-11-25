package com.surprising.gateway.provider.subaccount.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.ProductBalanceQueryResponse;
import com.surprising.account.provider.service.AccountService;
import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.gateway.provider.auth.model.AuthenticatedUser;
import com.surprising.gateway.provider.auth.service.AuthPersistenceService;
import com.surprising.gateway.provider.subaccount.model.*;
import com.surprising.gateway.provider.subaccount.repository.SubAccountRepository;
import com.surprising.gateway.provider.auth.state.AuthStateSync;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubAccountService {
    private static final Logger log = LoggerFactory.getLogger(SubAccountService.class);

    private final SubAccountRepository subAccountRepository;
    private final AuthPersistenceService authPersistenceService;
    private final GatewayProductServices products;
    private final AccountCommandGateway accountCommandGateway;
    private AuthStateSync authStateSync;
    private final AccountService accountService;

    public SubAccountService(SubAccountRepository subAccountRepository,
                             AuthPersistenceService authPersistenceService,
                             GatewayProductServices products) {
        this(subAccountRepository, authPersistenceService, products, null);
    }

    @Autowired
    public SubAccountService(SubAccountRepository subAccountRepository,
                             AuthPersistenceService authPersistenceService,
                             GatewayProductServices products,
                             @Autowired(required = false) AuthStateSync authStateSync) {
        this.subAccountRepository = subAccountRepository;
        this.authPersistenceService = authPersistenceService;
        this.products = products;
        this.accountCommandGateway = null;
        this.accountService = null;
        this.authStateSync = authStateSync;
    }

    public SubAccountService(SubAccountRepository subAccountRepository,
                             AuthPersistenceService authPersistenceService,
                             AccountCommandGateway accountCommandGateway) {
        this(subAccountRepository, authPersistenceService, accountCommandGateway, null);
    }

    public SubAccountService(SubAccountRepository subAccountRepository,
                             AuthPersistenceService authPersistenceService,
                             AccountCommandGateway accountCommandGateway,
                             AccountService accountService) {
        this.subAccountRepository = subAccountRepository;
        this.authPersistenceService = authPersistenceService;
        this.products = null;
        this.accountCommandGateway = accountCommandGateway;
        this.accountService = accountService;
    }

    public void setAuthStateSync(AuthStateSync authStateSync) {
        this.authStateSync = authStateSync;
    }

    private void publishUser(long userId) {
        if (authStateSync != null) authStateSync.reloadUser(userId);
    }

    /**
     * 主账户创建子账户。
     */
    @Transactional
    public SubAccountRecord createSubAccount(long parentUserId, CreateSubAccountRequest request) {
        if (parentUserId <= 0) {
            throw new IllegalArgumentException("parentUserId must be positive");
        }
        if (request == null || request.subAccountName() == null || request.subAccountName().isBlank()) {
            throw new IllegalArgumentException("subAccountName is required");
        }

        // 格式化子账号用户名：sub_<parent>_<name>
        String subUsername = "sub_" + parentUserId + "_" + request.subAccountName().trim().toLowerCase();
        // 截取保证符合长度规则
        if (subUsername.length() > 32) {
            subUsername = subUsername.substring(0, 32);
        }

        Instant now = Instant.now();
        // 自动创建子账号用户，初始随机哈希
        String dummyHash = "$2a$10$" + UUID.randomUUID().toString().replace("-", "").substring(0, 22) + "SubAccountP@ss";
        AuthenticatedUser subUser = authPersistenceService.createUser(subUsername, null, dummyHash, now);

        String perms = request.permissions() != null && !request.permissions().isBlank()
                ? request.permissions()
                : "TRADE_SPOT,TRADE_CONTRACT";

        SubAccountRecord record = subAccountRepository.createRelation(
                parentUserId, subUser.userId(), request.subAccountName().trim(), perms);

        publishUser(subUser.userId());
        log.info("SubAccount created: parentUserId={}, subUserId={}, name={}",
                parentUserId, subUser.userId(), request.subAccountName());
        return record;
    }

    /**
     * 查询主账户下的所有子账户列表。
     */
    public List<SubAccountRecord> listSubAccounts(long parentUserId) {
        if (parentUserId <= 0) {
            throw new IllegalArgumentException("parentUserId must be positive");
        }
        return subAccountRepository.listByParent(parentUserId);
    }

    public List<SubAccountRecord> adminListSubAccounts(Long masterUserId, String status, int limit) {
        if (masterUserId != null && masterUserId <= 0) {
            throw new IllegalArgumentException("masterUserId must be positive");
        }
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        if (normalizedStatus != null && !Set.of("ACTIVE", "FROZEN").contains(normalizedStatus)) {
            throw new IllegalArgumentException("unsupported sub-account status: " + status);
        }
        return subAccountRepository.listForAdmin(masterUserId, normalizedStatus, limit);
    }

    @Transactional
    public SubAccountRecord adminUpdateStatus(long subUserId, String status) {
        if (subUserId <= 0) {
            throw new IllegalArgumentException("subUserId must be positive");
        }
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "FROZEN").contains(normalized)) {
            throw new IllegalArgumentException("status must be ACTIVE or FROZEN");
        }
        subAccountRepository.findBySubUserId(subUserId)
                .orElseThrow(() -> new IllegalArgumentException("SubAccount not found: " + subUserId));
        subAccountRepository.updateStatusBySubUserId(subUserId, normalized);
        publishUser(subUserId);
        return subAccountRepository.findBySubUserId(subUserId)
                .orElseThrow(() -> new IllegalStateException("SubAccount disappeared after update: " + subUserId));
    }

    /**
     * 主子账户间资产 0 手续费秒级划转。
     */
    public SubAccountTransferResponse transfer(long parentUserId, SubAccountTransferRequest request) {
        if (parentUserId <= 0) {
            throw new IllegalArgumentException("parentUserId must be positive");
        }
        if (request == null || request.subUserId() <= 0 || request.amountUnits() <= 0) {
            throw new IllegalArgumentException("valid subUserId and amountUnits are required");
        }

        // 校验父子关系
        SubAccountRecord relation = subAccountRepository.findRelation(parentUserId, request.subUserId())
                .orElseThrow(() -> new IllegalArgumentException("SubAccount not found for this parent user"));

        if (!"ACTIVE".equalsIgnoreCase(relation.status())) {
            throw new IllegalStateException("SubAccount is not active");
        }

        long fromUserId = request.fromParentToSub() ? parentUserId : request.subUserId();
        long toUserId = request.fromParentToSub() ? request.subUserId() : parentUserId;

        ProductLine productLine = request.productLine() != null ? request.productLine() : ProductLine.SPOT;
        long transferId = System.currentTimeMillis() * 1000 + (long) (Math.random() * 1000);
        String refId = "sub-xfer-" + UUID.randomUUID().toString().substring(0, 8);

        ProductTransferOperationRequest opRequest = new ProductTransferOperationRequest(
                transferId,
                fromUserId,
                productLine,
                productLine,
                AccountType.SPOT,
                AccountType.SPOT,
                request.asset().toUpperCase(),
                request.amountUnits(),
                refId,
                "SUB_ACCOUNT_INTERNAL_TRANSFER",
                toUserId
        );

        AccountCommandGateway gateway = this.accountCommandGateway != null
                ? this.accountCommandGateway
                : products.service(productLine, AccountCommandGateway.class);

        // 1. 出账方扣除
        gateway.transferOut(opRequest);
        // 2. 入账方到账
        gateway.transferIn(opRequest);
        // 3. 终态确认
        gateway.completeTransfer(opRequest);

        log.info("SubAccount transfer success: from={}, to={}, asset={}, amount={}",
                fromUserId, toUserId, request.asset(), request.amountUnits());

        return new SubAccountTransferResponse(
                refId, fromUserId, toUserId, request.asset(), request.amountUnits(), "SUCCESS", Instant.now()
        );
    }

    public SubAccountRecord requireOwnedRelation(long parentUserId, long subUserId) {
        if (parentUserId <= 0 || subUserId <= 0) {
            throw new IllegalArgumentException("parentUserId and subUserId must be positive");
        }
        return subAccountRepository.findRelation(parentUserId, subUserId)
                .orElseThrow(() -> new IllegalArgumentException("SubAccount not found for this parent user"));
    }

    /** 主账号冻结或解冻自己的子账号，并立即发布到 Valkey。 */
    @Transactional
    public SubAccountRecord updateStatus(long parentUserId, long subUserId, String status) {
        requireOwnedRelation(parentUserId, subUserId);
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "FROZEN").contains(normalized)) {
            throw new IllegalArgumentException("status must be ACTIVE or FROZEN");
        }
        subAccountRepository.updateStatus(parentUserId, subUserId, normalized);
        publishUser(subUserId);
        return requireOwnedRelation(parentUserId, subUserId);
    }

    /** 主账号查看某个子账号的产品余额；冻结子账号也可以看。 */
    public ProductBalanceQueryResponse assets(long parentUserId, long subUserId, AccountType accountType) {
        requireOwnedRelation(parentUserId, subUserId);
        AccountType type = accountType == null ? AccountType.SPOT : accountType;
        if (accountService != null) {
            return accountService.productBalances(subUserId, type);
        }
        if (products != null) {
            ProductLine product = type.productLine().orElse(ProductLine.SPOT);
            if (products.disabled().contains(product)) {
                return new ProductBalanceQueryResponse(0, List.of());
            }
            return products.service(product, AccountService.class).productBalances(subUserId, type);
        }
        throw new IllegalStateException("account service is not available");
    }
}
