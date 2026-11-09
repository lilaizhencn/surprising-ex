package com.surprising.account.api.client;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.BalanceQueryResponse;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.PositionMarginAdjustmentRequest;
import com.surprising.account.api.model.PositionMarginAdjustmentResponse;
import com.surprising.account.api.model.PositionMarginResponse;
import com.surprising.account.api.model.PositionModeResponse;
import com.surprising.account.api.model.PositionModeUpdateRequest;
import com.surprising.account.api.model.PositionQueryResponse;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.api.model.ProductBalanceQueryResponse;
import com.surprising.account.api.model.ProductBalanceResponse;
import com.surprising.account.api.model.ProductTransferRequest;
import com.surprising.account.api.model.ProductTransferResponse;
import com.surprising.product.api.ProductLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * 账户资产 RPC 客户端熔断保护。
 */
@Component
public class AccountRpcApiFallbackFactory implements FallbackFactory<AccountRpcApi> {

    private static final Logger log = LoggerFactory.getLogger(AccountRpcApiFallbackFactory.class);

    @Override
    public AccountRpcApi create(Throwable cause) {
        return new AccountRpcApi() {
            private String causeMsg() {
                return cause != null ? cause.getMessage() : "circuit breaker triggered";
            }

            @Override
            public BalanceResponse balance(long userId, String asset) {
                log.warn("AccountRpcApi#balance 熔断触发: userId={}, asset={}, 原因: {}", userId, asset, causeMsg());
                return null;
            }

            @Override
            public BalanceQueryResponse balances(long userId) {
                log.warn("AccountRpcApi#balances 熔断触发: userId={}, 原因: {}", userId, causeMsg());
                return new BalanceQueryResponse(0, Collections.emptyList());
            }

            @Override
            public ProductBalanceResponse productBalance(long userId, AccountType accountType, String asset) {
                log.warn("AccountRpcApi#productBalance 熔断触发: userId={}, accountType={}, asset={}, 原因: {}",
                        userId, accountType, asset, causeMsg());
                return null;
            }

            @Override
            public ProductBalanceQueryResponse productBalances(long userId, AccountType accountType) {
                log.warn("AccountRpcApi#productBalances 熔断触发: userId={}, accountType={}, 原因: {}",
                        userId, accountType, causeMsg());
                return new ProductBalanceQueryResponse(0, Collections.emptyList());
            }

            @Override
            public ProductTransferResponse transfer(ProductTransferRequest request) {
                log.warn("AccountRpcApi#transfer 熔断触发: {}", causeMsg());
                throw new IllegalStateException("交易网关划转服务熔断中: " + causeMsg(), cause);
            }

            @Override
            public PositionModeResponse positionMode(long userId, ProductLine productLine) {
                log.warn("AccountRpcApi#positionMode 熔断触发: userId={}, productLine={}, 原因: {}",
                        userId, productLine, causeMsg());
                return null;
            }

            @Override
            public PositionModeResponse updatePositionMode(PositionModeUpdateRequest request) {
                log.warn("AccountRpcApi#updatePositionMode 熔断触发: {}", causeMsg());
                throw new IllegalStateException("交易网关修改持仓模式服务熔断中: " + causeMsg(), cause);
            }

            @Override
            public PositionResponse position(long userId, String instrumentId, String marginMode, String positionSide) {
                log.warn("AccountRpcApi#position 熔断触发: userId={}, instrumentId={}, 原因: {}",
                        userId, instrumentId, causeMsg());
                return null;
            }

            @Override
            public PositionMarginResponse positionMargin(long userId, String instrumentId, String marginMode) {
                log.warn("AccountRpcApi#positionMargin 熔断触发: userId={}, instrumentId={}, 原因: {}",
                        userId, instrumentId, causeMsg());
                return null;
            }

            @Override
            public PositionMarginAdjustmentResponse adjustPositionMargin(PositionMarginAdjustmentRequest request) {
                log.warn("AccountRpcApi#adjustPositionMargin 熔断触发: {}", causeMsg());
                throw new IllegalStateException("交易网关调整保证金服务熔断中: " + causeMsg(), cause);
            }

            @Override
            public PositionQueryResponse positions(long userId, String positionSide) {
                log.warn("AccountRpcApi#positions 熔断触发: userId={}, 原因: {}", userId, causeMsg());
                return new PositionQueryResponse(0, Collections.emptyList());
            }
        };
    }
}
