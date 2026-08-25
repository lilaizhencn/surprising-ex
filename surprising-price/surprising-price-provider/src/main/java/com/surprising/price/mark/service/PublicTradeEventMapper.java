package com.surprising.price.mark.service;

import com.surprising.price.api.model.PerpTradeEvent;
import com.surprising.price.mark.model.MarkPriceEncoding;
import com.surprising.trading.api.model.PublicTradeEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

@Service
public class PublicTradeEventMapper {

    private static final int DISPLAY_SCALE = 18;

    private final MarkPriceEncodingService encodingService;

    public PublicTradeEventMapper(MarkPriceEncodingService encodingService) {
        this.encodingService = encodingService;
    }

    public PerpTradeEvent toPerpTradeEvent(PublicTradeEvent event) {
        if (event == null || event.symbol() == null || event.symbol().isBlank()
                || event.tradeId() == null || event.tradeId().isBlank() || event.sequence() < 0
                || event.instrumentVersion() <= 0 || event.priceTicks() <= 0 || event.quantitySteps() <= 0
                || event.eventTime() == null) {
            throw new IllegalArgumentException("canonical public trade is invalid");
        }
        MarkPriceEncoding encoding = encodingService.encoding(event.symbol(), event.instrumentVersion());
        return new PerpTradeEvent(event.symbol(), event.tradeId(), event.sequence(), event.eventTime(),
                decimal(event.priceTicks(), encoding.priceTickUnits(), encoding.quoteScaleUnits()),
                decimal(event.quantitySteps(), encoding.quantityStepUnits(), encoding.baseScaleUnits()),
                event.takerSide() == null ? null : event.takerSide().name());
    }

    private BigDecimal decimal(long value, long unitSize, long scaleUnits) {
        return BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(unitSize))
                .divide(BigDecimal.valueOf(scaleUnits), DISPLAY_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }
}
