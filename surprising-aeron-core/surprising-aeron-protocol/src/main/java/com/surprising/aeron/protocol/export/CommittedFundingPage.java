package com.surprising.aeron.protocol.export;

import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.CoreFundingPaymentView;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.product.api.ProductLine;
import java.util.List;

/** 当前已提交资金费命令对应的进度和支付事实，列表属于本页结果。 */
public record CommittedFundingPage(ProductLine product, long clusterPosition, long occurredAt,
                                   ApplyFundingCommand command, CoreFundingProgressView progress,
                                   String resultCode, List<CoreFundingPaymentView> payments) {
    public CommittedFundingPage {
        if (product == null || !product.isFundingProduct() || clusterPosition <= 0 || occurredAt <= 0
                || command == null || progress == null || progress.settlementId() != command.settlementId()
                || resultCode == null || payments == null)
            throw new IllegalArgumentException("invalid committed funding page");
        payments = List.copyOf(payments);
        for (var payment : payments) {
            if (payment.settlementId() != command.settlementId()
                    || !payment.instrumentId().equals(command.instrumentId())
                    || payment.fundingRatePpm() != command.fundingRatePpm())
                throw new IllegalArgumentException("funding payment does not belong to committed command");
        }
    }
}
