package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.CoreFundingPaymentView;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.product.api.ProductLine;
import java.util.List;

/** Exact payments of one successfully replayed committed funding command, owned by the
 * export worker until its SQL transaction succeeds. No current-position reconstruction. */
public record CommittedFundingPage(ProductLine product, long clusterPosition, long occurredAt,
                                   ApplyFundingCommand command, CoreFundingProgressView progress,
                                   String resultCode, List<CoreFundingPaymentView> payments) {
    public CommittedFundingPage {
        if (product == null || !product.isFundingProduct() || clusterPosition <= 0 || occurredAt <= 0
                || command == null || progress == null || progress.settlementId() != command.settlementId()
                || resultCode == null || payments == null) throw new IllegalArgumentException("invalid committed funding page");
        payments = List.copyOf(payments);
        for (var payment : payments) {
            if (payment.settlementId() != command.settlementId()
                    || !payment.instrumentId().equals(command.instrumentId())
                    || payment.fundingRatePpm() != command.fundingRatePpm())
                throw new IllegalArgumentException("funding payment does not belong to committed command");
        }
    }
}
