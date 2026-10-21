package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.service.C2cMarketService;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.gateway.provider.service.ProductAccountAdjustment;
import com.surprising.gateway.provider.service.ProductAccountClient;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 原指令逐步续办；任何未知结果都维持在途，绝不生成另一个扣款/退款身份。 */
@Service
public class C2cFunds {
    private final C2cFundsRepository intents;
    private final ProductAccountClient accounts;
    private final C2cMarketService markets;
    public C2cFunds(C2cFundsRepository intents, ProductAccountClient accounts, C2cMarketService markets) {
        this.intents = intents;
        this.accounts = accounts;
        this.markets = markets;
    }

    C2cFundsRepository.Intent resume(UUID id) {
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
            final ProductAccountAdjustment outcome;
            try {
                outcome = switch (intent.status()) {
                    case "PREPARED" -> accounts.transferOut("SPOT", request);
                    case "DEBITED" -> accounts.transferIn("SPOT", request);
                    case "CREDITED" -> accounts.completeTransfer("SPOT", request);
                    default -> throw new IllegalStateException("C2C 资金进度无效");
                };
            } catch (RuntimeException ex) {
                intents.retry(intent, "RESULT_UNKNOWN");
                return intents.get(id);
            }
            if (outcome.status() == ProductAccountAdjustment.Status.APPLIED) {
                intents.advance(intent, switch (intent.status()) {
                    case "PREPARED" -> "DEBITED";
                    case "DEBITED" -> "CREDITED";
                    case "CREDITED" -> "APPLIED";
                    default -> throw new IllegalStateException("C2C 资金进度无效");
                });
            } else if (intent.status().equals("PREPARED") && outcome.status() == ProductAccountAdjustment.Status.REJECTED) {
                intents.reject(intent, outcome.errorMessage());
            } else {
                intents.retry(intent, outcome.errorMessage());
                return intents.get(id);
            }
            intent = intents.get(id);
        }
        return intent;
    }
}
