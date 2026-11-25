package com.surprising.gateway.c2c.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.account.provider.exception.AccountCommandRejectedException;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.gateway.c2c.repository.C2cFundsRepository;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 原指令逐步续办；任何未知结果都维持在途，绝不生成另一个扣款/退款身份。 */
@Service
public class C2cFundService {
    private final C2cFundsRepository intents;
    private final GatewayProductServices products;
    private final C2cMarketService markets;
    public C2cFundService(C2cFundsRepository intents, GatewayProductServices products, C2cMarketService markets) {
        this.intents = intents;
        this.products = products;
        this.markets = markets;
    }

    public C2cFundsRepository.Intent resume(UUID id) {
        var intent = intents.get(id);
        while (!intent.terminal()) {
            var configuration = markets.configuration();
            boolean valid = switch (intent.purpose()) {
                case "AD_RESERVE", "ORDER_RESERVE" -> intent.targetUserId() == configuration.escrowUserId()
                        && intent.sourceUserId() != configuration.escrowUserId() && intent.sourceUserId() != configuration.feeUserId();
                case "MERCHANT_BOND", "MERCHANT_BOND_REFUND" -> markets.validMerchantBondIntent(intent);
                case "BUYER_FEE" -> intent.sourceUserId() == configuration.escrowUserId()
                        && intent.targetUserId() == configuration.feeUserId();
                case "AD_REFUND", "ORDER_REFUND", "ORDER_RELEASE" -> intent.sourceUserId() == configuration.escrowUserId()
                        && intent.targetUserId() != configuration.escrowUserId() && intent.targetUserId() != configuration.feeUserId();
                default -> false;
            };
            if (!valid) throw new IllegalStateException("C2C 资金意图不符合托管账户归属");
            var request = new ProductTransferOperationRequest(intent.transferId(), intent.sourceUserId(),
                    ProductLine.SPOT, ProductLine.SPOT, AccountType.SPOT, AccountType.SPOT, intent.asset(),
                    intent.amountUnits(), "c2c:" + intent.id(), intent.purpose(), intent.targetUserId());
            try {
                var commands = products.service(ProductLine.SPOT, AccountCommandGateway.class);
                switch (intent.status()) {
                    case "PREPARED" -> commands.transferOut(request);
                    case "DEBITED" -> commands.transferIn(request);
                    case "CREDITED" -> commands.completeTransfer(request);
                    default -> throw new IllegalStateException("C2C 资金进度无效");
                }
                intents.advance(intent, switch (intent.status()) {
                    case "PREPARED" -> "DEBITED";
                    case "DEBITED" -> "CREDITED";
                    case "CREDITED" -> "APPLIED";
                    default -> throw new IllegalStateException("C2C 资金进度无效");
                });
            } catch (AccountCommandRejectedException ex) {
                if ("PREPARED".equals(intent.status())) {
                    intents.reject(intent, ex.errorCode());
                } else {
                    intents.retry(intent, ex.errorCode());
                    return intents.get(id);
                }
            } catch (IllegalArgumentException ex) {
                if ("PREPARED".equals(intent.status())) {
                    intents.reject(intent, ex.getMessage());
                } else {
                    intents.retry(intent, ex.getMessage());
                    return intents.get(id);
                }
            } catch (RuntimeException ex) {
                intents.retry(intent, "RESULT_UNKNOWN");
                return intents.get(id);
            }
            intent = intents.get(id);
        }
        return intent;
    }
}
